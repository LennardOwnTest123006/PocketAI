package com.pocketai.app.ui.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketai.app.core.DeviceCapabilities
import com.pocketai.app.data.model.CatalogModel
import com.pocketai.app.data.model.DownloadStatus
import com.pocketai.app.data.model.InstalledModel
import com.pocketai.app.data.model.ModelFit
import com.pocketai.app.data.model.ModelRepository
import com.pocketai.app.data.model.ModelSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(viewModel: ModelsViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val download by viewModel.downloadState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    var confirmDownload by remember { mutableStateOf<CatalogModel?>(null) }
    var confirmDelete by remember { mutableStateOf<InstalledModel?>(null) }
    var showCustomUrl by remember { mutableStateOf(false) }
    var detailsFor by remember { mutableStateOf<InstalledModel?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importModel(uri) }

    LaunchedEffect(uiState.message, uiState.error) {
        val text = uiState.error ?: uiState.message
        if (text != null) {
            snackbarHost.showSnackbar(text)
            viewModel.clearMessages()
        }
    }
    LaunchedEffect(download.status) {
        if (download.status == DownloadStatus.COMPLETED) viewModel.refresh()
    }

    val caps = uiState.capabilities

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("Models") },
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
            if (caps != null) {
                item { DeviceCard(caps, engineState.acceleration, viewModel) }
            }

            if (download.status != DownloadStatus.IDLE) {
                item {
                    DownloadCard(
                        displayName = download.displayName,
                        status = download.status,
                        downloaded = download.downloadedBytes,
                        total = download.totalBytes,
                        speed = download.bytesPerSecond,
                        eta = download.etaSeconds,
                        message = download.message,
                        onPause = viewModel::pauseDownload,
                        onResume = {
                            viewModel.resumeDownload(
                                viewModel.catalog.firstOrNull { it.id == download.catalogId }
                            )
                        },
                        onCancel = viewModel::cancelDownload,
                        onDismiss = viewModel::dismissDownload
                    )
                }
            }

            item {
                SectionHeader(
                    title = "Installed",
                    subtitle = "${uiState.installed.size} model" +
                        (if (uiState.installed.size == 1) "" else "s") +
                        " · " + ModelRepository.formatBytes(
                            uiState.installed.sumOf { it.sizeBytes }
                        ) + " used"
                )
            }

            if (uiState.installed.isEmpty()) {
                item {
                    EmptyHint("No models installed yet. Pick one below or import a .gguf file from your device.")
                }
            } else {
                items(uiState.installed, key = { it.absolutePath }) { model ->
                    InstalledModelCard(
                        model = model,
                        isLoaded = engineState.loadedModel?.absolutePath == model.absolutePath,
                        isSelected = settings.selectedModelId == model.id,
                        isBusy = uiState.busyMessage != null,
                        loadProgress = uiState.loadProgress,
                        onLoad = { viewModel.load(model) },
                        onUnload = viewModel::unload,
                        onDelete = { confirmDelete = model },
                        onDetails = { detailsFor = model }
                    )
                }
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = { runCatching { importLauncher.launch(arrayOf("*/*")) } },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.FolderOpen, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Import file", fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = { showCustomUrl = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.CloudDownload, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("From URL", fontSize = 13.sp)
                    }
                }
            }

            if (uiState.importProgress != null) {
                item {
                    LinearProgressIndicator(
                        progress = { uiState.importProgress ?: 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                SectionHeader(
                    title = "Available to download",
                    subtitle = if (caps != null)
                        "Recommended for ${caps.deviceClass.label} devices with ${
                            DeviceCapabilities.formatGb(caps.totalRamBytes)
                        } RAM"
                    else null
                )
            }

            items(viewModel.catalog, key = { it.id }) { model ->
                val installed = uiState.installed.any { it.catalogId == model.id }
                CatalogModelCard(
                    model = model,
                    fit = caps?.let { model.fits(it) } ?: ModelFit.GOOD,
                    installed = installed,
                    downloading = download.isActive && download.catalogId == model.id,
                    onDownload = { confirmDownload = model }
                )
            }
        }
    }

    confirmDownload?.let { model ->
        DownloadConfirmDialog(
            model = model,
            capabilities = caps,
            onDismiss = { confirmDownload = null },
            onConfirm = { viewModel.download(model) }
        )
    }

    confirmDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete model") },
            text = {
                Text(
                    "${model.displayName} (${ModelRepository.formatBytes(model.sizeBytes)}) " +
                        "will be removed from this device. Your conversations are not affected."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(model); confirmDelete = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }

    if (showCustomUrl) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCustomUrl = false },
            title = { Text("Download from URL") },
            text = {
                Column {
                    Text(
                        "Paste a direct https link to a .gguf file. The download is validated " +
                            "before it is added.",
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        singleLine = true,
                        placeholder = { Text("https://...", fontSize = 13.sp) }
                    )
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.downloadCustom(url); showCustomUrl = false }) {
                    Text("Download")
                }
            },
            dismissButton = { TextButton(onClick = { showCustomUrl = false }) { Text("Cancel") } }
        )
    }

    detailsFor?.let { model ->
        ModelDetailsDialog(model = model, onDismiss = { detailsFor = null })
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String?) {
    Column(Modifier.padding(top = 6.dp)) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) {
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(14.dp)
        )
    }
}

