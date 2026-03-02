package com.xjanova.tping.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.xjanova.tping.service.TpingAccessibilityService
import com.xjanova.tping.ui.viewmodel.MainViewModel
import com.xjanova.tping.util.PermissionHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToData: () -> Unit,
    onNavigateToWorkflows: () -> Unit,
    onNavigateToPlay: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isAccessibilityEnabled by remember { mutableStateOf(TpingAccessibilityService.instance != null) }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            else true
        )
    }
    var showGuide by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasNotificationPermission = granted }

    // Request notification permission on first launch (Android 13+)
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Re-check permissions every time screen becomes visible (ON_RESUME)
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            isAccessibilityEnabled = TpingAccessibilityService.instance != null
            hasOverlayPermission = Settings.canDrawOverlays(context)
            hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            else true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Tping", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(
                            "by Xman Studio",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                        )
                    }
                },
                actions = {
                    Text(
                        "v${com.xjanova.tping.BuildConfig.VERSION_NAME}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.padding(end = 16.dp)
                    )
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
                val allGranted = isAccessibilityEnabled && hasOverlayPermission && hasNotificationPermission
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (allGranted)
                            Color(0xFF1B5E20).copy(alpha = 0.12f)
                        else Color(0xFFB71C1C).copy(alpha = 0.12f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (allGranted) Icons.Default.VerifiedUser else Icons.Default.Shield,
                                contentDescription = null,
                                tint = if (allGranted) Color(0xFF22C55E) else Color(0xFFEF4444),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "สถานะสิทธิ์",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        // Accessibility Permission
                        PermissionRow(
                            label = "Accessibility Service",
                            enabled = isAccessibilityEnabled,
                            onClick = { PermissionHelper.openAccessibilitySettings(context) }
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        // Overlay Permission
                        PermissionRow(
                            label = "Overlay Permission",
                            enabled = hasOverlayPermission,
                            onClick = { PermissionHelper.openOverlaySettings(context) }
                        )

                        // Notification Permission (Android 13+)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Spacer(modifier = Modifier.height(6.dp))
                            PermissionRow(
                                label = "Notification",
                                enabled = hasNotificationPermission,
                                onClick = { PermissionHelper.openNotificationSettings(context) }
                            )
                        }

                        // Battery optimization tip
                        if (!allGranted) {
                            val batteryTip = PermissionHelper.getBatteryOptimizationTip()
                            if (batteryTip != null) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    batteryTip,
                                    fontSize = 11.sp,
                                    color = Color(0xFFF59E0B),
                                    lineHeight = 16.sp
                                )
                            }
                        }

                        // Brand-specific instructions when accessibility is not enabled
                        if (!isAccessibilityEnabled) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFFF59E0B).copy(alpha = 0.1f)
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        "วิธีเปิด Accessibility (${PermissionHelper.getDeviceBrand()})",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = Color(0xFFF59E0B)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        PermissionHelper.getAccessibilityInstructions(),
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
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
                    gradientColors = listOf(Color(0xFF3B82F6), Color(0xFF6366F1)),
                    onClick = onNavigateToData
                )
            }

            item {
                MainActionCard(
                    icon = Icons.Default.FiberManualRecord,
                    title = "บันทึกขั้นตอน",
                    subtitle = "เรียนรู้ขั้นตอนการกรอกจากการใช้งานจริง",
                    gradientColors = listOf(Color(0xFFEF4444), Color(0xFFF97316)),
                    onClick = onNavigateToWorkflows
                )
            }

            item {
                MainActionCard(
                    icon = Icons.Default.PlayArrow,
                    title = "เล่นอัตโนมัติ",
                    subtitle = "เลือก Workflow + ข้อมูล แล้วกรอกอัตโนมัติ",
                    gradientColors = listOf(Color(0xFF22C55E), Color(0xFF10B981)),
                    onClick = onNavigateToPlay
                )
            }

            // Detailed Guide Section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showGuide = !showGuide },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.MenuBook,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "คู่มือการใช้งานละเอียด",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Icon(
                                if (showGuide) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null
                            )
                        }

                        if (!showGuide) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "แตะเพื่อดูคู่มือทุกขั้นตอน สิทธิ์ การบันทึก การเล่น และแก้ปัญหา",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }

                        if (showGuide) {
                            Spacer(modifier = Modifier.height(16.dp))

                            // ===== Section 1: Permissions =====
                            GuideSection(
                                icon = Icons.Default.Security,
                                title = "1. สิทธิ์ที่จำเป็น",
                                color = Color(0xFFEF4444)
                            ) {
                                GuideStep("Accessibility Service", "ให้แอพอ่านหน้าจอเพื่อจำขั้นตอนและกรอกข้อมูลอัตโนมัติ")
                                GuideStep("Overlay Permission", "แสดงปุ่มควบคุมลอยบนหน้าจอ (บันทึก/เล่น/หยุด)")
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    GuideStep("Notification", "แจ้งเตือนเมื่อ Tping ทำงานอยู่เบื้องหลัง")
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "⚡ กดปุ่ม \"เปิด\" ข้างบนเพื่อไปหน้าตั้งค่าโดยตรง",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF22C55E)
                                )

                                // Battery optimization
                                val batteryTip = PermissionHelper.getBatteryOptimizationTip()
                                if (batteryTip != null) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(batteryTip, fontSize = 11.sp, color = Color(0xFFF59E0B), lineHeight = 15.sp)
                                }
                            }

                            // ===== Section 2: Data Setup =====
                            GuideSection(
                                icon = Icons.Default.Storage,
                                title = "2. เตรียมข้อมูล",
                                color = Color(0xFF3B82F6)
                            ) {
                                GuideStep("เพิ่มชุดข้อมูล", "ไปที่ \"จัดการข้อมูล\" แล้วกด + เพื่อสร้างชุดข้อมูลใหม่")
                                GuideStep("เพิ่มฟิลด์", "ตั้งชื่อ Key (เช่น อีเมล, รหัสผ่าน) และใส่ค่าที่ต้องการกรอก")
                                GuideStep("หลายชุดข้อมูล", "สร้างได้หลายชุด เช่น \"บัญชี A\", \"บัญชี B\" สลับใช้ตอนเล่น")
                            }

                            // ===== Section 3: Recording =====
                            GuideSection(
                                icon = Icons.Default.FiberManualRecord,
                                title = "3. วิธีบันทึกขั้นตอน",
                                color = Color(0xFFEF4444)
                            ) {
                                GuideStep("เปิด Overlay", "กด \"บันทึกขั้นตอน\" → ปุ่มควบคุมจะลอยบนหน้าจอ")
                                GuideStep("ไปแอพเป้าหมาย", "สลับไปแอพที่ต้องการ (เช่น Facebook, เกม) แอพจะตรวจจับเอง")
                                GuideStep("ทำตามปกติ", "คลิก พิมพ์ เลื่อน ตามที่ต้องการ - Tping จะจำทุกขั้นตอน")
                                GuideStep("Tag ข้อมูล", "กดปุ่ม \"Tag\" บน Overlay เพื่อผูกช่องกับ Key ข้อมูล")
                                GuideStep("ชื่ออัตโนมัติ", "ระบบแนะนำชื่อฟิลด์ (อีเมล, รหัสผ่าน) และชื่อ Workflow จากแอพเป้าหมาย")
                                GuideStep("บันทึก", "กดหยุด → ตั้งชื่อ Workflow → บันทึก")
                            }

                            // ===== Section 4: Playback =====
                            GuideSection(
                                icon = Icons.Default.PlayArrow,
                                title = "4. วิธีเล่นอัตโนมัติ",
                                color = Color(0xFF22C55E)
                            ) {
                                GuideStep("เลือก Workflow", "เลือกขั้นตอนที่บันทึกไว้")
                                GuideStep("เลือกข้อมูล", "เลือกชุดข้อมูลที่จะกรอก (ถ้ามี)")
                                GuideStep("ตั้งจำนวนรอบ", "กรอกซ้ำกี่รอบ (1-999)")
                                GuideStep("กดเล่น", "แอพเป้าหมายจะเปิดอัตโนมัติ แล้วทำทุกขั้นตอนให้")
                                GuideStep("ควบคุม", "พัก/ต่อ/หยุดได้ทุกเวลาจาก Overlay")
                            }

                            // ===== Section 5: Game Support =====
                            GuideSection(
                                icon = Icons.Default.SportsEsports,
                                title = "5. รองรับเกม",
                                color = Color(0xFF8B5CF6)
                            ) {
                                GuideStep("ระบบ 3 ชั้น", "Tping ใช้ 3 วิธีหาปุ่ม: ID → ข้อความ → พิกัดหน้าจอ")
                                GuideStep("เกมใช้พิกัด", "เกมส่วนใหญ่ไม่มี ID ระบบจะใช้ตำแหน่งจอ (จำจุดที่กด)")
                                GuideStep("ข้อควรระวัง", "ความละเอียดจอต้องเท่าเดิม ห้ามหมุนจอระหว่างเล่น")
                            }

                            // ===== Section 6: Troubleshooting =====
                            GuideSection(
                                icon = Icons.Default.Build,
                                title = "6. แก้ปัญหา",
                                color = Color(0xFFF59E0B)
                            ) {
                                GuideFAQ("ทำไมไม่ทำงาน?", "ตรวจสิทธิ์ Accessibility + Overlay ให้เปิดทั้งคู่")
                                GuideFAQ("กดผิดตำแหน่ง?", "บันทึกใหม่ในจอแนวเดียวกัน ห้ามหมุนจอ")
                                GuideFAQ("พิมพ์ไม่ครบ?", "ตรวจว่า Tag Data ผูก Key ตรงกับชุดข้อมูล")
                                GuideFAQ("แอพถูกปิด?", "ปิดการประหยัดแบตเตอรี่สำหรับ Tping (ดูข้างบน)")
                                GuideFAQ("Overlay หาย?", "กดแจ้งเตือน Tping เพื่อกลับไปควบคุม")
                                GuideFAQ("แอพเป้าหมายไม่เปิด?", "ตรวจว่าแอพนั้นยังติดตั้งอยู่ และไม่ถูกจำกัดโดยระบบ")
                            }
                        }
                    }
                }
            }

            // Footer
            item {
                Text(
                    "Xman Studio - Tping Auto Typing",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun GuideSection(
    icon: ImageVector,
    title: String,
    color: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.06f)
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = color)
            }
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun GuideStep(title: String, description: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text("•", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), lineHeight = 15.sp)
        }
    }
}

@Composable
fun GuideFAQ(question: String, answer: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text("Q:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B))
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(question, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text("→ $answer", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), lineHeight = 15.sp)
        }
    }
}

@Composable
fun StepText(number: String, text: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(number, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(text, fontSize = 13.sp)
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
            TextButton(onClick = onClick) { Text("เปิด", fontSize = 12.sp) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    gradientColors: List<Color>,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(gradientColors)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = title, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    }
}
