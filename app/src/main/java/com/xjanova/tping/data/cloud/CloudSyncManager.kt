package com.xjanova.tping.data.cloud

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.xjanova.tping.TpingApplication
import com.xjanova.tping.data.entity.DataProfile
import com.xjanova.tping.data.entity.Workflow
import com.xjanova.tping.data.license.LicenseManager
import com.xjanova.tping.data.license.LicenseStatus
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
 *
 * v1.2.27 improvements:
 * - Download deduplication (checks by name before inserting)
 * - License-gated cloud access (only licensed users can sync)
 * - Auto-sync on startup when logged in + licensed
 * - Bidirectional full sync (upload then download)
 */
object CloudSyncManager {

    private const val TAG = "CloudSync"
    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState
    private val gson = Gson()

    /**
     * Check if user is eligible for cloud sync (must be logged in + have active license).
     */
    fun canSync(): Boolean {
        if (!CloudAuthManager.isLoggedIn()) return false
        return try {
            val status = LicenseManager.state.value.status
            status == LicenseStatus.ACTIVE ||
                status == LicenseStatus.TRIAL
        } catch (e: Exception) {
            false
        }
    }

    // ========================== Upload ==========================

    suspend fun uploadAllWorkflows(): Int = withContext(Dispatchers.IO) {
        requireAuth()
        val token = CloudAuthManager.getToken()!!
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
        requireAuth()
        val token = CloudAuthManager.getToken()!!
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

    // ========================== Download with Dedup ==========================

    suspend fun downloadAllWorkflows(): Int = withContext(Dispatchers.IO) {
        requireAuth()
        val token = CloudAuthManager.getToken()!!
        val db = TpingApplication.instance.database
        val dao = db.workflowDao()

        val result = CloudApiClient.getWorkflows(token)
        if (!result.success) throw Exception(result.message ?: "ดาวน์โหลด workflow ไม่สำเร็จ")

        val dataArray = result.data?.getAsJsonObject("data")?.getAsJsonArray("data")
            ?: return@withContext 0

        var imported = 0
        var updated = 0
        for (item in dataArray) {
            val obj = item.asJsonObject
            val name = obj.get("name")?.asString ?: continue
            val stepsJson = obj.get("steps_json")?.asString ?: continue
            val pkg = obj.get("target_app_package")?.asString ?: ""
            val appName = obj.get("target_app_name")?.asString ?: ""

            // Dedup: check if workflow with same name+package exists locally
            val existing = dao.findByNameAndPackage(name, pkg)
            if (existing != null) {
                // Update existing — cloud data takes precedence
                dao.update(existing.copy(
                    stepsJson = stepsJson,
                    targetAppName = appName
                ))
                updated++
            } else {
                dao.insert(Workflow(
                    name = name,
                    targetAppPackage = pkg,
                    targetAppName = appName,
                    stepsJson = stepsJson
                ))
                imported++
            }
        }
        Log.d(TAG, "Workflows downloaded: $imported new, $updated updated")
        imported + updated
    }

    suspend fun downloadAllProfiles(): Int = withContext(Dispatchers.IO) {
        requireAuth()
        val token = CloudAuthManager.getToken()!!
        val db = TpingApplication.instance.database
        val dao = db.dataProfileDao()

        val result = CloudApiClient.getDataProfiles(token)
        if (!result.success) throw Exception(result.message ?: "ดาวน์โหลด profile ไม่สำเร็จ")

        val dataArray = result.data?.getAsJsonObject("data")?.getAsJsonArray("data")
            ?: return@withContext 0

        var imported = 0
        var updated = 0
        for (item in dataArray) {
            val obj = item.asJsonObject
            val name = obj.get("name")?.asString ?: continue
            val fieldsJson = obj.get("fields_json")?.asString ?: continue
            val category = obj.get("category")?.asString ?: ""

            // Dedup: check if profile with same name+category exists locally
            val existing = dao.findByNameAndCategory(name, category)
            if (existing != null) {
                // Update existing — cloud data takes precedence
                dao.update(existing.copy(fieldsJson = fieldsJson))
                updated++
            } else {
                dao.insert(DataProfile(
                    name = name,
                    category = category,
                    fieldsJson = fieldsJson
                ))
                imported++
            }
        }
        Log.d(TAG, "Profiles downloaded: $imported new, $updated updated")
        imported + updated
    }

    // ========================== Full Sync ==========================

    /**
     * Full bidirectional sync: upload local changes then download cloud changes.
     */
    suspend fun fullSync() {
        _syncState.value = SyncState(isSyncing = true, message = "กำลังอัพโหลด...")
        try {
            val wfUp = uploadAllWorkflows()
            _syncState.value = _syncState.value.copy(message = "อัพโหลด workflow $wfUp รายการ...")

            val pfUp = uploadAllProfiles()
            _syncState.value = _syncState.value.copy(message = "กำลังดาวน์โหลด...")

            val wfDown = downloadAllWorkflows()
            val pfDown = downloadAllProfiles()

            _syncState.value = SyncState(
                lastSyncAt = System.currentTimeMillis(),
                message = "ซิงค์สำเร็จ (ส่ง: $wfUp wf + $pfUp pf | รับ: $wfDown wf + $pfDown pf)"
            )
            Log.d(TAG, "Full sync completed")
        } catch (e: Exception) {
            Log.e(TAG, "Full sync failed", e)
            _syncState.value = SyncState(error = e.message ?: "ซิงค์ไม่สำเร็จ")
        }
    }

    // ========================== Auto-Sync ==========================

    /**
     * Called on app startup — auto-sync if logged in and has active license.
     * Silently fails without showing errors to user.
     */
    suspend fun autoSyncIfEligible() {
        if (!canSync()) {
            Log.d(TAG, "Auto-sync skipped: not eligible")
            return
        }
        try {
            Log.d(TAG, "Auto-sync starting...")
            _syncState.value = SyncState(isSyncing = true, message = "ซิงค์อัตโนมัติ...")

            val wfUp = uploadAllWorkflows()
            val pfUp = uploadAllProfiles()
            val wfDown = downloadAllWorkflows()
            val pfDown = downloadAllProfiles()

            _syncState.value = SyncState(
                lastSyncAt = System.currentTimeMillis(),
                message = "ซิงค์อัตโนมัติสำเร็จ"
            )
            Log.d(TAG, "Auto-sync done: up($wfUp wf, $pfUp pf) down($wfDown wf, $pfDown pf)")
        } catch (e: Exception) {
            Log.w(TAG, "Auto-sync failed silently: ${e.message}")
            // Don't show auto-sync errors to user
            _syncState.value = SyncState()
        }
    }

    // ========================== Helpers ==========================

    private fun requireAuth() {
        if (CloudAuthManager.getToken().isNullOrEmpty()) {
            throw Exception("ยังไม่ได้เข้าสู่ระบบ")
        }
    }
}
