package com.xjanova.tping.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FloatingOverlayContent(
    state: OverlayState,
    onStartRecord: () -> Unit,
    onStopRecord: () -> Unit,
    onTagData: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onToggleExpand: () -> Unit,
    onClose: () -> Unit
) {
    if (!state.isExpanded) {
        // Mini mode - small circular button
        MiniOverlay(
            mode = state.mode,
            step = state.currentStep,
            onClick = onToggleExpand
        )
    } else {
        // Expanded mode
        ExpandedOverlay(
            state = state,
            onStartRecord = onStartRecord,
            onStopRecord = onStopRecord,
            onTagData = onTagData,
            onPlay = onPlay,
            onPause = onPause,
            onStop = onStop,
            onMinimize = onToggleExpand,
            onClose = onClose
        )
    }
}

@Composable
fun MiniOverlay(mode: String, step: Int, onClick: () -> Unit) {
    val bgColor = when (mode) {
        "recording" -> Color(0xFFEF4444)
        "playing" -> Color(0xFF22C55E)
        "paused" -> Color(0xFFF59E0B)
        else -> Color(0xFF6750A4)
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(bgColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (mode == "recording") {
            Text("●", color = Color.White, fontSize = 20.sp)
        } else {
            Text(
                text = if (step > 0) "$step" else "T",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun ExpandedOverlay(
    state: OverlayState,
    onStartRecord: () -> Unit,
    onStopRecord: () -> Unit,
    onTagData: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit
) {
    val headerColor = when (state.mode) {
        "recording" -> Color(0xFFEF4444)
        "playing" -> Color(0xFF22C55E)
        "paused" -> Color(0xFFF59E0B)
        else -> Color(0xFF6750A4)
    }

    val headerText = when (state.mode) {
        "recording" -> "● REC  Step: ${state.stepCount}"
        "playing" -> "▶ PLAY  ${state.currentStep}/${state.totalSteps}"
        "paused" -> "⏸ PAUSED"
        else -> "Tping"
    }

    Card(
        modifier = Modifier.width(220.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF0222222)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = headerText,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Row {
                    Icon(
                        Icons.Default.Remove,
                        contentDescription = "Minimize",
                        tint = Color.White,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable(onClick = onMinimize)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable(onClick = onClose)
                    )
                }
            }

            // Status
            Text(
                text = state.statusText,
                color = Color(0xFFCCCCCC),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )

            // Progress bar (for playing mode)
            AnimatedVisibility(visible = state.mode == "playing" || state.mode == "paused") {
                Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Color(0xFF22C55E),
                        trackColor = Color(0xFF444444)
                    )
                    if (state.totalLoops > 1) {
                        Text(
                            text = "รอบ: ${state.currentLoop}/${state.totalLoops}",
                            color = Color(0xFF999999),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                when (state.mode) {
                    "idle" -> {
                        OverlayButton(
                            icon = Icons.Default.FiberManualRecord,
                            label = "บันทึก",
                            color = Color(0xFFEF4444),
                            onClick = onStartRecord
                        )
                        OverlayButton(
                            icon = Icons.Default.PlayArrow,
                            label = "เล่น",
                            color = Color(0xFF22C55E),
                            onClick = onPlay
                        )
                    }
                    "recording" -> {
                        OverlayButton(
                            icon = Icons.Default.Label,
                            label = "Tag",
                            color = Color(0xFF3B82F6),
                            onClick = onTagData
                        )
                        OverlayButton(
                            icon = Icons.Default.Stop,
                            label = "หยุด",
                            color = Color(0xFFEF4444),
                            onClick = onStopRecord
                        )
                    }
                    "playing" -> {
                        OverlayButton(
                            icon = Icons.Default.Pause,
                            label = "พัก",
                            color = Color(0xFFF59E0B),
                            onClick = onPause
                        )
                        OverlayButton(
                            icon = Icons.Default.Stop,
                            label = "หยุด",
                            color = Color(0xFFEF4444),
                            onClick = onStop
                        )
                    }
                    "paused" -> {
                        OverlayButton(
                            icon = Icons.Default.PlayArrow,
                            label = "ต่อ",
                            color = Color(0xFF22C55E),
                            onClick = onPlay
                        )
                        OverlayButton(
                            icon = Icons.Default.Stop,
                            label = "หยุด",
                            color = Color(0xFFEF4444),
                            onClick = onStop
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
fun OverlayButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label,
            color = Color(0xFFAAAAAA),
            fontSize = 10.sp
        )
    }
}