@Composable
private fun DeviceCard(
    caps: DeviceCapabilities,
    acceleration: String,
    viewModel: ModelsViewModel
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("This device", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            InfoLine("Model", "${caps.manufacturer} ${caps.deviceModel}".trim())
            InfoLine("Chipset", caps.socModel)
            InfoLine("RAM", "${DeviceCapabilities.formatGb(caps.totalRamBytes)} total")
            InfoLine("CPU", "${caps.cpuCores} cores · ${caps.supportedAbis.firstOrNull() ?: "unknown"}")
            InfoLine("Free storage", ModelRepository.formatBytes(caps.availableStorageBytes))
            InfoLine("Acceleration", acceleration)
            InfoLine(
                "Suggested model size",
                "up to ${ModelRepository.formatBytes(caps.recommendedMaxModelBytes)}"
            )
            if (caps.isFlipFoldable) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Galaxy Z Flip detected - PocketAI adapts its layout when you fold and unfold.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(140.dp)
        )
        Text(text = value, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun InstalledModelCard(
    model: InstalledModel,
    isLoaded: Boolean,
    isSelected: Boolean,
    isBusy: Boolean,
    loadProgress: Float,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit,
    onDetails: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isLoaded) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = model.displayName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOfNotNull(
                            model.parametersLabel.takeIf { it != "unknown" },
                            model.quantization,
                            ModelRepository.formatBytes(model.sizeBytes),
                            model.source.takeIf { it != ModelSource.DOWNLOAD }?.let { "imported" }
                        ).joinToString(" · "),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isLoaded) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = "Loaded",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(if (isLoaded) "Loaded in memory" else "Not loaded")
                if (isSelected) Chip("Selected")
                if (model.supportsThinking) Chip("Reasoning")
                model.architecture?.let { Chip(it) }
                Chip("~${ModelRepository.formatBytes(model.estimatedRamBytes)} RAM")
            }

            if (isBusy && !isLoaded && loadProgress > 0f) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { loadProgress },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isLoaded) {
                    OutlinedButton(onClick = onUnload, modifier = Modifier.weight(1f)) {
                        Text("Unload", fontSize = 13.sp)
                    }
                } else {
                    Button(
                        onClick = onLoad,
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Load", fontSize = 13.sp)
                    }
                }
                OutlinedButton(onClick = onDetails) {
                    Icon(Icons.Outlined.Info, contentDescription = "Details", modifier = Modifier.size(17.dp))
                }
                OutlinedButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogModelCard(
    model: CatalogModel,
    fit: ModelFit,
    installed: Boolean,
    downloading: Boolean,
    onDownload: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(model.displayName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "${model.publisher} · ${model.license}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = model.description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(model.parametersLabel)
                Chip(model.quantization)
                Chip(ModelRepository.formatBytes(model.approxSizeBytes))
                Chip("~${ModelRepository.formatBytes(model.estimatedRamBytes)} RAM")
                if (model.supportsThinking) Chip("Reasoning")
            }
            if (fit != ModelFit.GOOD) {
                Spacer(Modifier.height(8.dp))
                FitWarning(fit, model)
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onDownload,
                enabled = !installed && !downloading &&
                    fit != ModelFit.NOT_ENOUGH_STORAGE,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        installed -> "Installed"
                        downloading -> "Downloading..."
                        else -> "Download"
                    },
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun FitWarning(fit: ModelFit, model: CatalogModel) {
    val (text, color) = when (fit) {
        ModelFit.TIGHT -> "This model runs on your device but leaves little headroom. " +
            "${model.recommendedRamGb.toInt()} GB RAM is recommended." to MaterialTheme.colorScheme.tertiary
        ModelFit.NOT_ENOUGH_RAM -> "Your device has less RAM than this model needs " +
            "(${model.minRamGb.toInt()} GB minimum). It may fail to load or slow the phone down." to
            MaterialTheme.colorScheme.error
        ModelFit.NOT_ENOUGH_STORAGE -> "There is not enough free storage for this download." to
            MaterialTheme.colorScheme.error
        ModelFit.GOOD -> return
    }
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Warning, null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(7.dp))
        Text(text, fontSize = 11.sp, color = color)
    }
}

