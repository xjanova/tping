package com.xjanova.tping.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.xjanova.tping.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val hasUpdate: Boolean = false,
    val latestVersion: String = "",
    val currentVersion: String = BuildConfig.VERSION_NAME,
    val downloadUrl: String = "",
    val releaseNotes: String = "",
    val fileName: String = "",
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0, // 0-100
    val error: String = ""
)

/**
 * Checks for app updates from xman4289.com.
 *
 * API: GET https://xman4289.com/api/v1/product/tping/update/check?current_version=X.Y.Z
 * Response: {
 *   "has_update": true,
 *   "latest_version": "1.2.47",
 *   "download_url": "https://xman4289.com/downloads/tping/Tping-v1.2.47.apk",
 *   "changelog": "..."
 * }
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val UPDATE_CHECK_URL = "https://xman4289.com/api/v1/product/tping/update/check"

    private const val PREFS_NAME = "tping_update"
    private const val KEY_LAST_CHECK = "last_check_at"
    private const val KEY_DISMISSED_VERSION = "dismissed_version"

    // Check interval: don't check more often than every 6 hours
    private const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

    private val _updateInfo = MutableStateFlow(UpdateInfo())
    val updateInfo: StateFlow<UpdateInfo> = _updateInfo

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val gson = Gson()

    /**
     * Dismiss a specific version update notification.
     */
    fun dismissVersion(context: Context, version: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_DISMISSED_VERSION, version).apply()
        _updateInfo.value = _updateInfo.value.copy(hasUpdate = false)
    }

    private fun getDismissedVersion(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DISMISSED_VERSION, null)
    }

    /**
     * Check for updates from xman4289.com.
     * If shouldThrottle is true, will skip if checked recently.
     */
    suspend fun checkForUpdate(
        context: Context,
        shouldThrottle: Boolean = false
    ) = withContext(Dispatchers.IO) {
        // Throttle check
        if (shouldThrottle) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0)
            if (System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) {
                Log.d(TAG, "Skipping update check — checked recently")
                return@withContext
            }
        }

        _updateInfo.value = _updateInfo.value.copy(isChecking = true, error = "")

        try {
            val currentVersion = BuildConfig.VERSION_NAME
            val url = "$UPDATE_CHECK_URL?current_version=$currentVersion"

            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "Tping-Android/$currentVersion")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                _updateInfo.value = UpdateInfo(error = "เช็คอัพเดทไม่ได้: HTTP ${response.code}")
                return@withContext
            }

            val json = gson.fromJson(body, JsonObject::class.java)
            val hasUpdate = json.get("has_update")?.asBoolean ?: false
            val latestVersion = json.get("latest_version")?.asString ?: ""
            val downloadUrl = json.get("download_url")?.asString ?: ""
            val changelog = json.get("changelog")?.asString ?: ""

            // Save last check time
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

            // Check if this version was dismissed
            val dismissed = getDismissedVersion(context)
            val showUpdate = hasUpdate && latestVersion != dismissed

            _updateInfo.value = UpdateInfo(
                hasUpdate = showUpdate,
                latestVersion = latestVersion,
                currentVersion = currentVersion,
                downloadUrl = downloadUrl,
                releaseNotes = changelog,
                fileName = "Tping-v${latestVersion}.apk"
            )

            Log.d(TAG, "Update check: current=$currentVersion latest=$latestVersion hasUpdate=$hasUpdate")

        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            _updateInfo.value = UpdateInfo(error = "เช็คอัพเดทไม่ได้: ${e.message}")
        }
    }

    /**
     * Download APK from xman4289.com and trigger install.
     */
    suspend fun downloadAndInstall(context: Context) = withContext(Dispatchers.IO) {
        val info = _updateInfo.value
        if (info.downloadUrl.isBlank()) {
            _updateInfo.value = info.copy(error = "ไม่มีลิงก์ดาวน์โหลด")
            return@withContext
        }

        _updateInfo.value = info.copy(isDownloading = true, downloadProgress = 0, error = "")

        try {
            val request = Request.Builder()
                .url(info.downloadUrl)
                .addHeader("User-Agent", "Tping-Android/${BuildConfig.VERSION_NAME}")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                _updateInfo.value = _updateInfo.value.copy(
                    isDownloading = false,
                    error = "ดาวน์โหลดไม่สำเร็จ: HTTP ${response.code}"
                )
                return@withContext
            }

            val contentLength = response.body?.contentLength() ?: -1L
            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            val fileName = info.fileName.ifBlank { "Tping-v${info.latestVersion}.apk" }
            val apkFile = File(downloadDir, fileName)

            // Delete existing file if present
            if (apkFile.exists()) apkFile.delete()

            response.body?.byteStream()?.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Long = 0
                    var read: Int

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        if (contentLength > 0) {
                            val progress = (bytesRead * 100 / contentLength).toInt()
                            _updateInfo.value = _updateInfo.value.copy(downloadProgress = progress)
                        }
                    }
                    output.flush()
                }
            }

            Log.d(TAG, "APK downloaded: ${apkFile.absolutePath} (${apkFile.length()} bytes)")

            _updateInfo.value = _updateInfo.value.copy(isDownloading = false, downloadProgress = 100)

            // Trigger install
            installApk(context, apkFile)

        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            _updateInfo.value = _updateInfo.value.copy(
                isDownloading = false,
                error = "ดาวน์โหลดผิดพลาด: ${e.message}"
            )
        }
    }

    /**
     * Open the APK file for installation using FileProvider.
     */
    private fun installApk(context: Context, apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Install APK failed", e)
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "Install APK fallback failed", e2)
                _updateInfo.value = _updateInfo.value.copy(error = "เปิดตัวติดตั้งไม่ได้")
            }
        }
    }
}
