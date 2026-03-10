package com.xjanova.tping.overlay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xjanova.tping.MainActivity
import com.xjanova.tping.R
import com.xjanova.tping.TpingApplication
import com.xjanova.tping.data.license.LicenseManager
import com.xjanova.tping.data.entity.ActionType
import com.xjanova.tping.data.entity.DataField
import com.xjanova.tping.data.entity.RecordedAction
import com.xjanova.tping.data.entity.Workflow
import com.xjanova.tping.puzzle.PuzzleRecordingFlow
import com.xjanova.tping.puzzle.PuzzleRecordingState
import com.xjanova.tping.puzzle.PuzzleRecordingStep
import com.xjanova.tping.recorder.PlaybackEngine
import com.xjanova.tping.service.TpingAccessibilityService
import com.xjanova.tping.util.AppResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class FloatingOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var crosshairView: ComposeView? = null
    private var pendingGameActionType: ActionType = ActionType.CLICK
    private var crosshairMode: String = "tap" // "tap" or "input"
    private var pendingGameInputX: Int = 0
    private var pendingGameInputY: Int = 0
    private val gameActions = mutableListOf<RecordedAction>()
    private var gameStepCounter = 0
    private var gameLastActionTime = System.currentTimeMillis()
    private var puzzleRecordingState = PuzzleRecordingState()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var playbackObserverJob: Job? = null
    private var recordingObserverJob: Job? = null
    private val gson = Gson()

    companion object {
        var instance: FloatingOverlayService? = null
            private set

        private val _overlayState = MutableStateFlow(OverlayState())
        val overlayState: StateFlow<OverlayState> = _overlayState

        var playbackEngine: PlaybackEngine? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        loadWorkflowsAndProfiles()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra("mode") ?: "idle"
        _overlayState.value = _overlayState.value.copy(mode = mode)
        startForeground(1, createNotification())
        showOverlay()
        if (mode == "playing") {
            observePlaybackState()
        }
        return START_STICKY
    }

    // ====== Load workflows/profiles for play dialog ======

    private fun loadWorkflowsAndProfiles() {
        serviceScope.launch {
            try {
                val db = (application as TpingApplication).database
                launch {
                    db.workflowDao().getAll().collect { workflows ->
                        val items = workflows.map { wf ->
                            val appName = wf.targetAppName.ifEmpty {
                                if (wf.targetAppPackage.isNotEmpty()) AppResolver.getAppName(this@FloatingOverlayService, wf.targetAppPackage) else ""
                            }
                            val actions: List<RecordedAction> = try {
                                val type = object : TypeToken<List<RecordedAction>>() {}.type
                                gson.fromJson<List<RecordedAction>>(wf.stepsJson, type) ?: emptyList()
                            } catch (_: Exception) { emptyList() }
                            val stepCount = actions.size
                            val dataKeys = actions.filter { it.dataFieldKey.isNotEmpty() }.map { it.dataFieldKey }.distinct()
                            WorkflowItem(wf.id, wf.name, appName, stepCount, dataKeys)
                        }
                        _overlayState.value = _overlayState.value.copy(workflowItems = items)
                    }
                }
                launch {
                    db.dataProfileDao().getAll().collect { profiles ->
                        // Build category items grouped by category
                        val allKeys = mutableSetOf<String>()
                        val grouped = profiles.filter { it.category.isNotEmpty() }.groupBy { it.category }
                        val catItems = grouped.map { (cat, catProfiles) ->
                            val fieldKeys = catProfiles.flatMap { p ->
                                try {
                                    val type = object : TypeToken<List<DataField>>() {}.type
                                    val fields: List<DataField> = gson.fromJson(p.fieldsJson, type) ?: emptyList()
                                    fields.map { it.key }
                                } catch (_: Exception) { emptyList() }
                            }.distinct()
                            allKeys.addAll(fieldKeys)
                            ProfileCategoryItem(cat, catProfiles.size, fieldKeys, catProfiles.map { it.id })
                        }
                        // Also collect keys from uncategorized profiles
                        profiles.filter { it.category.isEmpty() }.forEach { p ->
                            try {
                                val type = object : TypeToken<List<DataField>>() {}.type
                                val fields: List<DataField> = gson.fromJson(p.fieldsJson, type) ?: emptyList()
                                allKeys.addAll(fields.map { it.key })
                            } catch (_: Exception) {}
                        }
                        _overlayState.value = _overlayState.value.copy(
                            profileCategories = catItems,
                            allFieldKeys = allKeys.toList().sorted()
                        )
                    }
                }
            } catch (_: Exception) {}
        }
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
                    onStartRecord = {
                        setOverlayFocusable(true)
                        _overlayState.value = _overlayState.value.copy(showRecordModeDialog = true)
                    },
                    onStartNormalRecord = { startRecording() },
                    onStartGameRecord = { startGameRecording() },
                    onDismissRecordModeDialog = {
                        _overlayState.value = _overlayState.value.copy(showRecordModeDialog = false)
                        setOverlayFocusable(false)
                    },
                    onStopRecord = { stopRecording() },
                    onStopGameRecord = { stopGameRecording() },
                    onSaveRecording = { name -> saveRecordingFromOverlay(name) },
                    onDismissSaveDialog = {
                        _overlayState.value = _overlayState.value.copy(showSaveDialog = false)
                        setOverlayFocusable(false)
                    },
                    onTagData = { fieldKey -> tagDataField(fieldKey) },
                    onShowTagDialog = {
                        setOverlayFocusable(true)
                        val suggestion = computeFieldSuggestion()
                        _overlayState.value = _overlayState.value.copy(showTagDialog = true, suggestedFieldName = suggestion)
                    },
                    onDismissTagDialog = {
                        _overlayState.value = _overlayState.value.copy(showTagDialog = false)
                        setOverlayFocusable(false)
                    },
                    onShowPlayDialog = {
                        setOverlayFocusable(true)
                        loadWorkflowsAndProfiles()
                        _overlayState.value = _overlayState.value.copy(showPlayDialog = true)
                    },
                    onDismissPlayDialog = {
                        _overlayState.value = _overlayState.value.copy(showPlayDialog = false)
                        setOverlayFocusable(false)
                    },
                    onStartPlayback = { workflowId, category, loops, shuffle ->
                        startPlaybackFromOverlay(workflowId, category, loops, shuffle)
                    },
                    onDeleteWorkflow = { workflowId -> deleteWorkflow(workflowId) },
                    onPause = { pausePlayback() },
                    onResume = { resumePlayback() },
                    onStop = { stopPlayback() },
                    onToggleExpand = { toggleExpand() },
                    onClose = { stopSelf() },
                    onDragDelta = { dx, dy -> moveOverlay(dx, dy) },
                    onShowGameCrosshair = { actionType -> showCrosshair(actionType) },
                    onAddGameWait = { delayMs -> addGameWaitAction(delayMs) },
                    onShowGameInputCrosshair = { showCrosshair(ActionType.CLICK, "input") },
                    onGameTagConfirm = { fieldKey -> addGameInputAction(fieldKey) },
                    onDismissGameTagDialog = {
                        _overlayState.value = _overlayState.value.copy(showGameTagDialog = false)
                        setOverlayFocusable(false)
                    },
                    onShowPuzzleCrosshair = { startPuzzleCrosshairFlow() }
                )
            }
        }
        windowManager?.addView(overlayView, params)
    }

    private fun moveOverlay(dx: Float, dy: Float) {
        val params = overlayParams ?: return
        params.x += dx.toInt()
        params.y += dy.toInt()
        try { windowManager?.updateViewLayout(overlayView, params) } catch (_: Exception) {}
    }

    private fun setOverlayFocusable(focusable: Boolean) {
        val params = overlayParams ?: return
        if (focusable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        try { windowManager?.updateViewLayout(overlayView, params) } catch (_: Exception) {}
    }

    /**
     * Make overlay completely transparent to touch events (for CAPTCHA solving).
     * When passthrough=true, FLAG_NOT_TOUCHABLE is set so the overlay window
     * cannot intercept ANY touch events, eliminating FLAG_WINDOW_IS_PARTIALLY_OBSCURED
     * on MotionEvents reaching the app underneath.
     */
    fun setTouchPassthrough(passthrough: Boolean) {
        val params = overlayParams ?: return
        if (passthrough) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        try { windowManager?.updateViewLayout(overlayView, params) } catch (_: Exception) {}
    }

    // ====== Recording Controls ======

    private fun startRecording() {
        if (!LicenseManager.isLicenseValid()) {
            _overlayState.value = _overlayState.value.copy(
                statusText = "⛔ License หมดอายุ — กรุณาซื้อคีย์"
            )
            return
        }
        val service = TpingAccessibilityService.instance
        if (service == null) {
            _overlayState.value = _overlayState.value.copy(
                statusText = "⚠ เปิด Accessibility ก่อน! (กลับไปแอพ Tping)"
            )
            return
        }
        service.getRecorder().startRecording()
        TpingAccessibilityService.setRecording(true)
        _overlayState.value = _overlayState.value.copy(
            showRecordModeDialog = false,
            mode = "recording", stepCount = 0, statusText = "กำลังบันทึก...",
            targetAppName = "", recordingDone = false
        )
        setOverlayFocusable(false)
        recordingObserverJob?.cancel()
        recordingObserverJob = serviceScope.launch {
            service.getRecorder().actionCount.collect { count ->
                val currentState = _overlayState.value
                val appName = if (currentState.targetAppName.isEmpty() && count > 0) {
                    val actions = service.getRecorder().getActions()
                    val pkg = actions.firstOrNull()?.packageName ?: ""
                    if (pkg.isNotEmpty()) AppResolver.getAppName(this@FloatingOverlayService, pkg) else ""
                } else currentState.targetAppName
                _overlayState.value = currentState.copy(stepCount = count, targetAppName = appName)
            }
        }
    }

    private fun stopRecording() {
        recordingObserverJob?.cancel()
        TpingAccessibilityService.setRecording(false)
        val service = TpingAccessibilityService.instance ?: return
        val actions = service.getRecorder().stopRecording()
        val targetPkg = actions.firstOrNull()?.packageName ?: ""
        val appName = if (targetPkg.isNotEmpty()) AppResolver.getAppName(this, targetPkg) else ""
        val suggestedName = appName.ifEmpty { "Workflow" }
        _overlayState.value = _overlayState.value.copy(
            mode = "idle",
            statusText = "บันทึกแล้ว ${actions.size} ขั้นตอน - ตั้งชื่อเพื่อบันทึก",
            recordingDone = true,
            showSaveDialog = true,
            suggestedWorkflowName = suggestedName
        )
        setOverlayFocusable(true)
    }

    private fun saveRecordingFromOverlay(name: String) {
        // Check if this is a game mode recording
        if (gameActions.isNotEmpty()) {
            saveGameRecordingFromOverlay(name)
            return
        }
        val service = TpingAccessibilityService.instance ?: return
        val actions = service.getRecorder().getActions()
        if (actions.isEmpty()) return
        val targetPkg = actions.firstOrNull()?.packageName ?: ""
        val targetAppName = if (targetPkg.isNotEmpty()) AppResolver.getAppName(this, targetPkg) else ""

        serviceScope.launch {
            try {
                val db = (application as TpingApplication).database
                db.workflowDao().insert(
                    Workflow(name = name, stepsJson = gson.toJson(actions),
                        targetAppPackage = targetPkg, targetAppName = targetAppName)
                )
                _overlayState.value = _overlayState.value.copy(
                    showSaveDialog = false, recordingDone = false,
                    statusText = "บันทึก \"$name\" แล้ว!"
                )
                setOverlayFocusable(false)
                service.getRecorder().clear()
            } catch (_: Exception) {
                _overlayState.value = _overlayState.value.copy(statusText = "เกิดข้อผิดพลาด")
            }
        }
    }

    private fun tagDataField(fieldKey: String) {
        val service = TpingAccessibilityService.instance ?: return
        service.getRecorder().tagLastActionAsDataField(fieldKey)
        _overlayState.value = _overlayState.value.copy(showTagDialog = false, statusText = "ผูกข้อมูล: $fieldKey")
        setOverlayFocusable(false)
    }

    private fun computeFieldSuggestion(): String {
        val service = TpingAccessibilityService.instance ?: return ""
        val actions = service.getRecorder().getActions()
        val lastAction = actions.lastOrNull() ?: return ""
        return AppResolver.suggestFieldName(lastAction.resourceId, lastAction.hintText, lastAction.contentDescription)
    }

    // ====== Game Mode Recording ======

    private fun getScreenMetrics(): DisplayMetrics {
        return resources.displayMetrics
    }

    private fun startGameRecording() {
        if (!LicenseManager.isLicenseValid()) {
            _overlayState.value = _overlayState.value.copy(
                statusText = "⛔ License หมดอายุ — กรุณาซื้อคีย์"
            )
            return
        }
        _overlayState.value = _overlayState.value.copy(
            showRecordModeDialog = false,
            mode = "game_recording", stepCount = 0,
            statusText = "โหมดเกม — ลากแถบด้านบนเพื่อย้าย",
            targetAppName = "", recordingDone = false
        )
        setOverlayFocusable(false)
        gameActions.clear()
        gameStepCounter = 0
        gameLastActionTime = System.currentTimeMillis()
    }

    private fun showCrosshair(actionType: ActionType, mode: String = "tap") {
        if (crosshairView != null) return
        pendingGameActionType = actionType
        crosshairMode = mode
        _overlayState.value = _overlayState.value.copy(showGameCrosshair = true)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        val actionLabel = when {
            mode == "puzzle" -> PuzzleRecordingFlow.getCrosshairLabel(puzzleRecordingState.step)
            mode == "input" -> "กรอกข้อมูล"
            actionType == ActionType.CLICK -> "กด"
            actionType == ActionType.LONG_CLICK -> "กดค้าง"
            else -> "กด"
        }

        val metrics = getScreenMetrics()
        crosshairView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingOverlayService)
            setViewTreeSavedStateRegistryOwner(this@FloatingOverlayService)
            setContent {
                CrosshairOverlay(
                    screenWidth = metrics.widthPixels,
                    screenHeight = metrics.heightPixels,
                    actionLabel = actionLabel,
                    onConfirm = { x, y ->
                        hideCrosshair()
                        when (mode) {
                            "puzzle" -> onPuzzleCrosshairConfirm(x, y)
                            "input" -> {
                                pendingGameInputX = x
                                pendingGameInputY = y
                                setOverlayFocusable(true)
                                _overlayState.value = _overlayState.value.copy(
                                    showGameTagDialog = true,
                                    pendingInputCoords = "($x, $y)"
                                )
                            }
                            else -> addGameTapAction(actionType, x, y)
                        }
                    },
                    onCancel = {
                        hideCrosshair()
                        if (mode == "puzzle") {
                            puzzleRecordingState = PuzzleRecordingState()
                            _overlayState.value = _overlayState.value.copy(
                                statusText = "ยกเลิก Captcha — ${gameActions.size} ขั้นตอน"
                            )
                        }
                    }
                )
            }
        }
        windowManager?.addView(crosshairView, params)
    }

    private fun hideCrosshair() {
        crosshairView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        crosshairView = null
        _overlayState.value = _overlayState.value.copy(showGameCrosshair = false)
    }

    private fun addGameTapAction(actionType: ActionType, x: Int, y: Int) {
        val metrics = getScreenMetrics()
        val now = System.currentTimeMillis()
        val delay = (now - gameLastActionTime).coerceIn(100, 5000)
        gameLastActionTime = now

        val action = RecordedAction(
            stepOrder = ++gameStepCounter,
            actionType = actionType,
            boundsLeft = x - 1, boundsTop = y - 1,
            boundsRight = x + 1, boundsBottom = y + 1,
            delayAfterMs = if (gameStepCounter == 1) 500 else delay,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            isGameMode = true
        )
        gameActions.add(action)
        _overlayState.value = _overlayState.value.copy(
            stepCount = gameActions.size,
            statusText = "${actionType.name} ที่ ($x, $y) — ${gameActions.size} ขั้นตอน"
        )
    }

    private fun addGameWaitAction(delayMs: Long) {
        val action = RecordedAction(
            stepOrder = ++gameStepCounter,
            actionType = ActionType.WAIT,
            delayAfterMs = delayMs,
            screenWidth = getScreenMetrics().widthPixels,
            screenHeight = getScreenMetrics().heightPixels,
            isGameMode = true
        )
        gameActions.add(action)
        gameLastActionTime = System.currentTimeMillis()
        _overlayState.value = _overlayState.value.copy(
            stepCount = gameActions.size,
            statusText = "รอ ${delayMs}ms — ${gameActions.size} ขั้นตอน"
        )
    }

    private fun addGameInputAction(fieldKey: String) {
        val metrics = getScreenMetrics()
        val now = System.currentTimeMillis()
        val delay = (now - gameLastActionTime).coerceIn(100, 5000)
        gameLastActionTime = now

        val action = RecordedAction(
            stepOrder = ++gameStepCounter,
            actionType = ActionType.INPUT_TEXT,
            boundsLeft = pendingGameInputX - 1, boundsTop = pendingGameInputY - 1,
            boundsRight = pendingGameInputX + 1, boundsBottom = pendingGameInputY + 1,
            dataFieldKey = fieldKey,
            delayAfterMs = if (gameStepCounter == 1) 500 else delay,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            isGameMode = true
        )
        gameActions.add(action)
        _overlayState.value = _overlayState.value.copy(
            showGameTagDialog = false,
            stepCount = gameActions.size,
            statusText = "กรอก [$fieldKey] ที่ (${pendingGameInputX}, ${pendingGameInputY}) — ${gameActions.size} ขั้นตอน"
        )
        setOverlayFocusable(false)
    }

    // ====== Puzzle CAPTCHA Recording ======

    private fun startPuzzleCrosshairFlow() {
        puzzleRecordingState = PuzzleRecordingState(step = PuzzleRecordingStep.SELECT_SLIDER_BUTTON)
        _overlayState.value = _overlayState.value.copy(
            statusText = "กดที่ปุ่มสไลด์"
        )
        showCrosshair(ActionType.CLICK, "puzzle")
    }

    private fun onPuzzleCrosshairConfirm(x: Int, y: Int) {
        when (puzzleRecordingState.step) {
            PuzzleRecordingStep.SELECT_SLIDER_BUTTON -> {
                puzzleRecordingState = puzzleRecordingState.copy(
                    sliderButtonX = x, sliderButtonY = y,
                    step = PuzzleRecordingStep.SELECT_SLIDER_TRACK_END
                )
                _overlayState.value = _overlayState.value.copy(
                    statusText = "กดที่ปลายทาง slider (ขวาสุด)"
                )
                showCrosshair(ActionType.CLICK, "puzzle")
            }
            PuzzleRecordingStep.SELECT_SLIDER_TRACK_END -> {
                puzzleRecordingState = puzzleRecordingState.copy(
                    sliderTrackEndX = x, sliderTrackEndY = y,
                    step = PuzzleRecordingStep.SELECT_REFRESH_BUTTON
                )
                _overlayState.value = _overlayState.value.copy(
                    statusText = "กดที่ปุ่มรีเฟรช Puzzle"
                )
                showCrosshair(ActionType.CLICK, "puzzle")
            }
            PuzzleRecordingStep.SELECT_REFRESH_BUTTON -> {
                puzzleRecordingState = puzzleRecordingState.copy(
                    refreshButtonX = x, refreshButtonY = y,
                    step = PuzzleRecordingStep.DONE
                )
                addPuzzleCaptchaAction()
            }
            else -> {}
        }
    }

    private fun addPuzzleCaptchaAction() {
        val metrics = getScreenMetrics()
        val now = System.currentTimeMillis()
        val delay = (now - gameLastActionTime).coerceIn(100, 5000)
        gameLastActionTime = now

        val action = PuzzleRecordingFlow.buildAction(
            state = puzzleRecordingState,
            stepOrder = ++gameStepCounter,
            delayAfterMs = if (gameStepCounter == 1) 500 else delay,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            packageName = try {
                TpingAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString() ?: ""
            } catch (_: Exception) { "" }
        )
        gameActions.add(action)
        puzzleRecordingState = PuzzleRecordingState() // reset
        _overlayState.value = _overlayState.value.copy(
            stepCount = gameActions.size,
            statusText = "Captcha Puzzle — ${gameActions.size} ขั้นตอน"
        )
    }

    private fun stopGameRecording() {
        if (gameActions.isEmpty()) {
            _overlayState.value = _overlayState.value.copy(
                mode = "idle", statusText = "ไม่มีขั้นตอน"
            )
            return
        }
        // Detect target app from foreground
        val currentPkg = try {
            TpingAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString() ?: ""
        } catch (_: Exception) { "" }
        val appName = if (currentPkg.isNotEmpty() && currentPkg != packageName) {
            AppResolver.getAppName(this, currentPkg)
        } else ""
        val suggestedName = if (appName.isNotEmpty()) "$appName (เกม)" else "Game Workflow"

        // Set package on all actions
        if (currentPkg.isNotEmpty()) {
            for (i in gameActions.indices) {
                gameActions[i] = gameActions[i].copy(packageName = currentPkg)
            }
        }

        _overlayState.value = _overlayState.value.copy(
            mode = "idle",
            statusText = "บันทึกแล้ว ${gameActions.size} ขั้นตอน (เกม)",
            recordingDone = true, showSaveDialog = true,
            suggestedWorkflowName = suggestedName,
            targetAppName = appName
        )
        setOverlayFocusable(true)
    }

    // Override saveRecordingFromOverlay to handle game mode actions too
    private fun saveGameRecordingFromOverlay(name: String) {
        if (gameActions.isEmpty()) return
        val targetPkg = gameActions.firstOrNull()?.packageName ?: ""
        val targetAppName = if (targetPkg.isNotEmpty()) AppResolver.getAppName(this, targetPkg) else ""

        serviceScope.launch {
            try {
                val db = (application as TpingApplication).database
                db.workflowDao().insert(
                    Workflow(name = name, stepsJson = gson.toJson(gameActions),
                        targetAppPackage = targetPkg, targetAppName = targetAppName)
                )
                _overlayState.value = _overlayState.value.copy(
                    showSaveDialog = false, recordingDone = false,
                    statusText = "บันทึก \"$name\" แล้ว!"
                )
                setOverlayFocusable(false)
                gameActions.clear()
            } catch (_: Exception) {
                _overlayState.value = _overlayState.value.copy(statusText = "เกิดข้อผิดพลาด")
            }
        }
    }

    // ====== Delete Workflow ======

    private fun deleteWorkflow(workflowId: Long) {
        serviceScope.launch {
            try {
                val db = (application as TpingApplication).database
                val workflow = db.workflowDao().getById(workflowId) ?: return@launch
                db.workflowDao().delete(workflow)
                _overlayState.value = _overlayState.value.copy(
                    statusText = "ลบแล้ว"
                )
            } catch (_: Exception) {
                _overlayState.value = _overlayState.value.copy(statusText = "เกิดข้อผิดพลาด")
            }
        }
    }

    // ====== Playback from Overlay ======

    private fun startPlaybackFromOverlay(workflowId: Long, category: String, loops: Int, shuffleData: Boolean) {
        if (!LicenseManager.isLicenseValid()) {
            _overlayState.value = _overlayState.value.copy(
                showPlayDialog = false,
                statusText = "⛔ License หมดอายุ — กรุณาซื้อคีย์"
            )
            setOverlayFocusable(false)
            return
        }
        if (TpingAccessibilityService.instance == null) {
            _overlayState.value = _overlayState.value.copy(
                showPlayDialog = false,
                statusText = "⚠ เปิด Accessibility ก่อน! (กลับไปแอพ Tping)"
            )
            setOverlayFocusable(false)
            return
        }
        _overlayState.value = _overlayState.value.copy(showPlayDialog = false, mode = "playing", statusText = "กำลังเตรียม...")
        setOverlayFocusable(false)
        serviceScope.launch {
            try {
                val db = (application as TpingApplication).database
                val workflow = db.workflowDao().getById(workflowId) ?: return@launch
                val actions: List<RecordedAction> = try {
                    val type = object : TypeToken<List<RecordedAction>>() {}.type
                    gson.fromJson(workflow.stepsJson, type) ?: emptyList()
                } catch (_: Exception) { emptyList() }

                // Load all profiles in the selected category
                val dataFieldSets: List<List<DataField>> = if (category.isNotEmpty()) {
                    val profiles = db.dataProfileDao().getByCategory(category)
                    profiles.mapNotNull { profile ->
                        try {
                            val type = object : TypeToken<List<DataField>>() {}.type
                            gson.fromJson<List<DataField>>(profile.fieldsJson, type)
                        } catch (_: Exception) { null }
                    }
                } else emptyList()

                playbackEngine = PlaybackEngine()
                playbackEngine?.play(
                    actions = actions,
                    dataFieldSets = dataFieldSets,
                    loopCount = loops,
                    shuffleData = shuffleData,
                    scope = serviceScope
                )
                observePlaybackState()
            } catch (e: Exception) {
                _overlayState.value = _overlayState.value.copy(mode = "idle", statusText = "เกิดข้อผิดพลาด")
            }
        }
    }

    // ====== Playback Controls ======

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
        _overlayState.value = _overlayState.value.copy(isExpanded = !_overlayState.value.isExpanded)
    }
    fun updatePlaybackState(step: Int, total: Int, desc: String, loop: Int, totalLoops: Int) {
        _overlayState.value = _overlayState.value.copy(
            mode = "playing", currentStep = step, totalSteps = total,
            statusText = desc, currentLoop = loop, totalLoops = totalLoops,
            progress = if (total > 0) step.toFloat() / total else 0f
        )
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, TpingApplication.CHANNEL_ID)
            .setContentTitle("Tping ช่วยพิมพ์กำลังทำงาน")
            .setContentText("แตะเพื่อเปิดแอพ - ช่วยเหลือผู้ที่ใช้นิ้วไม่สะดวก")
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
        hideCrosshair()
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null; overlayParams = null; instance = null
        super.onDestroy()
    }
}

