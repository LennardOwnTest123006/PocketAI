package com.pocketai.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketai.app.core.DeviceCapabilities
import com.pocketai.app.data.model.GgufMetadata
import com.pocketai.app.data.repo.AppSettings
import com.pocketai.app.data.repo.ChatRole
import com.pocketai.app.data.repo.GenerationStats
import com.pocketai.app.llm.GenerationOutcome
import com.pocketai.app.llm.InferenceEngine
import com.pocketai.app.llm.LoadResult
import com.pocketai.app.llm.PromptTurn
import com.pocketai.app.llm.ResponseMode
import com.pocketai.app.llm.SystemPrompt
import com.pocketai.app.data.model.InstalledModel
import com.pocketai.app.data.model.ModelSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * End-to-end latency measurement against a real GGUF model.
 *
 * This exists because a green build proves nothing about speed. It downloads a
 * small instruct model, runs the same prompt three ways, and reports genuinely
 * measured numbers.
 *
 * The numbers come from a CI emulator on x86_64, so they are NOT representative
 * of a Snapdragon 8 Gen 3 in absolute terms - the in-app benchmark screen exists
 * for that. What does transfer is the shape of the result: how many prompt
 * tokens each turn has to evaluate, and therefore how much of the latency the
 * caching removes.
 *
 * Skips itself (rather than failing the build) when the model cannot be fetched.
 */
@RunWith(AndroidJUnit4::class)
class InferenceBenchmarkTest {

    private data class Measurement(val label: String, val stats: GenerationStats)

    @Test
    fun measuresTimeToFirstTokenAndThroughput() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = obtainModel() ?: run {
            assumeTrue("benchmark model unavailable; skipping", false)
            return@runBlocking
        }

        val engine = InferenceEngine(context)
        assumeTrue("native engine unavailable", engine.initialize())

        val caps = DeviceCapabilities.read(context)
        val settings = AppSettings()

        val installed = InstalledModel(
            id = "benchmark",
            displayName = model.nameWithoutExtension,
            fileName = model.name,
            absolutePath = model.absolutePath,
            sizeBytes = model.length(),
            source = ModelSource.IMPORT
        )

        val loadStart = System.currentTimeMillis()
        val load = engine.loadModel(installed, settings, caps)
        val loadMs = System.currentTimeMillis() - loadStart
        assumeTrue("model failed to load: $load", load is LoadResult.Success)

        val system = SystemPrompt.build(settings, installed.displayName, ResponseMode.BALANCED)
        val results = ArrayList<Measurement>()

        suspend fun run(label: String): GenerationStats? {
            val prompt = engine.buildPrompt(
                systemPrompt = system,
                turns = listOf(PromptTurn(ChatRole.USER, PROMPT)),
                reserveTokens = MAX_TOKENS
            )
            var streamed = 0
            return when (val outcome = engine.generate(prompt, settings, caps, MAX_TOKENS) { streamed++ }) {
                is GenerationOutcome.Success -> {
                    assertTrue("nothing was streamed to the UI callback", streamed > 0)
                    // Emit immediately rather than only in the closing summary, so a
                    // measurement survives anything that goes wrong later in the run.
                    Log.i(TAG, describe(label, outcome.stats))
                    println(describe(label, outcome.stats))
                    outcome.stats.also { results.add(Measurement(label, it)) }
                }
                is GenerationOutcome.Failure -> {
                    Log.w(TAG, "generation failed: ${outcome.message}")
                    null
                }
            }
        }

        // 1. Cold: empty KV cache, so the prefill includes the whole system prompt.
        engine.resetContext()
        val cold = run("cold cache")

        // 2. Warm: the system prompt was pre-evaluated, exactly as the app does
        //    right after loading a model.
        engine.resetContext()
        val warm = engine.warmSystemPrompt(system)
        val warmed = run("warm prefix")

        // 3. Follow-up: the cache now also holds the previous exchange.
        val followUp = run("follow-up turn")

