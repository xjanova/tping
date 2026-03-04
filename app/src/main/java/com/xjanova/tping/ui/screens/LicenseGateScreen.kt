package com.xjanova.tping.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjanova.tping.data.license.LicenseManager
import com.xjanova.tping.data.license.LicenseStatus
import kotlinx.coroutines.launch

@Composable
fun LicenseGateScreen(
    onLicenseActivated: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val licenseState by LicenseManager.state.collectAsState()

    var licenseKeyInput by remember { mutableStateOf("") }
    var isActivating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var successMessage by remember { mutableStateOf("") }

    // Navigate when license becomes active
    LaunchedEffect(licenseState.status) {
        if (licenseState.status == LicenseStatus.ACTIVE || licenseState.status == LicenseStatus.TRIAL) {
            onLicenseActivated()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1A1A2E), Color(0xFF16213E), Color(0xFF0F3460))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Logo/Icon
            Icon(
                Icons.Default.Keyboard,
                contentDescription = null,
                tint = Color(0xFF8B5CF6),
                modifier = Modifier.size(72.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Tping",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                "ช่วยพิมพ์ สำหรับผู้ที่ใช้นิ้วไม่สะดวก",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Status message
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFEF4444).copy(alpha = 0.15f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.TimerOff,
                        contentDescription = null,
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "ทดลองใช้หมดแล้ว",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFEF4444)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "กรุณากรอก License Key หรือซื้อคีย์เพื่อใช้งานต่อ",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // License key input
            OutlinedTextField(
                value = licenseKeyInput,
                onValueChange = {
                    licenseKeyInput = it.uppercase().take(19) // XXXX-XXXX-XXXX-XXXX = 19 chars
                    errorMessage = ""
                    successMessage = ""
                },
                label = { Text("License Key", color = Color.White.copy(alpha = 0.6f)) },
                placeholder = { Text("XXXX-XXXX-XXXX-XXXX", color = Color.White.copy(alpha = 0.3f)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF8B5CF6),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                    cursorColor = Color(0xFF8B5CF6)
                ),
                shape = RoundedCornerShape(12.dp),
                leadingIcon = {
                    Icon(Icons.Default.Key, null, tint = Color(0xFF8B5CF6))
                }
            )

            // Error/Success message
            if (errorMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(errorMessage, color = Color(0xFFEF4444), fontSize = 12.sp)
            }
            if (successMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(successMessage, color = Color(0xFF22C55E), fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Activate button
            Button(
                onClick = {
                    if (licenseKeyInput.isBlank()) {
                        errorMessage = "กรุณากรอก License Key"
                        return@Button
                    }
                    isActivating = true
                    errorMessage = ""
                    scope.launch {
                        val result = LicenseManager.activateKey(context, licenseKeyInput.trim())
                        isActivating = false
                        result.onSuccess { typeDisplay ->
                            successMessage = "เปิดใช้งานสำเร็จ! ($typeDisplay)"
                        }.onFailure { e ->
                            errorMessage = e.message ?: "เกิดข้อผิดพลาด"
                        }
                    }
                },
                enabled = !isActivating && licenseKeyInput.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF8B5CF6),
                    disabledContainerColor = Color(0xFF8B5CF6).copy(alpha = 0.4f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isActivating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(Icons.Default.VpnKey, null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isActivating) "กำลังตรวจสอบ..." else "เปิดใช้งาน License",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Buy license button
            OutlinedButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(LicenseManager.getPurchaseUrl()))
                    context.startActivity(intent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFF22C55E)
                ),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = Brush.horizontalGradient(listOf(Color(0xFF22C55E), Color(0xFF10B981)))
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.ShoppingCart, null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("ซื้อ License Key", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Device ID info
            Text(
                "Device ID: ${licenseState.deviceId.take(8)}...",
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.3f)
            )
            Text(
                "Tping by Xman Studio",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.3f)
            )
        }
    }
}
