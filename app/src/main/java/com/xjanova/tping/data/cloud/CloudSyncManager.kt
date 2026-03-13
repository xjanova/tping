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
 * Uses license-based deviceAuth for automatic cloud access (no email/password needed).
 * - Auto deviceAuth when license is active but not logged in
 * - Token refresh via deviceAuth on 401
 * - Download deduplication (checks by name before inserting)
 * - Auto-sync on startup when license is active
 * - Bidirectional full sync (upload then download)
 */
object CloudSyncManager {

    private const val TAG = "CloudSync"
    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState
    private val gson = Gson()

    /**
     * Check if user can sync — logged in, or has active license (can auto-auth).
     */
    fun canSync(): Boolean {
        if (CloudAuthManager.isLoggedIn()) return true
        val status = LicenseManager.state.value.status
        return status == LicenseStatus.ACTIVE
    }

    // ========================== Upload ==========================

    suspend fun uploadAllWorkflows(): Int = withContext(Dispatchers.IO) {
        val token = requireAuthToken()
        val db = TpingApplication.instance.database
        val workflows = db.workflowDao().getAll().first()
        if (workflows.isEmpty()) {
            Log.d(TAG, "No workflows to upload")
            return@withContext 0
        }

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

        Log.d(TAG, "Uploading ${workflows.size} workflows...")
        var result = CloudApiClient.bulkImportWorkflows(token, jsonArray)

        // Retry once with fresh token on 401
        if (result.httpCode == 401) {
            Log.w(TAG, "Upload workflows got 401, refreshing token...")
            val newToken = refreshAuth() ?: throw Exception("Token หมดอายุ ไม่สามารถเข้าสู่ระบบใหม่ได้")
            result = CloudApiClient.bulkImportWorkflows(newToken, jsonArray)
        }

        if (!result.success) throw Exception(result.message ?: "อัพโหลด workflow ไม่สำเร็จ (HTTP ${result.httpCode})")
        val imported = result.data?.getAsJsonObject("data")?.get("imported")?.asInt
        Log.d(TAG, "Upload workflows result: imported=$imported")
        imported ?: throw Exception("Server returned unexpected response format")
    }

    suspend fun uploadAllProfiles(): Int = withContext(Dispatchers.IO) {
        val token = requireAuthToken()
        val db = TpingApplication.instance.database
        val profiles = db.dataProfileDao().getAll().first()
        if (profiles.isEmpty()) {
            Log.d(TAG, "No profiles to upload")
            return@withContext 0
        }

        val jsonArray = JsonArray()
        for (p in profiles) {
            jsonArray.add(JsonObject().apply {
                addProperty("name", p.name)
                addProperty("category", p.category)
                addProperty("fields_json", p.fieldsJson)
                addProperty("local_id", p.id)
            })
        }

        Log.d(TAG, "Uploading ${profiles.size} profiles...")
        var result = CloudApiClient.bulkImportProfiles(token, jsonArray)

        // Retry once with fresh token on 401
        if (result.httpCode == 401) {
            Log.w(TAG, "Upload profiles got 401, refreshing token...")
            val newToken = refreshAuth() ?: throw Exception("Token หมดอายุ ไม่สามารถเข้าสู่ระบบใหม่ได้")
            result = CloudApiClient.bulkImportProfiles(newToken, jsonArray)
        }

        if (!result.success) throw Exception(result.message ?: "อัพโหลด profile ไม่สำเร็จ (HTTP ${result.httpCode})")
        val imported = result.data?.getAsJsonObject("data")?.get("imported")?.asInt
        Log.d(TAG, "Upload profiles result: imported=$imported")
        imported ?: throw Exception("Server returned unexpected response format")
    }

    // ========================== Download with Dedup ==========================

