package com.xjanova.tping.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjanova.tping.overlay.FloatingOverlayService
import com.xjanova.tping.service.TpingAccessibilityService
import com.xjanova.tping.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val workflows by viewModel.workflows.collectAsState()
    val profiles by viewModel.dataProfiles.collectAsState()
    val selectedWorkflowId by viewModel.selectedWorkflowId.collectAsState()
    val selectedProfileId by viewModel.selectedProfileId.collectAsState()
    val loopCount by viewModel.loopCount.collectAsState()
    val playbackState by viewModel.playbackEngine.state.collectAsState()
    val launchStatus by viewModel.launchStatus.collectAsState()

    // Get target app info and data keys of selected workflow
    val selectedWorkflow = workflows.find { it.id == selectedWorkflowId }
    val targetAppName = remember(selectedWorkflowId) {
        selectedWorkflow?.let { viewModel.resolveAppName(it) } ?: ""
    }
    val requiredDataKeys = remember(selectedWorkflowId) {
        selectedWorkflow?.let { viewModel.getDataKeysFromWorkflow(it) } ?: emptyList()
    }
    val selectedProfile = profiles.find { it.id == selectedProfileId }
    val selectedProfileFields = remember(selectedProfileId) {
        selectedProfile?.let { viewModel.getFieldsFromProfile(it) } ?: emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("เล่นอัตโนมัติ") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Select Workflow
            item {
                Text("เลือก Workflow", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            if (workflows.isEmpty()) {
                item {
                    Text(
                        "ยังไม่มี Workflow - ไปบันทึกขั้นตอนก่อน",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            } else {
                items(workflows) { workflow ->
                    val isSelected = selectedWorkflowId == workflow.id
                    val wfAppName = viewModel.resolveAppName(workflow)
                    Card(
                        onClick = { viewModel.selectWorkflow(workflow.id) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        border = if (isSelected) BorderStroke(2.dp, Color(0xFF22C55E)) else null,
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Color(0xFF22C55E).copy(alpha = 0.1f)
                            else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isSelected) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF22C55E),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(workflow.name, fontWeight = FontWeight.Medium)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${viewModel.getActionsFromWorkflow(workflow).size} ขั้นตอน",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                    if (wfAppName.isNotEmpty()) {
                                        Text(
                                            " | ",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                        )
                                        Icon(
                                            Icons.Default.Apps,
                                            contentDescription = null,
                                            tint = Color(0xFF3B82F6),
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text(
                                            wfAppName,
                                            fontSize = 12.sp,
                                            color = Color(0xFF3B82F6)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Auto-launch indicator
            if (targetAppName.isNotEmpty() && selectedWorkflowId != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF3B82F6).copy(alpha = 0.1f)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.RocketLaunch,
                                contentDescription = null,
                                tint = Color(0xFF3B82F6),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "จะเปิด $targetAppName อัตโนมัติเมื่อกดเล่น",
                                fontSize = 13.sp,
                                color = Color(0xFF3B82F6),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Data requirements for selected workflow
            if (requiredDataKeys.isNotEmpty() && selectedWorkflowId != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFF59E0B).copy(alpha = 0.08f)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.DataObject,
                                    contentDescription = null,
                                    tint = Color(0xFFF59E0B),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "ข้อมูลที่ต้องใช้:",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFF59E0B)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            requiredDataKeys.forEach { key ->
                                val hasKey = selectedProfileFields.any { it.key == key }
                                val matchValue = selectedProfileFields.find { it.key == key }?.value
                                Row(
                                    modifier = Modifier.padding(vertical = 1.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (hasKey) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (hasKey) Color(0xFF22C55E) else Color(0xFFEF4444),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        key,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (hasKey) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                    if (matchValue != null) {
                                        Text(
                                            " → $matchValue",
                                            fontSize = 12.sp,
                                            color = Color(0xFF22C55E).copy(alpha = 0.8f)
                                        )
                                    }
                                }
                            }
                            if (selectedProfileId == null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "⬇ เลือกชุดข้อมูลด้านล่างเพื่อกรอกอัตโนมัติ",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }

            // Launch status
            if (launchStatus.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFF59E0B).copy(alpha = 0.15f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFFF59E0B)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(launchStatus, fontSize = 13.sp)
                        }
                    }
                }
            }

            // Select Data Profile
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        if (requiredDataKeys.isNotEmpty()) "เลือกชุดข้อมูล" else "เลือกชุดข้อมูล (ไม่บังคับ)",
                        fontWeight = FontWeight.Bold, fontSize = 16.sp
                    )
                }
            }
            if (profiles.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "ยังไม่มีชุดข้อมูล",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "ไปที่ \"จัดการข้อมูล\" เพื่อสร้างชุดข้อมูล\n" +
                                "ตั้ง Key ให้ตรงกับที่ Tag ไว้ตอนบันทึก",
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                            if (requiredDataKeys.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "Key ที่ต้องใช้: ${requiredDataKeys.joinToString(", ")}",
                                    fontSize = 12.sp,
                                    color = Color(0xFF3B82F6),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            } else {
                items(profiles) { profile ->
                    val isSelected = selectedProfileId == profile.id
                    val fields = viewModel.getFieldsFromProfile(profile)
                    val matchCount = if (requiredDataKeys.isNotEmpty()) {
                        requiredDataKeys.count { key -> fields.any { it.key == key } }
                    } else -1
                    Card(
                        onClick = { viewModel.selectProfile(profile.id) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        border = if (isSelected) BorderStroke(2.dp, Color(0xFF3B82F6)) else null,
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Color(0xFF3B82F6).copy(alpha = 0.1f)
                            else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isSelected) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF3B82F6),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(profile.name, fontWeight = FontWeight.Medium)
                                Text(
                                    "${fields.size} ฟิลด์: ${fields.joinToString(", ") { it.key }}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    maxLines = 1
                                )
                            }
                            // Match indicator
                            if (matchCount >= 0) {
                                val matchColor = when {
                                    matchCount == requiredDataKeys.size -> Color(0xFF22C55E)
                                    matchCount > 0 -> Color(0xFFF59E0B)
                                    else -> Color(0xFFEF4444)
                                }
                                Text(
                                    "$matchCount/${requiredDataKeys.size}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = matchColor
                                )
                            }
                        }
                    }
                }
            }

            // Loop Count
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("จำนวนรอบ", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { viewModel.setLoopCount(loopCount - 1) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Remove, "Decrease")
                        }
                        Text(
                            "$loopCount",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                        IconButton(
                            onClick = { viewModel.setLoopCount(loopCount + 1) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Add, "Increase")
                        }
                    }
                }
            }

            // Playback Status
            if (playbackState.isPlaying) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF22C55E).copy(alpha = 0.15f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                if (playbackState.isPaused) "⏸ หยุดชั่วคราว" else "▶ กำลังเล่น",
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(playbackState.currentActionDesc, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = playbackState.progress,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "ขั้น ${playbackState.currentStep}/${playbackState.totalSteps} | รอบ ${playbackState.currentLoop}/${playbackState.totalLoops}",
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Play Controls
            item {
                Spacer(modifier = Modifier.height(8.dp))
                if (!playbackState.isPlaying) {
                    Button(
                        onClick = {
                            if (TpingAccessibilityService.instance == null) {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                )
                            } else {
                                // Start overlay
                                val intent = Intent(context, FloatingOverlayService::class.java)
                                intent.putExtra("mode", "playing")
                                context.startForegroundService(intent)
                                // Start playback (auto-launches target app)
                                viewModel.startPlayback()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selectedWorkflowId != null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF22C55E)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, "Play")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("เริ่มเล่น", fontSize = 16.sp)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (playbackState.isPaused) {
                            Button(
                                onClick = { viewModel.resumePlayback() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF22C55E)
                                )
                            ) {
                                Icon(Icons.Default.PlayArrow, "Resume")
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("ต่อ")
                            }
                        } else {
                            Button(
                                onClick = { viewModel.pausePlayback() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFF59E0B)
                                )
                            ) {
                                Icon(Icons.Default.Pause, "Pause")
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("พัก")
                            }
                        }
                        Button(
                            onClick = { viewModel.stopPlayback() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFEF4444)
                            )
                        ) {
                            Icon(Icons.Default.Stop, "Stop")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("หยุด")
                        }
                    }
                }
            }
        }
    }
}
