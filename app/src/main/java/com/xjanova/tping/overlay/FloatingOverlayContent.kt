package com.xjanova.tping.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjanova.tping.data.entity.ActionType
import kotlin.math.roundToInt

private val RecordColor = Color(0xFFEF4444)
private val PlayColor = Color(0xFF22C55E)
private val PauseColor = Color(0xFFF59E0B)
private val PrimaryColor = Color(0xFF6750A4)
private val TagColor = Color(0xFF3B82F6)
private val GameColor = Color(0xFFE040FB)

@Composable
fun FloatingOverlayContent(
    state: OverlayState,
    onStartRecord: () -> Unit,
    onStartNormalRecord: () -> Unit,
    onStartGameRecord: () -> Unit,
    onDismissRecordModeDialog: () -> Unit,
    onStopRecord: () -> Unit,
    onStopGameRecord: () -> Unit,
    onSaveRecording: (String) -> Unit,
    onDismissSaveDialog: () -> Unit,
    onTagData: (String) -> Unit,
    onShowTagDialog: () -> Unit,
    onDismissTagDialog: () -> Unit,
    onShowPlayDialog: () -> Unit,
    onDismissPlayDialog: () -> Unit,
    onStartPlayback: (Long, String, Int, Boolean) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onToggleExpand: () -> Unit,
    onClose: () -> Unit,
    onDragDelta: (Float, Float) -> Unit = { _, _ -> },
    onShowGameCrosshair: (ActionType) -> Unit = {},
    onAddGameWait: (Long) -> Unit = {},
    onShowGameInputCrosshair: () -> Unit = {},
    onGameTagConfirm: (String) -> Unit = {},
    onDismissGameTagDialog: () -> Unit = {},
    onShowPuzzleCrosshair: () -> Unit = {}
) {
    Column {
        if (!state.isExpanded) {
            MiniOverlay(mode = state.mode, step = state.currentStep, onClick = onToggleExpand, onDragDelta = onDragDelta)
        } else {
            // Show dialog OR main panel (not both overlapping)
            when {
                state.showRecordModeDialog -> {
                    RecordModeDialog(
                        onNormal = onStartNormalRecord,
                        onGame = onStartGameRecord,
                        onDismiss = onDismissRecordModeDialog
                    )
                }
                state.showGameTagDialog -> {
                    GameDataFieldDialog(
                        coordsText = state.pendingInputCoords,
                        availableKeys = state.allFieldKeys,
                        onConfirm = { fieldKey -> onGameTagConfirm(fieldKey) },
                        onDismiss = onDismissGameTagDialog
                    )
                }
                state.showTagDialog -> {
                    TagDataDialog(
                        suggestion = state.suggestedFieldName,
                        availableKeys = state.allFieldKeys,
                        onConfirm = { fieldKey -> onTagData(fieldKey) },
                        onDismiss = onDismissTagDialog
                    )
                }
                state.showSaveDialog -> {
                    SaveWorkflowDialog(
                        suggestedName = state.suggestedWorkflowName,
                        onSave = onSaveRecording,
                        onDismiss = onDismissSaveDialog
                    )
                }
                state.showPlayDialog -> {
                    PlaySelectDialog(
                        workflows = state.workflowItems,
                        categories = state.profileCategories,
                        onStart = onStartPlayback,
                        onDismiss = onDismissPlayDialog
                    )
                }
                else -> {
                    ExpandedOverlay(
                        state = state,
                        onStartRecord = onStartRecord,
                        onStopRecord = onStopRecord,
                        onStopGameRecord = onStopGameRecord,
                        onShowTagDialog = onShowTagDialog,
                        onShowPlayDialog = onShowPlayDialog,
                        onPause = onPause,
                        onResume = onResume,
                        onStop = onStop,
                        onMinimize = onToggleExpand,
                        onClose = onClose,
                        onDragDelta = onDragDelta,
                        onShowGameCrosshair = onShowGameCrosshair,
                        onAddGameWait = onAddGameWait,
                        onShowGameInputCrosshair = onShowGameInputCrosshair,
                        onShowPuzzleCrosshair = onShowPuzzleCrosshair
                    )
                }
            }
        }
    }
}

