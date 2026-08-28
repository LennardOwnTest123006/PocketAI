package com.pocketai.app.llm

/**
 * Callback invoked while a GGUF file is being read into memory.
 * Kept as an explicit interface (not a lambda type) because the native side
 * resolves it through JNI by method name.
 */
fun interface LoadProgressListener {
    fun onProgress(progress: Float)
}

/** Receives decoded text as soon as the model produces it. */
fun interface TokenListener {
    fun onToken(piece: String)
}

/**
 * Thin, 1:1 binding over `libpocketai_llm.so`.
 *
 * Nothing in here does any policy work - all decisions live in [InferenceEngine].
 * Every method is blocking and must be called off the main thread.
 */
object LlamaNative {

    @Volatile
    private var libraryLoaded = false

    @Volatile
    var loadError: String? = null
        private set

    @Synchronized
    fun loadLibrary(): Boolean {
        if (libraryLoaded) return true
        return try {
            System.loadLibrary("pocketai_llm")
            libraryLoaded = true
            loadError = null
            true
        } catch (t: Throwable) {
            // A missing or incompatible .so must degrade to a clear message in the
            // UI rather than taking the process down.
            loadError = t.message ?: t.javaClass.simpleName
            false
        }
    }

    val isAvailable: Boolean get() = libraryLoaded

    external fun nativeInit(nativeLibDir: String): Boolean

    external fun nativeBackendInfo(): String

    external fun nativeLoadModel(
        path: String,
        nCtx: Int,
        nThreads: Int,
        nGpuLayers: Int,
        useMmap: Boolean,
        useMlock: Boolean,
        flashAttn: Boolean,
        progress: LoadProgressListener?
    ): Long

    external fun nativeFreeModel(handle: Long)

    external fun nativeModelInfo(handle: Long): String

    external fun nativeApplyChatTemplate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean
    ): String?

    external fun nativeTokenCount(handle: Long, text: String): Int

    external fun nativeContextSize(handle: Long): Int

    external fun nativeRequestStop(handle: Long)

    external fun nativeResetContext(handle: Long)

    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        repeatLastN: Int,
        seed: Int,
        nThreads: Int,
        callback: TokenListener?
    ): String
}
