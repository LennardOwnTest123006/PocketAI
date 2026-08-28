// PocketAI native inference bridge.
//
// Wraps llama.cpp behind a small JNI surface that streams tokens back into
// Kotlin as they are produced, supports immediate cancellation, and reuses the
// KV cache across turns so that follow-up messages in a conversation only have
// to evaluate the newly appended tokens.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstring>
#include <chrono>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"

#define LOG_TAG "PocketAI-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::once_flag g_backend_once;
std::atomic<bool> g_backend_ready{false};

// ---------------------------------------------------------------------------
// UTF-8 aware streaming buffer.
//
// A single token can end in the middle of a multi-byte code point (very common
// with emoji and CJK).  Handing such a fragment to NewStringUTF corrupts the
// string, so we only ever emit whole code points and keep the tail behind.
// ---------------------------------------------------------------------------
size_t complete_utf8_prefix(const std::string & s) {
    size_t i = s.size();
    // Walk back over at most 3 continuation bytes looking for a lead byte.
    size_t scan = 0;
    while (i > 0 && scan < 4) {
        unsigned char c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) == 0x80) {           // continuation byte
            i--;
            scan++;
            continue;
        }
        // Lead byte (or ASCII): decide whether the sequence starting here is complete.
        size_t need;
        if      ((c & 0x80) == 0x00) need = 1;
        else if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        else                         need = 1;   // invalid lead, let it through
        size_t have = s.size() - (i - 1);
        return (have >= need) ? s.size() : (i - 1);
    }
    return (scan >= 4) ? s.size() : i;
}

struct Session {
    llama_model   * model = nullptr;
    llama_context * ctx   = nullptr;
    const llama_vocab * vocab = nullptr;

    std::mutex mutex;                 // serialises generate/reset on one session
    std::atomic<bool> stop{false};
    std::atomic<bool> busy{false};

    std::vector<llama_token> cached;  // tokens currently resident in the KV cache
    int n_ctx = 0;
    int n_threads = 4;
};

std::string jstr(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    if (c == nullptr) return {};
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

jstring to_jstr(JNIEnv * env, const std::string & s) {
    return env->NewStringUTF(s.c_str());
}

std::string json_escape(const std::string & in) {
    std::string out;
    out.reserve(in.size() + 8);
    for (char ch : in) {
        switch (ch) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:
                if (static_cast<unsigned char>(ch) < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", ch);
                    out += buf;
                } else {
                    out += ch;
                }
        }
    }
    return out;
}

int64_t now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

// NOTE: special tokens must be rendered, not suppressed. Reasoning models such
// as Qwen3 emit <think> / </think> as dedicated vocabulary entries; with
// special=false they detokenise to nothing and the Thinking panel would never
// receive its delimiters. End-of-generation tokens never reach here because the
// decode loop breaks on them first.
std::string token_to_text(const llama_vocab * vocab, llama_token tok) {
    char buf[256];
    int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, /*special=*/true);
    if (n < 0) {
        std::vector<char> big(static_cast<size_t>(-n) + 1);
        n = llama_token_to_piece(vocab, tok, big.data(), (int32_t) big.size(), 0, true);
        if (n < 0) return {};
        return std::string(big.data(), static_cast<size_t>(n));
    }
    return std::string(buf, static_cast<size_t>(n));
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text,
                                  bool add_special, bool parse_special) {
    int32_t upper = static_cast<int32_t>(text.size()) + 16;
    std::vector<llama_token> out(upper);
    int32_t n = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(),
                               out.data(), upper, add_special, parse_special);
    if (n < 0) {
        out.resize(static_cast<size_t>(-n));
        n = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(),
                           out.data(), (int32_t) out.size(), add_special, parse_special);
        if (n < 0) return {};
    }
    out.resize(static_cast<size_t>(n));
    return out;
}

// Abort hook so a long prompt ingest can be cancelled straight away.
bool decode_abort_cb(void * data) {
    auto * flag = static_cast<std::atomic<bool> *>(data);
    return flag != nullptr && flag->load(std::memory_order_relaxed);
}

struct ProgressCtx {
    JNIEnv  * env  = nullptr;
    jobject   cb   = nullptr;
    jmethodID mid  = nullptr;
};

