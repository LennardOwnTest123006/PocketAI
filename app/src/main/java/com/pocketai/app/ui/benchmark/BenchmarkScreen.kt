package com.pocketai.app.ui.benchmark

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketai.app.core.DeviceCapabilities
import com.pocketai.app.data.model.ModelRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(viewModel: BenchmarkViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val env = state.environment

    LaunchedEffect(state.error, state.message) {
        val text = state.error ?: state.message
        if (text != null) {
            snackbarHost.showSnackbar(text)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("Performance") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card("Measured on this device") {
                    Text(
                        "Every number below comes from a real generation run on this phone. " +
                            "Nothing is estimated.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.runBenchmark() },
                            enabled = !state.running,
                            modifier = Modifier.weight(1f)
                        ) { Text("Run benchmark", fontSize = 13.sp) }
                        if (state.running) {
                            OutlinedButton(onClick = viewModel::stop) { Text("Stop", fontSize = 13.sp) }
                        }
                    }
                    if (state.statusText != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(state.statusText.orEmpty(), fontSize = 12.sp)
                        }
                    }
                }
            }

            if (state.runs.isNotEmpty()) {
                item {
                    Card("Results") {
                        state.runs.forEachIndexed { index, run ->
                            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp))
                            RunResult(run)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Cold start pays for the whole system prompt. The warm run has it " +
                                "already in the KV cache, and the follow-up turn also reuses the " +
                                "previous exchange - which is what normal chatting looks like.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Card("Model") {
                    Line("Name", env.modelName)
                    Line("Parameters", env.parameters)
                    Line("Quantization", env.quantization)
                    Line("File size", ModelRepository.formatBytes(env.modelSizeBytes))
                    Line("Architecture", env.architecture)
                    Line("Context window", if (env.contextSize > 0) "${env.contextSize} tokens" else "-")
                }
            }

            item {
                Card("Compute") {
                    Line("Backend", env.backend)
                    Line("CPU threads in use", env.threads.toString())
                    Line("CPU cores", "${env.cpuCores} (${env.performanceCores} fast)")
                    Line("Chipset", env.soc)
                    Line("GPU backend registered", if (env.gpuAvailable) "yes" else "no")
                    Line(
                        "Vulkan",
                        when {
                            env.vulkanUsed -> "in use"
                            env.vulkanSupported -> "driver present, not built into this APK"
                            else -> "not supported by this device"
                        }
                    )
                    Line("NNAPI", if (env.nnapiSupported) "device supports it" else "not available")
                    Text(
                        env.nnapiNote,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (env.devices.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        env.devices.forEach {
                            Text(
                                it,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Card("Memory and temperature") {
                    Line("Device RAM", DeviceCapabilities.formatGb(env.totalRamBytes))
                    Line("PocketAI process (PSS)", ModelRepository.formatBytes(env.processMemoryBytes))
                    Line("Thermal status", env.thermal.label)
                    if (env.thermal.shouldWarnUser) {
                        Text(
                            "The device is throttling, so thread count has been reduced " +
                                "automatically. Measurements taken now will be slower than normal.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            item {
                Card("Thread sweep") {
                    Text(
                        "Measures tokens per second at each thread count instead of assuming " +
                            "more is faster. Past the fast-core count it usually is not.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    state.threadSweep.forEach { result ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(
                                "${result.threads} threads",
                                fontSize = 13.sp,
                                modifier = Modifier.width(110.dp)
                            )
                            Text(
                                "%.1f tok/s".format(result.tokensPerSecond),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${result.ttftMs} ms TTFT",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.runThreadSweep() },
                            enabled = !state.running,
                            modifier = Modifier.weight(1f)
                        ) { Text("Run sweep", fontSize = 13.sp) }
                        if (state.threadSweep.isNotEmpty()) {
                            Button(
                                onClick = viewModel::applyBestThreads,
                                enabled = !state.running
                            ) { Text("Use fastest", fontSize = 13.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunResult(run: BenchmarkRun) {
    Column {
        Text(run.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Line("Time to first token", "%.2f s".format(run.ttftSeconds))
        Line("Total time", "%.2f s".format(run.totalSeconds))
        Line(
            "Prompt tokens",
            "${run.stats.promptTokens} (${run.stats.cachedTokens} cached, " +
                "${run.stats.evaluatedTokens} evaluated)"
        )
        Line("Prompt processing", "${run.stats.promptMs} ms")
        Line("Prefill speed", "%.1f tok/s".format(run.stats.promptTokensPerSecond))
        Line("Generated tokens", run.stats.generatedTokens.toString())
        Line("Generation time", "${run.stats.decodeMs} ms")
        Line("Generation speed", "%.1f tok/s".format(run.stats.tokensPerSecond))
        Line("Cache hit", "%.0f%%".format(run.stats.cacheHitRatio * 100))
        Line("Threads", run.threads.toString())
        Line("Stop reason", run.stats.stopReason.ifBlank { "-" })
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(165.dp)
        )
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
