package com.pocketai.app.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketai.app.core.DeviceCapabilities
import com.pocketai.app.core.PerformanceMode
import com.pocketai.app.data.model.CatalogModel
import com.pocketai.app.data.model.DownloadStatus
import com.pocketai.app.data.model.ModelCatalog
import com.pocketai.app.data.model.ModelFit
import com.pocketai.app.data.model.ModelRepository
import com.pocketai.app.ui.models.ModelsViewModel
import kotlinx.coroutines.launch

/**
 * First-run setup: explain what PocketAI is, install a model, choose a
 * performance profile. No account is ever requested.
 */
@Composable
fun OnboardingScreen(
    viewModel: ModelsViewModel,
    onFinished: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val download by viewModel.downloadState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var step by remember { mutableIntStateOf(0) }
    val caps = uiState.capabilities
    val hasModel = uiState.installed.isNotEmpty()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importModel(uri) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp)
        ) {
            StepDots(step = step, total = 3)
            Spacer(Modifier.height(28.dp))

            AnimatedVisibility(
                visible = step == 0,
                enter = fadeIn() + slideInVertically { it / 6 }
            ) {
                WelcomeStep(caps)
            }
            AnimatedVisibility(
                visible = step == 1,
                enter = fadeIn() + slideInVertically { it / 6 }
            ) {
                ModelStep(
                    caps = caps,
                    hasModel = hasModel,
                    installedName = uiState.installed.firstOrNull()?.displayName,
                    downloadStatus = download.status,
                    downloadName = download.displayName,
                    downloadProgress = download.progress,
                    downloadedBytes = download.downloadedBytes,
                    totalBytes = download.totalBytes,
                    speed = download.bytesPerSecond,
                    message = download.message,
                    onDownload = { viewModel.download(it) },
                    onCancel = viewModel::cancelDownload,
                    onImport = { runCatching { importLauncher.launch(arrayOf("*/*")) } }
                )
            }
            AnimatedVisibility(
                visible = step == 2,
                enter = fadeIn() + slideInVertically { it / 6 }
            ) {
                PerformanceStep(
                    caps = caps,
                    selected = settings.performanceMode,
                    onSelect = { mode -> scope.launch { viewModel.settingsPrefs.setPerformanceMode(mode) } }
                )
            }

            Spacer(Modifier.height(28.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (step > 0) {
                    OutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f)) {
                        Text("Back")
                    }
                }
                Button(
                    onClick = {
                        if (step < 2) step++ else {
                            scope.launch {
                                viewModel.settingsPrefs.setOnboardingComplete(true)
                                onFinished()
                            }
                        }
                    },
                    enabled = step != 1 || hasModel || download.status == DownloadStatus.COMPLETED,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (step < 2) "Continue" else "Start chatting")
                }
            }

            if (step == 1 && !hasModel) {
                TextButton(
                    onClick = {
                        scope.launch {
                            viewModel.settingsPrefs.setOnboardingComplete(true)
                            onFinished()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Skip for now", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun StepDots(step: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { index ->
            Box(
                Modifier
                    .height(4.dp)
                    .width(if (index == step) 26.dp else 14.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (index <= step) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest
                    )
            )
        }
    }
}

@Composable
private fun WelcomeStep(caps: DeviceCapabilities?) {
    Column {
        Text(
            "Welcome to PocketAI",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "A complete AI assistant that runs on your phone instead of someone else's server.",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(26.dp))
        Feature(
            Icons.Outlined.Memory,
            "The model lives on your device",
            "PocketAI loads a quantised language model into memory and generates every answer " +
                "locally with an ARM64-optimised inference engine."
        )
        Feature(
            Icons.Outlined.Lock,
            "Private by default",
            "No account, no sign-in, no analytics. Your conversations stay in a private database " +
                "on this phone and are never uploaded."
        )
        Feature(
            Icons.Outlined.CloudOff,
            "Works offline",
            "Chat, summarise, format, export - all of it works with aeroplane mode on. " +
                "Only optional web search needs a connection, and you control that switch."
        )
        if (caps != null) {
            Spacer(Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Detected: ${caps.manufacturer} ${caps.deviceModel} · " +
                        "${DeviceCapabilities.formatGb(caps.totalRamBytes)} RAM · " +
                        "${caps.cpuCores} CPU cores · " +
                        "${ModelRepository.formatBytes(caps.availableStorageBytes)} free",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun ModelStep(
    caps: DeviceCapabilities?,
    hasModel: Boolean,
    installedName: String?,
    downloadStatus: DownloadStatus,
    downloadName: String,
    downloadProgress: Float,
    downloadedBytes: Long,
    totalBytes: Long,
    speed: Long,
    message: String?,
    onDownload: (CatalogModel) -> Unit,
    onCancel: () -> Unit,
    onImport: () -> Unit
) {
    val recommended = remember(caps) {
        if (caps != null) ModelCatalog.defaultFor(caps) else ModelCatalog.models.first()
    }
    val alternatives = remember(caps) {
        ModelCatalog.models.filter { it.id != recommended.id }
            .filter { caps == null || it.fits(caps) != ModelFit.NOT_ENOUGH_RAM }
            .take(3)
    }

    Column {
        Text("Choose your model", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "PocketAI needs one local model file to think with. " +
                "Nothing is downloaded until you choose.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        if (hasModel && downloadStatus != DownloadStatus.RUNNING) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Model ready",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        installedName ?: "",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        if (downloadStatus == DownloadStatus.RUNNING || downloadStatus == DownloadStatus.CONNECTING) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(downloadName, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    if (totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = ModelRepository.formatBytes(downloadedBytes) +
                            (if (totalBytes > 0) " of ${ModelRepository.formatBytes(totalBytes)}" else "") +
                            (if (speed > 0) "  ·  ${ModelRepository.formatBytes(speed)}/s" else ""),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onCancel) { Text("Cancel", fontSize = 13.sp) }
                }
            }
        } else if (!hasModel) {
            RecommendedModelCard(recommended, caps, onDownload)
            if (message != null) {
                Spacer(Modifier.height(8.dp))
                Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Other options",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            alternatives.forEach { model ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDownload(model) }
                        .padding(vertical = 9.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(model.displayName, fontSize = 14.sp)
                        Text(
                            "${model.parametersLabel} · ${ModelRepository.formatBytes(model.approxSizeBytes)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "Download",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.FolderOpen, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text("Import a .gguf file I already have", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun RecommendedModelCard(
    model: CatalogModel,
    caps: DeviceCapabilities?,
    onDownload: (CatalogModel) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Recommended for your device",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                model.displayName,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(6.dp))
            Text(
                model.description,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Download ${ModelRepository.formatBytes(model.approxSizeBytes)} · " +
                    "about ${ModelRepository.formatBytes(model.estimatedRamBytes)} RAM while loaded" +
                    (caps?.let { " · ${ModelRepository.formatBytes(it.availableStorageBytes)} free" } ?: ""),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = { onDownload(model) }, modifier = Modifier.fillMaxWidth()) {
                Text("Download ${model.displayName}")
            }
        }
    }
}

@Composable
private fun PerformanceStep(
    caps: DeviceCapabilities?,
    selected: PerformanceMode,
    onSelect: (PerformanceMode) -> Unit
) {
    val recommended = caps?.let { PerformanceMode.recommendedFor(it) } ?: PerformanceMode.BALANCED
    Column {
        Text("How should it run?", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "You can change this at any time in Settings.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(18.dp))
        PerformanceMode.entries.forEach { mode ->
            val active = mode == selected
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (active) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .clickable { onSelect(mode) }
            ) {
                Column(Modifier.padding(15.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                            tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(mode.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        if (mode == recommended) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Recommended",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        mode.description,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(Modifier.padding(bottom = 18.dp), verticalAlignment = Alignment.Top) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                body,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start
            )
        }
    }
}