bool load_progress_cb(float progress, void * user_data) {
    auto * p = static_cast<ProgressCtx *>(user_data);
    if (p == nullptr || p->env == nullptr || p->cb == nullptr || p->mid == nullptr) return true;
    p->env->CallVoidMethod(p->cb, p->mid, static_cast<jfloat>(progress));
    if (p->env->ExceptionCheck()) {
        p->env->ExceptionClear();
        return false;
    }
    return true;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_pocketai_app_llm_LlamaNative_nativeInit(JNIEnv * env, jobject, jstring nativeLibDir) {
    const std::string dir = jstr(env, nativeLibDir);
    std::call_once(g_backend_once, [&]() {
        llama_log_set([](ggml_log_level level, const char * text, void *) {
            if (text == nullptr) return;
            if (level == GGML_LOG_LEVEL_ERROR)      LOGE("%s", text);
            else if (level == GGML_LOG_LEVEL_WARN)  LOGW("%s", text);
        }, nullptr);

        // ggml ships one dlopen-able backend per CPU feature level; load whatever
        // this device actually supports.  Missing/incompatible backends (e.g. no
        // Vulkan driver) are skipped silently rather than aborting.
        if (!dir.empty()) {
            ggml_backend_load_all_from_path(dir.c_str());
        } else {
            ggml_backend_load_all();
        }
        llama_backend_init();
        g_backend_ready.store(true);
        LOGI("llama backend ready, %zu device(s)", ggml_backend_dev_count());
    });
    return g_backend_ready.load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_pocketai_app_llm_LlamaNative_nativeBackendInfo(JNIEnv * env, jobject) {
    std::string json = "{\"devices\":[";
    const size_t n = ggml_backend_dev_count();
    bool first = true;
    bool has_gpu = false;
    for (size_t i = 0; i < n; i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (dev == nullptr) continue;
        const char * name = ggml_backend_dev_name(dev);
        const char * desc = ggml_backend_dev_description(dev);
        // The function ggml_backend_dev_type() hides the enum of the same
        // name in C++, so the type needs its explicit 'enum' tag here.
        const enum ggml_backend_dev_type type = ggml_backend_dev_type(dev);
        size_t free_mem = 0, total_mem = 0;
        ggml_backend_dev_memory(dev, &free_mem, &total_mem);
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU) has_gpu = true;
        if (!first) json += ",";
        first = false;
        json += "{\"name\":\"";
        json += json_escape(name != nullptr ? name : "");
        json += "\",\"description\":\"";
        json += json_escape(desc != nullptr ? desc : "");
        json += "\",\"type\":";
        json += std::to_string(static_cast<int>(type));
        json += ",\"freeBytes\":" + std::to_string((long long) free_mem);
        json += ",\"totalBytes\":" + std::to_string((long long) total_mem);
        json += "}";
    }
    json += "],\"gpu\":";
    json += has_gpu ? "true" : "false";
    json += ",\"maxDevices\":" + std::to_string((long long) llama_max_devices());
    json += "}";
    return to_jstr(env, json);
}

JNIEXPORT jlong JNICALL
Java_com_pocketai_app_llm_LlamaNative_nativeLoadModel(
        JNIEnv * env, jobject, jstring path, jint nCtx, jint nThreads, jint nGpuLayers,
        jboolean useMmap, jboolean useMlock, jboolean flashAttn, jobject progressCb) {

    if (!g_backend_ready.load()) {
        LOGE("nativeLoadModel called before nativeInit");
        return 0;
    }
    const std::string model_path = jstr(env, path);
    if (model_path.empty()) return 0;

    ProgressCtx pctx;
    if (progressCb != nullptr) {
        jclass cls = env->GetObjectClass(progressCb);
        if (cls != nullptr) {
            pctx.env = env;
            pctx.cb  = progressCb;
            pctx.mid = env->GetMethodID(cls, "onProgress", "(F)V");
            env->DeleteLocalRef(cls);
        }
    }

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = nGpuLayers;
    mparams.use_mmap     = useMmap  == JNI_TRUE;
    mparams.use_mlock    = useMlock == JNI_TRUE;
    if (pctx.mid != nullptr) {
        mparams.progress_callback           = load_progress_cb;
        mparams.progress_callback_user_data = &pctx;
    }

    llama_model * model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (model == nullptr) {
        LOGE("failed to load model: %s", model_path.c_str());
        return 0;
    }

    auto * s = new Session();
    s->model = model;
    s->vocab = llama_model_get_vocab(model);
    s->n_threads = nThreads > 0 ? nThreads : 4;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = static_cast<uint32_t>(nCtx > 0 ? nCtx : 4096);
    cparams.n_batch         = 512;
    cparams.n_ubatch        = 256;
    cparams.n_threads       = s->n_threads;
    cparams.n_threads_batch = s->n_threads;
    cparams.flash_attn      = flashAttn == JNI_TRUE;
    cparams.no_perf         = false;
    cparams.abort_callback      = decode_abort_cb;
    cparams.abort_callback_data = &s->stop;

    s->ctx = llama_init_from_model(model, cparams);
    if (s->ctx == nullptr) {
        // Most often this is an out-of-memory failure for the KV cache; retry once
        // with a smaller window before giving up so the app degrades instead of dying.
        LOGW("context creation failed at n_ctx=%d, retrying at 2048", (int) cparams.n_ctx);
        cparams.n_ctx = 2048;
        s->ctx = llama_init_from_model(model, cparams);
    }
    if (s->ctx == nullptr) {
        llama_model_free(model);
        delete s;
        LOGE("failed to create context");
        return 0;
    }

    s->n_ctx = static_cast<int>(llama_n_ctx(s->ctx));
    LOGI("model loaded, n_ctx=%d threads=%d", s->n_ctx, s->n_threads);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_pocketai_app_llm_LlamaNative_nativeFreeModel(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return;
    s->stop.store(true);
    {
        std::lock_guard<std::mutex> lock(s->mutex);
        if (s->ctx   != nullptr) llama_free(s->ctx);
        if (s->model != nullptr) llama_model_free(s->model);
        s->ctx = nullptr;
        s->model = nullptr;
    }
    delete s;
}

JNIEXPORT jstring JNICALL
Java_com_pocketai_app_llm_LlamaNative_nativeModelInfo(JNIEnv * env, jobject, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr || s->model == nullptr) return to_jstr(env, "{}");

    char desc[512] = {0};
    llama_model_desc(s->model, desc, sizeof(desc));

    char arch[128] = {0};
    llama_model_meta_val_str(s->model, "general.architecture", arch, sizeof(arch));

    char name[256] = {0};
    llama_model_meta_val_str(s->model, "general.name", name, sizeof(name));

    const char * tmpl = llama_model_chat_template(s->model, nullptr);

    std::string json = "{";
    json += "\"description\":\"" + json_escape(desc) + "\",";
    json += "\"architecture\":\"" + json_escape(arch) + "\",";
    json += "\"name\":\"" + json_escape(name) + "\",";
    json += "\"parameters\":" + std::to_string((long long) llama_model_n_params(s->model)) + ",";
    json += "\"sizeBytes\":" + std::to_string((long long) llama_model_size(s->model)) + ",";
    json += "\"nCtxTrain\":" + std::to_string((long long) llama_model_n_ctx_train(s->model)) + ",";
    json += "\"nCtx\":" + std::to_string(s->n_ctx) + ",";
    json += "\"nEmbd\":" + std::to_string((long long) llama_model_n_embd(s->model)) + ",";
    json += "\"nLayer\":" + std::to_string((long long) llama_model_n_layer(s->model)) + ",";
    json += "\"vocabSize\":" + std::to_string((long long) llama_vocab_n_tokens(s->vocab)) + ",";
    json += "\"hasChatTemplate\":";
    json += (tmpl != nullptr && tmpl[0] != '\0') ? "true" : "false";
    json += "}";
    return to_jstr(env, json);
}

JNIEXPORT jstring JNICALL
Java_com_pocketai_app_llm_LlamaNative_nativeApplyChatTemplate(
        JNIEnv * env, jobject, jlong handle, jobjectArray roles, jobjectArray contents,
        jboolean addAssistant) {

    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr || s->model == nullptr) return nullptr;

    const jsize n = env->GetArrayLength(roles);
    if (n != env->GetArrayLength(contents)) return nullptr;

    std::vector<std::string> role_store(static_cast<size_t>(n));
    std::vector<std::string> text_store(static_cast<size_t>(n));
    std::vector<llama_chat_message> msgs(static_cast<size_t>(n));

    size_t approx = 0;
    for (jsize i = 0; i < n; i++) {
        auto r = (jstring) env->GetObjectArrayElement(roles, i);
        auto c = (jstring) env->GetObjectArrayElement(contents, i);
        role_store[i] = jstr(env, r);
        text_store[i] = jstr(env, c);
        if (r != nullptr) env->DeleteLocalRef(r);
        if (c != nullptr) env->DeleteLocalRef(c);
        msgs[i].role    = role_store[i].c_str();
        msgs[i].content = text_store[i].c_str();
        approx += role_store[i].size() + text_store[i].size() + 32;
    }

    const char * tmpl = llama_model_chat_template(s->model, nullptr);
    std::vector<char> buf(approx * 2 + 1024);
    int32_t written = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(),
                                                addAssistant == JNI_TRUE,
                                                buf.data(), (int32_t) buf.size());
    if (written > (int32_t) buf.size()) {
        buf.resize(static_cast<size_t>(written) + 1);
        written = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(),
                                            addAssistant == JNI_TRUE,
                                            buf.data(), (int32_t) buf.size());
    }
    if (written < 0) return nullptr;   // no usable template -> caller falls back
    return to_jstr(env, std::string(buf.data(), static_cast<size_t>(written)));
}

