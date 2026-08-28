package com.pocketai.app

import com.pocketai.app.core.DeviceCapabilities
import com.pocketai.app.core.DeviceClass
import com.pocketai.app.core.PerformanceMode
import com.pocketai.app.data.model.ModelCatalog
import com.pocketai.app.data.model.ModelFit
import com.pocketai.app.data.model.ModelTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model catalogue must never propose something that would exhaust a
 * device's memory or storage - that is the difference between a usable app and
 * a phone that stops responding mid-download.
 */
class ModelRecommendationTest {

    private fun device(
        ramGb: Double,
        freeStorageGb: Double = 64.0,
        cores: Int = 8
    ) = DeviceCapabilities(
        totalRamBytes = (ramGb * GB).toLong(),
        availableRamBytes = (ramGb * GB * 0.5).toLong(),
        cpuCores = cores,
        performanceCores = cores / 2 + 1,
        supportedAbis = listOf("arm64-v8a"),
        is64Bit = true,
        availableStorageBytes = (freeStorageGb * GB).toLong(),
        totalStorageBytes = (256 * GB).toLong(),
        socModel = "Qualcomm SM8650",
        manufacturer = "samsung",
        deviceModel = "SM-F741B",
        androidRelease = "14",
        sdkInt = 34,
        isLowRamDevice = false
    )

    @Test
    fun `never recommends a model the device cannot hold in memory`() {
        val entry = device(ramGb = 3.0)
        val recommended = ModelCatalog.recommendedFor(entry)
        assertTrue(recommended.isNotEmpty())
        recommended.forEach { model ->
            assertTrue(
                "${model.displayName} needs ${model.minRamGb} GB on a 3 GB device",
                model.minRamGb <= entry.totalRamGb
            )
        }
        assertFalse(recommended.any { it.id == "qwen3-4b-q4km" })
    }

    @Test
    fun `flagship gets the larger models`() {
        val flagship = device(ramGb = 12.0)
        assertEquals(DeviceClass.FLAGSHIP, flagship.deviceClass)
        val recommended = ModelCatalog.recommendedFor(flagship)
        assertTrue(recommended.any { it.id == "qwen3-4b-q4km" })
    }

    @Test
    fun `default choice fits the memory budget`() {
        listOf(3.0, 4.0, 6.0, 8.0, 12.0, 16.0).forEach { ram ->
            val caps = device(ramGb = ram)
            val choice = ModelCatalog.defaultFor(caps)
            assertTrue(
                "picked ${choice.displayName} on a ${ram} GB device",
                choice.minRamGb <= caps.totalRamGb
            )
        }
    }

    @Test
    fun `the default is chosen for speed, not for size`() {
        // The old rule was "largest model that fits", which on a flagship meant
        // a 4B reasoning model and roughly a hundred seconds per answer.
        val flagship = device(ramGb = 12.0)
        val choice = ModelCatalog.defaultFor(flagship)
        val largest = ModelCatalog.recommendedFor(flagship).maxByOrNull { it.approxSizeBytes }!!

        assertEquals(ModelTier.BALANCED, choice.tier)
        assertTrue(
            "default ${choice.displayName} should not be the largest option",
            choice.approxSizeBytes < largest.approxSizeBytes
        )
        assertTrue(
            "default should decode meaningfully faster than the largest model",
            choice.estimatedTokensPerSecond > largest.estimatedTokensPerSecond
        )
    }

    @Test
    fun `recommendations are ordered fastest first`() {
        val speeds = ModelCatalog.recommendedFor(device(ramGb = 12.0))
            .map { it.estimatedTokensPerSecond }
        assertEquals(speeds.sortedDescending(), speeds)
    }

    @Test
    fun `constrained devices still get a runnable default`() {
        val modest = device(ramGb = 3.0)
        val choice = ModelCatalog.defaultFor(modest)
        assertEquals(ModelFit.GOOD, choice.fits(modest))
        assertEquals(ModelTier.FASTEST, choice.tier)
    }

    @Test
    fun `every tier is represented and speeds are ordered by tier`() {
        ModelTier.entries.forEach { tier ->
            assertTrue("no models in $tier", ModelCatalog.models.any { it.tier == tier })
        }
        val fastest = ModelCatalog.models.filter { it.tier == ModelTier.FASTEST }
            .minOf { it.estimatedTokensPerSecond }
        val smartest = ModelCatalog.models.filter { it.tier == ModelTier.SMARTEST }
            .maxOf { it.estimatedTokensPerSecond }
        assertTrue("fastest tier must outrun the smartest tier", fastest > smartest)
    }

    @Test
    fun `low storage blocks a download regardless of memory`() {
        // 0.2 GB free is below even the smallest model plus its 10% margin.
        val caps = device(ramGb = 12.0, freeStorageGb = 0.2)
        val large = ModelCatalog.byId("qwen3-4b-q4km")!!
        assertEquals(ModelFit.NOT_ENOUGH_STORAGE, large.fits(caps))
        assertTrue(ModelCatalog.recommendedFor(caps).isEmpty())
    }

    @Test
    fun `tight fit is flagged rather than hidden`() {
        // 6 GB clears the 3B model's 6 GB minimum but not its 8 GB recommendation.
        val caps = device(ramGb = 6.0)
        val model = ModelCatalog.byId("llama3.2-3b-instruct-q4km")!!
        assertEquals(ModelFit.TIGHT, model.fits(caps))
    }

    @Test
    fun `estimated ram always exceeds the download size`() {
        ModelCatalog.models.forEach { model ->
            assertTrue(
                "${model.displayName} understates its memory use",
                model.estimatedRamBytes > model.approxSizeBytes
            )
        }
    }

    @Test
    fun `catalogue entries are well formed`() {
        val ids = ModelCatalog.models.map { it.id }
        assertEquals("duplicate catalogue ids", ids.size, ids.toSet().size)
        ModelCatalog.models.forEach { model ->
            assertTrue(model.downloadUrl.startsWith("https://"))
            assertTrue(model.fileName.endsWith(".gguf"))
            assertTrue(model.recommendedRamGb >= model.minRamGb)
            assertTrue(model.approxSizeBytes > 0)
        }
    }

    @Test
    fun `performance modes scale threads with the device and never exceed it`() {
        val octa = device(ramGb = 12.0, cores = 8)
        val quad = device(ramGb = 4.0, cores = 4)
        listOf(octa, quad).forEach { caps ->
            PerformanceMode.entries.forEach { mode ->
                val threads = mode.threadsFor(caps)
                assertTrue("$mode produced $threads threads", threads in 1..caps.cpuCores)
            }
        }
        assertTrue(
            PerformanceMode.MAXIMUM_SPEED.threadsFor(octa) >
                PerformanceMode.BATTERY_SAVER.threadsFor(octa)
        )
    }

    @Test
    fun `entry level devices are steered to battery saver`() {
        assertEquals(PerformanceMode.BATTERY_SAVER, PerformanceMode.recommendedFor(device(ramGb = 3.0)))
        assertEquals(PerformanceMode.BALANCED, PerformanceMode.recommendedFor(device(ramGb = 12.0)))
    }

    private companion object {
        const val GB = 1024.0 * 1024.0 * 1024.0
    }
}