    suspend fun downloadAllWorkflows(): Int = withContext(Dispatchers.IO) {
        var token = requireAuthToken()
        val db = TpingApplication.instance.database
        val dao = db.workflowDao()

        Log.d(TAG, "Downloading workflows...")
        var result = CloudApiClient.getWorkflows(token)

        // Retry once with fresh token on 401
        if (result.httpCode == 401) {
            Log.w(TAG, "Download workflows got 401, refreshing token...")
            token = refreshAuth() ?: throw Exception("Token หมดอายุ ไม่สามารถเข้าสู่ระบบใหม่ได้")
            result = CloudApiClient.getWorkflows(token)
        }

        if (!result.success) throw Exception(result.message ?: "ดาวน์โหลด workflow ไม่สำเร็จ (HTTP ${result.httpCode})")

        val dataArray = result.data?.getAsJsonObject("data")?.getAsJsonArray("data")
        if (dataArray == null) {
            Log.d(TAG, "No workflows on server")
            return@withContext 0
        }

        var imported = 0
        var updated = 0
        for (item in dataArray) {
            val obj = item.asJsonObject
            val name = obj.get("name")?.asString ?: continue
            val stepsJson = obj.get("steps_json")?.asString ?: continue
            val pkg = obj.get("target_app_package")?.asString ?: ""
            val appName = obj.get("target_app_name")?.asString ?: ""

            val existing = dao.findByNameAndPackage(name, pkg)
            if (existing != null) {
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
        var token = requireAuthToken()
        val db = TpingApplication.instance.database
        val dao = db.dataProfileDao()

        Log.d(TAG, "Downloading profiles...")
        var result = CloudApiClient.getDataProfiles(token)

        // Retry once with fresh token on 401
        if (result.httpCode == 401) {
            Log.w(TAG, "Download profiles got 401, refreshing token...")
            token = refreshAuth() ?: throw Exception("Token หมดอายุ ไม่สามารถเข้าสู่ระบบใหม่ได้")
            result = CloudApiClient.getDataProfiles(token)
        }

        if (!result.success) throw Exception(result.message ?: "ดาวน์โหลด profile ไม่สำเร็จ (HTTP ${result.httpCode})")

        val dataArray = result.data?.getAsJsonObject("data")?.getAsJsonArray("data")
        if (dataArray == null) {
            Log.d(TAG, "No profiles on server")
            return@withContext 0
        }

        var imported = 0
        var updated = 0
        for (item in dataArray) {
            val obj = item.asJsonObject
            val name = obj.get("name")?.asString ?: continue
            val fieldsJson = obj.get("fields_json")?.asString ?: continue
            val category = obj.get("category")?.asString ?: ""

            val existing = dao.findByNameAndCategory(name, category)
            if (existing != null) {
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
     * Auto-authenticates via deviceAuth if not logged in but license is active.
     */
    suspend fun fullSync() {
        _syncState.value = SyncState(isSyncing = true, message = "กำลังเชื่อมต่อ...")
        try {
            if (!CloudAuthManager.isLoggedIn()) {
                // Try deviceAuth if license is active
                if (!tryDeviceAuth()) {
                    _syncState.value = SyncState(error = "กรุณาเข้าสู่ระบบก่อนซิงค์")
                    return
                }
            }
            _syncState.value = _syncState.value.copy(message = "กำลังอัพโหลด Workflow...")
            val wfUp = uploadAllWorkflows()
            _syncState.value = _syncState.value.copy(message = "กำลังอัพโหลด Profile...")

            val pfUp = uploadAllProfiles()
            _syncState.value = _syncState.value.copy(message = "กำลังดาวน์โหลด...")

            val wfDown = downloadAllWorkflows()
            val pfDown = downloadAllProfiles()

            _syncState.value = SyncState(
                lastSyncAt = System.currentTimeMillis(),
                message = "ซิงค์สำเร็จ (ส่ง: $wfUp wf + $pfUp pf | รับ: $wfDown wf + $pfDown pf)"
            )
            Log.d(TAG, "Full sync completed: up($wfUp wf, $pfUp pf) down($wfDown wf, $pfDown pf)")
        } catch (e: Exception) {
            Log.e(TAG, "Full sync failed", e)
            _syncState.value = SyncState(error = "ซิงค์ไม่สำเร็จ: ${e.message}")
        }
    }

    // ========================== Auto-Sync ==========================

    /**
     * Called on app startup — auto-sync if user is logged in or has active license.
     * Tries deviceAuth automatically if not logged in but license is active.
     * Silently fails without showing errors to user.
     */
    suspend fun autoSyncIfEligible() {
        if (!CloudAuthManager.isLoggedIn()) {
            // Try deviceAuth if license is active
            if (!tryDeviceAuth()) {
                Log.d(TAG, "Auto-sync skipped: not logged in and no active license")
                return
            }
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
            _syncState.value = SyncState()
        }
    }

    // ========================== Real-time Sync ==========================

    /**
     * Trigger a background sync after a local data change (add/edit/delete).
     * Silently uploads all local data and removes server-side items that no longer exist locally.
     */
    suspend fun syncAfterLocalChange() {
        if (!canSync()) return
        withContext(Dispatchers.IO) {
            try {
                if (!CloudAuthManager.isLoggedIn() && !tryDeviceAuth()) return@withContext

                // Upload all local data (upsert)
                uploadAllWorkflows()
                uploadAllProfiles()

                // Reconcile deletions: remove server items not present locally
                reconcileWorkflowDeletions()
                reconcileProfileDeletions()

                Log.d(TAG, "Real-time sync completed")
            } catch (e: Exception) {
                Log.w(TAG, "Real-time sync failed: ${e.message}")
            }
        }
    }

    /**
     * Delete workflows from server that no longer exist locally.
     */
    private suspend fun reconcileWorkflowDeletions() {
        val token = CloudAuthManager.getToken() ?: return
        val db = TpingApplication.instance.database
        val localWorkflows = db.workflowDao().getAllOnce()

        val result = CloudApiClient.getWorkflows(token)
        if (!result.success) return

        val serverArray = result.data?.getAsJsonObject("data")?.getAsJsonArray("data") ?: return

        for (item in serverArray) {
            val obj = item.asJsonObject
            val serverId = obj.get("id")?.asLong ?: continue
            val name = obj.get("name")?.asString ?: continue
            val pkg = obj.get("target_app_package")?.asString ?: ""

            val existsLocally = localWorkflows.any { it.name == name && it.targetAppPackage == pkg }
            if (!existsLocally) {
                Log.d(TAG, "Deleting server workflow $serverId ($name)")
                CloudApiClient.deleteWorkflow(token, serverId)
            }
        }
    }

    /**
     * Delete profiles from server that no longer exist locally.
     */
    private suspend fun reconcileProfileDeletions() {
        val token = CloudAuthManager.getToken() ?: return
        val db = TpingApplication.instance.database
        val localProfiles = db.dataProfileDao().getAllOnce()

        val result = CloudApiClient.getDataProfiles(token)
        if (!result.success) return

        val serverArray = result.data?.getAsJsonObject("data")?.getAsJsonArray("data") ?: return

        for (item in serverArray) {
            val obj = item.asJsonObject
            val serverId = obj.get("id")?.asLong ?: continue
            val name = obj.get("name")?.asString ?: continue
            val category = obj.get("category")?.asString ?: ""

            val existsLocally = localProfiles.any { it.name == name && it.category == category }
            if (!existsLocally) {
                Log.d(TAG, "Deleting server profile $serverId ($name)")
                CloudApiClient.deleteDataProfile(token, serverId)
            }
        }
    }

    // ========================== Helpers ==========================

    private fun requireAuthToken(): String {
        val token = CloudAuthManager.getToken()
        if (token.isNullOrEmpty()) {
            throw Exception("ยังไม่ได้เข้าสู่ระบบ Cloud")
        }
        return token
    }

    /**
     * Token expired — try deviceAuth to get a fresh token.
     * Falls back to logout if deviceAuth fails.
     */
    private suspend fun refreshAuth(): String? {
        Log.w(TAG, "Auth token expired — trying deviceAuth refresh...")
        if (tryDeviceAuth()) {
            return CloudAuthManager.getToken()
        }
        Log.w(TAG, "deviceAuth refresh failed — logging out")
        CloudAuthManager.logout()
        return null
    }

    /**
     * Try to authenticate via deviceAuth using the active license.
     * Returns true if successful, false if no license or auth failed.
     */
    private suspend fun tryDeviceAuth(): Boolean {
        val licenseState = LicenseManager.state.value
        if (licenseState.status != LicenseStatus.ACTIVE) {
            return false
        }
        val licenseKey = LicenseManager.getLicenseKey()
        val machineId = LicenseManager.getMachineId()
        if (licenseKey.isNullOrEmpty() || machineId.isNullOrEmpty()) {
            Log.d(TAG, "deviceAuth skipped: missing license key or machine ID")
            return false
        }
        return try {
            Log.d(TAG, "Attempting deviceAuth...")
            CloudAuthManager.deviceAuth(licenseKey, machineId)
            Log.d(TAG, "deviceAuth succeeded")
            true
        } catch (e: Exception) {
            Log.w(TAG, "deviceAuth failed: ${e.message}")
            false
        }
    }
}
