package com.xjanova.tping.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val RecordColor = Color(0xFFEF4444)
private val PlayColor = Color(0xFF22C55E)
private val PauseColor = Color(0xFFF59E0B)
private val PrimaryColor = Color(0xFF6750A4)
private val TagColor = Color(0xFF3B82F6)

@Composable
fun FloatingOverlayContent(
    state: OverlayState,
    onStartRecord: () -> Unit,
    onStopRecord: () -> Unit,
    onTagData: (String) -> Unit,
    onShowTagDialog: () -> Unit,
    onDismissTagDialog: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onToggleExpand: () -> Unit,
    onClose: () -> Unit
) {
    if (!state.isExpanded) {
        MiniOverlay(mode = state.mode, step = state.currentStep, onClick = onToggleExpand)
    } else {
        ExpandedOverlay(
            state = state,
            onStartRecord = onStartRecord,
            onStopRecord = onStopRecord,
            onShowTagDialog = onShowTagDialog,
            onPause = onPause,
            onResume = onResume,
            onStop = onStop,
            onMinimize = onToggleExpand,
            onClose = onClose
        )
    }

    // Tag Data Dialog
    if (state.showTagDialog) {
        TagDataDialog(
            suggestion = state.suggestedFieldName,
            onConfirm = { fieldKey -> onTagData(fieldKey) },
            onDismiss = onDismissTagDialog
        )
    }
}

@Composable
fun MiniOverlay(mode: String, step: Int, onClick: () -> Unit) {
    val bgColor = when (mode) {
        "recording" -> RecordColor
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
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        when (mode) {
            "recording" -> Text("●", color = Color.White, fontSize = 22.sp)
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
    onShowTagDialog: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit
) {
    val headerColor = when (state.mode) {
        "recording" -> RecordColor
        "playing" -> PlayColor
        "paused" -> PauseColor
        else -> PrimaryColor
    }

    val appLabel = if (state.targetAppName.isNotEmpty()) " ${state.targetAppName}" else ""
    val headerText = when (state.mode) {
        "recording" -> "● REC$appLabel [${state.stepCount}]"
        "playing" -> "▶ PLAY  ${state.currentStep}/${state.totalSteps}"
        "paused" -> "⏸ PAUSED"
        else -> "⌨ Tping"
    }

    Card(
        modifier = Modifier
            .width(230.dp)
            .shadow(12.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF5181818)),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
    ) {
        Column {
            // Header with gradient
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(headerColor, headerColor.copy(alpha = 0.7f))
                        )
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(headerText, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                when (state.mode) {
                    "idle" -> {
                        OverlayButton(Icons.Default.FiberManualRecord, "บันทึก", RecordColor, onStartRecord)
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

@Composable
fun TagDataDialog(
    suggestion: String = "",
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
            Text("ใส่ชื่อ Key ที่ตรงกับข้อมูลที่ตั้งไว้", color = Color(0xFF999999), fontSize = 11.sp)

            // Smart suggestion chip
            if (suggestion.isNotEmpty()) {
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
