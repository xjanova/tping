@file:OptIn(ExperimentalMaterial3Api::class)

package com.xjanova.tping.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjanova.tping.data.cloud.CloudAuthManager
import com.xjanova.tping.data.cloud.CloudSyncManager
import com.xjanova.tping.data.update.UpdateChecker
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CloudScreen(
    onBack: () -> Unit
) {
    val authState by CloudAuthManager.authState.collectAsState()
    val syncState by CloudSyncManager.syncState.collectAsState()
    val scope = rememberCoroutineScope()
    var isAutoAuthenticating by remember { mutableStateOf(false) }

    // Auto device-auth when screen opens and license is active
    LaunchedEffect(Unit) {
        if (!authState.isLoggedIn && CloudSyncManager.hasActiveLicense()) {
            isAutoAuthenticating = true
            CloudSyncManager.ensureDeviceAuth()
            isAutoAuthenticating = false
        }
    }

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
            isAutoAuthenticating -> {
                // Show loading while auto-authenticating
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF06B6D4))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("กำลังเชื่อมต่อ Cloud...", fontSize = 14.sp)
                    }
                }
            }
            authState.isLoggedIn -> {
                CloudDashboard(
                    modifier = Modifier.padding(padding),
                    syncState = syncState
                )
            }
            CloudSyncManager.hasActiveLicense() -> {
                // License active but auth failed — show retry
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(Icons.Default.CloudOff, null, tint = Color(0xFFF59E0B), modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("เชื่อมต่อ Cloud ไม่สำเร็จ", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("ตรวจสอบการเชื่อมต่ออินเทอร์เน็ต", fontSize = 13.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    isAutoAuthenticating = true
                                    CloudSyncManager.ensureDeviceAuth()
                                    isAutoAuthenticating = false
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4))
                        ) {
                            Text("ลองอีกครั้ง")
                        }
                    }
                }
            }
            else -> {
                // No active license — need to activate first
                NoLicenseMessage(modifier = Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun NoLicenseMessage(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Default.Cloud,
                contentDescription = null,
                tint = Color(0xFF06B6D4),
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Cloud Sync",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "ซิงค์ Workflow และข้อมูลอัตโนมัติ\nเปิดใช้งานเมื่อมี License ที่ Active",
                fontSize = 14.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "ไม่ต้องสมัครสมาชิก — ใช้ได้ทันทีเมื่อ License Active",
                fontSize = 12.sp,
                color = Color(0xFF06B6D4),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun LoginRegisterForm(
    modifier: Modifier = Modifier,
    isLoading: Boolean,
    errorMessage: String
) {
    var isLoginMode by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF06B6D4).copy(alpha = 0.08f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Cloud,
                        contentDescription = null,
                        tint = Color(0xFF06B6D4),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Xman Studio Cloud",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                    Text(
                        "ซิงค์ Workflow และข้อมูลข้ามเครื่อง",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // Tab selector
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = isLoginMode,
                    onClick = { isLoginMode = true },
                    label = { Text("เข้าสู่ระบบ") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = !isLoginMode,
                    onClick = { isLoginMode = false },
                    label = { Text("สมัครสมาชิก") },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Name field (register only)
        if (!isLoginMode) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("ชื่อ") },
                    leadingIcon = { Icon(Icons.Default.Person, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
            }
        }

        // Email field
        item {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("อีเมล") },
                leadingIcon = { Icon(Icons.Default.Email, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                )
            )
        }

        // Password field
        item {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("รหัสผ่าน") },
                leadingIcon = { Icon(Icons.Default.Lock, null) },
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null
                        )
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                )
            )
        }

        // Error message
        if (errorMessage.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFEF4444).copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        errorMessage,
                        color = Color(0xFFEF4444),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        // Submit button
        item {
            Button(
                onClick = {
                    scope.launch {
                        if (isLoginMode) {
                            val result = CloudAuthManager.login(email.trim(), password)
                            result.onSuccess {
                                Toast.makeText(context, "เข้าสู่ระบบสำเร็จ", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            if (name.isBlank()) {
                                Toast.makeText(context, "กรุณากรอกชื่อ", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            val result = CloudAuthManager.register(name.trim(), email.trim(), password)
                            result.onSuccess {
                                Toast.makeText(context, "สมัครสมาชิกสำเร็จ", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4))
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    if (isLoginMode) "เข้าสู่ระบบ" else "สมัครสมาชิก",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
private fun CloudDashboard(
    modifier: Modifier = Modifier,
    syncState: com.xjanova.tping.data.cloud.SyncState
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Cloud connected card
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
                    Icon(
                        Icons.Default.CloudDone,
                        contentDescription = null,
                        tint = Color(0xFF06B6D4),
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Cloud เชื่อมต่อแล้ว", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "ซิงค์ข้อมูลอัตโนมัติเมื่อ License Active",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF22C55E),
                        modifier = Modifier.size(24.dp)
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
                            Toast.makeText(
                                context,
                                "อัพโหลดสำเร็จ (workflow: $wfCount, profile: $pfCount)",
                                Toast.LENGTH_SHORT
                            ).show()
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
                            Toast.makeText(
                                context,
                                "ดาวน์โหลดสำเร็จ (workflow: $wfCount, profile: $pfCount)",
                                Toast.LENGTH_SHORT
                            ).show()
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
                    scope.launch {
                        CloudSyncManager.fullSync()
                    }
                }
            )
        }

        // Divider
        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        }

        // Open dashboard links (with auto-login)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            openWebDashboard(context, "/my-account/tping-workflows")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Workflow Dashboard บนเว็บ", fontWeight = FontWeight.Medium)
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            openWebDashboard(context, "/my-account/tping-data-profiles")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Data Profile Dashboard บนเว็บ", fontWeight = FontWeight.Medium)
                }
            }
        }

        // Divider
        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        }

        // Check for update button
        item {
            val updateInfo by UpdateChecker.updateInfo.collectAsState()
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF8B5CF6).copy(alpha = 0.06f)
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = Color(0xFF8B5CF6),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "เช็คอัพเดท",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                "เวอร์ชันปัจจุบัน: ${updateInfo.currentVersion}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    UpdateChecker.checkForUpdate(context, shouldThrottle = false)
                                }
                            },
                            enabled = !updateInfo.isChecking,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
                        ) {
                            if (updateInfo.isChecking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
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
 * Falls back to direct URL if token generation fails.
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
    // Fallback: open direct URL (user may need to log in manually)
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
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.08f)
        )
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
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        }
    }
}
