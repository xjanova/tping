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

class FloatingOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var isExpanded = true

    companion object {
        var instance: FloatingOverlayService? = null
            private set
        var overlayState = OverlayState()
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
        overlayState = overlayState.copy(mode = mode)

        startForeground(1, createNotification())
        showOverlay()
        return START_STICKY
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
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingOverlayService)
            setViewTreeSavedStateRegistryOwner(this@FloatingOverlayService)

            setContent {
                FloatingOverlayContent(
                    state = overlayState,
                    onStartRecord = { startRecording() },
                    onStopRecord = { stopRecording() },
                    onTagData = { showTagDialog() },
                    onPlay = { startPlayback() },
                    onPause = { pausePlayback() },
                    onStop = { stopPlayback() },
                    onToggleExpand = { toggleExpand() },
                    onClose = { stopSelf() }
                )
            }

            // Make draggable
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f

            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(this, params)
                        true
                    }
                    else -> false
                }
            }
        }

        windowManager?.addView(overlayView, params)
    }

    private fun startRecording() {
        val service = com.xjanova.tping.service.TpingAccessibilityService.instance ?: return
        service.getRecorder().startRecording()
        com.xjanova.tping.service.TpingAccessibilityService.isRecording = true
        overlayState = overlayState.copy(
            mode = "recording",
            stepCount = 0,
            statusText = "กำลังบันทึก..."
        )
        refreshOverlay()
    }

    private fun stopRecording() {
        val service = com.xjanova.tping.service.TpingAccessibilityService.instance ?: return
        com.xjanova.tping.service.TpingAccessibilityService.isRecording = false
        val actions = service.getRecorder().stopRecording()
        overlayState = overlayState.copy(
            mode = "idle",
            statusText = "บันทึกแล้ว ${actions.size} ขั้นตอน"
        )
        // Save to database via broadcast
        val intent = Intent("com.xjanova.tping.RECORDING_DONE")
        intent.putExtra("stepCount", actions.size)
        sendBroadcast(intent)
        refreshOverlay()
    }

    private fun showTagDialog() {
        overlayState = overlayState.copy(showTagDialog = true)
        refreshOverlay()
    }

    private fun startPlayback() {
        overlayState = overlayState.copy(mode = "playing", statusText = "กำลังเล่น...")
        refreshOverlay()
    }

    private fun pausePlayback() {
        overlayState = overlayState.copy(mode = "paused", statusText = "หยุดชั่วคราว")
        refreshOverlay()
    }

    private fun stopPlayback() {
        overlayState = overlayState.copy(mode = "idle", statusText = "หยุดแล้ว")
        refreshOverlay()
    }

    private fun toggleExpand() {
        isExpanded = !isExpanded
        overlayState = overlayState.copy(isExpanded = isExpanded)
        refreshOverlay()
    }

    private fun refreshOverlay() {
        overlayView?.let {
            it.setContent {
                FloatingOverlayContent(
                    state = overlayState,
                    onStartRecord = { startRecording() },
                    onStopRecord = { stopRecording() },
                    onTagData = { showTagDialog() },
                    onPlay = { startPlayback() },
                    onPause = { pausePlayback() },
                    onStop = { stopPlayback() },
                    onToggleExpand = { toggleExpand() },
                    onClose = { stopSelf() }
                )
            }
        }
    }

    fun updatePlaybackState(step: Int, total: Int, desc: String, loop: Int, totalLoops: Int) {
        overlayState = overlayState.copy(
            currentStep = step,
            totalSteps = total,
            statusText = desc,
            currentLoop = loop,
            totalLoops = totalLoops,
            progress = if (total > 0) step.toFloat() / total else 0f
        )
        refreshOverlay()
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, TpingApplication.CHANNEL_ID)
            .setContentTitle("Tping กำลังทำงาน")
            .setContentText("แตะเพื่อเปิดแอพ")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
        instance = null
        super.onDestroy()
    }
}

data class OverlayState(
    val mode: String = "idle", // idle, recording, playing, paused
    val isExpanded: Boolean = true,
    val statusText: String = "พร้อมใช้งาน",
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val stepCount: Int = 0,
    val currentLoop: Int = 0,
    val totalLoops: Int = 0,
    val progress: Float = 0f,
    val showTagDialog: Boolean = false
)
