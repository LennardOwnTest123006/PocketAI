package com.pocketai.app.core

import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * What the platform currently thinks of the device's temperature.
 *
 * Sustained local inference is one of the few phone workloads that pins every
 * big core for a minute at a time, and a throttled Snapdragon produces *fewer*
 * tokens per second at 8 threads than a cool one does at 4. Backing off is
 * faster, not just cooler.
 */
enum class ThermalLevel(val label: String) {
    UNKNOWN("Unknown"),
    NOMINAL("Normal"),
    WARM("Warm"),
    THROTTLING("Throttling"),
    CRITICAL("Critical");

    /** Multiplier applied to the configured thread count. */
    val threadScale: Float
        get() = when (this) {
            CRITICAL -> 0.5f
            THROTTLING -> 0.65f
            WARM -> 0.85f
            else -> 1f
        }

    val shouldWarnUser: Boolean get() = this == THROTTLING || this == CRITICAL
}

class ThermalMonitor(context: Context) {

    private val powerManager: PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    /** Reads the live thermal status; UNKNOWN on devices that do not report it. */
    fun current(): ThermalLevel {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalLevel.UNKNOWN
        val pm = powerManager ?: return ThermalLevel.UNKNOWN
        return when (runCatching { pm.currentThermalStatus }.getOrNull()) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalLevel.NOMINAL
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.WARM
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.WARM
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.THROTTLING
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalLevel.CRITICAL
            else -> ThermalLevel.UNKNOWN
        }
    }

    /**
     * Applies thermal headroom to a thread count, never dropping below two so
     * generation keeps making progress.
     */
    fun adjustThreads(threads: Int): Int {
        val level = current()
        if (level == ThermalLevel.UNKNOWN || level == ThermalLevel.NOMINAL) return threads
        return (threads * level.threadScale).toInt().coerceAtLeast(2).coerceAtMost(threads)
    }
}