@Composable
fun MiniOverlay(mode: String, step: Int, onClick: () -> Unit, onDragDelta: (Float, Float) -> Unit = { _, _ -> }) {
    val bgColor = when (mode) {
        "recording" -> RecordColor
        "game_recording" -> GameColor
        "playing" -> PlayColor
        "paused" -> PauseColor
        else -> PrimaryColor
    }

    Box(
        modifier = Modifier
            .size(52.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(bgColor, bgColor.copy(alpha = 0.8f))
                )
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var dragged = false
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.all { !it.pressed }) {
                            // All fingers released
                            if (!dragged) onClick()
                            break
                        }
                        val change = event.changes.first()
                        val delta = change.positionChange()
                        if (delta.x != 0f || delta.y != 0f) {
                            dragged = true
                            change.consume()
                            onDragDelta(delta.x, delta.y)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        when (mode) {
            "recording" -> Text("●", color = Color.White, fontSize = 22.sp)
            "game_recording" -> Text("●", color = Color.White, fontSize = 22.sp)
            "playing" -> Text("▶", color = Color.White, fontSize = 18.sp)
            "paused" -> Text("⏸", color = Color.White, fontSize = 16.sp)
            else -> Text("T", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ExpandedOverlay(
    state: OverlayState,
    onStartRecord: () -> Unit,
    onStopRecord: () -> Unit,
    onStopGameRecord: () -> Unit,
    onShowTagDialog: () -> Unit,
    onShowPlayDialog: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onDragDelta: (Float, Float) -> Unit = { _, _ -> },
    onShowGameCrosshair: (ActionType) -> Unit = {},
    onAddGameWait: (Long) -> Unit = {},
    onShowGameInputCrosshair: () -> Unit = {},
    onShowPuzzleCrosshair: () -> Unit = {}
) {
    val headerColor = when (state.mode) {
        "recording" -> RecordColor
        "game_recording" -> GameColor
        "playing" -> PlayColor
        "paused" -> PauseColor
        else -> PrimaryColor
    }

    val appLabel = if (state.targetAppName.isNotEmpty()) " ${state.targetAppName}" else ""
    val headerText = when (state.mode) {
        "recording" -> "● REC$appLabel [${state.stepCount}]"
        "game_recording" -> "● GAME [${state.stepCount}]"
        "playing" -> "▶ PLAY  ${state.currentStep}/${state.totalSteps}"
        "paused" -> "⏸ PAUSED"
        else -> "⌨ Tping"
    }

    Card(
        modifier = Modifier
            .width(240.dp)
            .shadow(12.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF5181818)),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
    ) {
        Column {
            // Header with gradient (draggable)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(headerColor, headerColor.copy(alpha = 0.7f))
                        )
                    )
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDragDelta(dragAmount.x, dragAmount.y)
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.mode == "game_recording") {
                        Icon(Icons.Default.DragIndicator, "Drag", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(headerText, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        Icons.Default.Remove, "Minimize", tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(18.dp).clickable(onClick = onMinimize)
                    )
                    Icon(
                        Icons.Default.Close, "Close", tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(18.dp).clickable(onClick = onClose)
                    )
                }
            }

            // Status text
            Text(
                text = state.statusText,
                color = Color(0xFFBBBBBB),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )

            // Progress bar
            AnimatedVisibility(
                visible = state.mode == "playing" || state.mode == "paused",
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp)) {
                    LinearProgressIndicator(
                        progress = state.progress,
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = PlayColor,
                        trackColor = Color(0xFF333333)
                    )
                    if (state.totalLoops > 1) {
                        Text(
                            "รอบ: ${state.currentLoop}/${state.totalLoops}",
                            color = Color(0xFF888888), fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Controls
            when (state.mode) {
                "game_recording" -> {
                    // Game mode: 3 rows of buttons
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        OverlayButton(Icons.Default.TouchApp, "กด", GameColor) { onShowGameCrosshair(ActionType.CLICK) }
                        OverlayButton(Icons.Default.PanTool, "กดค้าง", Color(0xFFFF7043)) { onShowGameCrosshair(ActionType.LONG_CLICK) }
                        OverlayButton(Icons.Default.EditNote, "กรอก", TagColor, onShowGameInputCrosshair)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        OverlayButton(Icons.Default.Timer, "รอ 1s", PauseColor) { onAddGameWait(1000) }
                        OverlayButton(Icons.Default.Timer3, "รอ 3s", PauseColor) { onAddGameWait(3000) }
                        OverlayButton(Icons.Default.Extension, "Captcha", Color(0xFF00BCD4), onShowPuzzleCrosshair)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        OverlayButton(Icons.Default.Stop, "หยุด", RecordColor, onStopGameRecord)
                    }
                }
                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        when (state.mode) {
                            "idle" -> {
                                OverlayButton(Icons.Default.FiberManualRecord, "บันทึก", RecordColor, onStartRecord)
                                OverlayButton(Icons.Default.PlayArrow, "เล่น", PlayColor, onShowPlayDialog)
                            }
                            "recording" -> {
                                OverlayButton(Icons.Default.Label, "Tag", TagColor, onShowTagDialog)
                                OverlayButton(Icons.Default.Stop, "หยุด", RecordColor, onStopRecord)
                            }
                            "playing" -> {
                                OverlayButton(Icons.Default.Pause, "พัก", PauseColor, onPause)
                                OverlayButton(Icons.Default.Stop, "หยุด", RecordColor, onStop)
                            }
                            "paused" -> {
                                OverlayButton(Icons.Default.PlayArrow, "ต่อ", PlayColor, onResume)
                                OverlayButton(Icons.Default.Stop, "หยุด", RecordColor, onStop)
                            }
                        }
                    }
                }
            }

            // Xman Studio watermark
            Text(
                "Xman Studio",
                color = Color(0xFF555555), fontSize = 9.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp)
            )
        }
    }
}

