package com.xjanova.tping.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.xjanova.tping.MainActivity
import com.xjanova.tping.R
import com.xjanova.tping.TpingApplication
import com.xjanova.tping.recorder.PlaybackEngine
import com.xjanova.tping.service.TpingAccessibilityService
import com.xjanova.tping.util.AppResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var playbackObserverJob: Job? = null
    private var recordingObserverJob: Job? = null

    companion object {
        var instance: FloatingOverlayService? = null
            private set

        // Reactive state - Compose will auto-recompose when these change
        private val _overlayState = MutableStateFlow(OverlayState())
        val overlayState: StateFlow<OverlayState> = _overlayState

        // Shared playback engine (set by ViewModel)
        var playbackEngine: PlaybackEngine? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra("mode") ?: "idle"
        _overlayState.value = _overlayState.value.copy(mode = mode)
        startForeground(1, createNotification())
        showOverlay()

        // Observe PlaybackEngine state when in playing mode
        if (mode == "playing") {
            observePlaybackState()
        }
        return START_STICKY
    }

    private fun observePlaybackState() {
        playbackObserverJob?.cancel()
        val engine = playbackEngine ?: return
        playbackObserverJob = serviceScope.launch {
            engine.state.collect { pState ->
                if (pState.isPlaying) {
                    _overlayState.value = _overlayState.value.copy(
                        mode = if (pState.isPaused) "paused" else "playing",
                        currentStep = pState.currentStep,
                        totalSteps = pState.totalSteps,
                        statusText = pState.currentActionDesc.ifEmpty { "กำลังเล่น..." },
                        currentLoop = pState.currentLoop,
                        totalLoops = pState.totalLoops,
                        progress = pState.progress
                    )
                } else if (_overlayState.value.mode == "playing" || _overlayState.value.mode == "paused") {
                    _overlayState.value = _overlayState.value.copy(
                        mode = "idle", statusText = "เสร็จสิ้น", progress = 0f
                    )
                    playbackObserverJob?.cancel()
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (overlayView != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50; y = 200
        }
        overlayParams = params

        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingOverlayService)
            setViewTreeSavedStateRegistryOwner(this@FloatingOverlayService)

            setContent {
                val state by _overlayState.collectAsState()
                FloatingOverlayContent(
                    state = state,
                    onStartRecord = { startRecording() },
                    onStopRecord = { stopRecording() },
                    onTagData = { fieldKey -> tagDataField(fieldKey) },
                    onShowTagDialog = {
                        setOverlayFocusable(true)
                        // Compute smart field suggestion from last action
                        val suggestion = computeFieldSuggestion()
                        _overlayState.value = _overlayState.value.copy(showTagDialog = true, suggestedFieldName = suggestion)
                    },
                    onDismissTagDialog = { _overlayState.value = _overlayState.value.copy(showTagDialog = false); setOverlayFocusable(false) },
                    onPlay = { /* Play is started from main UI */ },
                    onPause = { pausePlayback() },
                    onResume = { resumePlayback() },
                    onStop = { stopPlayback() },
                    onToggleExpand = { toggleExpand() },
                    onClose = { stopSelf() }
                )
            }

            // Draggable with movement threshold (>10px = drag, else click)
            var initialX = 0; var initialY = 0
            var initialTouchX = 0f; var initialTouchY = 0f
            var isDragging = false

            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x; initialY = params.y
                        initialTouchX = event.rawX; initialTouchY = event.rawY
                        isDragging = false
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = abs(event.rawX - initialTouchX)
                        val dy = abs(event.rawY - initialTouchY)
                        if (dx > 10 || dy > 10) {
                            isDragging = true
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager?.updateViewLayout(this, params)
                            true
                        } else false
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isDragging) true else false
                    }
                    else -> false
                }
            }
        }
        windowManager?.addView(overlayView, params)
    }

    // ====== Focus toggle for keyboard input in overlay ======

    private fun setOverlayFocusable(focusable: Boolean) {
        val params = overlayParams ?: return
        if (focusable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        try { windowManager?.updateViewLayout(overlayView, params) } catch (_: Exception) {}
    }

    // ====== Recording Controls (connected to actual service) ======

    private fun startRecording() {
        val service = TpingAccessibilityService.instance ?: return
        service.getRecorder().startRecording()
        TpingAccessibilityService.setRecording(true)
        _overlayState.value = _overlayState.value.copy(
            mode = "recording", stepCount = 0, statusText = "กำลังบันทึก...",
            targetAppName = ""
        )
        // Observe recording step count in real-time + resolve target app name
        recordingObserverJob?.cancel()
        recordingObserverJob = serviceScope.launch {
            service.getRecorder().actionCount.collect { count ->
                val currentState = _overlayState.value
                // Resolve target app name from first recorded action
                val appName = if (currentState.targetAppName.isEmpty() && count > 0) {
                    val actions = service.getRecorder().getActions()
                    val pkg = actions.firstOrNull()?.packageName ?: ""
                    if (pkg.isNotEmpty()) AppResolver.getAppName(this@FloatingOverlayService, pkg) else ""
                } else {
                    currentState.targetAppName
                }
                _overlayState.value = currentState.copy(
                    stepCount = count,
                    targetAppName = appName
                )
            }
        }
    }

    private fun stopRecording() {
        recordingObserverJob?.cancel()
        TpingAccessibilityService.setRecording(false)
        val service = TpingAccessibilityService.instance ?: return
        val actions = service.getRecorder().stopRecording()
        _overlayState.value = _overlayState.value.copy(
            mode = "idle",
            statusText = "บันทึกแล้ว ${actions.size} ขั้นตอน",
            recordingDone = true
        )
    }

    private fun tagDataField(fieldKey: String) {
        val service = TpingAccessibilityService.instance ?: return
        service.getRecorder().tagLastActionAsDataField(fieldKey)
        _overlayState.value = _overlayState.value.copy(
            showTagDialog = false,
            statusText = "ผูกข้อมูล: $fieldKey"
        )
        setOverlayFocusable(false)
    }

    private fun computeFieldSuggestion(): String {
        val service = TpingAccessibilityService.instance ?: return ""
        val actions = service.getRecorder().getActions()
        val lastAction = actions.lastOrNull() ?: return ""
        return AppResolver.suggestFieldName(
            resourceId = lastAction.resourceId,
            hintText = lastAction.hintText,
            contentDescription = lastAction.contentDescription
        )
    }

    // ====== Playback Controls (connected to actual PlaybackEngine) ======

    private fun pausePlayback() {
        playbackEngine?.pause()
        _overlayState.value = _overlayState.value.copy(mode = "paused", statusText = "หยุดชั่วคราว")
    }

    private fun resumePlayback() {
        playbackEngine?.resume()
        _overlayState.value = _overlayState.value.copy(mode = "playing", statusText = "กำลังเล่นต่อ...")
    }

    private fun stopPlayback() {
        playbackEngine?.stop()
        _overlayState.value = _overlayState.value.copy(mode = "idle", statusText = "หยุดแล้ว")
    }

    private fun toggleExpand() {
        val current = _overlayState.value
        _overlayState.value = current.copy(isExpanded = !current.isExpanded)
    }

    // Called by PlaybackEngine to update overlay during playback
    fun updatePlaybackState(step: Int, total: Int, desc: String, loop: Int, totalLoops: Int) {
        _overlayState.value = _overlayState.value.copy(
            mode = "playing",
            currentStep = step, totalSteps = total,
            statusText = desc,
            currentLoop = loop, totalLoops = totalLoops,
            progress = if (total > 0) step.toFloat() / total else 0f
        )
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, TpingApplication.CHANNEL_ID)
            .setContentTitle("Tping กำลังทำงาน")
            .setContentText("Xman Studio - แตะเพื่อเปิดแอพ")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        playbackObserverJob?.cancel()
        recordingObserverJob?.cancel()
        serviceScope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
        overlayParams = null
        instance = null
        super.onDestroy()
    }
}

data class OverlayState(
    val mode: String = "idle",
    val isExpanded: Boolean = true,
    val statusText: String = "พร้อมใช้งาน",
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val stepCount: Int = 0,
    val currentLoop: Int = 0,
    val totalLoops: Int = 0,
    val progress: Float = 0f,
    val showTagDialog: Boolean = false,
    val recordingDone: Boolean = false,
    val targetAppName: String = "",
    val suggestedFieldName: String = ""
)
