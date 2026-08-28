package com.pocketai.app.ui.speak

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketai.app.data.repo.AnimationLevel
import com.pocketai.app.data.repo.ChatRole
import com.pocketai.app.ui.chat.ChatViewModel
import com.pocketai.app.voice.SpeakPhase
import com.pocketai.app.voice.SpokenLanguage

/**
 * The hands-free conversation screen.
 *
 * Deliberately an overlay on the chat rather than a separate destination: a
 * spoken exchange is the same conversation, written to the same history, and
 * the user can close this and carry straight on by typing.
 *
 * The screen shows exactly one thing at a time - who is speaking - because it is
 * meant to be usable at arm's length, or not looked at at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeakModeOverlay(viewModel: ChatViewModel, onClose: () -> Unit) {
    val state by viewModel.speakState.collectAsStateWithLifecycle()
    val chat by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val animated = settings.animationLevel != AnimationLevel.NONE

    var showLanguages by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startSpeakMode() else permissionDenied = true
    }

    // Asking on entry rather than behind a settings toggle: the microphone is
    // the entire feature, so the request arrives when its purpose is obvious.
    LaunchedEffect(Unit) {
        if (viewModel.hasMicrophonePermission()) viewModel.startSpeakMode()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val lastUser = chat.messages.lastOrNull { it.role == ChatRole.USER }?.content
    val reply = chat.streaming?.answer?.takeIf { it.isNotBlank() }
        ?: chat.messages.lastOrNull { it.role == ChatRole.ASSISTANT }?.content

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                AssistChip(
                    onClick = { showLanguages = true },
                    label = {
                        Text(
                            if (state.autoDetectLanguage) "${state.language.nativeName} · auto"
                            else state.language.nativeName,
                            fontSize = 12.sp
                        )
                    }
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { viewModel.stopSpeakMode(); onClose() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Close Speak Mode")
                }
            }

            Spacer(Modifier.weight(1f))

            VoiceOrb(
                phase = state.phase,
                level = state.level,
                animated = animated
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = when {
                    permissionDenied -> "Microphone access is off"
                    state.phase == SpeakPhase.LISTENING -> "Listening"
                    state.phase == SpeakPhase.THINKING -> "Thinking"
                    state.phase == SpeakPhase.SPEAKING -> "Speaking"
                    state.phase == SpeakPhase.ERROR -> "Speak Mode stopped"
                    // active + IDLE means it paused between turns and is waiting
                    // for a tap, not still starting up.
                    state.active && state.phase == SpeakPhase.IDLE -> "Tap Speak to talk"
                    else -> "Starting"
                },
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(8.dp))

            // While the user talks this shows their words appearing; between
            // turns it shows what was actually heard, which is the thing people
            // want to check when an answer surprises them.
            Text(
                text = state.partial.ifBlank { lastUser.orEmpty() },
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 8.dp)
            )

            if (reply != null) {
                Spacer(Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        reply,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp)
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            val message = state.error ?: state.notice
            if (permissionDenied) {
                Text(
                    "Speak Mode needs the microphone to hear you. You can grant it in " +
                        "Android's app permissions, or keep using the keyboard.",
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(12.dp))
            } else if (message != null) {
                Text(
                    message,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = if (state.error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
            }

            if (state.recognitionOnDevice) {
                Text(
                    "Speech is being recognised on this device.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
            }

            // Hold-to-talk. The microphone is open only while the button is
            // held, and releasing sends immediately - no guessing when a turn
            // ended, which is what did not work with automatic listening.
            HoldToTalkButton(
                phase = state.phase,
                enabled = state.active && !permissionDenied,
                onHoldStart = viewModel::startHoldToTalk,
                onHoldEnd = viewModel::stopHoldToTalk
            )

            Spacer(Modifier.height(10.dp))

            OutlinedButton(onClick = { viewModel.stopSpeakMode(); onClose() }) {
                Text("Done")
            }
        }
    }

    if (showLanguages) {
        ModalBottomSheet(onDismissRequest = { showLanguages = false }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("Conversation language", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "With detection on, PocketAI answers in whichever of these languages you " +
                        "speak to it. Choosing one directly is more reliable if you always " +
                        "use the same language.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Detect the language I speak", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = state.autoDetectLanguage,
                        onCheckedChange = viewModel::setSpeakAutoDetect
                    )
                }
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SpokenLanguage.entries.forEach { language ->
                        TextButton(
                            onClick = {
                                viewModel.setSpokenLanguage(language)
                                showLanguages = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "${language.nativeName}  ·  ${language.englishName}",
                                fontSize = 14.sp,
                                fontWeight = if (language == state.language) FontWeight.Bold
                                else FontWeight.Normal,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Press-and-hold to talk.
 *
 * The label tells the user what the button will do next, and the fill reflects
 * the current phase so a held button plainly reads as recording. While PocketAI
 * is thinking the button is disabled - there is nothing to speak into yet.
 */
