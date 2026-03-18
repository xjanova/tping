package com.xjanova.tping.ui.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.xjanova.tping.data.entity.DataField
import com.xjanova.tping.data.entity.DataProfile
import com.xjanova.tping.data.license.LicenseManager
import com.xjanova.tping.data.license.LicenseStatus
import com.xjanova.tping.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataProfileScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val profiles by viewModel.dataProfiles.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showGenDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<DataProfile?>(null) }
    val licenseState by LicenseManager.state.collectAsState()
    val isPaidLicense = licenseState.status == LicenseStatus.ACTIVE

    // Group profiles by category
    val grouped = profiles.groupBy { it.category.ifEmpty { "" } }
    val categories = grouped.keys.sorted()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("จัดการข้อมูล") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Gen button — always visible, grayed if not licensed
                    TextButton(
                        onClick = { if (isPaidLicense) showGenDialog = true },
                        enabled = isPaidLicense
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome, "Gen",
                            modifier = Modifier.size(18.dp),
                            tint = if (isPaidLicense) Color(0xFF8B5CF6) else Color.Gray.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            if (isPaidLicense) "Gen ชุดข้อมูล" else "Gen (ไลเซนส์)",
                            fontSize = 13.sp,
                            color = if (isPaidLicense) Color(0xFF8B5CF6) else Color.Gray.copy(alpha = 0.4f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = Color(0xFF3B82F6)
            ) {
                Icon(Icons.Default.Add, "Add", tint = Color.White)
            }
        }
    ) { padding ->
        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Storage,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("ยังไม่มีข้อมูล", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    Text("กด + เพื่อเพิ่มชุดข้อมูลใหม่", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("ตั้งหมวดหมู่เพื่อจัดกลุ่ม เช่น \"โซเชียล\", \"เกม\"", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { cat ->
                    val catProfiles = grouped[cat] ?: emptyList()
                    if (cat.isNotEmpty()) {
                        item(key = "cat_$cat") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Folder, null, tint = Color(0xFFF59E0B), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(cat, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "${catProfiles.size} ชุด",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                    } else if (categories.size > 1) {
                        // Show "no category" header only if there are also categorized items
                        item(key = "cat_uncategorized") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.FolderOpen, null, tint = Color(0xFF888888), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("ไม่มีหมวด", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF888888))
                            }
                        }
                    }
                    items(catProfiles, key = { it.id }) { profile ->
                        ProfileCard(
                            profile = profile,
                            fields = viewModel.getFieldsFromProfile(profile),
                            onEdit = { editingProfile = profile },
                            onCopy = { viewModel.copyProfile(profile) },
                            onDelete = { viewModel.deleteProfile(profile) }
                        )
                    }
                }
            }
        }
    }

    // Existing categories for suggestions
    val existingCategories = profiles.map { it.category }.filter { it.isNotEmpty() }.distinct()

    // Add Dialog
    if (showAddDialog) {
        ProfileEditDialog(
            title = "เพิ่มชุดข้อมูลใหม่",
            initialName = "",
            initialCategory = "",
            initialFields = listOf(DataField("", "")),
            existingCategories = existingCategories,
            onDismiss = { showAddDialog = false },
            onSave = { name, category, fields ->
                viewModel.saveProfile(name, category, fields)
                showAddDialog = false
            }
        )
    }

    // Edit Dialog
    editingProfile?.let { profile ->
        ProfileEditDialog(
            title = "แก้ไขข้อมูล",
            initialName = profile.name,
            initialCategory = profile.category,
            initialFields = viewModel.getFieldsFromProfile(profile),
            existingCategories = existingCategories,
            onDismiss = { editingProfile = null },
            onSave = { name, category, fields ->
                viewModel.updateProfile(profile, name, category, fields)
                editingProfile = null
            }
        )
    }

    // Generate Dialog
    if (showGenDialog) {
        GenerateProfilesDialog(
            existingCategories = existingCategories,
            onDismiss = { showGenDialog = false },
            onGenerate = { name, category, fields, start, end ->
                viewModel.generateProfiles(name, category, fields, start, end)
                showGenDialog = false
            }
        )
    }
}

