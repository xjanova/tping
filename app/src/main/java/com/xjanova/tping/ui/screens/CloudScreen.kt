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
        if (authState.isLoggedIn) {
            CloudDashboard(
                modifier = Modifier.padding(padding),
                userName = authState.userName,
                userEmail = authState.userEmail,
                syncState = syncState
            )
        } else {
            LoginRegisterForm(
                modifier = Modifier.padding(padding),
                isLoading = authState.isLoading,
                errorMessage = authState.errorMessage
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
    userName: String,
    userEmail: String,
    syncState: com.xjanova.tping.data.cloud.SyncState
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showLogoutConfirm by remember { mutableStateOf(false) }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("ออกจากระบบ") },
            text = { Text("ต้องการออกจากระบบ Cloud Sync?") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    CloudAuthManager.logout()
                    Toast.makeText(context, "ออกจากระบบแล้ว", Toast.LENGTH_SHORT).show()
                }) {
                    Text("ออกจากระบบ", color = Color(0xFFEF4444))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text("ยกเลิก") }
            }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // User info card
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
                        Icons.Default.AccountCircle,
                        contentDescription = null,
                        tint = Color(0xFF06B6D4),
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(userName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            userEmail,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    IconButton(onClick = { showLogoutConfirm = true }) {
                        Icon(
                            Icons.Default.Logout,
                            contentDescription = "ออกจากระบบ",
                            tint = Color(0xFFEF4444)
                        )
                    }
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
            Divider(modifier = Modifier.padding(vertical = 4.dp))
        }

        // Open dashboard
        item {
            OutlinedButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://xman4289.com/my-account/tping-workflows"))
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("เปิด Dashboard บนเว็บ", fontWeight = FontWeight.Medium)
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
