package com.pocketai.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketai.app.llm.LlamaNative
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the on-device inference stack actually comes up on a real Android
 * runtime: the JNI library loads, ggml dlopens a CPU backend out of the app's
 * native library directory, and a device is registered.
 *
 * This is the part that a successful Gradle build cannot tell you about - under
 * GGML_BACKEND_DL the CPU kernels live in separate .so files, so "it compiled"
 * and "it can run a model" are different claims.
 */
@RunWith(AndroidJUnit4::class)
class NativeEngineTest {

    @Test
    fun nativeLibraryLoadsAndRegistersACpuBackend() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        assertTrue(
            "libpocketai_llm.so failed to load: ${LlamaNative.loadError}",
            LlamaNative.loadLibrary()
        )

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        assertTrue("nativeLibraryDir is empty", !nativeLibDir.isNullOrBlank())

        assertTrue(
            "llama/ggml backend initialisation failed",
            LlamaNative.nativeInit(nativeLibDir)
        )

        val info = JSONObject(LlamaNative.nativeBackendInfo())
        val devices = info.getJSONArray("devices")
        assertTrue(
            "ggml registered no compute devices - no backend .so was loaded",
            devices.length() >= 1
        )

        var sawCpu = false
        val names = StringBuilder()
        for (i in 0 until devices.length()) {
            val device = devices.getJSONObject(i)
            names.append(device.optString("name")).append(' ')
            // GGML_BACKEND_DEVICE_TYPE_CPU == 0
            if (device.optInt("type") == 0) sawCpu = true
        }
        assertTrue("no CPU backend among devices: $names", sawCpu)
    }

    @Test
    fun tokenCountingRejectsAnAbsentModelInsteadOfCrashing() {
        assertTrue(LlamaNative.loadLibrary())
        // Handle 0 is the "no model loaded" case; it must return a sentinel
        // rather than dereference a null session.
        assertTrue(LlamaNative.nativeTokenCount(0L, "hello") < 0)
        assertTrue(LlamaNative.nativeContextSize(0L) == 0)
        // These must be safe no-ops on a null handle.
        LlamaNative.nativeRequestStop(0L)
        LlamaNative.nativeResetContext(0L)
        LlamaNative.nativeFreeModel(0L)
    }

    @Test
    fun generateWithoutAModelReportsAnErrorRatherThanFailing() {
        assertTrue(LlamaNative.loadLibrary())
        val result = LlamaNative.nativeGenerate(
            handle = 0L,
            prompt = "hello",
            maxTokens = 8,
            temperature = 0.7f,
            topP = 0.95f,
            topK = 40,
            minP = 0.05f,
            repeatPenalty = 1.1f,
            repeatLastN = 64,
            seed = -1,
            nThreads = 2,
            callback = null
        )
        assertTrue("expected a structured error, got: $result", result.contains("no_model"))
    }
}
