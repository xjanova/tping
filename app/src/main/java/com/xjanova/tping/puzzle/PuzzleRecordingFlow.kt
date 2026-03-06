package com.xjanova.tping.puzzle

import com.google.gson.Gson
import com.xjanova.tping.data.entity.ActionType
import com.xjanova.tping.data.entity.RecordedAction

enum class PuzzleRecordingStep {
    IDLE,
    SELECT_PUZZLE_TOP_LEFT,
    SELECT_PUZZLE_BOTTOM_RIGHT,
    SELECT_SLIDER_BUTTON,
    DONE
}

data class PuzzleRecordingState(
    val step: PuzzleRecordingStep = PuzzleRecordingStep.IDLE,
    val puzzleLeft: Int = 0,
    val puzzleTop: Int = 0,
    val puzzleRight: Int = 0,
    val puzzleBottom: Int = 0,
    val sliderButtonX: Int = 0,
    val sliderButtonY: Int = 0
)

object PuzzleRecordingFlow {

    private val gson = Gson()

    fun getCrosshairLabel(step: PuzzleRecordingStep): String {
        return when (step) {
            PuzzleRecordingStep.SELECT_PUZZLE_TOP_LEFT -> "มุมซ้ายบน ภาพ Puzzle"
            PuzzleRecordingStep.SELECT_PUZZLE_BOTTOM_RIGHT -> "มุมขวาล่าง ภาพ Puzzle"
            PuzzleRecordingStep.SELECT_SLIDER_BUTTON -> "กดที่ปุ่มสไลด์"
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
            puzzleLeft = state.puzzleLeft,
            puzzleTop = state.puzzleTop,
            puzzleRight = state.puzzleRight,
            puzzleBottom = state.puzzleBottom,
            sliderButtonX = state.sliderButtonX,
            sliderButtonY = state.sliderButtonY
        )
        return RecordedAction(
            stepOrder = stepOrder,
            actionType = ActionType.SOLVE_CAPTCHA,
            inputText = gson.toJson(config),
            // Slider data is now in PuzzleConfig; bounds not used for new recordings
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
