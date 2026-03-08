package com.xjanova.tping.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.JsonArray
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
 * Checks for app updates from GitHub Releases.
 *
 * Supports both public and private repositories:
 * - Public: uses unauthenticated GitHub API
 * - Private: uses GitHub Personal Access Token (PAT)
 *
 * GitHub repo: xjanova/tping
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val GITHUB_OWNER = "xjanova"
    private const val GITHUB_REPO = "tping"
    private const val GITHUB_API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    private const val PREFS_NAME = "tping_update"
    private const val KEY_GITHUB_TOKEN = "github_pat"
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
     * Save a GitHub PAT for accessing private repos.
     */
    fun setGitHubToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GITHUB_TOKEN, token.trim())
            .apply()
        Log.d(TAG, "GitHub token saved (length: ${token.trim().length})")
    }

    fun getGitHubToken(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GITHUB_TOKEN, null)
    }

    fun clearGitHubToken(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_GITHUB_TOKEN).apply()
    }

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
     * Check for updates from GitHub releases.
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
            val token = getGitHubToken(context)

            val requestBuilder = Request.Builder()
                .url(GITHUB_API_URL)
                .addHeader("Accept", "application/vnd.github.v3+json")
                .addHeader("User-Agent", "Tping-Android/${BuildConfig.VERSION_NAME}")

            // Add auth token for private repos
            if (!token.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "token $token")
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                val errorMsg = when (response.code) {
                    401 -> "GitHub Token ไม่ถูกต้อง"
                    403 -> if (token.isNullOrBlank()) "Repo เป็น private — ต้องใส่ GitHub Token" else "Token หมดสิทธิ์หรือ rate limit"
                    404 -> if (token.isNullOrBlank()) "ไม่พบ release (Repo อาจเป็น private)" else "ไม่พบ release"
                    else -> "HTTP ${response.code}"
                }
                _updateInfo.value = UpdateInfo(error = errorMsg)
                return@withContext
            }

            val json = gson.fromJson(body, JsonObject::class.java)
            val tagName = json.get("tag_name")?.asString ?: ""
            val latestVersion = tagName.removePrefix("v")
            val releaseBody = json.get("body")?.asString ?: ""

            // Find APK asset
            val assets = json.getAsJsonArray("assets") ?: JsonArray()
            var apkUrl = ""
            var apkName = ""
            for (asset in assets) {
                val assetObj = asset.asJsonObject
                val name = assetObj.get("name")?.asString ?: ""
                if (name.endsWith(".apk")) {
                    // For private repos, we need the browser_download_url with token
                    // or the url (API endpoint) with Accept header
                    apkUrl = assetObj.get("browser_download_url")?.asString ?: ""
                    apkName = name
                    break
                }
            }

            // Compare versions
            val hasUpdate = isNewerVersion(latestVersion, BuildConfig.VERSION_NAME)

            // Save last check time
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

            // Check if this version was dismissed
            val dismissed = getDismissedVersion(context)
            val showUpdate = hasUpdate && latestVersion != dismissed

            _updateInfo.value = UpdateInfo(
                hasUpdate = showUpdate,
                latestVersion = latestVersion,
                currentVersion = BuildConfig.VERSION_NAME,
                downloadUrl = apkUrl,
                releaseNotes = releaseBody,
                fileName = apkName
            )

            Log.d(TAG, "Update check: current=${BuildConfig.VERSION_NAME} latest=$latestVersion hasUpdate=$hasUpdate apk=$apkName")

        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            _updateInfo.value = UpdateInfo(error = "เช็คอัพเดทไม่ได้: ${e.message}")
        }
    }

    /**
     * Download APK and trigger install.
     * For private repos, uses the GitHub token for authentication.
     */
    suspend fun downloadAndInstall(context: Context) = withContext(Dispatchers.IO) {
        val info = _updateInfo.value
        if (info.downloadUrl.isBlank()) {
            _updateInfo.value = info.copy(error = "ไม่มีลิงก์ดาวน์โหลด")
            return@withContext
        }

        _updateInfo.value = info.copy(isDownloading = true, downloadProgress = 0, error = "")

        try {
            val token = getGitHubToken(context)

            val requestBuilder = Request.Builder()
                .url(info.downloadUrl)
                .addHeader("User-Agent", "Tping-Android/${BuildConfig.VERSION_NAME}")

            // For private repo assets, need token auth
            if (!token.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "token $token")
                requestBuilder.addHeader("Accept", "application/octet-stream")
            }

            val response = client.newCall(requestBuilder.build()).execute()

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
            // Fallback: try opening directly
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

    /**
     * Semantic version comparison.
     * Returns true if `latest` is newer than `current`.
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        try {
            val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }

            for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Version comparison failed: $latest vs $current", e)
        }
        return false
    }
}
