package com.xjanova.tping.puzzle

/**
 * Configuration for a slide CAPTCHA puzzle step.
 * Serialized as JSON into RecordedAction.inputText.
 *
 * New format (v2): only slider button + refresh button positions.
 * The puzzle area is auto-detected from screenshots.
 *
 * Legacy format: puzzle bounds + slider bounds (kept for backward compatibility).
 */
data class PuzzleConfig(
    // Slider button center (screen coordinates at recording time)
    val sliderButtonX: Int = 0,
    val sliderButtonY: Int = 0,
    // Refresh/reload button center (for when gap detection fails)
    val refreshButtonX: Int = 0,
    val refreshButtonY: Int = 0,
    // Tuning
    val maxRetries: Int = 3,
    val retryDelayMs: Long = 2000,
    val swipeDurationMs: Long = 600,
    val analyzeMethod: String = "edge",
    // Legacy fields (kept for old recordings backward compatibility)
    val puzzleLeft: Int = 0,
    val puzzleTop: Int = 0,
    val puzzleRight: Int = 0,
    val puzzleBottom: Int = 0,
    val sliderPaddingPx: Int = 30
)