@Composable
private fun Chip(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.padding(bottom = 6.dp)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun DownloadCard(
    displayName: String,
    status: DownloadStatus,
    downloaded: Long,
    total: Long,
    speed: Long,
    eta: Long?,
    message: String?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    val finished = status == DownloadStatus.COMPLETED || status == DownloadStatus.FAILED ||
        status == DownloadStatus.CANCELLED
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = when (status) {
            DownloadStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
            DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = when (status) {
                    DownloadStatus.COMPLETED -> "Model ready"
                    DownloadStatus.FAILED -> "Download failed"
                    DownloadStatus.CANCELLED -> "Download cancelled"
                    DownloadStatus.PAUSED -> "Paused"
                    DownloadStatus.CONNECTING -> "Connecting"
                    else -> "Downloading"
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(displayName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (!finished) {
                Spacer(Modifier.height(10.dp))
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { (downloaded.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = buildString {
                        append(ModelRepository.formatBytes(downloaded))
                        if (total > 0) append(" of ${ModelRepository.formatBytes(total)}")
                        if (speed > 0) append("  ·  ${ModelRepository.formatBytes(speed)}/s")
                        if (eta != null) append("  ·  ${formatEta(eta)} left")
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (message != null) {
                Spacer(Modifier.height(6.dp))
                Text(message, fontSize = 11.sp)
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    finished -> OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Done", fontSize = 13.sp)
                    }
                    status == DownloadStatus.PAUSED -> {
                        Button(onClick = onResume, modifier = Modifier.weight(1f)) {
                            Text("Resume", fontSize = 13.sp)
                        }
                        OutlinedButton(onClick = onCancel) { Text("Cancel", fontSize = 13.sp) }
                    }
                    else -> {
                        OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f)) {
                            Text("Pause", fontSize = 13.sp)
                        }
                        OutlinedButton(onClick = onCancel) { Text("Cancel", fontSize = 13.sp) }
                    }
                }
            }
        }
    }
}

private fun formatEta(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    seconds >= 60 -> "${seconds / 60} min"
    else -> "$seconds s"
}

@Composable
private fun DownloadConfirmDialog(
    model: CatalogModel,
    capabilities: DeviceCapabilities?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val fit = capabilities?.let { model.fits(it) } ?: ModelFit.GOOD
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download ${model.displayName}?") },
        text = {
            Column {
                InfoLine("Download size", ModelRepository.formatBytes(model.approxSizeBytes))
                InfoLine("Estimated RAM", ModelRepository.formatBytes(model.estimatedRamBytes))
                InfoLine("Recommended", "${model.recommendedRamGb.toInt()} GB RAM or more")
                if (capabilities != null) {
                    InfoLine(
                        "Free storage",
                        ModelRepository.formatBytes(capabilities.availableStorageBytes)
                    )
                    InfoLine("Your RAM", DeviceCapabilities.formatGb(capabilities.totalRamBytes))
                }
                if (fit != ModelFit.GOOD) {
                    Spacer(Modifier.height(10.dp))
                    FitWarning(fit, model)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "The file is downloaded from the provider over HTTPS. Nothing else leaves your device.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(); onDismiss() }) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ModelDetailsDialog(model: InstalledModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(model.displayName) },
        text = {
            Column {
                InfoLine("File", model.fileName)
                InfoLine("Size on disk", ModelRepository.formatBytes(model.sizeBytes))
                InfoLine("Parameters", model.parametersLabel)
                InfoLine("Quantization", model.quantization ?: "unknown")
                InfoLine("Architecture", model.architecture ?: "unknown")
                InfoLine(
                    "Trained context",
                    if (model.trainedContextLength > 0) "${model.trainedContextLength} tokens" else "unknown"
                )
                InfoLine("Estimated RAM", ModelRepository.formatBytes(model.estimatedRamBytes))
                InfoLine("Reasoning output", if (model.supportsThinking) "yes" else "not expected")
                InfoLine(
                    "Source",
                    when (model.source) {
                        ModelSource.DOWNLOAD -> "downloaded"
                        ModelSource.IMPORT -> "imported from device"
                        ModelSource.CUSTOM_URL -> "custom URL"
                    }
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
