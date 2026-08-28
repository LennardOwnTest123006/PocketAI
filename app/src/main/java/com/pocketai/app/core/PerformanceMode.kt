package com.pocketai.app.core

/**
 * Runtime profiles that trade generation speed against battery and heat.
 * The numbers are applied to the llama.cpp context on load and per request.
 */
enum class PerformanceMode(
    val id: String,
    val title: String,
    val description: String
) {
    MAXIMUM_SPEED(
        id = "max_speed",
        title = "Maximum Speed",
        description = "Uses every performance core and the largest batch size. Fastest tokens per second, highest power draw."
    ),
    BALANCED(
        id = "balanced",
        title = "Balanced",
        description = "Recommended. Keeps generation fast while limiting heat and battery drain."
    ),
    BATTERY_SAVER(
        id = "battery",
        title = "Battery Saver",
        description = "Fewer threads and a shorter context. Slower, but noticeably lighter on the battery."
    );

    /** Threads to hand llama.cpp for this profile on a device with [caps]. */
    fun threadsFor(caps: DeviceCapabilities): Int {
        val perf = caps.performanceCores.coerceAtLeast(1)
        return when (this) {
            MAXIMUM_SPEED -> perf.coerceAtMost(8)
            BALANCED -> (perf - 1).coerceAtLeast(2).coerceAtMost(6)
            BATTERY_SAVER -> 2
        }
    }

    /** Upper bound on the context window for this profile. */
    fun contextCeiling(): Int = when (this) {
        MAXIMUM_SPEED -> 8192
        BALANCED -> 4096
        BATTERY_SAVER -> 2048
    }

    companion object {
        fun fromId(id: String?): PerformanceMode =
            entries.firstOrNull { it.id == id } ?: BALANCED

        /** What PocketAI suggests for this hardware on first run. */
        fun recommendedFor(caps: DeviceCapabilities): PerformanceMode = when (caps.deviceClass) {
            DeviceClass.FLAGSHIP, DeviceClass.HIGH_END -> BALANCED
            DeviceClass.MID_RANGE -> BALANCED
            DeviceClass.ENTRY -> BATTERY_SAVER
        }
    }
}
