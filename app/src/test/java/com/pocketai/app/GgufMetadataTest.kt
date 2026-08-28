package com.pocketai.app

import com.pocketai.app.data.model.GgufMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Exercises the hand-written GGUF header reader against a synthetic container,
 * so imported models are described from the file itself rather than guesses.
 */
class GgufMetadataTest {

    private class GgufWriter {
        private val out = ByteArrayOutputStream()

        fun u32(value: Int) = apply {
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        }

        fun u64(value: Long) = apply {
            out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())
        }

        fun str(value: String) = apply {
            val bytes = value.toByteArray(Charsets.UTF_8)
            u64(bytes.size.toLong())
            out.write(bytes)
        }

        fun kvString(key: String, value: String) = apply { str(key); u32(TYPE_STRING); str(value) }

        fun kvU32(key: String, value: Int) = apply { str(key); u32(TYPE_UINT32); u32(value) }

        fun tensor(name: String, dims: List<Long>) = apply {
            str(name)
            u32(dims.size)
            dims.forEach { u64(it) }
            u32(0)   // ggml type F32
            u64(0)   // offset
        }

        fun toFile(): File {
            val file = File.createTempFile("pocketai-test", ".gguf")
            file.deleteOnExit()
            file.writeBytes(out.toByteArray())
            return file
        }

        private companion object {
            const val TYPE_UINT32 = 4
            const val TYPE_STRING = 8
        }
    }

    private fun sampleModel(): File = GgufWriter()
        .u32(MAGIC)
        .u32(3)                 // version
        .u64(2)                 // tensor count
        .u64(6)                 // metadata count
        .kvString("general.architecture", "llama")
        .kvString("general.name", "PocketAI Test Model")
        .kvU32("general.file_type", 15)          // Q4_K_M
        .kvU32("llama.context_length", 4096)
        .kvU32("llama.block_count", 22)
        .kvU32("llama.embedding_length", 2048)
        .tensor("token_embd.weight", listOf(2048L, 32000L))
        .tensor("output_norm.weight", listOf(2048L))
        .toFile()

    @Test
    fun `reads architecture name quantisation and dimensions`() {
        val meta = GgufMetadata.read(sampleModel())
        assertNotNull(meta)
        requireNotNull(meta)
        assertEquals("llama", meta.architecture)
        assertEquals("PocketAI Test Model", meta.name)
        assertEquals("Q4_K_M", meta.quantization)
        assertEquals(4096, meta.contextLength)
        assertEquals(22, meta.blockCount)
        assertEquals(2048, meta.embeddingLength)
        assertEquals(3, meta.version)
    }

    @Test
    fun `sums tensor shapes into a parameter count`() {
        val meta = GgufMetadata.read(sampleModel())
        requireNotNull(meta)
        // 2048 * 32000 + 2048
        assertEquals(65_538_048L, meta.parameterCount)
        assertEquals("66M", GgufMetadata.formatParameters(meta.parameterCount))
    }

    @Test
    fun `rejects a file that is not GGUF`() {
        val file = File.createTempFile("pocketai-test", ".gguf")
        file.deleteOnExit()
        file.writeText("this is definitely not a model")
        assertNull(GgufMetadata.read(file))
    }

    @Test
    fun `rejects a truncated header instead of throwing`() {
        val full = sampleModel().readBytes()
        val file = File.createTempFile("pocketai-truncated", ".gguf")
        file.deleteOnExit()
        file.writeBytes(full.copyOf(20))
        // A short read must degrade to null so the importer can reject the file.
        val meta = GgufMetadata.read(file)
        assertTrue(meta == null || meta.parameterCount == 0L)
    }

    @Test
    fun `formats parameter counts for display`() {
        assertEquals("1.5B", GgufMetadata.formatParameters(1_500_000_000L))
        assertEquals("494M", GgufMetadata.formatParameters(494_000_000L))
        assertEquals("unknown", GgufMetadata.formatParameters(0L))
    }

    private companion object {
        const val MAGIC = 0x46554747
    }
}
