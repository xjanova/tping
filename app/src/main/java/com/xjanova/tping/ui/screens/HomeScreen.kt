package com.xjanova.tping.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import com.xjanova.tping.service.TpingAccessibilityService
import com.xjanova.tping.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToData: () -> Unit,
    onNavigateToWorkflows: () -> Unit,
    onNavigateToPlay: () -> Unit
) {
    val context = LocalContext.current
    var isAccessibilityEnabled by remember {
        mutableStateOf(TpingAccessibilityService.instance != null)
    }
    var hasOverlayPermission by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(500)
        isAccessibilityEnabled = TpingAccessibilityService.instance != null
        hasOverlayPermission = Settings.canDrawOverlays(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⌨ Tping", fontWeight = FontWeight.Bold)
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
            // Permission Status
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isAccessibilityEnabled && hasOverlayPermission)
                            Color(0xFF1B5E20).copy(alpha = 0.15f)
                        else Color(0xFFB71C1C).copy(alpha = 0.15f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "สถานะสิทธิ์",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        PermissionRow(
                            label = "Accessibility Service",
                            enabled = isAccessibilityEnabled,
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                )
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        PermissionRow(
                            label = "Overlay Permission",
                            enabled = hasOverlayPermission,
                            onClick = {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                        )
                    }
                }
            }

            // Main Actions
            item {
                Text(
                    "เมนูหลัก",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            item {
                MainActionCard(
                    icon = Icons.Default.Storage,
                    title = "จัดการข้อมูล",
                    subtitle = "เพิ่ม/แก้ไข ชุดข้อมูลที่จะกรอก",
                    color = Color(0xFF3B82F6),
                    onClick = onNavigateToData
                )
            }

            item {
                MainActionCard(
                    icon = Icons.Default.FiberManualRecord,
                    title = "บันทึกขั้นตอน",
                    subtitle = "เรียนรู้ขั้นตอนการกรอกข้อมูลจากการใช้งานจริง",
                    color = Color(0xFFEF4444),
                    onClick = onNavigateToWorkflows
                )
            }

            item {
                MainActionCard(
                    icon = Icons.Default.PlayArrow,
                    title = "เล่นอัตโนมัติ",
                    subtitle = "เลือก Workflow + ข้อมูล แล้วกรอกอัตโนมัติ",
                    color = Color(0xFF22C55E),
                    onClick = onNavigateToPlay
                )
            }

            // Quick Info
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "วิธีใช้งาน",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("1. เปิดสิทธิ์ Accessibility + Overlay", fontSize = 13.sp)
                        Text("2. เพิ่มข้อมูลที่ต้องกรอก", fontSize = 13.sp)
                        Text("3. กด 'บันทึกขั้นตอน' แล้วทำบนแอพเป้าหมาย", fontSize = 13.sp)
                        Text("4. กด Tag Data เพื่อผูกช่องกับข้อมูล", fontSize = 13.sp)
                        Text("5. เลือก Workflow + ข้อมูล แล้วกด Play", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (enabled) Icons.Default.CheckCircle else Icons.Default.Cancel,
                contentDescription = null,
                tint = if (enabled) Color(0xFF22C55E) else Color(0xFFEF4444),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(label, fontSize = 14.sp)
        }
        if (!enabled) {
            TextButton(onClick = onClick) {
                Text("เปิด", fontSize = 12.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = title,
                tint = color,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    subtitle,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}
