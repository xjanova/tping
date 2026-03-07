package com.xjanova.tping.ui.screens

import android.content.Intent
import android.provider.Settings
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.xjanova.tping.data.entity.RecordedAction
import com.xjanova.tping.data.entity.Workflow
import com.xjanova.tping.overlay.FloatingOverlayService
import com.xjanova.tping.service.TpingAccessibilityService
import com.xjanova.tping.ui.viewmodel.MainViewModel
import com.xjanova.tping.util.PermissionHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val workflows by viewModel.workflows.collectAsState()
    var showSaveDialog by remember { mutableStateOf(false) }
    var permissionMessage by remember { mutableStateOf("") }
    var waitingForPermission by remember { mutableStateOf(false) }

    // Auto-launch overlay when returning from settings with permissions granted
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (waitingForPermission) {
                val hasAccess = TpingAccessibilityService.instance != null
                val hasOverlay = Settings.canDrawOverlays(context)
                if (hasAccess && hasOverlay) {
                    waitingForPermission = false
                    permissionMessage = ""
                    val intent = Intent(context, FloatingOverlayService::class.java)
                    intent.putExtra("mode", "idle")
                    context.startForegroundService(intent)
                } else if (hasAccess && !hasOverlay) {
                    // Accessibility granted, now need overlay
                    permissionMessage = "เปิด Accessibility แล้ว! ต้องเปิด Overlay อีก 1 อย่าง"
                    PermissionHelper.openOverlaySettings(context)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("บันทึกขั้นตอน") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
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
            // Recording controls
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFEF4444).copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.FiberManualRecord,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "โหมดบันทึก",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "กดเริ่ม → ไปทำบนแอพเป้าหมาย → แอพจะจำทุกขั้นตอน",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // Permission warning message
                        if (permissionMessage.isNotEmpty()) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFFF59E0B).copy(alpha = 0.15f)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    permissionMessage,
                                    fontSize = 12.sp,
                                    color = Color(0xFFF59E0B),
                                    modifier = Modifier.padding(10.dp),
                                    lineHeight = 17.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        Button(
                            onClick = {
                                if (TpingAccessibilityService.instance == null) {
                                    permissionMessage = "ต้องเปิด Accessibility Service ก่อน\nเปิดแล้วกลับมา จะเริ่มให้อัตโนมัติ"
                                    waitingForPermission = true
                                    PermissionHelper.openAccessibilitySettings(context)
                                } else if (!Settings.canDrawOverlays(context)) {
                                    permissionMessage = "ต้องเปิด Overlay Permission ก่อน\nเปิดแล้วกลับมา จะเริ่มให้อัตโนมัติ"
                                    waitingForPermission = true
                                    PermissionHelper.openOverlaySettings(context)
                                } else {
                                    permissionMessage = ""
                                    waitingForPermission = false
                                    val intent = Intent(context, FloatingOverlayService::class.java)
                                    intent.putExtra("mode", "idle")
                                    context.startForegroundService(intent)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFEF4444)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.FiberManualRecord, "Record")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("เริ่มบันทึกขั้นตอน")
                        }

                        // Save recording button (reactive via StateFlow)
                        val recorder = TpingAccessibilityService.instance?.getRecorder()
                        val actionCount by (recorder?.actionCount ?: kotlinx.coroutines.flow.MutableStateFlow(0)).collectAsState()
                        if (actionCount > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { showSaveDialog = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Save, "Save")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("บันทึก ($actionCount ขั้นตอน)")
                            }
                        }
                    }
                }
            }

            // Saved workflows
            if (workflows.isNotEmpty()) {
                item {
                    Text(
                        "ขั้นตอนที่บันทึกไว้",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                items(workflows) { workflow ->
                    WorkflowCard(
                        workflow = workflow,
                        actions = viewModel.getActionsFromWorkflow(workflow),
                        appName = viewModel.resolveAppName(workflow),
                        onDelete = { viewModel.deleteWorkflow(workflow) }
                    )
                }
            }
        }
    }

    // Save Dialog with auto-suggest name
    if (showSaveDialog) {
        val suggestedName = remember { viewModel.suggestWorkflowName() }
        var workflowName by remember { mutableStateOf(suggestedName) }

        // Get target app info for display
        val targetAppName = remember {
            val service = TpingAccessibilityService.instance
            val actions = service?.getRecorder()?.getActions() ?: emptyList()
            val pkg = actions.firstOrNull()?.packageName ?: ""
            if (pkg.isNotEmpty()) com.xjanova.tping.util.AppResolver.getAppName(context, pkg) else ""
        }

        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("บันทึก Workflow") },
            text = {
                Column {
                    // Show target app info
                    if (targetAppName.isNotEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF3B82F6).copy(alpha = 0.1f)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Apps,
                                    contentDescription = null,
                                    tint = Color(0xFF3B82F6),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "แอพเป้าหมาย: $targetAppName",
                                    fontSize = 13.sp,
                                    color = Color(0xFF3B82F6)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    OutlinedTextField(
                        value = workflowName,
                        onValueChange = { workflowName = it },
                        label = { Text("ชื่อ Workflow") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (workflowName.isNotBlank()) {
                        viewModel.saveCurrentRecording(workflowName)
                        showSaveDialog = false
                    }
                }) {
                    Text("บันทึก")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("ยกเลิก")
                }
            }
        )
    }
}

@Composable
fun WorkflowCard(
    workflow: Workflow,
    actions: List<RecordedAction>,
    appName: String = "",
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(workflow.name, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${actions.size} ขั้นตอน",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        if (appName.isNotEmpty()) {
                            Text(
                                " | ",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            )
                            Icon(
                                Icons.Default.Apps,
                                contentDescription = null,
                                tint = Color(0xFF3B82F6),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                appName,
                                fontSize = 12.sp,
                                color = Color(0xFF3B82F6)
                            )
                        }
                    }
                }
                Row {
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            "Expand",
                            modifier = Modifier.size(18.dp)
                        )
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

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                // Show data field summary
                val dataKeys = actions.filter { it.dataFieldKey.isNotEmpty() }.map { it.dataFieldKey }.distinct()
                if (dataKeys.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFF59E0B).copy(alpha = 0.08f)
                        ),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.DataObject,
                                contentDescription = null,
                                tint = Color(0xFFF59E0B),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "ข้อมูลที่ต้องใช้: ${dataKeys.joinToString(", ")}",
                                fontSize = 11.sp,
                                color = Color(0xFFF59E0B),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                actions.forEach { action ->
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${action.stepOrder}.",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(24.dp)
                        )
                        Text(
                            when (action.actionType.name) {
                                "CLICK" -> "กด"
                                "INPUT_TEXT" -> "พิมพ์"
                                "LONG_CLICK" -> "กดค้าง"
                                "SCROLL_UP" -> "เลื่อนขึ้น"
                                "SCROLL_DOWN" -> "เลื่อนลง"
                                "BACK_BUTTON" -> "ย้อนกลับ"
                                "WAIT" -> "รอ"
                                else -> action.actionType.name
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(50.dp)
                        )
                        Text(
                            buildString {
                                val target = action.resourceId.substringAfterLast("/").ifEmpty { action.text.take(20) }
                                if (target.isNotEmpty()) append(target)
                                if (action.dataFieldKey.isNotEmpty()) {
                                    append(" ")
                                    append("[${action.dataFieldKey}]")
                                }
                            },
                            fontSize = 12.sp,
                            color = if (action.dataFieldKey.isNotEmpty()) Color(0xFF3B82F6)
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("ยืนยันลบ") },
            text = { Text("ต้องการลบ \"${workflow.name}\" หรือไม่?") },
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