@Composable
fun OverlayButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Icon(icon, label, tint = color, modifier = Modifier.size(22.dp))
        Text(label, color = Color(0xFFBBBBBB), fontSize = 10.sp)
    }
}

// ====== Save Workflow Dialog ======

@Composable
fun SaveWorkflowDialog(
    suggestedName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(suggestedName) }

    Card(
        modifier = Modifier.width(270.dp).padding(8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF5222222)),
        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Save, null, tint = PlayColor, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("บันทึก Workflow", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("ตั้งชื่อเพื่อเรียกใช้ภายหลัง", color = Color(0xFF999999), fontSize = 11.sp)

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("ชื่อ Workflow", color = Color(0xFF888888)) },
                placeholder = { Text("เช่น สมัครสมาชิก, Login", color = Color(0xFF555555), fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color(0xFFCCCCCC),
                    focusedBorderColor = PlayColor,
                    unfocusedBorderColor = Color(0xFF444444)
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text("ทิ้ง", color = Color(0xFF999999))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { if (name.isNotBlank()) onSave(name.trim()) },
                    colors = ButtonDefaults.buttonColors(containerColor = PlayColor),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("บันทึก", fontSize = 13.sp)
                }
            }
        }
    }
}

// ====== Play Selection Dialog ======

@Composable
fun PlaySelectDialog(
    workflows: List<WorkflowItem>,
    categories: List<ProfileCategoryItem>,
    onStart: (Long, String, Int, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedWorkflowId by remember { mutableStateOf<Long?>(null) }
    var selectedCategory by remember { mutableStateOf("") }
    var loops by remember { mutableIntStateOf(1) }
    var shuffleMode by remember { mutableStateOf(false) }

    val selectedWorkflow = workflows.find { it.id == selectedWorkflowId }
    val requiredKeys = selectedWorkflow?.dataKeys ?: emptyList()
    val selectedCatItem = categories.find { it.category == selectedCategory }
    val categoryKeys = selectedCatItem?.fieldKeys ?: emptyList()

    Card(
        modifier = Modifier.width(290.dp).heightIn(max = 400.dp).padding(8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF5222222)),
        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlayArrow, null, tint = PlayColor, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("เลือก Workflow", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("เลือกขั้นตอนและหมวดข้อมูล", color = Color(0xFF999999), fontSize = 11.sp)

            Spacer(modifier = Modifier.height(8.dp))

            // ---- Scrollable content area ----
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {

            // Smart playback tip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFF59E0B).copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(Icons.Default.Info, null, tint = Color(0xFFF59E0B), modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "ควรเริ่มเล่นที่หน้าจอเดียวกับที่เริ่มบันทึก เพื่อให้กดตำแหน่งถูกต้อง",
                    color = Color(0xFFF59E0B),
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Workflow list
            if (workflows.isEmpty()) {
                Text("ยังไม่มี Workflow", color = Color(0xFF666666), fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp))
            } else {
                Column {
                    workflows.forEach { wf ->
                        val isSelected = selectedWorkflowId == wf.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) PlayColor.copy(alpha = 0.2f) else Color.Transparent)
                                .clickable { selectedWorkflowId = wf.id }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isSelected) {
                                Icon(Icons.Default.CheckCircle, null, tint = PlayColor, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(wf.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row {
                                    Text("${wf.stepCount} ขั้นตอน", color = Color(0xFF888888), fontSize = 10.sp)
                                    if (wf.appName.isNotEmpty()) {
                                        Text(" | ", color = Color(0xFF555555), fontSize = 10.sp)
                                        Text(wf.appName, color = TagColor, fontSize = 10.sp)
                                    }
                                    if (wf.dataKeys.isNotEmpty()) {
                                        Text(" | ", color = Color(0xFF555555), fontSize = 10.sp)
                                        Text("${wf.dataKeys.size} ฟิลด์", color = Color(0xFFF59E0B), fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Show required data keys for selected workflow
            if (selectedWorkflow != null && requiredKeys.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF59E0B).copy(alpha = 0.1f))
                        .padding(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(Icons.Default.DataObject, null, tint = Color(0xFFF59E0B), modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text("ต้องใช้ข้อมูล:", color = Color(0xFFF59E0B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(2.dp))
                        requiredKeys.forEach { key ->
                            val hasKey = categoryKeys.contains(key)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (hasKey) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    null,
                                    tint = if (hasKey) PlayColor else Color(0xFF666666),
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(key, color = if (hasKey) Color(0xFFCCCCCC) else Color(0xFF888888), fontSize = 11.sp)
                            }
                        }
                        if (selectedCategory.isEmpty()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("⬆ เลือกหมวดข้อมูลด้านล่าง", color = Color(0xFF888888), fontSize = 10.sp)
                        }
                    }
                }
            }

            // Category picker (show when workflow has data keys OR categories exist)
            if (selectedWorkflow != null && requiredKeys.isNotEmpty() && categories.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("หมวดข้อมูล", color = Color(0xFFBBBBBB), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                Column {
                    categories.forEach { cat ->
                        val isSelected = selectedCategory == cat.category
                        val matchCount = requiredKeys.count { cat.fieldKeys.contains(it) }
                        val matchColor = when {
                            matchCount == requiredKeys.size -> PlayColor
                            matchCount > 0 -> Color(0xFFF59E0B)
                            else -> Color(0xFF666666)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) TagColor.copy(alpha = 0.2f) else Color.Transparent)
                                .clickable { selectedCategory = if (isSelected) "" else cat.category }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                null,
                                tint = if (isSelected) TagColor else Color(0xFF666666),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(cat.category, color = Color(0xFFCCCCCC), fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${cat.profileCount} ชุด", color = Color(0xFF888888), fontSize = 10.sp)
                            }
                            Text(
                                "$matchCount/${requiredKeys.size}",
                                color = matchColor, fontSize = 10.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Shuffle toggle (show when category selected and has multiple profiles)
                if (selectedCatItem != null && selectedCatItem.profileCount > 1) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF2A2A2A))
                            .clickable { shuffleMode = !shuffleMode }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (shuffleMode) Icons.Default.Shuffle else Icons.Default.FormatListNumbered,
                            null,
                            tint = if (shuffleMode) PauseColor else Color(0xFF888888),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (shuffleMode) "สุ่มข้อมูล" else "ใช้ตามลำดับ",
                                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium
                            )
                            Text(
                                if (shuffleMode) "สุ่มแต่ไม่ซ้ำในหมวดเดียวกัน" else "แต่ละรอบใช้ข้อมูลชุดถัดไป",
                                color = Color(0xFF888888), fontSize = 9.sp
                            )
                        }
                        Switch(
                            checked = shuffleMode,
                            onCheckedChange = { shuffleMode = it },
                            modifier = Modifier.height(20.dp),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = PauseColor,
                                checkedTrackColor = PauseColor.copy(alpha = 0.3f)
                            )
                        )
                    }
                }
            } else if (categories.isNotEmpty() && (selectedWorkflow == null || requiredKeys.isEmpty())) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("หมวดข้อมูล (ไม่บังคับ)", color = Color(0xFFBBBBBB), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                Column {
                    categories.forEach { cat ->
                        val isSelected = selectedCategory == cat.category
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) TagColor.copy(alpha = 0.2f) else Color.Transparent)
                                .clickable { selectedCategory = if (isSelected) "" else cat.category }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                null,
                                tint = if (isSelected) TagColor else Color(0xFF666666),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(cat.category, color = Color(0xFFCCCCCC), fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("${cat.profileCount} ชุด", color = Color(0xFF888888), fontSize = 10.sp)
                        }
                    }
                }
            }

            // Loop count
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("จำนวนรอบ", color = Color(0xFFBBBBBB), fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF333333))
                            .clickable { if (loops > 1) loops-- },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Remove, "Decrease", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    Text(
                        "$loops",
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF333333))
                            .clickable { if (loops < 999) loops++ },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Add, "Increase", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }

            } // ---- End scrollable content area ----

            Spacer(modifier = Modifier.height(4.dp))

            // Action buttons (pinned at bottom)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text("ยกเลิก", color = Color(0xFF999999))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val wfId = selectedWorkflowId
                        if (wfId != null) onStart(wfId, selectedCategory, loops, shuffleMode)
                    },
                    enabled = selectedWorkflowId != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PlayColor,
                        disabledContainerColor = PlayColor.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("เริ่มเล่น", fontSize = 13.sp)
                }
            }
        }
    }
}

