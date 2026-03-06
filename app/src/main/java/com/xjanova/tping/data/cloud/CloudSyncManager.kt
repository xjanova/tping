package com.xjanova.tping.data.cloud

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.xjanova.tping.TpingApplication
import com.xjanova.tping.data.entity.DataProfile
import com.xjanova.tping.data.entity.Workflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class SyncState(
    val isSyncing: Boolean = false,
    val lastSyncAt: Long = 0,
    val message: String = "",
    val error: String = ""
)

/**
 * Orchestrates upload/download of workflows and data profiles to/from xmanstudio.
 */
object CloudSyncManager {

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState
    private val gson = Gson()

    suspend fun uploadAllWorkflows(): Int = withContext(Dispatchers.IO) {
        val token = CloudAuthManager.getToken() ?: throw Exception("ยังไม่ได้เข้าสู่ระบบ")
        val db = TpingApplication.instance.database
        val workflows = db.workflowDao().getAll().first()

        val jsonArray = JsonArray()
        for (wf in workflows) {
            jsonArray.add(JsonObject().apply {
                addProperty("name", wf.name)
                addProperty("target_app_package", wf.targetAppPackage)
                addProperty("target_app_name", wf.targetAppName)
                addProperty("steps_json", wf.stepsJson)
                addProperty("local_id", wf.id)
            })
        }

        val result = CloudApiClient.bulkImportWorkflows(token, jsonArray)
        if (!result.success) throw Exception(result.message ?: "อัพโหลด workflow ไม่สำเร็จ")
        result.data?.getAsJsonObject("data")?.get("imported")?.asInt ?: workflows.size
    }

    suspend fun uploadAllProfiles(): Int = withContext(Dispatchers.IO) {
        val token = CloudAuthManager.getToken() ?: throw Exception("ยังไม่ได้เข้าสู่ระบบ")
        val db = TpingApplication.instance.database
        val profiles = db.dataProfileDao().getAll().first()

        val jsonArray = JsonArray()
        for (p in profiles) {
            jsonArray.add(JsonObject().apply {
                addProperty("name", p.name)
                addProperty("category", p.category)
                addProperty("fields_json", p.fieldsJson)
                addProperty("local_id", p.id)
            })
        }

        val result = CloudApiClient.bulkImportProfiles(token, jsonArray)
        if (!result.success) throw Exception(result.message ?: "อัพโหลด profile ไม่สำเร็จ")
        result.data?.getAsJsonObject("data")?.get("imported")?.asInt ?: profiles.size
    }

    suspend fun downloadAllWorkflows(): Int = withContext(Dispatchers.IO) {
        val token = CloudAuthManager.getToken() ?: throw Exception("ยังไม่ได้เข้าสู่ระบบ")
        val db = TpingApplication.instance.database
        val dao = db.workflowDao()

        val result = CloudApiClient.getWorkflows(token)
        if (!result.success) throw Exception(result.message ?: "ดาวน์โหลด workflow ไม่สำเร็จ")

        val dataArray = result.data?.getAsJsonObject("data")?.getAsJsonArray("data") ?: return@withContext 0
        var count = 0
        for (item in dataArray) {
            val obj = item.asJsonObject
            val name = obj.get("name")?.asString ?: continue
            val stepsJson = obj.get("steps_json")?.asString ?: continue

            dao.insert(
                Workflow(
                    name = name,
                    targetAppPackage = obj.get("target_app_package")?.asString ?: "",
                    targetAppName = obj.get("target_app_name")?.asString ?: "",
                    stepsJson = stepsJson
                )
            )
            count++
        }
        count
    }

    suspend fun downloadAllProfiles(): Int = withContext(Dispatchers.IO) {
        val token = CloudAuthManager.getToken() ?: throw Exception("ยังไม่ได้เข้าสู่ระบบ")
        val db = TpingApplication.instance.database
        val dao = db.dataProfileDao()

        val result = CloudApiClient.getDataProfiles(token)
        if (!result.success) throw Exception(result.message ?: "ดาวน์โหลด profile ไม่สำเร็จ")

        val dataArray = result.data?.getAsJsonObject("data")?.getAsJsonArray("data") ?: return@withContext 0
        var count = 0
        for (item in dataArray) {
            val obj = item.asJsonObject
            val name = obj.get("name")?.asString ?: continue
            val fieldsJson = obj.get("fields_json")?.asString ?: continue

            dao.insert(
                DataProfile(
                    name = name,
                    category = obj.get("category")?.asString ?: "",
                    fieldsJson = fieldsJson
                )
            )
            count++
        }
        count
    }

    suspend fun fullSync() {
        _syncState.value = SyncState(isSyncing = true, message = "กำลังอัพโหลด...")
        try {
            val wfUp = uploadAllWorkflows()
            _syncState.value = _syncState.value.copy(message = "อัพโหลด workflow $wfUp รายการ...")

            val pfUp = uploadAllProfiles()
            _syncState.value = SyncState(
                lastSyncAt = System.currentTimeMillis(),
                message = "ซิงค์สำเร็จ (workflow: $wfUp, profile: $pfUp)"
            )
        } catch (e: Exception) {
            _syncState.value = SyncState(error = e.message ?: "ซิงค์ไม่สำเร็จ")
        }
    }
}
