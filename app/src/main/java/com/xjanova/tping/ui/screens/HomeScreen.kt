package com.xjanova.tping.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedTextField
import com.xjanova.tping.data.diagnostic.DiagnosticReporter
import com.xjanova.tping.data.license.LicenseManager
import com.xjanova.tping.data.license.LicenseStatus
import kotlinx.coroutines.launch
import com.xjanova.tping.overlay.FloatingOverlayService
import com.xjanova.tping.service.TpingAccessibilityService
import com.xjanova.tping.ui.components.LicenseKeyField
import com.xjanova.tping.ui.components.QrScannerDialog
import com.xjanova.tping.ui.viewmodel.MainViewModel
import com.xjanova.tping.util.PermissionHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToData: () -> Unit,
    onNavigateToWorkflows: () -> Unit,
    onNavigateToPlay: () -> Unit,
    onNavigateToExport: () -> Unit = {},
    onNavigateToCloud: () -> Unit = {}
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
    var waitingForPermission by remember { mutableStateOf(false) }

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

            // Auto-launch overlay after returning from settings
            if (waitingForPermission && isAccessibilityEnabled && hasOverlayPermission) {
                waitingForPermission = false
                try {
                    val intent = Intent(context, FloatingOverlayService::class.java)
                    intent.putExtra("mode", "idle")
                    context.startForegroundService(intent)
                } catch (_: Exception) {
                    // ForegroundServiceStartNotAllowedException on Android 12+
                }
            }
        }
    }

    var licenseKeyInput by remember { mutableStateOf("") }
    var isActivating by remember { mutableStateOf(false) }
    var activateError by remember { mutableStateOf("") }
    var activateSuccess by remember { mutableStateOf("") }
    var showQrScanner by remember { mutableStateOf(false) }

    // QR Scanner Dialog
    if (showQrScanner) {
        QrScannerDialog(
            onDismiss = { showQrScanner = false },
            onKeyScanned = { key ->
                showQrScanner = false
                licenseKeyInput = key
                activateError = ""
                activateSuccess = ""
            }
        )
    }
    val activateScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Tping", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(
                            "ช่วยพิมพ์ สำหรับผู้ที่ใช้นิ้วไม่สะดวก",
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
        val licenseState by LicenseManager.state.collectAsState()

        // Check for saved crash from previous session
        val crashPrefs = context.getSharedPreferences("crash_log", android.content.Context.MODE_PRIVATE)
        var lastCrash by remember { mutableStateOf(crashPrefs.getString("last_crash", null)) }
        var diagnosticCount by remember { mutableIntStateOf(DiagnosticReporter.getPendingCount()) }
        var sendingReport by remember { mutableStateOf(false) }
        var sendResultMsg by remember { mutableStateOf<String?>(null) }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // === Crash Report + Diagnostics ===
            item(key = "crash_report") {
                val scope = rememberCoroutineScope()
                if (lastCrash != null || diagnosticCount > 0 || sendResultMsg != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFEF4444).copy(alpha = 0.1f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.BugReport, null, tint = Color(0xFFEF4444), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (lastCrash != null) "พบข้อผิดพลาดครั้งก่อน"
                                    else "รายงานวินิจฉัย ($diagnosticCount รายการ)",
                                    fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFFEF4444)
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                if (lastCrash != null) {
                                    TextButton(onClick = {
                                        crashPrefs.edit().remove("last_crash").apply()
                                        lastCrash = null
                                    }) {
                                        Text("ปิด", fontSize = 12.sp)
                                    }
                                }
                            }
                            if (lastCrash != null) {
                                Text(
                                    lastCrash ?: "",
                                    fontSize = 9.sp,
                                    lineHeight = 12.sp,
                                    maxLines = 15,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (sendResultMsg != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(sendResultMsg ?: "", fontSize = 12.sp, color = Color(0xFF22C55E))
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row {
                                if (lastCrash != null) {
                                    TextButton(onClick = {
                                        val clip = android.content.ClipData.newPlainText("crash", lastCrash)
                                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        cm.setPrimaryClip(clip)
                                    }) {
                                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("คัดลอก", fontSize = 11.sp)
                                    }
                                }
                                Spacer(modifier = Modifier.weight(1f))
                                TextButton(
                                    onClick = {
                                        if (!sendingReport) {
                                            sendingReport = true
                                            sendResultMsg = null
                                            scope.launch {
                                                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                    DiagnosticReporter.sendReport()
                                                }
                                                sendResultMsg = if (result.success) "✓ ${result.message}" else "✗ ${result.message}"
                                                sendingReport = false
                                                diagnosticCount = DiagnosticReporter.getPendingCount()
                                            }
                                        }
                                    },
                                    enabled = !sendingReport && diagnosticCount > 0
                                ) {
                                    if (sendingReport) {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(14.dp))
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        if (sendingReport) "กำลังส่ง..." else "ส่งรายงาน${if (diagnosticCount > 0) " ($diagnosticCount)" else ""}",
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // === License Status Card ===
            item(key = "license_status") {
                val licColor = when (licenseState.status) {
                    LicenseStatus.ACTIVE -> Color(0xFF22C55E)
                    LicenseStatus.TRIAL -> Color(0xFF3B82F6)
                    LicenseStatus.EXPIRED -> Color(0xFFEF4444)
                    LicenseStatus.NONE -> Color(0xFFF59E0B)
                    LicenseStatus.CHECKING -> Color(0xFF888888)
                }
                val licIcon = when (licenseState.status) {
                    LicenseStatus.ACTIVE -> Icons.Default.VerifiedUser
                    LicenseStatus.TRIAL -> Icons.Default.CardGiftcard
                    LicenseStatus.EXPIRED -> Icons.Default.TimerOff
                    LicenseStatus.NONE -> Icons.Default.Key
                    LicenseStatus.CHECKING -> Icons.Default.HourglassTop
                }
                val licText = when (licenseState.status) {
                    LicenseStatus.ACTIVE -> {
                        val typeDisplay = LicenseManager.getLicenseTypeDisplay()
                        if (licenseState.licenseType == "lifetime") {
                            "ไลเซนส์: $typeDisplay (ไม่มีวันหมดอายุ)"
                        } else {
                            "ไลเซนส์: $typeDisplay — เหลือ ${licenseState.remainingDays} วัน"
                        }
                    }
                    LicenseStatus.TRIAL -> "ทดลองใช้ฟรี — เหลือ ${licenseState.remainingHours} ชั่วโมง"
                    LicenseStatus.EXPIRED -> "ไลเซนส์หมดอายุ — กดซื้อคีย์ใหม่"
                    LicenseStatus.NONE -> "กรุณากรอก License Key"
                    LicenseStatus.CHECKING -> "กำลังตรวจสอบ..."
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = licColor.copy(alpha = 0.1f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(licIcon, null, tint = licColor, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                licText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = licColor
                            )
                            if (licenseState.status == LicenseStatus.ACTIVE) {
                                Text(
                                    "Device: ${licenseState.deviceId.take(8)}...",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                            }
                        }
                        // Show buy/upgrade button for ALL non-lifetime users
                        val isLifetime = licenseState.status == LicenseStatus.ACTIVE && licenseState.licenseType == "lifetime"
                        val isChecking = licenseState.status == LicenseStatus.CHECKING
                        if (!isLifetime && !isChecking) {
                            val buyLabel = when (licenseState.status) {
                                LicenseStatus.EXPIRED, LicenseStatus.NONE -> "ซื้อคีย์"
                                else -> "อัพเกรด"
                            }
                            TextButton(
                                onClick = {
                                    val intent = android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        Uri.parse(LicenseManager.getPurchaseUrl())
                                    )
                                    context.startActivity(intent)
                                }
                            ) {
                                Text(buyLabel, fontSize = 12.sp, color = licColor, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // License key input when expired/none
                    if (licenseState.status == LicenseStatus.EXPIRED || licenseState.status == LicenseStatus.NONE) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LicenseKeyField(
                                value = licenseKeyInput,
                                onValueChange = {
                                    licenseKeyInput = it
                                    activateError = ""
                                    activateSuccess = ""
                                },
                                onScanQr = { showQrScanner = true },
                                modifier = Modifier.weight(1f),
                                compact = true
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (licenseKeyInput.isBlank()) return@Button
                                    isActivating = true
                                    activateError = ""
                                    activateScope.launch {
                                        val result = LicenseManager.activateKey(context, licenseKeyInput.trim())
                                        isActivating = false
                                        result.onSuccess { activateSuccess = "สำเร็จ! ($it)" }
                                            .onFailure { activateError = it.message ?: "ผิดพลาด" }
                                    }
                                },
                                enabled = !isActivating && licenseKeyInput.isNotBlank(),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                if (isActivating) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                                } else {
                                    Text("ใช้คีย์", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        if (activateError.isNotEmpty()) {
                            Text(activateError, color = Color(0xFFEF4444), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                        if (activateSuccess.isNotEmpty()) {
                            Text(activateSuccess, color = Color(0xFF22C55E), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }

            // === Permission Status ===
            item(key = "permission_status") {
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

            // ===== Accessibility Shortcut Setup =====
            item(key = "accessibility_shortcut") {
                if (isAccessibilityEnabled && hasOverlayPermission) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF8B5CF6).copy(alpha = 0.08f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Accessibility,
                                    contentDescription = null,
                                    tint = Color(0xFF8B5CF6),
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "ตั้งค่าปุ่มลัดการช่วยเหลือพิเศษ",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF8B5CF6)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "ตั้งค่าปุ่มลัดเพื่อเปิด Tping ได้ทันทีจากทุกหน้าจอ\n" +
                                "ไม่ต้องกลับมาที่แอพ กดปุ่มลัดแล้ว Tping เปิดเลย",
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Brand-specific shortcut instructions
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFF8B5CF6).copy(alpha = 0.08f)
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    PermissionHelper.getAccessibilityShortcutInstructions(),
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(10.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Button(
                                onClick = { PermissionHelper.openAccessibilityShortcutSettings(context) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("ไปตั้งค่าปุ่มลัด", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // ===== Start Overlay Button =====
            item(key = "overlay_button") {
                val overlayReady = isAccessibilityEnabled && hasOverlayPermission
                Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                if (overlayReady) listOf(Color(0xFF6750A4), Color(0xFF8B5CF6))
                                else listOf(Color(0xFF666666), Color(0xFF888888))
                            )
                        )
                        .clickable {
                            if (!isAccessibilityEnabled) {
                                waitingForPermission = true
                                PermissionHelper.openAccessibilitySettings(context)
                            } else if (!hasOverlayPermission) {
                                waitingForPermission = true
                                PermissionHelper.openOverlaySettings(context)
                            } else {
                                try {
                                    val intent = Intent(context, FloatingOverlayService::class.java)
                                    intent.putExtra("mode", "idle")
                                    context.startForegroundService(intent)
                                } catch (_: Exception) { }
                            }
                        }
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Widgets,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            if (overlayReady) "เปิดปุ่มลอย" else "ต้องเปิดสิทธิ์ก่อน",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            if (overlayReady) "แตะเพื่อเปิด Overlay แล้วสลับไปเกม/แอพได้เลย"
                            else "กดเพื่อไปเปิด Accessibility + Overlay",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                        if (overlayReady) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "หรือใช้ \"ปุ่มการช่วยเหลือพิเศษ\" เปิดได้เลย",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 10.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
            }

            // === Main Actions ===
            item(key = "main_actions_header") {
                Text(
                    "เมนูหลัก",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            item(key = "action_data") {
                MainActionCard(
                    icon = Icons.Default.Storage,
                    title = "จัดการข้อมูล",
                    subtitle = "เพิ่ม/แก้ไข ชุดข้อมูลที่จะกรอก",
                    gradientColors = listOf(Color(0xFF3B82F6), Color(0xFF6366F1)),
                    onClick = onNavigateToData
                )
            }

            item(key = "action_record") {
                MainActionCard(
                    icon = Icons.Default.FiberManualRecord,
                    title = "บันทึกขั้นตอน",
                    subtitle = "เรียนรู้ขั้นตอนการกรอกจากการใช้งานจริง",
                    gradientColors = listOf(Color(0xFFEF4444), Color(0xFFF97316)),
                    onClick = onNavigateToWorkflows
                )
            }

            // ===== Quick Play — select + play directly on HomeScreen =====
            item(key = "quick_play") {
                QuickPlaySection(viewModel = viewModel)
            }

            item(key = "action_export") {
                MainActionCard(
                    icon = Icons.Default.SwapHoriz,
                    title = "Export / Import",
                    subtitle = "สำรองหรือนำเข้า Workflow + ข้อมูล",
                    gradientColors = listOf(Color(0xFFEC4899), Color(0xFFF43F5E)),
                    onClick = onNavigateToExport
                )
            }

            item(key = "action_cloud") {
                MainActionCard(
                    icon = Icons.Default.Cloud,
                    title = "Cloud Sync",
                    subtitle = "ซิงค์ข้อมูลผ่าน Xman Studio Cloud",
                    gradientColors = listOf(Color(0xFF06B6D4), Color(0xFF3B82F6)),
                    onClick = onNavigateToCloud
                )
            }

            // Detailed Guide Section
            item(key = "guide") {
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

                            // ===== Section: Accessibility Button =====
                            GuideSection(
                                icon = Icons.Default.Accessibility,
                                title = "ปุ่มลัดการช่วยเหลือพิเศษ",
                                color = Color(0xFF8B5CF6)
                            ) {
                                GuideStep("ต้องตั้งค่าก่อน", "ไปที่ ตั้งค่า → การช่วยเหลือพิเศษ → ปุ่มลัด → เลือก Tping (ใช้ปุ่ม \"ไปตั้งค่าปุ่มลัด\" ด้านบน)")
                                GuideStep("เลือกวิธีเปิด", "ปุ่มลอย (ปุ่มเล็ก ๆ ที่ขอบจอ) / กดปุ่มเพิ่ม-ลดเสียงค้าง 3 วินาที / ปัดขึ้น 2 นิ้ว (แล้วแต่รุ่น)")
                                GuideStep("กดเพื่อเปิด Overlay", "กดปุ่มลัดแล้ว Tping จะเปิดแผงควบคุมลอยหน้าจอทันที ไม่ต้องกลับมาที่แอพ")
                                GuideStep("ใช้งานได้ทุกแอพ", "ปุ่มลัดใช้ได้ทุกที่ ไม่ว่าจะอยู่ในเกมหรือแอพไหน")
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
                                GuideStep("เลือก Workflow", "กดเลือกจาก Dropdown บนหน้าหลัก")
                                GuideStep("เลือกข้อมูล", "ถ้า Workflow มี Tag ข้อมูล จะมี Dropdown ชุดข้อมูลให้เลือก")
                                GuideStep("ตั้งจำนวนรอบ", "กด +/- เพื่อตั้งจำนวนรอบ (1-999)")
                                GuideStep("กดเล่น ▶", "กดปุ่มเล่นได้เลย แอพเป้าหมายเปิดอัตโนมัติ")
                                GuideStep("ควบคุม", "พัก/ต่อ/หยุดได้จากปุ่มบนหน้าหลัก หรือจาก Overlay")
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
            item(key = "footer") {
                Text(
                    "Tping - ช่วยพิมพ์สำหรับผู้ที่ใช้นิ้วไม่สะดวก | Xman Studio",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    textAlign = TextAlign.Center
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

@Composable
fun QuickPlaySection(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val workflows by viewModel.workflows.collectAsState()
    val profiles by viewModel.dataProfiles.collectAsState()
    val selectedWorkflowId by viewModel.selectedWorkflowId.collectAsState()
    val selectedProfileId by viewModel.selectedProfileId.collectAsState()
    val loopCount by viewModel.loopCount.collectAsState()
    val playbackState by viewModel.playbackEngine.state.collectAsState()
    val launchStatus by viewModel.launchStatus.collectAsState()

    val selectedWorkflow = workflows.find { it.id == selectedWorkflowId }
    val selectedProfile = profiles.find { it.id == selectedProfileId }
    val requiredDataKeys = remember(selectedWorkflowId, workflows) {
        selectedWorkflow?.let { viewModel.getDataKeysFromWorkflow(it) } ?: emptyList()
    }

    var showWorkflowDialog by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }

    // === Workflow Selection Dialog ===
    if (showWorkflowDialog) {
        AlertDialog(
            onDismissRequest = { showWorkflowDialog = false },
            title = { Text("เลือก Workflow แล้วเล่นเลย", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    workflows.forEach { wf ->
                        val appName = try { viewModel.resolveAppName(wf) } catch (_: Exception) { "" }
                        val isSelected = selectedWorkflowId == wf.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.selectWorkflow(wf.id)
                                    showWorkflowDialog = false
                                    // Auto-start playback on selection
                                    if (TpingAccessibilityService.instance != null) {
                                        // Bridge ViewModel's engine to overlay so it can observe state
                                        FloatingOverlayService.playbackEngine = viewModel.playbackEngine
                                        try {
                                            context.startForegroundService(
                                                Intent(context, FloatingOverlayService::class.java)
                                                    .putExtra("mode", "playing")
                                            )
                                        } catch (_: Exception) { }
                                        viewModel.startPlayback()
                                    } else {
                                        Toast.makeText(context, "⚠ กรุณาเปิด Accessibility Service ก่อน", Toast.LENGTH_SHORT).show()
                                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    }
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PlayArrow, null, tint = Color(0xFF22C55E), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(wf.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                if (appName.isNotEmpty()) {
                                    Text(appName, fontSize = 11.sp, color = Color(0xFF3B82F6))
                                }
                            }
                            if (isSelected) {
                                Icon(Icons.Default.Check, null, tint = Color(0xFF22C55E), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showWorkflowDialog = false }) {
                    Text("ปิด")
                }
            }
        )
    }

    // === Profile Selection Dialog ===
    if (showProfileDialog && profiles.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showProfileDialog = false },
            title = { Text("เลือกชุดข้อมูล", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.selectProfile(null)
                                showProfileDialog = false
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "ไม่ใช้ข้อมูล",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    profiles.forEach { p ->
                        val fields = viewModel.getFieldsFromProfile(p)
                        val matchCount = requiredDataKeys.count { key -> fields.any { it.key == key } }
                        val isSelected = selectedProfileId == p.id
                        val matchColor = when {
                            matchCount == requiredDataKeys.size -> Color(0xFF22C55E)
                            matchCount > 0 -> Color(0xFFF59E0B)
                            else -> Color(0xFFEF4444)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.selectProfile(p.id)
                                    showProfileDialog = false
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Person, null, tint = Color(0xFF3B82F6), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(p.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text(
                                    "${fields.size} ฟิลด์",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                            Text(
                                "$matchCount/${requiredDataKeys.size}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = matchColor
                            )
                            if (isSelected) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(Icons.Default.Check, null, tint = Color(0xFF3B82F6), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showProfileDialog = false }) {
                    Text("ปิด")
                }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF22C55E), Color(0xFF10B981)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("เล่นอัตโนมัติ", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "เลือกแล้วกดเล่นได้เลย",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            if (workflows.isEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "ยังไม่มี Workflow — ไปบันทึกขั้นตอนก่อน",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                return@Column
            }

            Spacer(modifier = Modifier.height(12.dp))

            // === Workflow Selector ===
            Text(
                "Workflow",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showWorkflowDialog = true },
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AccountTree, null, tint = Color(0xFF22C55E), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            selectedWorkflow?.name ?: "แตะเพื่อเลือก...",
                            fontSize = 14.sp,
                            fontWeight = if (selectedWorkflow != null) FontWeight.Medium else FontWeight.Normal,
                            color = if (selectedWorkflow != null) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                        if (selectedWorkflow != null) {
                            val appName = try { viewModel.resolveAppName(selectedWorkflow) } catch (_: Exception) { "" }
                            if (appName.isNotEmpty()) {
                                Text(appName, fontSize = 11.sp, color = Color(0xFF3B82F6))
                            }
                        }
                    }
                    Icon(
                        Icons.Default.ArrowDropDown, null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }

            // === Profile Selector (only if workflow needs data) ===
            if (requiredDataKeys.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "ชุดข้อมูล",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (profiles.isEmpty()) {
                    Text(
                        "ยังไม่มีชุดข้อมูล — ไปสร้างที่ \"จัดการข้อมูล\"",
                        fontSize = 12.sp,
                        color = Color(0xFFF59E0B)
                    )
                } else {
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showProfileDialog = true },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Person, null, tint = Color(0xFF3B82F6), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                selectedProfile?.name ?: "แตะเพื่อเลือก...",
                                fontSize = 14.sp,
                                fontWeight = if (selectedProfile != null) FontWeight.Medium else FontWeight.Normal,
                                color = if (selectedProfile != null) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Default.ArrowDropDown, null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            }

            // === Loop Count ===
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("จำนวนรอบ", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { viewModel.setLoopCount(loopCount - 1) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Remove, "Decrease", modifier = Modifier.size(18.dp))
                    }
                    Text(
                        "$loopCount",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    IconButton(
                        onClick = { viewModel.setLoopCount(loopCount + 1) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Add, "Increase", modifier = Modifier.size(18.dp))
                    }
                }
            }

            // === Launch Status ===
            if (launchStatus.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFFF59E0B)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(launchStatus, fontSize = 13.sp, color = Color(0xFFF59E0B))
                }
            }

            // === Playback Progress ===
            if (playbackState.isPlaying) {
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF22C55E).copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            if (playbackState.isPaused) "⏸ หยุดชั่วคราว" else "▶ กำลังเล่น",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(playbackState.currentActionDesc, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { playbackState.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "ขั้น ${playbackState.currentStep}/${playbackState.totalSteps} | รอบ ${playbackState.currentLoop}/${playbackState.totalLoops}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            // === Play / Pause / Stop Controls ===
            Spacer(modifier = Modifier.height(12.dp))
            if (!playbackState.isPlaying) {
                Button(
                    onClick = {
                        if (TpingAccessibilityService.instance == null) {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } else {
                            try {
                                val intent = Intent(context, FloatingOverlayService::class.java)
                                intent.putExtra("mode", "playing")
                                context.startForegroundService(intent)
                            } catch (_: Exception) { }
                            viewModel.startPlayback()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedWorkflowId != null,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, "Play")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("เริ่มเล่น", fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, "Resume")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("ต่อ")
                        }
                    } else {
                        Button(
                            onClick = { viewModel.pausePlayback() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Pause, "Pause")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("พัก")
                        }
                    }
                    Button(
                        onClick = { viewModel.stopPlayback() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        shape = RoundedCornerShape(8.dp)
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

@Composable
fun MainActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    gradientColors: List<Color>,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
