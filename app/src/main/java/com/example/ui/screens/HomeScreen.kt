package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.gemini.GeminiService
import com.example.data.model.Memory
import com.example.data.repository.MemoryRepository
import com.example.service.ListeningForegroundService
import com.example.ui.components.MemoryCard
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.GreenActive
import com.example.ui.theme.IndigoDark
import com.example.ui.theme.IndigoPrimary
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
fun HomeScreen(
    userId: String,
    memoryRepository: MemoryRepository,
    onNavigateToMemories: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isListening by ListeningForegroundService.isListening.collectAsState()
    val rmsLevel by ListeningForegroundService.rmsLevel.collectAsState()
    val recentTranscript by ListeningForegroundService.recentTranscript.collectAsState()
    val livePartialText by ListeningForegroundService.livePartialText.collectAsState()
    val lastError by ListeningForegroundService.lastError.collectAsState()

    val memories by memoryRepository.getMemoriesFlow(userId).collectAsState(initial = emptyList())

    // Calculate memories saved today
    val todayDateStr = remember {
        SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date())
    }
    val todayMemories = remember(memories, todayDateStr) {
        memories.filter { it.date == todayDateStr }
    }
    val recentMemories = remember(memories) {
        memories.take(4)
    }

    var permissionError by remember { mutableStateOf<String?>(null) }
    var testInputText by remember { mutableStateOf("") }
    var isProcessingTest by remember { mutableStateOf(false) }

    // Multi-permission launcher for Microphone and Notifications
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (recordAudioGranted) {
            permissionError = null
            ListeningForegroundService.start(context, userId)
        } else {
            permissionError = "Microphone permission is required for continuous memory listening."
        }
    }

    fun toggleListening() {
        if (isListening) {
            ListeningForegroundService.stop(context)
        } else {
            val neededPermissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(neededPermissions.toTypedArray())
        }
    }

    fun handleSimulatedSpeech(speech: String) {
        if (speech.isBlank()) return
        isProcessingTest = true
        scope.launch {
            val trimmed = speech.trim()
            val recallRegex = Regex("(?i)\\brecall\\s+me\\s+please\\b[.,!]?")
            val match = recallRegex.find(trimmed)

            val isRecall = match != null
            val noteContent = if (match != null) {
                val startIndex = match.range.last + 1
                if (startIndex < trimmed.length) {
                    trimmed.substring(startIndex).trim().trimStart(',', '.', ':', '-', ';').trim()
                } else {
                    trimmed
                }
            } else {
                trimmed
            }

            try {
                val analysis = GeminiService.analyzeSpeech(noteContent, isRecall)
                if (isRecall || analysis.isImportant) {
                    val now = System.currentTimeMillis()
                    val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
                    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

                    val memory = Memory(
                        memoryId = UUID.randomUUID().toString(),
                        userId = userId,
                        date = dateFormat.format(Date(now)),
                        time = timeFormat.format(Date(now)),
                        timestamp = now,
                        title = analysis.title.ifBlank { "Voice Note" },
                        content = analysis.content.ifBlank { noteContent },
                        source = if (isRecall) Memory.SOURCE_RECALL_ME_PLEASE else Memory.SOURCE_AUTOMATIC,
                        detectedPeople = analysis.detectedPeople,
                        detectedTask = analysis.detectedTask,
                        detectedDeadline = analysis.detectedDeadline,
                        importanceLevel = analysis.importanceLevel
                    )
                    memoryRepository.saveMemory(memory)
                }
            } catch (e: Exception) {
                // Handled
            } finally {
                isProcessingTest = false
                testInputText = ""
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            // App Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Recall Me Please",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Personal AI Memory Assistant",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Active status pill
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isListening) GreenActive.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.testTag("listening_status_pill")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isListening) GreenActive else Color.Gray)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isListening) "Listening" else "Stopped",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isListening) GreenActive else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Permission Error banner if denied
        if (permissionError != null) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = permissionError ?: "",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        // Central Listening Hero Card
        item {
            ListeningHeroCard(
                isListening = isListening,
                rmsLevel = rmsLevel,
                liveText = livePartialText,
                recentTranscript = recentTranscript,
                onToggleListening = { toggleListening() }
            )
        }

        // Today's Memories Count Display Card
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("today_memories_card")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "${todayMemories.size} ${if (todayMemories.size == 1) "memory" else "memories"} today",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (todayMemories.isEmpty()) "Speak or say 'Recall Me Please' to capture notes" else "Captured automatically & via voice command",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Surface(
                        shape = CircleShape,
                        color = IndigoPrimary.copy(alpha = 0.15f),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "${todayMemories.size}",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = IndigoPrimary
                            )
                        }
                    }
                }
            }
        }

        // Interactive Voice Command Simulator (Essential for testing on emulators and in quiet rooms!)
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Hearing,
                            contentDescription = null,
                            tint = AmberAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Test Voice Assistant",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tap a sample below or enter speech to test immediate voice recognition and AI detection:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Quick sample prompt chips
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SampleSpeechChip(
                            label = "\"Recall Me Please. John wants me to send the proposal tomorrow.\"",
                            onClick = { handleSimulatedSpeech("Recall Me Please. John wants me to send the proposal tomorrow.") },
                            isPriority = true
                        )
                        SampleSpeechChip(
                            label = "\"Recall Me Please. I had an idea for a new SEO service.\"",
                            onClick = { handleSimulatedSpeech("Recall Me Please. I had an idea for a new SEO service.") },
                            isPriority = true
                        )
                        SampleSpeechChip(
                            label = "\"I need to send Sarah the contract before Friday.\"",
                            onClick = { handleSimulatedSpeech("I need to send Sarah the contract before Friday.") },
                            isPriority = false
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Custom text simulation input
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = testInputText,
                            onValueChange = { testInputText = it },
                            placeholder = { Text("Or type spoken phrase to test...", fontSize = 13.sp) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("simulated_speech_input"),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = { handleSimulatedSpeech(testInputText) },
                            enabled = testInputText.isNotBlank() && !isProcessingTest,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (testInputText.isNotBlank()) IndigoPrimary else MaterialTheme.colorScheme.surfaceVariant)
                                .testTag("send_speech_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Test Speech",
                                tint = if (testInputText.isNotBlank()) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // Recent Memories Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent memories",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                if (memories.isNotEmpty()) {
                    TextButton(
                        onClick = onNavigateToMemories,
                        modifier = Modifier.testTag("view_all_memories_button")
                    ) {
                        Text("View all", color = IndigoPrimary, fontSize = 13.sp)
                    }
                }
            }
        }

        if (recentMemories.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No memories saved yet",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Start listening and say 'Recall Me Please' followed by anything you need to remember.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(recentMemories, key = { it.memoryId }) { memory ->
                MemoryCard(
                    memory = memory,
                    onDelete = { id ->
                        scope.launch { memoryRepository.deleteMemory(userId, id) }
                    }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun ListeningHeroCard(
    isListening: Boolean,
    rmsLevel: Float,
    liveText: String?,
    recentTranscript: String?,
    onToggleListening: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val waveColor by animateColorAsState(
        targetValue = if (isListening) CyanAccent else Color.Gray.copy(alpha = 0.5f),
        label = "color"
    )

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isListening) Color(0xFF131C31) else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hero_listening_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val surfaceColor = MaterialTheme.colorScheme.surface

            // Interactive Mic Orb with Soundwave ripples
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(130.dp)
                    .clip(CircleShape)
            ) {
                // Background ripple canvas
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val baseRadius = size.minDimension / 2f * 0.75f

                    if (isListening) {
                        // Dynamic audio reactivity
                        val activeRadius = baseRadius * pulseScale + (rmsLevel * 3f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(IndigoPrimary.copy(alpha = 0.35f), Color.Transparent),
                                center = center,
                                radius = activeRadius
                            ),
                            radius = activeRadius,
                            center = center
                        )
                    }

                    drawCircle(
                        color = if (isListening) IndigoPrimary else surfaceColor,
                        radius = baseRadius * 0.7f,
                        center = center
                    )
                }

                IconButton(
                    onClick = onToggleListening,
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(if (isListening) AmberAccent else IndigoPrimary)
                        .testTag("start_stop_listening_button")
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (isListening) "Stop Listening" else "Start Listening",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status label
            Text(
                text = if (isListening) "Continuous Listening Active" else "Listening is Paused",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isListening) Color.White else MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (isListening) {
                    "Listening for \"Recall Me Please\" or important plans..."
                } else {
                    "Tap microphone button to start background monitoring"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (isListening) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Live speech transcription indicator
            AnimatedVisibility(visible = isListening && (!liveText.isNullOrBlank() || !recentTranscript.isNullOrBlank())) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Black.copy(alpha = 0.3f),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Text(
                            text = "Heard: \"${liveText?.ifBlank { recentTranscript } ?: recentTranscript}\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyanAccent,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action button
            Button(
                onClick = onToggleListening,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isListening) Color(0xFFDC2626) else IndigoPrimary
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("action_toggle_listening_button")
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isListening) "Stop Listening" else "Start Listening",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun SampleSpeechChip(
    label: String,
    onClick: () -> Unit,
    isPriority: Boolean
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isPriority) AmberAccent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = if (isPriority) Icons.Default.Mic else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = if (isPriority) AmberAccent else IndigoPrimary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (isPriority) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isPriority) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
