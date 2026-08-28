package com.pocketai.app.data.model

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Facts read straight out of a GGUF file's header.
 *
 * This lets the model manager show a real architecture, quantisation and
 * parameter count for files the user imported themselves, without having to
 * load the weights first.
 */
data class GgufMetadata(
    val architecture: String?,
    val name: String?,
    val quantization: String?,
    val parameterCount: Long,
    val contextLength: Int,
    val blockCount: Int,
    val embeddingLength: Int,
    val chatTemplatePresent: Boolean,
    val version: Int
) {
    companion object {

        private const val MAGIC = 0x46554747 // "GGUF" little-endian

        // Mirrors llama_ftype; only the values that actually ship as GGUF builds.
        private val FILE_TYPES = mapOf(
            0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1",
            7 to "Q8_0", 8 to "Q5_0", 9 to "Q5_1", 10 to "Q2_K",
            11 to "Q3_K_S", 12 to "Q3_K_M", 13 to "Q3_K_L",
            14 to "Q4_K_S", 15 to "Q4_K_M", 16 to "Q5_K_S", 17 to "Q5_K_M",
            18 to "Q6_K", 19 to "IQ2_XXS", 20 to "IQ2_XS", 21 to "Q2_K_S",
            22 to "IQ3_XS", 23 to "IQ3_XXS", 24 to "IQ1_S", 25 to "IQ4_NL",
            26 to "IQ3_S", 27 to "IQ3_M", 28 to "IQ2_S", 29 to "IQ2_M",
            30 to "IQ4_XS", 31 to "IQ1_M", 32 to "BF16", 36 to "TQ1_0", 37 to "TQ2_0"
        )

        /** Returns null when the file is not a readable GGUF container. */
        fun read(file: File): GgufMetadata? = try {
            RandomAccessFile(file, "r").use { raf -> parse(raf) }
        } catch (_: Throwable) {
            null
        }

        private fun parse(raf: RandomAccessFile): GgufMetadata? {
            val reader = Reader(raf)
            if (reader.u32() != MAGIC) return null
            val version = reader.u32()
            if (version < 2 || version > 3) return null

            val tensorCount = reader.u64()
            val kvCount = reader.u64()
            if (tensorCount < 0 || kvCount < 0 || kvCount > 100_000) return null

            var architecture: String? = null
            var name: String? = null
            var fileType: Int? = null
            var chatTemplate = false
            val numeric = HashMap<String, Long>()

            for (i in 0 until kvCount) {
                val key = reader.string() ?: return null
                val value = reader.value() ?: return null
                when {
                    key == "general.architecture" -> architecture = value as? String
                    key == "general.name" -> name = value as? String
                    key == "general.file_type" -> fileType = (value as? Long)?.toInt()
                    key == "tokenizer.chat_template" -> chatTemplate = value is String
                    value is Long -> numeric[key] = value
                }
                if (reader.exhausted()) break
            }

            val arch = architecture ?: ""
            val contextLength = numeric["$arch.context_length"]?.toInt() ?: 0
            val blockCount = numeric["$arch.block_count"]?.toInt() ?: 0
            val embeddingLength = numeric["$arch.embedding_length"]?.toInt() ?: 0

            val params = runCatching { reader.sumTensorParameters(tensorCount) }.getOrDefault(0L)

            return GgufMetadata(
                architecture = architecture,
                name = name,
                quantization = fileType?.let { FILE_TYPES[it] ?: "type $it" },
                parameterCount = params,
                contextLength = contextLength,
                blockCount = blockCount,
                embeddingLength = embeddingLength,
                chatTemplatePresent = chatTemplate,
                version = version
            )
        }

        /** Human readable parameter count, e.g. 1.5B / 494M. */
        fun formatParameters(count: Long): String = when {
            count <= 0 -> "unknown"
            count >= 1_000_000_000L -> String.format("%.1fB", count / 1_000_000_000.0)
            count >= 1_000_000L -> String.format("%.0fM", count / 1_000_000.0)
            else -> count.toString()
        }
    }

    /** Buffered little-endian reader over the GGUF header region. */
    private class Reader(private val raf: RandomAccessFile) {
        private val length = raf.length()
        private var buf = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
        private var bufStart = 0L
        private var bufLimit = 0

        init {
            fill(0L)
        }

        var position: Long = 0L
            private set

        fun exhausted(): Boolean = position >= length

        private fun fill(from: Long) {
            raf.seek(from)
            val arr = buf.array()
            var read = 0
            while (read < arr.size) {
                val n = raf.read(arr, read, arr.size - read)
                if (n <= 0) break
                read += n
            }
            bufStart = from
            bufLimit = read
        }

        private fun ensure(n: Int) {
            if (position < bufStart || position + n > bufStart + bufLimit) {
                fill(position)
                if (bufLimit < n) throw IllegalStateException("truncated gguf")
            }
            buf.position((position - bufStart).toInt())
        }

        fun u32(): Int {
            ensure(4)
            val v = buf.int
            position += 4
            return v
        }

        fun u64(): Long {
            ensure(8)
            val v = buf.long
            position += 8
            return v
        }

        private fun f32(): Float {
            ensure(4); val v = buf.float; position += 4; return v
        }

        private fun f64(): Double {
            ensure(8); val v = buf.double; position += 8; return v
        }

        private fun u8(): Int {
            ensure(1); val v = buf.get().toInt() and 0xFF; position += 1; return v
        }

        private fun u16(): Int {
            ensure(2); val v = buf.short.toInt() and 0xFFFF; position += 2; return v
        }

        fun string(): String? {
            val len = u64()
            if (len < 0 || len > 1 shl 22) return null
            val bytes = ByteArray(len.toInt())
            var read = 0
            while (read < bytes.size) {
                // ensure() may refill the buffer, so only measure what is
                // available afterwards - otherwise a string straddling a buffer
                // boundary reads past the end of the window.
                ensure(1)
                val available = (bufStart + bufLimit - position).toInt()
                if (available <= 0) return null
                val take = minOf(available, bytes.size - read)
                buf.get(bytes, read, take)
                read += take
                position += take
            }
            return String(bytes, Charsets.UTF_8)
        }

        /** Returns String, Long or Double for scalars; the element count for arrays. */
        fun value(): Any? = readTyped(u32())

        private fun readTyped(type: Int): Any? = when (type) {
            0 -> u8().toLong()
            1 -> u8().toByte().toLong()
            2 -> u16().toLong()
            3 -> u16().toShort().toLong()
            4 -> (u32().toLong() and 0xFFFFFFFFL)
            5 -> u32().toLong()
            6 -> f32().toDouble()
            7 -> u8().toLong()
            8 -> string()
            9 -> readArray()
            10 -> u64()
            11 -> u64()
            12 -> f64()
            else -> null
        }

        private fun readArray(): Any? {
            val elemType = u32()
            val count = u64()
            if (count < 0 || count > 5_000_000) return null
            for (i in 0 until count) {
                if (readTyped(elemType) == null && elemType == 8) return null
            }
            return count
        }

        /** Walks the tensor table and multiplies out every shape. */
        fun sumTensorParameters(tensorCount: Long): Long {
            if (tensorCount <= 0 || tensorCount > 100_000) return 0L
            var total = 0L
            for (i in 0 until tensorCount) {
                string() ?: return total
                val nDims = u32()
                if (nDims < 0 || nDims > 4) return total
                var elems = 1L
                for (d in 0 until nDims) {
                    val dim = u64()
                    if (dim <= 0) return total
                    elems *= dim
                }
                u32()   // ggml type
                u64()   // offset
                total += elems
            }
            return total
        }
    }
}