JNIEXPORT jint JNICALL
Java_com_pocketai_app_llm_LlamaNative_nativeTokenCount(JNIEnv * env, jobject, jlong handle, jstring text) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr || s->vocab == nullptr) return -1;
    const std::string t = jstr(env, text);
    return (jint) tokenize(s->vocab, t, false, true).size();
}

JNIEXPORT jint JNICALL
Java_com_pocketai_app_llm_LlamaNative_nativeContextSize(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    return s == nullptr ? 0 : (jint) s->n_ctx;
}

JNIEXPORT void JNICALL
Java_com_pocketai_app_llm_LlamaNative_nativeRequestStop(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s != nullptr) s->stop.store(true, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_pocketai_app_llm_LlamaNative_nativeResetContext(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr || s->ctx == nullptr) return;
    std::lock_guard<std::mutex> lock(s->mutex);
    llama_memory_clear(llama_get_memory(s->ctx), true);
    s->cached.clear();
}

JNIEXPORT jstring JNICALL
Java_com_pocketai_app_llm_LlamaNative_nativeGenerate(
        JNIEnv * env, jobject, jlong handle, jstring prompt,
        jint maxTokens, jfloat temperature, jfloat topP, jint topK, jfloat minP,
        jfloat repeatPenalty, jint repeatLastN, jint seed, jint nThreads,
        jobject callback) {

    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr || s->ctx == nullptr) {
        return to_jstr(env, "{\"error\":\"no_model\"}");
    }
    if (s->busy.exchange(true)) {
        return to_jstr(env, "{\"error\":\"busy\"}");
    }
    struct BusyGuard {
        Session * s;
        ~BusyGuard() { s->busy.store(false); }
    } busy_guard{s};

    std::lock_guard<std::mutex> lock(s->mutex);
    s->stop.store(false);

    jmethodID on_token = nullptr;
    if (callback != nullptr) {
        jclass cls = env->GetObjectClass(callback);
        if (cls != nullptr) {
            on_token = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
            env->DeleteLocalRef(cls);
        }
    }

    if (nThreads > 0 && nThreads != s->n_threads) {
        s->n_threads = nThreads;
        llama_set_n_threads(s->ctx, nThreads, nThreads);
    }

    const std::string prompt_str = jstr(env, prompt);
    std::vector<llama_token> tokens = tokenize(s->vocab, prompt_str, /*add_special=*/true, /*parse_special=*/true);
    if (tokens.empty()) {
        return to_jstr(env, "{\"error\":\"empty_prompt\"}");
    }
    if ((int) tokens.size() >= s->n_ctx - 8) {
        return to_jstr(env, "{\"error\":\"context_overflow\",\"promptTokens\":"
                            + std::to_string((long long) tokens.size())
                            + ",\"nCtx\":" + std::to_string(s->n_ctx) + "}");
    }

    const int64_t t_start = now_ms();

    // ---- KV-cache prefix reuse -------------------------------------------
    size_t common = 0;
    const size_t max_common = tokens.size() - 1;   // always re-evaluate >=1 token for logits
    while (common < s->cached.size() && common < max_common && s->cached[common] == tokens[common]) {
        common++;
    }
    llama_memory_t mem = llama_get_memory(s->ctx);
    llama_memory_seq_rm(mem, 0, (llama_pos) common, -1);
    s->cached.resize(common);

    // ---- prompt ingest ----------------------------------------------------
    const int n_batch = (int) llama_n_batch(s->ctx);
    llama_batch batch = llama_batch_init(n_batch, 0, 1);
    struct BatchGuard {
        llama_batch b;
        ~BatchGuard() { llama_batch_free(b); }
    } batch_guard{batch};

    bool failed = false;
    for (size_t i = common; i < tokens.size() && !failed; ) {
        const size_t chunk = std::min<size_t>(static_cast<size_t>(n_batch), tokens.size() - i);
        batch.n_tokens = (int32_t) chunk;
        for (size_t j = 0; j < chunk; j++) {
            batch.token[j]     = tokens[i + j];
            batch.pos[j]       = (llama_pos) (i + j);
            batch.n_seq_id[j]  = 1;
            batch.seq_id[j][0] = 0;
            batch.logits[j]    = (i + j == tokens.size() - 1) ? 1 : 0;
        }
        if (llama_decode(s->ctx, batch) != 0) failed = true;
        i += chunk;
        if (s->stop.load(std::memory_order_relaxed)) break;
    }

    if (failed) {
        s->cached.clear();
        llama_memory_clear(mem, true);
        return to_jstr(env, "{\"error\":\"decode_failed\"}");
    }
    s->cached = tokens;

    if (s->stop.load(std::memory_order_relaxed)) {
        return to_jstr(env, "{\"stopReason\":\"cancelled\",\"promptTokens\":"
                            + std::to_string((long long) tokens.size())
                            + ",\"generatedTokens\":0}");
    }

    // ---- sampler chain ----------------------------------------------------
    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(sp);
    if (repeatPenalty > 1.0f && repeatLastN != 0) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(repeatLastN, repeatPenalty, 0.0f, 0.0f));
    }
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        if (topK > 0)                  llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
        if (topP > 0.0f && topP < 1.0f) llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
        if (minP > 0.0f)               llama_sampler_chain_add(smpl, llama_sampler_init_min_p(minP, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(
                seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed)));
    }
    struct SamplerGuard {
        llama_sampler * p;
        ~SamplerGuard() { llama_sampler_free(p); }
    } sampler_guard{smpl};

    // ---- decode loop ------------------------------------------------------
    std::string pending;      // bytes not yet emitted (incomplete UTF-8 tail)
    int generated = 0;
    int64_t t_first = 0;
    const char * stop_reason = "eos";
    int n_past = (int) tokens.size();
    const int limit = maxTokens > 0 ? maxTokens : 512;

    while (generated < limit) {
        if (s->stop.load(std::memory_order_relaxed)) { stop_reason = "stopped"; break; }

        const llama_token tok = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, tok)) { stop_reason = "eos"; break; }
        llama_sampler_accept(smpl, tok);

        if (t_first == 0) t_first = now_ms();
        generated++;

        pending += token_to_text(s->vocab, tok);
        const size_t cut = complete_utf8_prefix(pending);
        if (cut > 0 && on_token != nullptr) {
            const std::string emit = pending.substr(0, cut);
            pending.erase(0, cut);
            jstring js = env->NewStringUTF(emit.c_str());
            if (js != nullptr) {
                env->CallVoidMethod(callback, on_token, js);
                env->DeleteLocalRef(js);
            }
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                stop_reason = "stopped";
                break;
            }
        }

        if (n_past + 1 >= s->n_ctx) { stop_reason = "context_full"; break; }

        // feed the sampled token back in
        batch.n_tokens     = 1;
        batch.token[0]     = tok;
        batch.pos[0]       = (llama_pos) n_past;
        batch.n_seq_id[0]  = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0]    = 1;
        if (llama_decode(s->ctx, batch) != 0) { stop_reason = "decode_failed"; break; }

        s->cached.push_back(tok);
        n_past++;

        if (generated >= limit) { stop_reason = "max_tokens"; break; }
    }

    // Flush any trailing bytes (they form a complete code point by now, or the
    // model stopped mid-sequence and we drop the invalid tail).
    if (!pending.empty() && on_token != nullptr) {
        const size_t cut = complete_utf8_prefix(pending);
        if (cut > 0) {
            jstring js = env->NewStringUTF(pending.substr(0, cut).c_str());
            if (js != nullptr) {
                env->CallVoidMethod(callback, on_token, js);
                env->DeleteLocalRef(js);
            }
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }

    const int64_t t_end = now_ms();
    const int64_t ttft  = (t_first > 0) ? (t_first - t_start) : 0;
    const int64_t gen_ms = (t_first > 0) ? (t_end - t_first) : 0;
    const double tps = (gen_ms > 0) ? (generated * 1000.0 / (double) gen_ms) : 0.0;

    char tps_buf[32];
    snprintf(tps_buf, sizeof(tps_buf), "%.3f", tps);

    std::string json = "{";
    json += "\"stopReason\":\"" + std::string(stop_reason) + "\",";
    json += "\"promptTokens\":" + std::to_string((long long) tokens.size()) + ",";
    json += "\"cachedTokens\":" + std::to_string((long long) common) + ",";
    json += "\"generatedTokens\":" + std::to_string(generated) + ",";
    json += "\"firstTokenMs\":" + std::to_string((long long) ttft) + ",";
    json += "\"totalMs\":" + std::to_string((long long) (t_end - t_start)) + ",";
    json += "\"tokensPerSecond\":" + std::string(tps_buf);
    json += "}";
    return to_jstr(env, json);
}

} // extern "C"
