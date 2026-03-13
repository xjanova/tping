package com.xjanova.tping.data.entity

data class RecordedAction(
    val stepOrder: Int,
    val actionType: ActionType,
    // Layer 1: Resource ID (most reliable)
    val resourceId: String = "",
    // Layer 2: Text + Class + Description
    val text: String = "",
    val className: String = "",
    val contentDescription: String = "",
    // Layer 3: Coordinates (fallback)
    val boundsLeft: Int = 0,
    val boundsTop: Int = 0,
    val boundsRight: Int = 0,
    val boundsBottom: Int = 0,
    // Data binding
    val dataFieldKey: String = "", // links to DataField.key if this is an input field
    val inputText: String = "",    // literal text if not bound to data
    // Timing
    val delayAfterMs: Long = 500,
    // Extra
    val packageName: String = "",
    // Smart field detection
    val hintText: String = "",     // hint text from the input field for auto-suggesting field names
    // Screen resolution for coordinate scaling (game mode)
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    // Game mode flag: true = recorded via crosshair (coordinate-only, no accessibility node)
    val isGameMode: Boolean = false
)

enum class ActionType {
    CLICK,
    LONG_CLICK,
    INPUT_TEXT,
    SCROLL_UP,
    SCROLL_DOWN,
    BACK_BUTTON,
    HOME_BUTTON,
    WAIT,
    SOLVE_CAPTCHA
}
