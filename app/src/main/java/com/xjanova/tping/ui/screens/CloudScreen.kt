@file:OptIn(ExperimentalMaterial3Api::class)

package com.xjanova.tping.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjanova.tping.data.cloud.CloudAuthManager
import com.xjanova.tping.data.cloud.CloudSyncManager
import com.xjanova.tping.data.license.LicenseManager
import com.xjanova.tping.data.license.LicenseStatus
import com.xjanova.tping.data.update.UpdateChecker
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CloudScreen(
    onBack: () -> Unit,
    onNavigateToExport: () -> Unit = {}
) {
    val authState by CloudAuthManager.authState.collectAsState()
    val syncState by CloudSyncManager.syncState.collectAsState()
    val licenseState by LicenseManager.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Auto deviceAuth when license is active but not logged into cloud
    // Use rememberSaveable so state survives rotation (configuration change)
    var isAutoAuthenticating by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(licenseState.status, authState.isLoggedIn) {
        if (licenseState.status == LicenseStatus.ACTIVE &&
            !authState.isLoggedIn &&
            !isAutoAuthenticating &&
            licenseState.licenseType != "demo" &&
            licenseState.licenseType != "trial"
        ) {
            val licenseKey = LicenseManager.getLicenseKey()
            val machineId = LicenseManager.getMachineId()
            if (licenseKey != null && machineId != null) {
                isAutoAuthenticating = true
                CloudAuthManager.deviceAuth(licenseKey, machineId)
                isAutoAuthenticating = false
            }
        }
    }

    // Derived flag: license is active (non-demo/trial) — never show pricing for these users
    val hasActiveLicense = licenseState.status == LicenseStatus.ACTIVE &&
        licenseState.licenseType != "demo" &&
        licenseState.licenseType != "trial"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cloud Sync") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "กลับ")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        when {
            // Loading state — auto authenticating
            isAutoAuthenticating -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF06B6D4))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "กำลังเชื่อมต่อ Cloud...",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            // License active + logged in → show dashboard
            hasActiveLicense && authState.isLoggedIn -> {
                CloudDashboard(
                    modifier = Modifier.padding(padding),
                    syncState = syncState,
                    onNavigateToExport = onNavigateToExport
                )
            }

            // License active but NOT logged in yet → show loading or retry
            // (This prevents CloudPricingScreen from flashing during rotation)
            hasActiveLicense && !authState.isLoggedIn -> {
                if (authState.errorMessage.isNotEmpty()) {
                    // Auth attempted but failed → show retry
                    CloudAuthRetry(
                        modifier = Modifier.padding(padding),
                        errorMessage = authState.errorMessage,
                        onRetry = {
                            scope.launch {
                                val licenseKey = LicenseManager.getLicenseKey()
                                val machineId = LicenseManager.getMachineId()
                                if (licenseKey != null && machineId != null) {
                                    isAutoAuthenticating = true
                                    CloudAuthManager.deviceAuth(licenseKey, machineId)
                                    isAutoAuthenticating = false
                                }
                            }
                        }
                    )
                } else {
                    // Auth not yet attempted (e.g., just rotated) → show loading
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color(0xFF06B6D4))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "กำลังเชื่อมต่อ Cloud...",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            // No license, demo, trial, or expired → show pricing
            else -> {
                CloudPricingScreen(
                    modifier = Modifier.padding(padding),
                    licenseState = licenseState
                )
            }
        }
    }
}


@Composable
private fun CloudAuthRetry(
    modifier: Modifier = Modifier,
    errorMessage: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Default.CloudOff,
                contentDescription = null,
                tint = Color(0xFFF59E0B),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "เชื่อมต่อ Cloud ไม่สำเร็จ",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            if (errorMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    errorMessage,
                    fontSize = 13.sp,
                    color = Color(0xFFEF4444),
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4))
            ) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("ลองใหม่", fontWeight = FontWeight.Bold)
            }
        }
    }
}