// ====== Tag Data Dialog ======

@Composable
fun TagDataDialog(
    suggestion: String = "",
    availableKeys: List<String> = emptyList(),
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var fieldKey by remember { mutableStateOf(suggestion) }

    Card(
        modifier = Modifier.width(250.dp).padding(8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF5222222)),
        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("ผูกข้อมูลกับช่องนี้", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text("เลือก Key จากชุดข้อมูล หรือพิมพ์เอง", color = Color(0xFF999999), fontSize = 11.sp)

            // Available field key chips from profiles
            if (availableKeys.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    availableKeys.forEach { key ->
                        val isSelected = fieldKey == key
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) TagColor.copy(alpha = 0.3f) else Color(0xFF333333))
                                .clickable { fieldKey = key }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(key, color = if (isSelected) TagColor else Color(0xFFCCCCCC), fontSize = 11.sp)
                        }
                    }
                }
            }

            // Smart suggestion chip (only if not already in availableKeys)
            if (suggestion.isNotEmpty() && suggestion !in availableKeys) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(TagColor.copy(alpha = 0.2f))
                        .clickable { fieldKey = suggestion }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        Icons.Default.AutoAwesome, "Suggest",
                        tint = TagColor, modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("แนะนำ: $suggestion", color = TagColor, fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = fieldKey,
                onValueChange = { fieldKey = it },
                label = { Text("Data Key", color = Color(0xFF888888)) },
                placeholder = { Text("เช่น ชื่อ, เบอร์, ที่อยู่", color = Color(0xFF555555), fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color(0xFFCCCCCC),
                    focusedBorderColor = TagColor,
                    unfocusedBorderColor = Color(0xFF444444)
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text("ยกเลิก", color = Color(0xFF999999))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { if (fieldKey.isNotBlank()) onConfirm(fieldKey.trim()) },
                    colors = ButtonDefaults.buttonColors(containerColor = TagColor),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("ผูกข้อมูล", fontSize = 13.sp)
                }
            }
        }
    }
}

