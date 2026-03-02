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
import com.xjanova.tping.recorder.PlaybackEngine
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
                            Column {
                                Text(workflow.name, fontWeight = FontWeight.Medium)
                                Text(
                                    "${viewModel.getActionsFromWorkflow(workflow).size} ขั้นตอน",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }

            // Select Data Profile
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text("เลือกชุดข้อมูล", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            if (profiles.isEmpty()) {
                item {
                    Text(
                        "ยังไม่มีข้อมูล - ไปเพิ่มข้อมูลก่อน",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            } else {
                items(profiles) { profile ->
                    val isSelected = selectedProfileId == profile.id
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
                            Text(profile.name, fontWeight = FontWeight.Medium)
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
                                progress = { playbackState.progress },
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
                                // Start playback
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
