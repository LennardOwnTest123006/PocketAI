package com.pocketai.app.data.model

import kotlinx.serialization.Serializable

/** A GGUF file that is present on this device and ready to load. */
@Serializable
data class InstalledModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val catalogId: String? = null,
    val architecture: String? = null,
    val quantization: String? = null,
    val parameterCount: Long = 0L,
    val trainedContextLength: Int = 0,
    val supportsThinking: Boolean = false,
    val importedAtMillis: Long = 0L,
    val source: ModelSource = ModelSource.DOWNLOAD
) {
    val parametersLabel: String get() = GgufMetadata.formatParameters(parameterCount)

    /** Weights plus a typical KV cache; what the user should expect to see in RAM. */
    val estimatedRamBytes: Long get() = (sizeBytes * 1.25).toLong() + 220L * 1024 * 1024
}

@Serializable
enum class ModelSource { DOWNLOAD, IMPORT, CUSTOM_URL }
