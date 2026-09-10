package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.gemini.GeminiService
import com.example.data.model.Memory
import com.example.data.repository.MemoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class ListeningForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var speechRecognizer: SpeechRecognizer? = null
    private var speechIntent: Intent? = null
    private val handler = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var memoryRepository: MemoryRepository
    private var activeUserId: String = ""

    // State tracking for "Recall Me Please" multi-utterance support
    private var isRecallArmed: Boolean = false

    override fun onCreate() {
        super.onCreate()
        memoryRepository = MemoryRepository(applicationContext)
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopListeningInternal()
            stopSelf()
            return START_NOT_STICKY
        }

        activeUserId = intent?.getStringExtra(EXTRA_USER_ID) ?: activeUserId

        startForegroundServiceWithNotification()
        startSpeechRecognition()

        _isListening.value = true
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Recall Me Please Listening",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status of continuous microphone listening"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceWithNotification() {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ListeningForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recall Me Please is Active")
            .setContentText("Monitoring for 'Recall Me Please' & important memories...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingOpenApp)
            .addAction(android.R.drawable.ic_media_pause, "Stop Listening", pendingStop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showSavedNotification(title: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Memory saved")
            .setContentText(title)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingOpenApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        manager?.notify(SAVED_NOTIFICATION_ID, notification)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "RecallMePlease::ListeningWakeLock"
            )?.apply {
                acquire(12 * 60 * 60 * 1000L) // 12 hours max
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock: ${e.message}")
        }
        wakeLock = null
    }

    private fun startSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "SpeechRecognizer is not available on this device")
            _lastError.value = "Speech recognition is not available on this device"
            return
        }

        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(createRecognitionListener())
                }
            }

            speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }

            speechRecognizer?.startListening(speechIntent)
            Log.i(TAG, "SpeechRecognizer started listening")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognition: ${e.message}", e)
            scheduleRestart(1000)
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "SpeechRecognizer ready for speech")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "User started speaking")
            }

            override fun onRmsChanged(rmsdB: Float) {
                _rmsLevel.value = rmsdB.coerceIn(0f, 10f)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                _rmsLevel.value = 0f
            }

            override fun onError(error: Int) {
                _rmsLevel.value = 0f
                val message = getSpeechErrorMessage(error)
                Log.w(TAG, "SpeechRecognizer error: $error ($message)")

                // Recover and restart listening
                scheduleRestart(500)
            }

            override fun onResults(results: Bundle?) {
                _rmsLevel.value = 0f
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val primaryText = matches?.firstOrNull()?.trim() ?: ""

                if (primaryText.isNotBlank()) {
                    Log.i(TAG, "Recognized speech: $primaryText")
                    _recentTranscript.value = primaryText
                    handleTranscribedSpeech(primaryText)
                }

                scheduleRestart(300)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull() ?: ""
                if (partial.isNotBlank()) {
                    _livePartialText.value = partial
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private fun handleTranscribedSpeech(speech: String) {
        val trimmed = speech.trim()
        val lower = trimmed.lowercase()

        val recallRegex = Regex("(?i)\\brecall\\s+me\\s+please\\b[.,!]?")
        val match = recallRegex.find(trimmed)

        val isRecall: Boolean
        val noteContent: String

        if (match != null) {
            isRecall = true
            isRecallArmed = false
            // Extract the speech that immediately follows the command
            val startIndex = match.range.last + 1
            val followingSpeech = if (startIndex < trimmed.length) {
                trimmed.substring(startIndex).trim().trimStart(',', '.', ':', '-', ';').trim()
            } else {
                ""
            }

            if (followingSpeech.isBlank()) {
                // The user said ONLY "Recall Me Please" and paused
                // Arm next speech segment to be captured as the note!
                isRecallArmed = true
                Log.i(TAG, "Armed 'Recall Me Please' for next utterance.")
                return
            } else {
                noteContent = followingSpeech
            }
        } else if (isRecallArmed) {
            // This speech is immediately following a previous "Recall Me Please" command
            isRecall = true
            isRecallArmed = false
            noteContent = trimmed
        } else {
            // General conversation speech
            isRecall = false
            noteContent = trimmed
        }

        if (noteContent.isBlank()) return

        processAndSaveMemory(noteContent, isRecall)
    }

    fun processAndSaveMemory(content: String, isRecallCommand: Boolean) {
        serviceScope.launch {
            try {
                val analysis = GeminiService.analyzeSpeech(content, isRecallCommand)

                if (isRecallCommand || analysis.isImportant) {
                    val now = System.currentTimeMillis()
                    val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
                    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

                    val dateStr = dateFormat.format(Date(now))
                    val timeStr = timeFormat.format(Date(now))

                    val memory = Memory(
                        memoryId = UUID.randomUUID().toString(),
                        userId = activeUserId.ifBlank { "guest_user" },
                        date = dateStr,
                        time = timeStr,
                        timestamp = now,
                        title = analysis.title.ifBlank { "Quick Note" },
                        content = analysis.content.ifBlank { content },
                        source = if (isRecallCommand) Memory.SOURCE_RECALL_ME_PLEASE else Memory.SOURCE_AUTOMATIC,
                        detectedPeople = analysis.detectedPeople,
                        detectedTask = analysis.detectedTask,
                        detectedDeadline = analysis.detectedDeadline,
                        importanceLevel = analysis.importanceLevel
                    )

                    memoryRepository.saveMemory(memory)
                    _lastSavedMemory.value = memory
                    Log.i(TAG, "Saved memory: [${memory.source}] ${memory.title}")

                    if (isRecallCommand) {
                        showSavedNotification(memory.title)
                    }
                } else {
                    Log.d(TAG, "Speech discarded as unimportant ambient conversation.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving memory: ${e.message}", e)
            }
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!_isListening.value) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (_isListening.value) {
                try {
                    speechRecognizer?.cancel()
                    speechRecognizer?.startListening(speechIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error restarting speech listener: ${e.message}")
                    recreateRecognizer()
                }
            }
        }, delayMs)
    }

    private fun recreateRecognizer() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
            startSpeechRecognition()
        } catch (e: Exception) {
            Log.e(TAG, "Error recreating SpeechRecognizer: ${e.message}")
        }
    }

    private fun stopListeningInternal() {
        _isListening.value = false
        _rmsLevel.value = 0f
        handler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recognizer: ${e.message}")
        }
        speechRecognizer = null
        releaseWakeLock()
    }

    override fun onDestroy() {
        stopListeningInternal()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun getSpeechErrorMessage(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RecognitionService busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
            else -> "Unknown speech error ($errorCode)"
        }
    }

    companion object {
        private const val TAG = "ListeningService"
        const val CHANNEL_ID = "recall_me_listening_channel"
        const val NOTIFICATION_ID = 1001
        const val SAVED_NOTIFICATION_ID = 1002

        const val ACTION_START = "com.example.service.ACTION_START"
        const val ACTION_STOP = "com.example.service.ACTION_STOP"
        const val EXTRA_USER_ID = "extra_user_id"

        private val _isListening = MutableStateFlow(false)
        val isListening = _isListening.asStateFlow()

        private val _rmsLevel = MutableStateFlow(0f)
        val rmsLevel = _rmsLevel.asStateFlow()

        private val _recentTranscript = MutableStateFlow<String?>(null)
        val recentTranscript = _recentTranscript.asStateFlow()

        private val _livePartialText = MutableStateFlow<String?>(null)
        val livePartialText = _livePartialText.asStateFlow()

        private val _lastSavedMemory = MutableStateFlow<Memory?>(null)
        val lastSavedMemory = _lastSavedMemory.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError = _lastError.asStateFlow()

        fun start(context: Context, userId: String) {
            val intent = Intent(context, ListeningForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_USER_ID, userId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ListeningForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
