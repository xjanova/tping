package com.xjanova.tping.recorder

import android.util.Log
import com.xjanova.tping.data.entity.ActionType
import com.xjanova.tping.data.entity.DataField
import com.xjanova.tping.data.entity.RecordedAction
import com.xjanova.tping.puzzle.PuzzleCaptchaAction
import com.xjanova.tping.service.TpingAccessibilityService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PlaybackEngine {

    companion object {
        private const val TAG = "PlaybackEngine"
        /** Minimum delay between steps (ms). Users can add WAIT steps for more. */
        const val MIN_STEP_DELAY_MS = 3000L
    }

    private var playbackJob: Job? = null
    private var isPaused = false

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state

    data class PlaybackState(
        val isPlaying: Boolean = false,
        val isPaused: Boolean = false,
        val currentStep: Int = 0,
        val totalSteps: Int = 0,
        val currentLoop: Int = 0,
        val totalLoops: Int = 0,
        val currentActionDesc: String = "",
        val progress: Float = 0f
    )

    fun play(
        actions: List<RecordedAction>,
        dataFieldSets: List<List<DataField>>,
        loopCount: Int = 1,
        shuffleData: Boolean = false,
        scope: CoroutineScope
    ) {
        if (actions.isEmpty()) return

        val service = TpingAccessibilityService.instance ?: run {
            Log.e(TAG, "Accessibility Service not running")
            return
        }

        playbackJob?.cancel()
        isPaused = false
        TpingAccessibilityService.setPlaying(true)

        // Prepare data order: shuffle or sequential (always rotate through all sets)
        val orderedSets = if (dataFieldSets.size > 1 && shuffleData) {
            dataFieldSets.shuffled()
        } else {
            dataFieldSets
        }

        // Set state synchronously BEFORE launching coroutine so observer sees isPlaying=true
        _state.value = PlaybackState(
            isPlaying = true,
            totalSteps = actions.size,
            totalLoops = loopCount
        )

        playbackJob = scope.launch(Dispatchers.Main) {
            try {
                for (loop in 1..loopCount) {
                    _state.value = _state.value.copy(currentLoop = loop)

                    // Pick data fields for this loop — rotate through all sets
                    val dataFields = if (orderedSets.isEmpty()) {
                        emptyList()
                    } else if (orderedSets.size > 1) {
                        orderedSets[(loop - 1) % orderedSets.size]
                    } else {
                        orderedSets.firstOrNull() ?: emptyList()
                    }

                    for ((index, action) in actions.withIndex()) {
                        // Check pause
                        while (isPaused) {
                            delay(200)
                            if (!isActive) return@launch
                        }
                        if (!isActive) return@launch

                        val stepNum = index + 1
                        val stepDesc = "[${stepNum}/${actions.size}] ${describeAction(action)}"
                        _state.value = _state.value.copy(
                            currentStep = stepNum,
                            currentActionDesc = stepDesc,
                            progress = stepNum.toFloat() / actions.size
                        )
                        Log.d(TAG, "Step $stepDesc")

                        // Resolve data field
                        val textToInput = if (action.dataFieldKey.isNotEmpty()) {
                            dataFields.find { it.key == action.dataFieldKey }?.value ?: action.inputText
                        } else {
                            action.inputText
                        }

                        // Execute action
                        executeAction(service, action, textToInput)

                        // Delay: enforce minimum 3s between steps so the screen has time to respond
                        // WAIT actions use their own delay which is already intentional
                        val stepDelay = if (action.actionType == ActionType.WAIT) {
                            action.delayAfterMs
                        } else {
                            action.delayAfterMs.coerceAtLeast(MIN_STEP_DELAY_MS)
                        }
                        delay(stepDelay)
                    }

                    // Delay between loops
                    if (loop < loopCount) {
                        delay(1000)
                    }
                }
            } finally {
                TpingAccessibilityService.setPlaying(false)
                _state.value = PlaybackState()
            }
        }
    }

    private suspend fun executeAction(
        service: TpingAccessibilityService,
        action: RecordedAction,
        textToInput: String
    ) {
        // SOLVE_CAPTCHA is a suspend function — wrap entire call in timeout
        if (action.actionType == ActionType.SOLVE_CAPTCHA) {
            _state.value = _state.value.copy(currentActionDesc = _state.value.currentActionDesc + " ⏳")
            withTimeoutOrNull(90_000L) {
                try {
                    PuzzleCaptchaAction.execute(service, action) { status ->
                        val step = _state.value.currentStep
                        val total = _state.value.totalSteps
                        _state.value = _state.value.copy(
                            currentActionDesc = "[$step/$total] Captcha: $status"
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "SOLVE_CAPTCHA error: ${e.message}")
                    _state.value = _state.value.copy(
                        currentActionDesc = _state.value.currentActionDesc.replace("⏳", "❌ ${e.message?.take(30)}")
                    )
                }
            } ?: run {
                Log.w(TAG, "SOLVE_CAPTCHA timed out after 90s")
                _state.value = _state.value.copy(
                    currentActionDesc = _state.value.currentActionDesc.replace("⏳", "⏰ หมดเวลา")
                )
            }
            return
        }

        val done = CompletableDeferred<Unit>()

        when (action.actionType) {
            ActionType.CLICK -> {
                service.clickAtNode(action) { done.complete(Unit) }
            }
            ActionType.LONG_CLICK -> {
                service.longClickAtNode(action) { done.complete(Unit) }
            }
            ActionType.INPUT_TEXT -> {
                service.inputTextAtNode(action, textToInput) { done.complete(Unit) }
            }
            ActionType.SCROLL_UP -> {
                service.performScrollUp { done.complete(Unit) }
            }
            ActionType.SCROLL_DOWN -> {
                service.performScrollDown { done.complete(Unit) }
            }
            ActionType.BACK_BUTTON -> {
                service.performBack { done.complete(Unit) }
            }
            ActionType.WAIT -> {
                delay(action.delayAfterMs)
                done.complete(Unit)
            }
            else -> {
                done.complete(Unit)
            }
        }

        withTimeoutOrNull(5_000L) { done.await() }

        // Post-input verification for INPUT_TEXT
        if (action.actionType == ActionType.INPUT_TEXT && textToInput.isNotEmpty()) {
            verifyInputText(service, textToInput)
        }
    }

    /** Verify the text was set correctly by checking the focused input node */
    private fun verifyInputText(service: TpingAccessibilityService, expectedText: String) {
        try {
            val root = service.rootInActiveWindow ?: return
            val focused = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) {
                val actualText = focused.text?.toString() ?: ""
                if (actualText != expectedText) {
                    Log.w(TAG, "INPUT verify: mismatch! expected='${expectedText.take(20)}' actual='${actualText.take(20)}'")
                } else {
                    Log.d(TAG, "INPUT verify: OK '${expectedText.take(20)}'")
                }
                try { focused.recycle() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "INPUT verify exception: ${e.message}")
        }
    }

    private fun describeAction(action: RecordedAction): String {
        val coordText = if (action.isGameMode) {
            val cx = (action.boundsLeft + action.boundsRight) / 2
            val cy = (action.boundsTop + action.boundsBottom) / 2
            "($cx,$cy)"
        } else ""

        return when (action.actionType) {
            ActionType.CLICK -> {
                val target = action.text.ifEmpty { action.resourceId.substringAfterLast("/") }
                if (target.isNotEmpty()) "กด: $target" else "กด $coordText"
            }
            ActionType.LONG_CLICK -> {
                val target = action.text.ifEmpty { action.resourceId.substringAfterLast("/") }
                if (target.isNotEmpty()) "กดค้าง: $target" else "กดค้าง $coordText"
            }
            ActionType.INPUT_TEXT -> {
                val field = if (action.dataFieldKey.isNotEmpty()) "[${action.dataFieldKey}]" else action.inputText.take(15)
                "กด+กรอก: $field"
            }
            ActionType.SCROLL_UP -> "เลื่อนขึ้น"
            ActionType.SCROLL_DOWN -> "เลื่อนลง"
            ActionType.BACK_BUTTON -> "กดย้อนกลับ"
            ActionType.WAIT -> "รอ ${action.delayAfterMs / 1000.0}s"
            ActionType.SOLVE_CAPTCHA -> "แก้ Captcha สไลด์"
        }
    }

    fun pause() {
        isPaused = true
        _state.value = _state.value.copy(isPaused = true)
    }

    fun resume() {
        isPaused = false
        _state.value = _state.value.copy(isPaused = false)
    }

    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        isPaused = false
        TpingAccessibilityService.setPlaying(false)
        _state.value = PlaybackState()
    }
}
