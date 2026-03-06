package com.xjanova.tping.ui.screens

import android.content.Intent
import android.net.Uri
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
    var selectedPlan by remember { mutableStateOf("yearly") }

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
            )
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Keyboard,
                        contentDescription = null,
                        tint = Color(0xFF8B5CF6),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Tping",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        "ช่วยพิมพ์ สำหรับผู้ที่ใช้นิ้วไม่สะดวก",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }

            // Status message — changes based on state
            item {
                val hasConnectionError = licenseState.errorMessage.contains("เชื่อมต่อ")
                val statusIcon = if (hasConnectionError) Icons.Default.CloudOff else Icons.Default.TimerOff
                val statusColor = if (hasConnectionError) Color(0xFFF59E0B) else Color(0xFFEF4444)
                val statusTitle = if (hasConnectionError) "ไม่สามารถเชื่อมต่อเซิร์ฟเวอร์" else "ทดลองใช้หมดแล้ว"
                val statusSubtitle = if (hasConnectionError) "ต้องเชื่อมต่ออินเทอร์เน็ตเพื่อใช้งาน" else "เลือกแพ็คเกจเพื่อใช้งานต่อ"

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
                            statusIcon,
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

                // Retry button when connection error
                if (hasConnectionError) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                LicenseManager.initialize(context)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFF59E0B)
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("ลองใหม่", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Package selection title
            item {
                Text(
                    "เลือกแพ็คเกจ",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Monthly plan
            item {
                PricingCard(
                    title = "รายเดือน",
                    price = "399",
                    period = "/เดือน",
                    duration = "30 วัน",
                    features = listOf(
                        "ใช้งานทุกฟีเจอร์",
                        "Cloud Sync",
                        "ซัพพอร์ตมาตรฐาน"
                    ),
                    isSelected = selectedPlan == "monthly",
                    isPopular = false,
                    accentColor = Color(0xFF3B82F6),
                    onSelect = { selectedPlan = "monthly" }
                )
            }

            // Yearly plan (recommended)
            item {
                PricingCard(
                    title = "รายปี",
                    price = "2,500",
                    period = "/ปี",
                    duration = "365 วัน",
                    features = listOf(
                        "ใช้งานทุกฟีเจอร์",
                        "Cloud Sync",
                        "ซัพพอร์ตพรีเมียม",
                        "อัพเดทก่อนใคร"
                    ),
                    isSelected = selectedPlan == "yearly",
                    isPopular = true,
                    savingText = "ประหยัด 48%",
                    accentColor = Color(0xFF8B5CF6),
                    onSelect = { selectedPlan = "yearly" }
                )
            }

            // Lifetime plan
            item {
                PricingCard(
                    title = "ตลอดชีพ",
                    price = "5,000",
                    period = " ครั้งเดียว",
                    duration = "ไม่มีวันหมดอายุ",
                    features = listOf(
                        "ใช้งานทุกฟีเจอร์",
                        "Cloud Sync",
                        "ซัพพอร์ตพรีเมียม",
                        "อัพเดทตลอดชีพ",
                        "ใช้ได้หลายเครื่อง"
                    ),
                    isSelected = selectedPlan == "lifetime",
                    isPopular = false,
                    accentColor = Color(0xFF22C55E),
                    onSelect = { selectedPlan = "lifetime" }
                )
            }

            // Buy button
            item {
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(LicenseManager.getPurchaseUrl()))
                        context.startActivity(intent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF22C55E)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.ShoppingCart, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "ซื้อ License Key",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }

            // Divider
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Divider(
                        modifier = Modifier.weight(1f),
                        color = Color.White.copy(alpha = 0.15f)
                    )
                    Text(
                        "  มี License Key แล้ว?  ",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.4f)
                    )
                    Divider(
                        modifier = Modifier.weight(1f),
                        color = Color.White.copy(alpha = 0.15f)
                    )
                }
            }

            // License key input
            item {
                OutlinedTextField(
                    value = licenseKeyInput,
                    onValueChange = {
                        licenseKeyInput = it.uppercase().take(19)
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
            }

            // Error/Success message
            if (errorMessage.isNotEmpty()) {
                item {
                    Text(errorMessage, color = Color(0xFFEF4444), fontSize = 12.sp)
                }
            }
            if (successMessage.isNotEmpty()) {
                item {
                    Text(successMessage, color = Color(0xFF22C55E), fontSize = 12.sp)
                }
            }

            // Activate button
            item {
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
            }

            // Footer
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
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
    }
}

@Composable
private fun PricingCard(
    title: String,
    price: String,
    period: String,
    duration: String,
    features: List<String>,
    isSelected: Boolean,
    isPopular: Boolean,
    savingText: String? = null,
    accentColor: Color,
    onSelect: () -> Unit
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
                            Text(
                                title,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            if (isPopular) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = accentColor
                                ) {
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
                        Text(
                            duration,
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            price,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = accentColor
                        )
                        Text(
                            " ฿$period",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }
                    if (savingText != null) {
                        Text(
                            savingText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF22C55E)
                        )
                    }
                }
            }

            if (isSelected) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = Color.White.copy(alpha = 0.1f))
                Spacer(modifier = Modifier.height(8.dp))
                features.forEach { feature ->
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            feature,
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }
}