@Composable
private fun HoldToTalkButton(
    phase: SpeakPhase,
    enabled: Boolean,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit
) {
    val held = phase == SpeakPhase.LISTENING
    val busy = phase == SpeakPhase.THINKING

    val label = when {
        busy -> "Thinking..."
        held -> "Release to send"
        phase == SpeakPhase.SPEAKING -> "Hold to interrupt and talk"
        else -> "Hold to talk"
    }
    val fill = when {
        held -> MaterialTheme.colorScheme.primary
        !enabled || busy -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primaryContainer
    }

    // Read the latest enable/busy inside the gesture without ever re-attaching
    // the pointer handler. The old code swapped the modifier when the phase
    // changed, which cancelled the in-progress press and released the button on
    // its own - that is why holding turned itself off after a word.
    val canPress = rememberUpdatedState(enabled && !busy)
    val startHold = rememberUpdatedState(onHoldStart)
    val endHold = rememberUpdatedState(onHoldEnd)

    Surface(
        shape = RoundedCornerShape(40.dp),
        color = fill,
        tonalElevation = if (held) 6.dp else 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (canPress.value) {
                            startHold.value()
                            // Suspends until the finger actually lifts. The handler
                            // is never detached, so a state change cannot end the
                            // hold - only the real release does.
                            tryAwaitRelease()
                            endHold.value()
                        } else {
                            tryAwaitRelease()
                        }
                    }
                )
            }
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                label,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (held) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * One shape that says who has the floor: it swells with the user's voice while
 * listening, breathes while PocketAI thinks, and pulses while it talks.
 */
@Composable
private fun VoiceOrb(phase: SpeakPhase, level: Float, animated: Boolean) {
    val transition = rememberInfiniteTransition(label = "orb")
    val breathe by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (phase == SpeakPhase.SPEAKING) 620 else 1500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )
    // The microphone level is jumpy; easing it keeps the orb from flickering.
    val listenScale by animateFloatAsState(
        targetValue = 1f + level * 0.45f,
        animationSpec = tween(120),
        label = "level"
    )

    val scale = when {
        !animated -> 1f
        phase == SpeakPhase.LISTENING -> listenScale
        phase == SpeakPhase.THINKING || phase == SpeakPhase.SPEAKING -> breathe
        else -> 1f
    }

    val color = when (phase) {
        SpeakPhase.LISTENING -> MaterialTheme.colorScheme.primary
        SpeakPhase.THINKING -> MaterialTheme.colorScheme.tertiary
        SpeakPhase.SPEAKING -> MaterialTheme.colorScheme.secondary
        SpeakPhase.ERROR -> MaterialTheme.colorScheme.error
        SpeakPhase.IDLE -> MaterialTheme.colorScheme.outline
    }

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
        Box(
            Modifier
                .size(170.dp)
                .scale(scale)
                .background(color.copy(alpha = 0.18f), CircleShape)
        )
        Box(
            Modifier
                .size(110.dp)
                .scale(scale)
                .background(color.copy(alpha = 0.45f), CircleShape)
        )
        Box(
            Modifier
                .size(60.dp)
                .background(color, CircleShape)
        )
    }
}
