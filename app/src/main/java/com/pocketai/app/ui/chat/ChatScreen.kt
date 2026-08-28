package com.pocketai.app.ui.chat

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketai.app.data.repo.ChatMessage
import com.pocketai.app.data.repo.Conversation
import com.pocketai.app.ui.theme.LocalChatStyle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenPrivacy: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHost = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var actionTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var summaryTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var selectTextTarget by remember { mutableStateOf<String?>(null) }
    var showTextSize by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }
    var overflowOpen by remember { mutableStateOf(false) }

    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
        scope.launch { snackbarHost.showSnackbar("Copied") }
    }

    fun share(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "Share")) }
    }

    val attachLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.attachDocument(uri) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            snackbarHost.showSnackbar(viewModel.importFrom(uri))
        }
    }

    // Keep the newest content in view while tokens stream in.
    LaunchedEffect(uiState.messages.size, uiState.streaming?.answer?.length) {
        val target = uiState.messages.size + if (uiState.streaming != null) 1 else 0
        if (target > 0) runCatching { listState.animateScrollToItem(target) }
    }

    LaunchedEffect(uiState.error, uiState.notice) {
        val text = uiState.error ?: uiState.notice
        if (text != null) {
            snackbarHost.showSnackbar(text)
            viewModel.clearError()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                ConversationDrawer(
                    conversations = conversations,
                    searchQuery = searchQuery,
                    searchResults = searchResults,
                    activeId = uiState.conversationId,
                    onSearch = viewModel::setSearchQuery,
                    onSelect = {
                        viewModel.selectConversation(it)
                        scope.launch { drawerState.close() }
                    },
                    onNewChat = {
                        viewModel.newChat()
                        scope.launch { drawerState.close() }
                    },
                    onTogglePin = { id, pinned -> viewModel.setPinned(id, pinned) },
                    onToggleFavorite = { id, fav -> viewModel.setFavorite(id, fav) },
                    onRename = { renameTarget = it },
                    onDelete = { deleteTarget = it },
                    onOpenModels = { scope.launch { drawerState.close() }; onOpenModels() },
                    onOpenSettings = { scope.launch { drawerState.close() }; onOpenSettings() },
                    onOpenPrivacy = { scope.launch { drawerState.close() }; onOpenPrivacy() },
                    onImport = { runCatching { importLauncher.launch(arrayOf("application/json", "text/*")) } }
                )
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHost) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = uiState.title,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = engineState.loadedModel?.displayName ?: "No model loaded",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Open chats")
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::newChat) {
                            Icon(Icons.Filled.Add, contentDescription = "New chat")
                        }
                        Box {
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(
                                expanded = overflowOpen,
                                onDismissRequest = { overflowOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Export chat") },
                                    leadingIcon = { Icon(Icons.Outlined.IosShare, null) },
                                    onClick = { overflowOpen = false; showExport = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Text size") },
                                    onClick = { overflowOpen = false; showTextSize = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Models") },
                                    leadingIcon = { Icon(Icons.Outlined.Memory, null) },
                                    onClick = { overflowOpen = false; onOpenModels() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                                    onClick = { overflowOpen = false; onOpenSettings() }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            bottomBar = {
                Composer(
                    text = uiState.composerText,
                    onTextChange = viewModel::updateComposer,
                    onSend = viewModel::send,
                    onStop = viewModel::stopGeneration,
                    isGenerating = uiState.streaming != null,
                    webSearchEnabled = settings.webSearchEnabled,
                    webSearchAllowed = !settings.localOnlyMode,
                    onToggleWebSearch = {
                        scope.launch {
                            viewModel.prefs
                                .setWebSearchEnabled(!settings.webSearchEnabled)
                        }
                    },
                    attachment = uiState.attachment,
                    onAttach = { runCatching { attachLauncher.launch(DOCUMENT_MIME_TYPES) } },
                    onClearAttachment = viewModel::clearAttachment,
                    onVoiceResult = { spoken ->
                        val existing = uiState.composerText
                        viewModel.updateComposer(
                            if (existing.isBlank()) spoken else "$existing $spoken"
                        )
                    },
                    isEditing = uiState.editingMessageId != null,
                    onCancelEdit = viewModel::cancelEdit
                )
            }
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (uiState.messages.isEmpty() && uiState.streaming == null) {
                    EmptyChatState(
                        modelLoaded = engineState.loadedModel != null,
                        acceleration = engineState.acceleration,
                        onOpenModels = onOpenModels,
                        onSuggestion = { viewModel.updateComposer(it) }
                    )
                } else {
                    // On a wide screen - the Flip6 unfolded in landscape, or a
                    // tablet - long lines become hard to read, so the transcript
                    // is centred inside a comfortable measure.
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxHeight()
                            .widthIn(max = 720.dp)
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                        contentPadding = PaddingValues(top = 10.dp, bottom = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(LocalChatStyle.current.messageSpacing)
                    ) {
                        items(
                            items = uiState.messages,
                            key = { it.id }
                        ) { message ->
                            MessageBubble(
                                message = message,
                                showThinking = settings.showThinking,
                                showStats = settings.showPerformanceStats,
                                showSources = settings.showSources,
                                onCopy = ::copy,
                                onShare = ::share,
                                onOpenActions = { actionTarget = message }
                            )
                        }
                        uiState.streaming?.let { streaming ->
                            item(key = "streaming") {
                                StreamingBubble(
                                    state = streaming,
                                    showThinking = settings.showThinking,
                                    showSources = settings.showSources,
                                    onCopy = ::copy,
                                    onShare = ::share
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = uiState.status != null,
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    uiState.status?.let { StatusBanner(it) }
                }
            }
        }
    }

    actionTarget?.let { message ->
        MessageActionSheet(
            message = message,
            onDismiss = { actionTarget = null },
            onCopy = { copy(message.content) },
            onShare = { share(message.content) },
            onRegenerate = { viewModel.regenerate(message) },
            onContinue = { viewModel.continueGeneration(message) },
            onSummarize = { summaryTarget = message },
            onSelectText = { selectTextTarget = message.content },
            onChangeTextSize = { showTextSize = true },
            onEdit = { viewModel.beginEdit(message) },
            onDelete = { viewModel.deleteMessage(message) },
            onResend = { viewModel.resend(message) }
        )
    }

    summaryTarget?.let { message ->
        SummaryModeSheet(
            onDismiss = { summaryTarget = null },
            onPick = { mode -> viewModel.summarize(message, mode) }
        )
    }

    selectTextTarget?.let { text ->
        SelectTextDialog(
            text = text,
            onDismiss = { selectTextTarget = null },
            onCopyAll = { copy(text) }
        )
    }

    if (showTextSize) {
        TextSizeSheet(
            sizes = settings.textSizes,
            onDismiss = { showTextSize = false },
            onChange = { sizes ->
                scope.launch { viewModel.prefs.setTextSizes(sizes) }
            }
        )
    }

    if (showExport) {
        ExportFormatSheet(
            onDismiss = { showExport = false },
            onPick = { format ->
                scope.launch {
                    val file = viewModel.exportCurrent(format)
                    if (file == null) {
                        snackbarHost.showSnackbar("There is nothing to export yet.")
                    } else {
                        val intent = viewModel.exporter.shareIntent(
                            file, format.mime, uiState.title
                        )
                        runCatching {
                            context.startActivity(Intent.createChooser(intent, "Share chat"))
                        }.onFailure { snackbarHost.showSnackbar("No app can receive this file.") }
                    }
                }
            }
        )
    }

    renameTarget?.let { conversation ->
        RenameDialog(
            current = conversation.title,
            onDismiss = { renameTarget = null },
            onConfirm = { viewModel.renameConversation(conversation.id, it) }
        )
    }

    deleteTarget?.let { conversation ->
        ConfirmDialog(
            title = "Delete chat",
            message = "\"${conversation.title}\" and all of its messages will be removed from this device.",
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { deleteTarget = null },
            onConfirm = { viewModel.deleteConversation(conversation.id) }
        )
    }
}

@Composable
private fun StatusBanner(status: ChatStatus) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 4.dp,
        modifier = Modifier.padding(top = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            if (status.progress != null && status.progress > 0f) {
                LinearProgressIndicator(
                    progress = { status.progress },
                    modifier = Modifier.width(48.dp)
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = status.text,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun EmptyChatState(
    modelLoaded: Boolean,
    acceleration: String,
    onOpenModels: () -> Unit,
    onSuggestion: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 26.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "PocketAI",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (modelLoaded)
                "Your messages are processed on this device. Running on $acceleration."
            else
                "Install a local model to start chatting completely offline.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(22.dp))
        if (!modelLoaded) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.clickable(onClick = onOpenModels)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    Icon(
                        Icons.Outlined.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Open the model manager",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        } else {
            val suggestions = listOf(
                "Explain how large language models work, simply",
                "Summarise this text for me: ",
                "Compare Wi-Fi 6 and Wi-Fi 7 in a table",
                "Write a Kotlin function that debounces clicks"
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { suggestion ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSuggestion(suggestion) }
                    ) {
                        Text(
                            text = suggestion,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationDrawer(
    conversations: List<Conversation>,
    searchQuery: String,
    searchResults: List<com.pocketai.app.data.db.ConversationSearchResult>,
    activeId: Long,
    onSearch: (String) -> Unit,
    onSelect: (Long) -> Unit,
    onNewChat: () -> Unit,
    onTogglePin: (Long, Boolean) -> Unit,
    onToggleFavorite: (Long, Boolean) -> Unit,
    onRename: (Conversation) -> Unit,
    onDelete: (Conversation) -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onImport: () -> Unit
) {
    val searching = searchQuery.isNotBlank()
    val byId = remember(conversations) { conversations.associateBy { it.id } }

    Column(Modifier.fillMaxSize()) {
        Text(
            text = "PocketAI",
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 22.dp, top = 20.dp, bottom = 4.dp)
        )
        Text(
            text = "Private, on-device AI",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 22.dp, bottom = 12.dp)
        )

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearch,
            placeholder = { Text("Search chats", fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onNewChat)
                .padding(horizontal = 22.dp, vertical = 14.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Text("New chat", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
        }
        HorizontalDivider()

        LazyColumn(Modifier.weight(1f)) {
            if (searching) {
                items(searchResults, key = { it.id }) { result ->
                    val conversation = byId[result.id]
                    DrawerConversationRow(
                        title = result.title,
                        preview = result.matchedSnippet?.take(90) ?: result.preview,
                        pinned = result.pinned,
                        favorite = result.favorite,
                        active = result.id == activeId,
                        onClick = { onSelect(result.id) },
                        onTogglePin = { onTogglePin(result.id, !result.pinned) },
                        onToggleFavorite = { onToggleFavorite(result.id, !result.favorite) },
                        onRename = { conversation?.let(onRename) },
                        onDelete = { conversation?.let(onDelete) }
                    )
                }
                if (searchResults.isEmpty()) {
                    item {
                        Text(
                            text = "No chats match \"$searchQuery\".",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(22.dp)
                        )
                    }
                }
            } else {
                items(conversations, key = { it.id }) { conversation ->
                    DrawerConversationRow(
                        title = conversation.title,
                        preview = conversation.preview,
                        pinned = conversation.pinned,
                        favorite = conversation.favorite,
                        active = conversation.id == activeId,
                        onClick = { onSelect(conversation.id) },
                        onTogglePin = { onTogglePin(conversation.id, !conversation.pinned) },
                        onToggleFavorite = { onToggleFavorite(conversation.id, !conversation.favorite) },
                        onRename = { onRename(conversation) },
                        onDelete = { onDelete(conversation) }
                    )
                }
                if (conversations.isEmpty()) {
                    item {
                        Text(
                            text = "No chats yet. Start one above.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(22.dp)
                        )
                    }
                }
            }
        }

        HorizontalDivider()
        DrawerLink(Icons.Outlined.Memory, "Models", onOpenModels)
        DrawerLink(Icons.Outlined.IosShare, "Import chats", onImport)
        DrawerLink(Icons.Outlined.Shield, "Privacy Center", onOpenPrivacy)
        DrawerLink(Icons.Outlined.Settings, "Settings", onOpenSettings)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DrawerLink(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 13.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 14.sp)
    }
}

@Composable
private fun DrawerConversationRow(
    title: String,
    preview: String,
    pinned: Boolean,
    favorite: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (active) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 9.dp, bottom = 9.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) {
                    Icon(
                        Icons.Outlined.PushPin,
                        contentDescription = "Pinned",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (favorite) {
                    Icon(
                        Icons.Outlined.Favorite,
                        contentDescription = "Favourite",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (preview.isNotBlank()) {
                Text(
                    text = preview,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(38.dp)) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = "Chat options",
                    modifier = Modifier.size(17.dp)
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (pinned) "Unpin" else "Pin") },
                    leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                    onClick = { menuOpen = false; onTogglePin() }
                )
                DropdownMenuItem(
                    text = { Text(if (favorite) "Remove favourite" else "Favourite") },
                    leadingIcon = {
                        Icon(
                            if (favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                            null
                        )
                    },
                    onClick = { menuOpen = false; onToggleFavorite() }
                )
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                    onClick = { menuOpen = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                    onClick = { menuOpen = false; onDelete() }
                )
            }
        }
    }
}