@Composable
fun ProfileCard(
    profile: DataProfile,
    fields: List<DataField>,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(
                        profile.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    if (profile.category.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            profile.category,
                            fontSize = 9.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .background(Color(0xFFF59E0B), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
                Row {
                    IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ContentCopy, "Copy", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, "Edit", modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete, "Delete",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            fields.forEach { field ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        "${field.key}: ",
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        color = Color(0xFF3B82F6)
                    )
                    Text(field.value, fontSize = 13.sp)
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("ยืนยันลบ") },
            text = { Text("ต้องการลบ \"${profile.name}\" หรือไม่?") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteConfirm = false
                }) {
                    Text("ลบ", color = Color(0xFFEF4444))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("ยกเลิก")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateProfilesDialog(
    existingCategories: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onGenerate: (nameTemplate: String, category: String, fields: List<DataField>, rangeStart: Int, rangeEnd: Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var showCategoryDropdown by remember { mutableStateOf(false) }
    var fields by remember { mutableStateOf(listOf(DataField("", ""))) }
    var rangeStartText by remember { mutableStateOf("1") }
    var rangeEndText by remember { mutableStateOf("10") }

    val rangeStart = rangeStartText.toIntOrNull() ?: 0
    val rangeEnd = rangeEndText.toIntOrNull() ?: 0
    val count = if (rangeEnd >= rangeStart) (rangeEnd - rangeStart + 1) else 0
    val isValid = name.isNotBlank() && name.contains("*") && count in 1..500
        && fields.any { it.key.isNotBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = Color(0xFF8B5CF6), modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Gen ชุดข้อมูล")
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Category
                ExposedDropdownMenuBox(
                    expanded = showCategoryDropdown && existingCategories.isNotEmpty(),
                    onExpandedChange = { showCategoryDropdown = it }
                ) {
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it; showCategoryDropdown = true },
                        label = { Text("หมวดหมู่") },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        maxLines = 1
                    )
                    if (existingCategories.isNotEmpty()) {
                        val filtered = existingCategories.filter { it.contains(category, ignoreCase = true) }
                        if (filtered.isNotEmpty()) {
                            ExposedDropdownMenu(
                                expanded = showCategoryDropdown,
                                onDismissRequest = { showCategoryDropdown = false }
                            ) {
                                filtered.forEach { cat ->
                                    DropdownMenuItem(
                                        text = { Text(cat) },
                                        onClick = { category = cat; showCategoryDropdown = false }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Name template
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("ชื่อ (ใช้ * แทนตัวเลข)") },
                    placeholder = { Text("เช่น Account *") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                    isError = name.isNotBlank() && !name.contains("*"),
                    supportingText = if (name.isNotBlank() && !name.contains("*")) {
                        { Text("ต้องมี * อย่างน้อย 1 ตัว", color = Color(0xFFEF4444), fontSize = 11.sp) }
                    } else null
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text("Fields (ใช้ * แทนตัวเลข):", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))

                fields.forEachIndexed { index, field ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = field.key,
                            onValueChange = { newKey ->
                                fields = fields.toMutableList().apply { this[index] = field.copy(key = newKey) }
                            },
                            label = { Text("Key") },
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedTextField(
                            value = field.value,
                            onValueChange = { newVal ->
                                fields = fields.toMutableList().apply { this[index] = field.copy(value = newVal) }
                            },
                            label = { Text("Value") },
                            placeholder = { Text("mail*@gmail.com") },
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )
                        IconButton(
                            onClick = {
                                if (fields.size > 1) fields = fields.toMutableList().apply { removeAt(index) }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Remove, "Remove", modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                TextButton(onClick = { fields = fields + DataField("", "") }) {
                    Icon(Icons.Default.Add, "Add", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("เพิ่ม field")
                }

                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))

                // Range
                Text("ตัวแทน * :", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = rangeStartText,
                        onValueChange = { rangeStartText = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("เริ่ม") },
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    Text("  ถึง  ", fontSize = 14.sp)
                    OutlinedTextField(
                        value = rangeEndText,
                        onValueChange = { rangeEndText = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("สิ้นสุด") },
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Preview count
                val countColor = when {
                    count > 500 -> Color(0xFFEF4444)
                    count > 0 -> Color(0xFF10B981)
                    else -> Color.Gray
                }
                Text(
                    when {
                        count > 500 -> "เกินขีดจำกัด! สูงสุด 500 ชุด (ตอนนี้ $count)"
                        count > 0 -> "จะสร้าง $count ชุดข้อมูล"
                        else -> "กรุณากรอกช่วงตัวเลข"
                    },
                    color = countColor,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )

                // Example preview
                if (count > 0 && name.contains("*")) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val example1 = name.replace("*", rangeStart.toString())
                    val example2 = if (count > 1) name.replace("*", rangeEnd.coerceAtMost(rangeStart + 499).toString()) else null
                    Text(
                        "ตัวอย่าง: $example1${if (example2 != null) " ... $example2" else ""}",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val validFields = fields.filter { it.key.isNotBlank() }
                    if (isValid) {
                        onGenerate(name, category.trim(), validFields, rangeStart, rangeEnd.coerceAtMost(rangeStart + 499))
                    }
                },
                enabled = isValid,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
            ) {
                Text(if (isValid) "สร้าง $count ชุด" else "สร้าง")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ยกเลิก") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditDialog(
    title: String,
    initialName: String,
    initialCategory: String,
    initialFields: List<DataField>,
    existingCategories: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (String, String, List<DataField>) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var category by remember { mutableStateOf(initialCategory) }
    var showCategoryDropdown by remember { mutableStateOf(false) }
    var fields by remember {
        mutableStateOf(
            if (initialFields.isEmpty()) listOf(DataField("", ""))
            else initialFields
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // Category field with dropdown
                ExposedDropdownMenuBox(
                    expanded = showCategoryDropdown && existingCategories.isNotEmpty(),
                    onExpandedChange = { showCategoryDropdown = it }
                ) {
                    OutlinedTextField(
                        value = category,
                        onValueChange = {
                            category = it
                            showCategoryDropdown = true
                        },
                        label = { Text("หมวดหมู่") },
                        placeholder = { Text("เช่น โซเชียล, เกม") },
                        modifier = Modifier.fillMaxWidth().menuAnchor().horizontalScroll(rememberScrollState()),
                        maxLines = 1,
                        trailingIcon = {
                            if (existingCategories.isNotEmpty()) {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = showCategoryDropdown)
                            }
                        }
                    )
                    if (existingCategories.isNotEmpty()) {
                        val filtered = existingCategories.filter { it.contains(category, ignoreCase = true) }
                        if (filtered.isNotEmpty()) {
                            ExposedDropdownMenu(
                                expanded = showCategoryDropdown,
                                onDismissRequest = { showCategoryDropdown = false }
                            ) {
                                filtered.forEach { cat ->
                                    DropdownMenuItem(
                                        text = { Text(cat) },
                                        onClick = {
                                            category = cat
                                            showCategoryDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("ชื่อชุดข้อมูล") },
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("ข้อมูล:", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))

                fields.forEachIndexed { index, field ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = field.key,
                            onValueChange = { newKey ->
                                fields = fields.toMutableList().apply {
                                    this[index] = field.copy(key = newKey)
                                }
                            },
                            label = { Text("Key") },
                            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedTextField(
                            value = field.value,
                            onValueChange = { newVal ->
                                fields = fields.toMutableList().apply {
                                    this[index] = field.copy(value = newVal)
                                }
                            },
                            label = { Text("Value") },
                            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            maxLines = 1
                        )
                        IconButton(
                            onClick = {
                                if (fields.size > 1) {
                                    fields = fields.toMutableList().apply { removeAt(index) }
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Remove, "Remove", modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                TextButton(
                    onClick = { fields = fields + DataField("", "") }
                ) {
                    Icon(Icons.Default.Add, "Add", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("เพิ่ม field")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val validFields = fields.filter { it.key.isNotBlank() }
                    if (name.isNotBlank() && validFields.isNotEmpty()) {
                        onSave(name, category.trim(), validFields)
                    }
                }
            ) {
                Text("บันทึก")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("ยกเลิก")
            }
        }
    )
}