@Composable
private fun CloudPricingScreen(
    modifier: Modifier = Modifier,
    licenseState: com.xjanova.tping.data.license.LicenseState
) {
    val context = LocalContext.current
    var selectedPlan by remember { mutableStateOf("yearly") }

    val isExpired = licenseState.status == LicenseStatus.EXPIRED
    val isDemoOrTrial = licenseState.licenseType == "demo" || licenseState.licenseType == "trial"

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1A1A2E), Color(0xFF16213E), Color(0xFF0F3460))
                )
            )
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Cloud,
                        contentDescription = null,
                        tint = Color(0xFF06B6D4),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Cloud Sync",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        "ซิงค์ Workflow และข้อมูลข้ามเครื่อง",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }

            // Status message
            item {
                val statusTitle = when {
                    isExpired -> "License หมดอายุ"
                    isDemoOrTrial -> "ทดลองใช้ — Cloud ต้องมี License"
                    else -> "ต้องมี License เพื่อใช้ Cloud"
                }
                val statusSubtitle = when {
                    isExpired -> "ต่ออายุ License เพื่อใช้ Cloud Sync ต่อ"
                    isDemoOrTrial -> "ซื้อ License เพื่อเปิดใช้งาน Cloud Sync"
                    else -> "เลือกแพ็คเกจด้านล่างเพื่อเริ่มใช้งาน"
                }
                val statusColor = if (isExpired) Color(0xFFEF4444) else Color(0xFFF59E0B)

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = statusColor.copy(alpha = 0.15f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            if (isExpired) Icons.Default.TimerOff else Icons.Default.Lock,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(32.dp)
                        )
                        Column {
                            Text(
                                statusTitle,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = statusColor
                            )
                            Text(
                                statusSubtitle,
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            // Package title
            item {
                Text(
                    "เลือกแพ็คเกจ",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Monthly
            item {
                CloudPricingCard(
                    title = "รายเดือน",
                    price = "399",
                    period = "/เดือน",
                    duration = "30 วัน",
                    features = listOf("ใช้งานทุกฟีเจอร์", "Cloud Sync", "ซัพพอร์ตมาตรฐาน"),
                    isSelected = selectedPlan == "monthly",
                    isPopular = false,
                    accentColor = Color(0xFF3B82F6),
                    onSelect = { selectedPlan = "monthly" },
                    onBuy = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LicenseManager.getPurchaseUrl("monthly"))))
                    }
                )
            }

            // Yearly (recommended)
            item {
                CloudPricingCard(
                    title = "รายปี",
                    price = "2,500",
                    period = "/ปี",
                    duration = "365 วัน",
                    features = listOf("ใช้งานทุกฟีเจอร์", "Cloud Sync", "ซัพพอร์ตพรีเมียม", "อัพเดทก่อนใคร"),
                    isSelected = selectedPlan == "yearly",
                    isPopular = true,
                    savingText = "ประหยัด 48%",
                    accentColor = Color(0xFF8B5CF6),
                    onSelect = { selectedPlan = "yearly" },
                    onBuy = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LicenseManager.getPurchaseUrl("yearly"))))
                    }
                )
            }

            // Lifetime
            item {
                CloudPricingCard(
                    title = "ตลอดชีพ",
                    price = "5,000",
                    period = " ครั้งเดียว",
                    duration = "ไม่มีวันหมดอายุ",
                    features = listOf("ใช้งานทุกฟีเจอร์", "Cloud Sync", "ซัพพอร์ตพรีเมียม", "อัพเดทตลอดชีพ"),
                    isSelected = selectedPlan == "lifetime",
                    isPopular = false,
                    accentColor = Color(0xFF22C55E),
                    onSelect = { selectedPlan = "lifetime" },
                    onBuy = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LicenseManager.getPurchaseUrl("lifetime"))))
                    }
                )
            }

            // Buy button
            item {
                Button(
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LicenseManager.getPurchaseUrl(selectedPlan))))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.ShoppingCart, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("ซื้อ License Key", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }

            // Footer
            item {
                Text(
                    "เมื่อซื้อและเปิดใช้งาน License\nCloud Sync จะเชื่อมต่ออัตโนมัติ",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.4f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }
        }
    }
}


