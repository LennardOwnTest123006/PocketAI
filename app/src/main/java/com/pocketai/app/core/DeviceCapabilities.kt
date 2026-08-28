package com.pocketai.app.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File

/**
 * A snapshot of what this handset can realistically do.
 *
 * Everything here is measured, never assumed - model recommendations, thread
 * counts and the warnings shown before a download are all derived from it.
 */
data class DeviceCapabilities(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val cpuCores: Int,
    val performanceCores: Int,
    val supportedAbis: List<String>,
    val is64Bit: Boolean,
    val availableStorageBytes: Long,
    val totalStorageBytes: Long,
    val socModel: String,
    val manufacturer: String,
    val deviceModel: String,
    val androidRelease: String,
    val sdkInt: Int,
    val isLowRamDevice: Boolean
) {
    val totalRamGb: Double get() = totalRamBytes / GB
    val availableStorageGb: Double get() = availableStorageBytes / GB

    /** Rough ceiling for a model file, leaving headroom for the OS and the KV cache. */
    val recommendedMaxModelBytes: Long
        get() {
            // A quantised model is memory-mapped, but the OS still needs room for
            // the resident pages plus the KV cache. Half of total RAM is a safe,
            // widely-used heuristic that keeps the phone responsive.
            val budget = (totalRamBytes * 0.45).toLong()
            return budget.coerceAtMost(availableStorageBytes)
        }

    val deviceClass: DeviceClass
        get() = when {
            totalRamGb >= 11.0 && cpuCores >= 8 -> DeviceClass.FLAGSHIP
            totalRamGb >= 7.0 -> DeviceClass.HIGH_END
            totalRamGb >= 5.0 -> DeviceClass.MID_RANGE
            else -> DeviceClass.ENTRY
        }

    /** True for the Galaxy Z Flip series, which PocketAI treats as its reference device. */
    val isFlipFoldable: Boolean
        get() = manufacturer.equals("samsung", ignoreCase = true) &&
            (deviceModel.contains("Flip", ignoreCase = true) || Build.DEVICE.startsWith("b6q") ||
                Build.DEVICE.startsWith("b5q") || Build.DEVICE.startsWith("b7q"))

    companion object {
        const val GB = 1024.0 * 1024.0 * 1024.0

        fun read(context: Context): DeviceCapabilities {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)

            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

            val dataDir: File = context.filesDir
            val stat = StatFs(dataDir.absolutePath)
            val available = stat.availableBytes
            val total = stat.totalBytes

            return DeviceCapabilities(
                totalRamBytes = mi.totalMem,
                availableRamBytes = mi.availMem,
                cpuCores = cores,
                performanceCores = estimatePerformanceCores(cores),
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
                is64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty(),
                availableStorageBytes = available,
                totalStorageBytes = total,
                socModel = socName(),
                manufacturer = Build.MANUFACTURER ?: "",
                deviceModel = Build.MODEL ?: "",
                androidRelease = Build.VERSION.RELEASE ?: "",
                sdkInt = Build.VERSION.SDK_INT,
                isLowRamDevice = am.isLowRamDevice
            )
        }

        private fun socName(): String {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manufacturer = Build.SOC_MANUFACTURER
                val model = Build.SOC_MODEL
                if (!model.isNullOrBlank() && model != Build.UNKNOWN) {
                    return listOf(manufacturer, model)
                        .filter { !it.isNullOrBlank() && it != Build.UNKNOWN }
                        .joinToString(" ")
                }
            }
            return Build.HARDWARE ?: "unknown"
        }

        /**
         * Android does not expose the big/little split, so we approximate it.
         * Typical modern ARM layouts dedicate roughly half the cores to
         * performance; using all of them makes generation slower, not faster,
         * because the little cores stall the batch.
         */
        private fun estimatePerformanceCores(cores: Int): Int = when {
            cores >= 8 -> cores / 2 + 1
            cores >= 6 -> 4
            cores >= 4 -> 3
            else -> cores
        }

        /** e.g. "12.0 GB" - used in memory warnings. */
        fun formatGb(bytes: Long): String = String.format("%.1f GB", bytes / GB)

        fun externalStorageAvailable(): Long = try {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            stat.availableBytes
        } catch (_: Throwable) {
            0L
        }
    }
}

enum class DeviceClass(val label: String) {
    ENTRY("Entry level"),
    MID_RANGE("Mid range"),
    HIGH_END("High end"),
    FLAGSHIP("Flagship")
}
