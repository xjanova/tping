package com.xjanova.tping.puzzle

import com.google.gson.Gson
import com.xjanova.tping.data.entity.ActionType
import com.xjanova.tping.data.entity.RecordedAction

enum class PuzzleRecordingStep {
    IDLE,
    SELECT_SLIDER_BUTTON,
    SELECT_REFRESH_BUTTON,
    DONE
}

data class PuzzleRecordingState(
    val step: PuzzleRecordingStep = PuzzleRecordingStep.IDLE,
    val sliderButtonX: Int = 0,
    val sliderButtonY: Int = 0,
    val refreshButtonX: Int = 0,
    val refreshButtonY: Int = 0
)

object PuzzleRecordingFlow {

    private val gson = Gson()

    fun getCrosshairLabel(step: PuzzleRecordingStep): String {
        return when (step) {
            PuzzleRecordingStep.SELECT_SLIDER_BUTTON -> "กดที่ปุ่มสไลด์"
            PuzzleRecordingStep.SELECT_REFRESH_BUTTON -> "กดที่ปุ่มรีเฟรช Puzzle"
            else -> ""
        }
    }

    fun buildAction(
        state: PuzzleRecordingState,
        stepOrder: Int,
        delayAfterMs: Long,
        screenWidth: Int,
        screenHeight: Int,
        packageName: String
    ): RecordedAction {
        val config = PuzzleConfig(
            sliderButtonX = state.sliderButtonX,
            sliderButtonY = state.sliderButtonY,
            refreshButtonX = state.refreshButtonX,
            refreshButtonY = state.refreshButtonY
        )
        return RecordedAction(
            stepOrder = stepOrder,
            actionType = ActionType.SOLVE_CAPTCHA,
            inputText = gson.toJson(config),
            boundsLeft = 0,
            boundsTop = 0,
            boundsRight = 0,
            boundsBottom = 0,
            delayAfterMs = delayAfterMs,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            isGameMode = true,
            packageName = packageName
        )
    }
}