@Composable
private fun CloudPricingCard(
    title: String,
    price: String,
    period: String,
    duration: String,
    features: List<String>,
    isSelected: Boolean,
    isPopular: Boolean,
    savingText: String? = null,
    accentColor: Color,
    onSelect: () -> Unit,
    onBuy: () -> Unit = {}
) {
    val borderColor = if (isSelected) accentColor else Color.White.copy(alpha = 0.15f)
    val bgColor = if (isSelected) accentColor.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp)
            )
            .background(bgColor)
            .clickable { onSelect() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = isSelected,
                        onClick = onSelect,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = accentColor,
                            unselectedColor = Color.White.copy(alpha = 0.4f)
                        )
                    )
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            if (isPopular) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(shape = RoundedCornerShape(4.dp), color = accentColor) {
                                    Text(
                                        "แนะนำ",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(duration, fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(price, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = accentColor)
                        Text(
                            " ฿$period",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }
                    if (savingText != null) {
                        Text(savingText, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF22C55E))
                    }
                }
            }

            if (isSelected) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Spacer(modifier = Modifier.height(8.dp))
                features.forEach { feature ->
                    Row(modifier = Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = accentColor, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(feature, fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onBuy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.ShoppingCart, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("ซื้อแพ็คเกจนี้", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}


@Composable
private fun CloudDashboard(
    modifier: Modifier = Modifier,
    syncState: com.xjanova.tping.data.cloud.SyncState,
    onNavigateToExport: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val authState by CloudAuthManager.authState.collectAsState()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // User account card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF06B6D4).copy(alpha = 0.08f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Card(
                        shape = RoundedCornerShape(50),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF06B6D4))
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .size(40.dp)
                                .padding(8.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            authState.userName.ifEmpty { "User" },
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            authState.userEmail.ifEmpty { "เชื่อมต่อแล้ว" },
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF22C55E),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // Sync status
        if (syncState.isSyncing || syncState.message.isNotEmpty() || syncState.error.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            syncState.error.isNotEmpty() -> Color(0xFFEF4444).copy(alpha = 0.08f)
                            syncState.isSyncing -> Color(0xFF3B82F6).copy(alpha = 0.08f)
                            else -> Color(0xFF22C55E).copy(alpha = 0.08f)
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (syncState.isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF3B82F6)
                            )
                        } else {
                            Icon(
                                if (syncState.error.isNotEmpty()) Icons.Default.Error else Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = if (syncState.error.isNotEmpty()) Color(0xFFEF4444) else Color(0xFF22C55E),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                syncState.error.ifEmpty { syncState.message },
                                fontSize = 13.sp,
                                color = when {
                                    syncState.error.isNotEmpty() -> Color(0xFFEF4444)
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                            if (syncState.lastSyncAt > 0) {
                                val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                                    .format(Date(syncState.lastSyncAt))
                                Text(
                                    "ซิงค์ล่าสุด: $dateStr",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Sync actions
        item {
            Text(
                "จัดการข้อมูล Cloud",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // Upload all
        item {
            SyncActionCard(
                icon = Icons.Default.CloudUpload,
                title = "อัพโหลดทั้งหมด",
                subtitle = "ส่ง Workflow + Data Profile ไปยัง Cloud",
                color = Color(0xFF3B82F6),
                enabled = !syncState.isSyncing,
                onClick = {
                    scope.launch {
                        try {
                            val wfCount = CloudSyncManager.uploadAllWorkflows()
                            val pfCount = CloudSyncManager.uploadAllProfiles()
                            Toast.makeText(context, "อัพโหลดสำเร็จ (workflow: $wfCount, profile: $pfCount)", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "อัพโหลดผิดพลาด: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        }

        // Download all
        item {
            SyncActionCard(
                icon = Icons.Default.CloudDownload,
                title = "ดาวน์โหลดทั้งหมด",
                subtitle = "ดึง Workflow + Data Profile จาก Cloud มาเครื่องนี้",
                color = Color(0xFF22C55E),
                enabled = !syncState.isSyncing,
                onClick = {
                    scope.launch {
                        try {
                            val wfCount = CloudSyncManager.downloadAllWorkflows()
                            val pfCount = CloudSyncManager.downloadAllProfiles()
                            Toast.makeText(context, "ดาวน์โหลดสำเร็จ (workflow: $wfCount, profile: $pfCount)", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "ดาวน์โหลดผิดพลาด: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        }

        // Full sync
        item {
            SyncActionCard(
                icon = Icons.Default.Sync,
                title = "ซิงค์ทั้งหมด",
                subtitle = "อัพโหลด Workflow + Profile ไป Cloud ทั้งหมด",
                color = Color(0xFF8B5CF6),
                enabled = !syncState.isSyncing,
                onClick = {
                    scope.launch { CloudSyncManager.fullSync() }
                }
            )
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }

        // Open dashboard links
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { scope.launch { openWebDashboard(context, "/my-account/tping-workflows") } },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Workflow Dashboard บนเว็บ", fontWeight = FontWeight.Medium)
                }
                OutlinedButton(
                    onClick = { scope.launch { openWebDashboard(context, "/my-account/tping-data-profiles") } },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Data Profile Dashboard บนเว็บ", fontWeight = FontWeight.Medium)
                }
            }
        }

        // Export / Import card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF59E0B).copy(alpha = 0.08f)),
                onClick = onNavigateToExport
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.SwapHoriz, null, tint = Color(0xFFF59E0B), modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Import / Export โฟล & ไอดี", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "ส่งออก/นำเข้าโฟลและข้อมูลเป็นไฟล์ JSON เพื่อใช้บนเครื่องอื่น",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                }
            }
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }

        // Check for update
        item {
            val updateInfo by UpdateChecker.updateInfo.collectAsState()
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF8B5CF6).copy(alpha = 0.06f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SystemUpdate, null, tint = Color(0xFF8B5CF6), modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("เช็คอัพเดท", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(
                                "เวอร์ชันปัจจุบัน: ${updateInfo.currentVersion}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                        Button(
                            onClick = { scope.launch { UpdateChecker.checkForUpdate(context, shouldThrottle = false) } },
                            enabled = !updateInfo.isChecking,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
                        ) {
                            if (updateInfo.isChecking) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Text("เช็คเลย", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Footer
        item {
            Text(
                "ข้อมูลจะถูกเก็บอย่างปลอดภัยบน Xman Studio Cloud",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Get a one-time auto-login URL and open the web dashboard in browser.
 */
private suspend fun openWebDashboard(context: android.content.Context, path: String) {
    val baseUrl = "https://xman4289.com"
    try {
        val token = CloudAuthManager.getToken()
        if (token != null) {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.xjanova.tping.data.cloud.CloudApiClient.getWebLoginToken(token)
            }
            if (result.success) {
                val url = result.data?.getAsJsonObject("data")?.get("url")?.asString
                if (!url.isNullOrEmpty()) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    return
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.w("CloudScreen", "Auto-login failed: ${e.message}")
    }
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$baseUrl$path")))
}

@Composable
private fun SyncActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        enabled = enabled,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
        }
    }
}
