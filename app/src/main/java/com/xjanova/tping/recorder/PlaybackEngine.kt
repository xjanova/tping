package com.xjanova.tping.recorder

import android.util.Log
import com.xjanova.tping.data.entity.ActionType
import com.xjanova.tping.data.entity.DataField
import com.xjanova.tping.data.entity.RecordedAction
import com.xjanova.tping.service.TpingAccessibilityService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PlaybackEngine {

    companion object {
        private const val TAG = "PlaybackEngine"
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
        dataFields: List<DataField>,
        loopCount: Int = 1,
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

        playbackJob = scope.launch(Dispatchers.Main) {
            _state.value = PlaybackState(
                isPlaying = true,
                totalSteps = actions.size,
                totalLoops = loopCount
            )

            try {
                for (loop in 1..loopCount) {
                    _state.value = _state.value.copy(currentLoop = loop)

                    for ((index, action) in actions.withIndex()) {
                        // Check pause
                        while (isPaused) {
                            delay(200)
                            if (!isActive) return@launch
                        }
                        if (!isActive) return@launch

                        val stepNum = index + 1
                        _state.value = _state.value.copy(
                            currentStep = stepNum,
                            currentActionDesc = describeAction(action),
                            progress = stepNum.toFloat() / actions.size
                        )

                        // Resolve data field
                        val textToInput = if (action.dataFieldKey.isNotEmpty()) {
                            dataFields.find { it.key == action.dataFieldKey }?.value ?: action.inputText
                        } else {
                            action.inputText
                        }

                        // Execute action
                        executeAction(service, action, textToInput)

                        // Delay
                        delay(action.delayAfterMs)
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
        }

        withTimeoutOrNull(5000) { done.await() }
    }

    private fun describeAction(action: RecordedAction): String {
        return when (action.actionType) {
            ActionType.CLICK -> "กดที่: ${action.text.ifEmpty { action.resourceId.substringAfterLast("/") }}"
            ActionType.INPUT_TEXT -> "พิมพ์: ${if (action.dataFieldKey.isNotEmpty()) "[${action.dataFieldKey}]" else action.inputText.take(20)}"
            ActionType.SCROLL_UP -> "เลื่อนขึ้น"
            ActionType.SCROLL_DOWN -> "เลื่อนลง"
            ActionType.BACK_BUTTON -> "กดย้อนกลับ"
            ActionType.WAIT -> "รอ ${action.delayAfterMs}ms"
            ActionType.LONG_CLICK -> "กดค้าง: ${action.text.ifEmpty { action.resourceId }}"
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
