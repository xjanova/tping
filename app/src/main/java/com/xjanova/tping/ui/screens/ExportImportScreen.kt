package com.xjanova.tping.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjanova.tping.data.entity.Workflow
import com.xjanova.tping.data.export.DuplicateStrategy
import com.xjanova.tping.data.export.ExportImportManager
import com.xjanova.tping.data.export.ImportResult
import com.xjanova.tping.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportImportScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val workflows by viewModel.workflows.collectAsState()
    val profiles by viewModel.dataProfiles.collectAsState()

    // Export state
    var selectedWorkflowIds by remember { mutableStateOf(setOf<Long>()) }
    var includeProfiles by remember { mutableStateOf(true) }
    var isExporting by remember { mutableStateOf(false) }

    // Import state
    var isImporting by remember { mutableStateOf(false) }
    var showDuplicateDialog by remember { mutableStateOf(false) }
    var pendingImportJson by remember { mutableStateOf("") }
    var importResult by remember { mutableStateOf<ImportResult?>(null) }

    // SAF launchers
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isExporting = true
            try {
                val selectedWfs = workflows.filter { it.id in selectedWorkflowIds }
                val selectedProfiles = if (includeProfiles) profiles else emptyList()
                val json = ExportImportManager.exportToJson(selectedWfs, selectedProfiles)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                }
                Toast.makeText(context, "Export สำเร็จ (${selectedWfs.size} workflow)", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Export ผิดพลาด: ${e.message}", Toast.LENGTH_LONG).show()
            }
            isExporting = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                }
                if (json.isEmpty()) {
                    Toast.makeText(context, "ไฟล์ว่างเปล่า", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                // Validate JSON first
                ExportImportManager.parseImportJson(json)
                pendingImportJson = json
                showDuplicateDialog = true
            } catch (e: Exception) {
                Toast.makeText(context, "อ่านไฟล์ไม่ได้: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Duplicate strategy dialog
    if (showDuplicateDialog) {
        DuplicateStrategyDialog(
            onDismiss = { showDuplicateDialog = false },
            onSelect = { strategy ->
                showDuplicateDialog = false
                scope.launch {
                    isImporting = true
                    try {
                        val data = ExportImportManager.parseImportJson(pendingImportJson)
                        val result = ExportImportManager.importData(
                            workflowDao = viewModel.workflowDao,
                            profileDao = viewModel.profileDao,
                            data = data,
                            existingWorkflows = workflows,
                            existingProfiles = profiles,
                            strategy = strategy
                        )
                        importResult = result
                    } catch (e: Exception) {
                        Toast.makeText(context, "Import ผิดพลาด: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    isImporting = false
                    pendingImportJson = ""
                }
            }
        )
    }

    // Import result dialog
    importResult?.let { result ->
        AlertDialog(
            onDismissRequest = { importResult = null },
            title = { Text("Import สำเร็จ") },
            text = {
                Column {
                    Text("Workflow: ${result.workflowsImported} รายการ")
                    Text("Data Profile: ${result.profilesImported} รายการ")
                    if (result.skipped > 0) {
                        Text("ข้าม: ${result.skipped} รายการ", color = Color(0xFFF59E0B))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { importResult = null }) { Text("ตกลง") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export / Import") },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ===== Export Section =====
            item {
                Text(
                    "Export",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Select All
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("เลือก Workflow ที่จะ Export", fontWeight = FontWeight.Medium)
                            TextButton(onClick = {
                                selectedWorkflowIds = if (selectedWorkflowIds.size == workflows.size) {
                                    emptySet()
                                } else {
                                    workflows.map { it.id }.toSet()
                                }
                            }) {
                                Text(
                                    if (selectedWorkflowIds.size == workflows.size) "ยกเลิกทั้งหมด" else "เลือกทั้งหมด",
                                    fontSize = 12.sp
                                )
                            }
                        }

                        if (workflows.isEmpty()) {
                            Text(
                                "ยังไม่มี Workflow",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            // Workflow list
            items(workflows, key = { it.id }) { workflow ->
                WorkflowCheckItem(
                    workflow = workflow,
                    appName = viewModel.resolveAppName(workflow),
                    checked = workflow.id in selectedWorkflowIds,
                    onCheckedChange = { checked ->
                        selectedWorkflowIds = if (checked) {
                            selectedWorkflowIds + workflow.id
                        } else {
                            selectedWorkflowIds - workflow.id
                        }
                    }
                )
            }

            // Include profiles toggle
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("รวม Data Profile", fontWeight = FontWeight.Medium)
                        Text(
                            "${profiles.size} ชุดข้อมูล",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    Switch(checked = includeProfiles, onCheckedChange = { includeProfiles = it })
                }
            }

            // Export button
            item {
                Button(
                    onClick = {
                        exportLauncher.launch("tping_export.json")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedWorkflowIds.isNotEmpty() && !isExporting,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEC4899))
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Icon(Icons.Default.FileUpload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Export ${selectedWorkflowIds.size} Workflow",
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // ===== Divider =====
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            // ===== Import Section =====
            item {
                Text(
                    "Import",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF3B82F6).copy(alpha = 0.08f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Import ไฟล์ .json ที่ Export จาก Tping",
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "รองรับไฟล์จากเครื่องอื่นหรือเวอร์ชันก่อนหน้า",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                importLauncher.launch(arrayOf("application/json", "*/*"))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isImporting,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
                        ) {
                            if (isImporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Icon(Icons.Default.FileDownload, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("เลือกไฟล์ Import", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkflowCheckItem(
    workflow: Workflow,
    appName: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(workflow.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                if (appName.isNotEmpty()) {
                    Text(
                        appName,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DuplicateStrategyDialog(
    onDismiss: () -> Unit,
    onSelect: (DuplicateStrategy) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ถ้าชื่อซ้ำจะทำอย่างไร?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { onSelect(DuplicateStrategy.SKIP) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("ข้าม", fontWeight = FontWeight.Bold)
                        Text("ไม่นำเข้ารายการที่ชื่อซ้ำ", fontSize = 12.sp, color = Color.Gray)
                    }
                }
                TextButton(
                    onClick = { onSelect(DuplicateStrategy.REPLACE) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("แทนที่", fontWeight = FontWeight.Bold)
                        Text("เขียนทับรายการที่ชื่อซ้ำ", fontSize = 12.sp, color = Color.Gray)
                    }
                }
                TextButton(
                    onClick = { onSelect(DuplicateStrategy.RENAME) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("เปลี่ยนชื่อ", fontWeight = FontWeight.Bold)
                        Text("เพิ่ม (2), (3) ต่อท้ายชื่อ", fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ยกเลิก") }
        }
    )
}