// ====== Record Mode Dialog (Normal vs Game) ======

@Composable
fun RecordModeDialog(
    onNormal: () -> Unit,
    onGame: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.width(260.dp).padding(8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF5222222)),
        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FiberManualRecord, null, tint = RecordColor, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("เลือกโหมดบันทึก", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("เลือกวิธีบันทึกที่เหมาะกับแอพเป้าหมาย", color = Color(0xFF999999), fontSize = 11.sp)

            Spacer(modifier = Modifier.height(14.dp))

            // Normal mode option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(RecordColor.copy(alpha = 0.1f))
                    .clickable(onClick = onNormal)
                    .border(1.dp, RecordColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(RecordColor.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.FiberManualRecord, null, tint = RecordColor, modifier = Modifier.size(22.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("ปกติ (แอพ)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("จับการกด/พิมพ์อัตโนมัติ", color = Color(0xFF999999), fontSize = 11.sp)
                    Text("เหมาะกับ: แอพทั่วไป, เว็บ", color = Color(0xFF777777), fontSize = 10.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Game mode option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(GameColor.copy(alpha = 0.1f))
                    .clickable(onClick = onGame)
                    .border(1.dp, GameColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(GameColor.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.SportsEsports, null, tint = GameColor, modifier = Modifier.size(22.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("เกม (พิกัด)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("เลือกจุดกดด้วยกากบาท", color = Color(0xFF999999), fontSize = 11.sp)
                    Text("เหมาะกับ: เกม, แอพพิเศษ", color = Color(0xFF777777), fontSize = 10.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text("ยกเลิก", color = Color(0xFF999999))
                }
            }
        }
    }
}

// ====== Crosshair Overlay for Game Mode ======

@Composable
fun CrosshairOverlay(
    screenWidth: Int,
    screenHeight: Int,
    actionLabel: String,
    onConfirm: (Int, Int) -> Unit,
    onCancel: () -> Unit
) {
    val density = LocalDensity.current

    // Crosshair position state — start at center
    var crosshairX by remember { mutableFloatStateOf(screenWidth / 2f) }
    var crosshairY by remember { mutableFloatStateOf(screenHeight / 2f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    crosshairX = (crosshairX + dragAmount.x).coerceIn(0f, screenWidth.toFloat())
                    crosshairY = (crosshairY + dragAmount.y).coerceIn(0f, screenHeight.toFloat())
                }
            }
    ) {
        // Top bar with controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xDD000000))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Cancel button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF444444))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("ยกเลิก", color = Color.White, fontSize = 13.sp)
            }

            // Coordinate display
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    actionLabel,
                    color = GameColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    "(${crosshairX.roundToInt()}, ${crosshairY.roundToInt()})",
                    color = Color(0xFFCCCCCC),
                    fontSize = 12.sp
                )
            }

            // Confirm button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(GameColor)
                    .clickable {
                        onConfirm(crosshairX.roundToInt(), crosshairY.roundToInt())
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("ยืนยัน ✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Crosshair lines and circle
        val crossXDp = with(density) { crosshairX.toDp() }
        val crossYDp = with(density) { crosshairY.toDp() }

        // Horizontal line
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .offset(y = crossYDp)
                .background(GameColor.copy(alpha = 0.6f))
        )

        // Vertical line
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .offset(x = crossXDp)
                .background(GameColor.copy(alpha = 0.6f))
        )

        // Center circle
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (crosshairX - with(density) { 20.dp.toPx() }).roundToInt(),
                        (crosshairY - with(density) { 20.dp.toPx() }).roundToInt()
                    )
                }
                .size(40.dp)
                .border(2.dp, GameColor, CircleShape)
                .clip(CircleShape)
                .background(GameColor.copy(alpha = 0.15f))
        )

        // Inner dot
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (crosshairX - with(density) { 4.dp.toPx() }).roundToInt(),
                        (crosshairY - with(density) { 4.dp.toPx() }).roundToInt()
                    )
                }
                .size(8.dp)
                .clip(CircleShape)
                .background(GameColor)
        )

        // Instruction text at bottom
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xCC000000))
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Text(
                "ลากเพื่อเลื่อนกากบาท แล้วกด ยืนยัน",
                color = Color.White,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ====== Game Data Field Dialog ======

@Composable
fun GameDataFieldDialog(
    coordsText: String = "",
    availableKeys: List<String> = emptyList(),
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var fieldKey by remember { mutableStateOf("") }

    // Use available keys from profiles, fallback to defaults if none
    val quickKeys = availableKeys.ifEmpty { listOf("ชื่อผู้ใช้", "รหัสผ่าน", "อีเมล", "เบอร์โทร") }

    Card(
        modifier = Modifier.width(270.dp).padding(8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF5222222)),
        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.EditNote, null, tint = TagColor, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("กรอกข้อมูลตรงนี้", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(modifier = Modifier.height(4.dp))

            // Show coordinate info
            if (coordsText.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(GameColor.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.MyLocation, null, tint = GameColor, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("ตำแหน่ง: $coordsText", color = GameColor, fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("เลือกหรือพิมพ์ชื่อฟิลด์ข้อมูล", color = Color(0xFF999999), fontSize = 11.sp)

            Spacer(modifier = Modifier.height(10.dp))

            // Quick key chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                quickKeys.forEach { key ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (fieldKey == key) TagColor.copy(alpha = 0.3f)
                                else Color(0xFF333333)
                            )
                            .clickable { fieldKey = key }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            key,
                            color = if (fieldKey == key) TagColor else Color(0xFFBBBBBB),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = fieldKey,
                onValueChange = { fieldKey = it },
                label = { Text("Data Key", color = Color(0xFF888888)) },
                placeholder = { Text("เช่น ชื่อผู้ใช้, รหัสผ่าน", color = Color(0xFF555555), fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color(0xFFCCCCCC),
                    focusedBorderColor = TagColor,
                    unfocusedBorderColor = Color(0xFF444444)
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text("ยกเลิก", color = Color(0xFF999999))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { if (fieldKey.isNotBlank()) onConfirm(fieldKey.trim()) },
                    colors = ButtonDefaults.buttonColors(containerColor = TagColor),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("ยืนยัน", fontSize = 13.sp)
                }
            }
        }
    }
}