data class WorkflowItem(val id: Long, val name: String, val appName: String, val stepCount: Int, val dataKeys: List<String> = emptyList())
data class ProfileCategoryItem(
    val category: String,
    val profileCount: Int,
    val fieldKeys: List<String>,
    val profileIds: List<Long>
)

data class OverlayState(
    val mode: String = "idle", // idle, recording, game_recording, playing, paused
    val isExpanded: Boolean = true,
    val statusText: String = "พร้อมใช้งาน",
    val currentStep: Int = 0, val totalSteps: Int = 0, val stepCount: Int = 0,
    val currentLoop: Int = 0, val totalLoops: Int = 0, val progress: Float = 0f,
    val showTagDialog: Boolean = false, val showPlayDialog: Boolean = false,
    val showSaveDialog: Boolean = false, val recordingDone: Boolean = false,
    val showRecordModeDialog: Boolean = false,
    val showGameCrosshair: Boolean = false,
    val showGameTagDialog: Boolean = false,
    val pendingInputCoords: String = "",
    val targetAppName: String = "", val suggestedFieldName: String = "",
    val suggestedWorkflowName: String = "",
    val workflowItems: List<WorkflowItem> = emptyList(),
    val profileCategories: List<ProfileCategoryItem> = emptyList(),
    val allFieldKeys: List<String> = emptyList()
)
