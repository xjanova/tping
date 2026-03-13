package com.xjanova.tping.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xjanova.tping.TpingApplication
import com.xjanova.tping.data.entity.*
import com.xjanova.tping.recorder.PlaybackEngine
import com.xjanova.tping.service.TpingAccessibilityService
import com.xjanova.tping.data.cloud.CloudSyncManager
import com.xjanova.tping.util.AppResolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as TpingApplication).database
    val profileDao = db.dataProfileDao()
    val workflowDao = db.workflowDao()
    private val gson = Gson()
    val playbackEngine = PlaybackEngine()

    // Data Profiles
    val dataProfiles: StateFlow<List<DataProfile>> = profileDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Workflows
    val workflows: StateFlow<List<Workflow>> = workflowDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Selected items
    private val _selectedProfileId = MutableStateFlow<Long?>(null)
    val selectedProfileId: StateFlow<Long?> = _selectedProfileId

    private val _selectedWorkflowId = MutableStateFlow<Long?>(null)
    val selectedWorkflowId: StateFlow<Long?> = _selectedWorkflowId

    // Loop count
    private val _loopCount = MutableStateFlow(1)
    val loopCount: StateFlow<Int> = _loopCount

    // Persist selections across app restarts
    private val prefs = application.getSharedPreferences("tping_quick_play", Context.MODE_PRIVATE)

    init {
        val savedWf = prefs.getLong("selected_workflow_id", -1L)
        if (savedWf > 0) _selectedWorkflowId.value = savedWf
        val savedPf = prefs.getLong("selected_profile_id", -1L)
        if (savedPf > 0) _selectedProfileId.value = savedPf
        val savedLoop = prefs.getInt("loop_count", 1)
        _loopCount.value = savedLoop.coerceIn(1, 999)

        // Validate saved IDs against actual data — clear stale selections
        viewModelScope.launch {
            val wfs = workflows.first { it.isNotEmpty() }
            val currentId = _selectedWorkflowId.value
            if (currentId != null && wfs.none { it.id == currentId }) {
                _selectedWorkflowId.value = null
                prefs.edit().remove("selected_workflow_id").apply()
            }
        }
    }

    // Launch status
    private val _launchStatus = MutableStateFlow("")
    val launchStatus: StateFlow<String> = _launchStatus

    fun setLoopCount(count: Int) {
        val c = count.coerceIn(1, 999)
        _loopCount.value = c
        prefs.edit().putInt("loop_count", c).apply()
    }

    // ====== Data Profile Operations ======

    fun saveProfile(name: String, category: String, fields: List<DataField>) {
        viewModelScope.launch {
            val json = gson.toJson(fields)
            profileDao.insert(DataProfile(name = name, category = category, fieldsJson = json))
            triggerCloudSync()
        }
    }

    fun updateProfile(profile: DataProfile, name: String, category: String, fields: List<DataField>) {
        viewModelScope.launch {
            val json = gson.toJson(fields)
            profileDao.update(profile.copy(name = name, category = category, fieldsJson = json))
            triggerCloudSync()
        }
    }

    fun deleteProfile(profile: DataProfile) {
        viewModelScope.launch {
            profileDao.delete(profile)
            triggerCloudSync()
        }
    }

    fun copyProfile(profile: DataProfile) {
        viewModelScope.launch {
            val newName = "${profile.name} (สำเนา)"
            profileDao.insert(DataProfile(name = newName, category = profile.category, fieldsJson = profile.fieldsJson))
            triggerCloudSync()
        }
    }

    fun getFieldsFromProfile(profile: DataProfile): List<DataField> {
        return try {
            val type = object : TypeToken<List<DataField>>() {}.type
            gson.fromJson(profile.fieldsJson, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ====== Workflow Operations ======

    fun saveWorkflow(name: String, actions: List<RecordedAction>, targetApp: String = "") {
        viewModelScope.launch {
            val json = gson.toJson(actions)
            workflowDao.insert(Workflow(name = name, stepsJson = json, targetAppPackage = targetApp))
            triggerCloudSync()
        }
    }

    fun deleteWorkflow(workflow: Workflow) {
        viewModelScope.launch {
            workflowDao.delete(workflow)
            triggerCloudSync()
        }
    }

    private fun triggerCloudSync() {
        viewModelScope.launch {
            CloudSyncManager.syncAfterLocalChange()
        }
    }

    fun getActionsFromWorkflow(workflow: Workflow): List<RecordedAction> {
        return try {
            val type = object : TypeToken<List<RecordedAction>>() {}.type
            gson.fromJson(workflow.stepsJson, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun selectProfile(id: Long?) {
        _selectedProfileId.value = id
        if (id != null) prefs.edit().putLong("selected_profile_id", id).apply()
        else prefs.edit().remove("selected_profile_id").apply()
    }

    fun selectWorkflow(id: Long) {
        _selectedWorkflowId.value = id
        prefs.edit().putLong("selected_workflow_id", id).apply()
    }

    // ====== Playback with Auto-Launch ======

    fun startPlayback() {
        val workflowId = _selectedWorkflowId.value ?: return
        val profileId = _selectedProfileId.value

        viewModelScope.launch {
            val workflow = workflowDao.getById(workflowId) ?: return@launch
            val actions = getActionsFromWorkflow(workflow)

            val dataFields = if (profileId != null) {
                val profile = profileDao.getById(profileId)
                if (profile != null) getFieldsFromProfile(profile) else emptyList()
            } else {
                emptyList()
            }

            // Auto-launch target app if specified
            val targetPkg = workflow.targetAppPackage
            if (targetPkg.isNotEmpty()) {
                val appName = AppResolver.getAppName(getApplication(), targetPkg)
                _launchStatus.value = "กำลังเปิด $appName..."

                val launched = AppResolver.launchApp(getApplication(), targetPkg)
                if (launched) {
                    // Wait for app to come to foreground
                    delay(1500)
                    var ready = false
                    for (i in 0 until 15) { // poll up to ~4.5 seconds
                        val service = TpingAccessibilityService.instance
                        val currentPkg = try {
                            service?.rootInActiveWindow?.packageName?.toString() ?: ""
                        } catch (_: Exception) { "" }
                        if (currentPkg == targetPkg) {
                            ready = true
                            break
                        }
                        delay(300)
                    }
                    _launchStatus.value = if (ready) "เปิด $appName แล้ว" else "รอ $appName..."
                    delay(500)
                } else {
                    _launchStatus.value = "ไม่สามารถเปิด $appName"
                    delay(1000)
                }
            }
            _launchStatus.value = ""

            playbackEngine.play(
                actions = actions,
                dataFieldSets = if (dataFields.isNotEmpty()) listOf(dataFields) else emptyList(),
                loopCount = _loopCount.value,
                scope = viewModelScope
            )
        }
    }

    fun pausePlayback() = playbackEngine.pause()
    fun resumePlayback() = playbackEngine.resume()
    fun stopPlayback() = playbackEngine.stop()

    // ====== Recording ======

    fun saveCurrentRecording(name: String) {
        val service = TpingAccessibilityService.instance ?: return
        val actions = service.getRecorder().getActions()
        if (actions.isEmpty()) return

        val targetPkg = actions.firstOrNull()?.packageName ?: ""
        val targetAppName = if (targetPkg.isNotEmpty()) {
            AppResolver.getAppName(getApplication(), targetPkg)
        } else ""

        viewModelScope.launch {
            val json = gson.toJson(actions)
            workflowDao.insert(
                Workflow(
                    name = name,
                    stepsJson = json,
                    targetAppPackage = targetPkg,
                    targetAppName = targetAppName
                )
            )
        }
    }

    /**
     * Suggest a workflow name based on the target app being recorded.
     */
    fun suggestWorkflowName(): String {
        val service = TpingAccessibilityService.instance ?: return ""
        val actions = service.getRecorder().getActions()
        val targetPkg = actions.firstOrNull()?.packageName ?: return ""
        if (targetPkg.isEmpty()) return ""
        val appName = AppResolver.getAppName(getApplication(), targetPkg)
        if (appName.isEmpty()) return ""

        // Check for duplicates
        val existing = workflows.value.count { it.targetAppName == appName || it.name.startsWith(appName) }
        return if (existing > 0) "$appName #${existing + 1}" else appName
    }

    fun tagLastActionAsData(fieldKey: String) {
        val service = TpingAccessibilityService.instance ?: return
        service.getRecorder().tagLastActionAsDataField(fieldKey)
    }

    /**
     * Extract data field keys that a workflow requires (fields tagged during recording).
     */
    fun getDataKeysFromWorkflow(workflow: Workflow): List<String> {
        val actions = getActionsFromWorkflow(workflow)
        return actions.filter { it.dataFieldKey.isNotEmpty() }.map { it.dataFieldKey }.distinct()
    }

    /**
     * Resolve app name for a given workflow (for display).
     */
    fun resolveAppName(workflow: Workflow): String {
        if (workflow.targetAppName.isNotEmpty()) return workflow.targetAppName
        if (workflow.targetAppPackage.isNotEmpty()) {
            return AppResolver.getAppName(getApplication(), workflow.targetAppPackage)
        }
        return ""
    }
}
