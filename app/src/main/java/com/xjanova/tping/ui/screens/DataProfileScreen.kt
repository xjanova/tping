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
import com.xjanova.tping.data.entity.DataField
import com.xjanova.tping.data.entity.DataProfile
import com.xjanova.tping.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataProfileScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val profiles by viewModel.dataProfiles.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<DataProfile?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("จัดการข้อมูล") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
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
                items(profiles) { profile ->
                    ProfileCard(
                        profile = profile,
                        fields = viewModel.getFieldsFromProfile(profile),
                        onEdit = { editingProfile = profile },
                        onDelete = { viewModel.deleteProfile(profile) }
                    )
                }
            }
        }
    }

    // Add Dialog
    if (showAddDialog) {
        ProfileEditDialog(
            title = "เพิ่มชุดข้อมูลใหม่",
            initialName = "",
            initialFields = listOf(DataField("", "")),
            onDismiss = { showAddDialog = false },
            onSave = { name, fields ->
                viewModel.saveProfile(name, fields)
                showAddDialog = false
            }
        )
    }

    // Edit Dialog
    editingProfile?.let { profile ->
        ProfileEditDialog(
            title = "แก้ไขข้อมูล",
            initialName = profile.name,
            initialFields = viewModel.getFieldsFromProfile(profile),
            onDismiss = { editingProfile = null },
            onSave = { name, fields ->
                viewModel.updateProfile(profile.copy(name = name), fields)
                editingProfile = null
            }
        )
    }
}

@Composable
fun ProfileCard(
    profile: DataProfile,
    fields: List<DataField>,
    onEdit: () -> Unit,
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
                Text(
                    profile.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Row {
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

@Composable
fun ProfileEditDialog(
    title: String,
    initialName: String,
    initialFields: List<DataField>,
    onDismiss: () -> Unit,
    onSave: (String, List<DataField>) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
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
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("ชื่อชุดข้อมูล") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
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
                            modifier = Modifier.weight(1f),
                            singleLine = true
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
                            modifier = Modifier.weight(1f),
                            singleLine = true
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
                        onSave(name, validFields)
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
