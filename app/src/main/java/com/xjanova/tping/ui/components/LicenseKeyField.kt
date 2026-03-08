package com.xjanova.tping.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Auto-formats license key with dashes: XXXX-XXXX-XXXX-XXXX
 *
 * Features:
 * - Auto-inserts dashes every 4 characters
 * - Forces uppercase
 * - Limits to 19 chars (4+1+4+1+4+1+4)
 * - QR code scanner button
 */
@Composable
fun LicenseKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    onScanQr: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    var textFieldValue by remember(value) {
        mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    }

    if (compact) {
        // Compact mode for HomeScreen
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = textFieldValue,
                onValueChange = { newValue ->
                    val formatted = formatLicenseKey(newValue.text)
                    textFieldValue = TextFieldValue(formatted, TextRange(formatted.length))
                    onValueChange(formatted)
                },
                placeholder = {
                    Text("XXXX-XXXX-XXXX-XXXX", fontSize = 12.sp)
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 13.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                ),
                shape = RoundedCornerShape(8.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters
                ),
                trailingIcon = {
                    IconButton(
                        onClick = onScanQr,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = "สแกน QR",
                            tint = Color(0xFF8B5CF6),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
        }
    } else {
        // Full mode for LicenseGateScreen
        OutlinedTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                val formatted = formatLicenseKey(newValue.text)
                textFieldValue = TextFieldValue(formatted, TextRange(formatted.length))
                onValueChange(formatted)
            },
            label = {
                Text("License Key", color = Color.White.copy(alpha = 0.6f))
            },
            placeholder = {
                Text("XXXX-XXXX-XXXX-XXXX", color = Color.White.copy(alpha = 0.3f))
            },
            singleLine = true,
            modifier = modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 16.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF8B5CF6),
                unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                cursorColor = Color(0xFF8B5CF6)
            ),
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters
            ),
            leadingIcon = {
                Icon(Icons.Default.Key, null, tint = Color(0xFF8B5CF6))
            },
            trailingIcon = {
                IconButton(onClick = onScanQr) {
                    Icon(
                        Icons.Default.QrCodeScanner,
                        contentDescription = "สแกน QR Code",
                        tint = Color(0xFF8B5CF6)
                    )
                }
            }
        )
    }
}

/**
 * Format raw input into license key format: XXXX-XXXX-XXXX-XXXX
 * - Strips non-alphanumeric characters
 * - Forces uppercase
 * - Inserts dashes every 4 characters
 * - Max 16 alphanumeric characters (19 with dashes)
 */
fun formatLicenseKey(input: String): String {
    // Strip everything except alphanumeric
    val clean = input.uppercase().replace(Regex("[^A-Z0-9]"), "").take(16)

    // Insert dashes every 4 chars
    return clean.chunked(4).joinToString("-")
}
