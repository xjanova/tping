package com.xjanova.tping.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xjanova.tping.TpingApplication
import com.xjanova.tping.data.entity.*
import com.xjanova.tping.recorder.PlaybackEngine
import com.xjanova.tping.service.TpingAccessibilityService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as TpingApplication).database
    private val profileDao = db.dataProfileDao()
    private val workflowDao = db.workflowDao()
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

    fun setLoopCount(count: Int) {
        _loopCount.value = count.coerceIn(1, 999)
    }

    // ====== Data Profile Operations ======

    fun saveProfile(name: String, fields: List<DataField>) {
        viewModelScope.launch {
            val json = gson.toJson(fields)
            profileDao.insert(DataProfile(name = name, fieldsJson = json))
        }
    }

    fun updateProfile(profile: DataProfile, fields: List<DataField>) {
        viewModelScope.launch {
            val json = gson.toJson(fields)
            profileDao.update(profile.copy(fieldsJson = json))
        }
    }

    fun deleteProfile(profile: DataProfile) {
        viewModelScope.launch {
            profileDao.delete(profile)
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
        }
    }

    fun deleteWorkflow(workflow: Workflow) {
        viewModelScope.launch {
            workflowDao.delete(workflow)
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

    fun selectProfile(id: Long) {
        _selectedProfileId.value = id
    }

    fun selectWorkflow(id: Long) {
        _selectedWorkflowId.value = id
    }

    // ====== Playback ======

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

            playbackEngine.play(
                actions = actions,
                dataFields = dataFields,
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

        viewModelScope.launch {
            val json = gson.toJson(actions)
            workflowDao.insert(
                Workflow(
                    name = name,
                    stepsJson = json,
                    targetAppPackage = actions.firstOrNull()?.packageName ?: ""
                )
            )
        }
    }

    fun tagLastActionAsData(fieldKey: String) {
        val service = TpingAccessibilityService.instance ?: return
        service.getRecorder().tagLastActionAsDataField(fieldKey)
    }
}