        report(loadMs, warm.tokens, warm.millis, results)

        assertTrue("no successful generation", results.isNotEmpty())

        if (cold != null && warmed != null) {
            // The core claim of the warm-prefix optimisation: the second turn
            // evaluates strictly fewer prompt tokens than the cold one.
            assertTrue(
                "warm prefix did not reduce evaluated prompt tokens " +
                    "(cold=${cold.evaluatedTokens}, warm=${warmed.evaluatedTokens})",
                warmed.evaluatedTokens < cold.evaluatedTokens
            )
            assertTrue("warm run cached nothing", warmed.cachedTokens > 0)
        }
        if (followUp != null) {
            assertTrue("follow-up turn did not reuse the cache", followUp.cachedTokens > 0)
        }
        results.forEach {
            assertTrue("${it.label} produced no tokens", it.stats.generatedTokens > 0)
            assertTrue("${it.label} reported no timing", it.stats.totalMs > 0)
        }

        engine.unload()
        engine.shutdown()
    }

    /**
     * One measurement as a single line.
     *
     * Built with plain string templates on purpose: a format-specifier mistake
     * here would throw at the point where the numbers are reported, destroying a
     * measurement that had already been taken.
     */
    private fun describe(label: String, s: GenerationStats): String = buildString {
        append(label.padEnd(16))
        append(" ttft=").append(s.firstTokenMs).append(" ms")
        append("  total=").append(s.totalMs).append(" ms")
        append("  prompt=").append(s.promptTokens)
        append(" (cached ").append(s.cachedTokens)
        append(" / eval ").append(s.evaluatedTokens).append(")")
        append("  prefill=").append(round1(s.promptTokensPerSecond)).append(" tok/s")
        append("  gen=").append(s.generatedTokens)
        append(" @ ").append(round1(s.tokensPerSecond)).append(" tok/s")
        append("  stop=").append(s.stopReason.ifBlank { "-" })
    }

    private fun round1(value: Double): String {
        val scaled = Math.round(value * 10.0)
        return "${scaled / 10}.${scaled % 10}"
    }

    private fun report(
        loadMs: Long,
        warmTokens: Int,
        warmMs: Long,
        results: List<Measurement>
    ) {
        val sb = StringBuilder()
        sb.appendLine("=== PocketAI inference benchmark (CI emulator, x86_64) ===")
        sb.appendLine("model load: $loadMs ms")
        sb.appendLine("warm prefix: $warmTokens tokens in $warmMs ms")
        results.forEach { (label, s) -> sb.appendLine(describe(label, s)) }
        sb.appendLine("=========================================================")
        // Printed to both logcat and the Gradle test output so the numbers end
        // up in the CI log rather than only in a report artifact.
        Log.i(TAG, sb.toString())
        println(sb.toString())
    }

    companion object {
        private const val TAG = "PocketAIBench"
        private const val PROMPT = "In two sentences, explain what a language model is."
        private const val MAX_TOKENS = 48

        /** Small enough for a CI emulator, real enough to be meaningful. */
        private const val MODEL_URL =
            "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/" +
                "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf?download=true"

        private var cached: File? = null

        @JvmStatic
        @BeforeClass
        fun downloadOnce() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val target = File(context.filesDir, "benchmark-model.gguf")
            if (target.exists() && GgufMetadata.read(target) != null) {
                cached = target
                return
            }
            cached = runCatching { download(MODEL_URL, target) }.getOrElse {
                Log.w(TAG, "benchmark model download failed: ${it.message}")
                target.delete()
                null
            }
        }

        private fun download(url: String, target: File): File {
            val partial = File(target.parentFile, target.name + ".part")
            partial.delete()
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PocketAI-benchmark")
            }
            connection.inputStream.use { input ->
                partial.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
            check(GgufMetadata.read(partial) != null) { "downloaded file is not a GGUF model" }
            check(partial.renameTo(target)) { "could not move the downloaded model into place" }
            return target
        }
    }

    private fun obtainModel(): File? = cached
}
