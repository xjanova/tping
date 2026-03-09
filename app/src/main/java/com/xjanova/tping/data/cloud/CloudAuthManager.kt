package com.xjanova.tping.data.cloud

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class AuthState(
    val isLoggedIn: Boolean = false,
    val userId: Long = 0,
    val userName: String = "",
    val userEmail: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String = ""
)

/**
 * Manages xmanstudio cloud auth state using EncryptedSharedPreferences.
 */
object CloudAuthManager {

    private const val PREFS_NAME = "tping_cloud_auth"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_USER_EMAIL = "user_email"

    private val _authState = MutableStateFlow(AuthState())
    val authState: StateFlow<AuthState> = _authState

    private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        try {
            val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            prefs = EncryptedSharedPreferences.create(
                PREFS_NAME,
                masterKey,
                context.applicationContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        // Restore saved state
        val token = prefs?.getString(KEY_AUTH_TOKEN, null)
        if (!token.isNullOrEmpty()) {
            _authState.value = AuthState(
                isLoggedIn = true,
                userId = prefs?.getLong(KEY_USER_ID, 0) ?: 0,
                userName = prefs?.getString(KEY_USER_NAME, "") ?: "",
                userEmail = prefs?.getString(KEY_USER_EMAIL, "") ?: ""
            )
        }
    }

    suspend fun login(email: String, password: String): Result<AuthState> = withContext(Dispatchers.IO) {
        _authState.value = _authState.value.copy(isLoading = true, errorMessage = "")
        try {
            val result = CloudApiClient.login(email, password, "Tping Android")
            if (result.success && result.data != null) {
                val token = result.data.get("token")?.asString ?: ""
                val user = result.data.getAsJsonObject("user")
                val userId = user?.get("id")?.asLong ?: 0
                val name = user?.get("name")?.asString ?: ""
                val userEmail = user?.get("email")?.asString ?: ""

                saveAuth(token, userId, name, userEmail)
                val state = AuthState(isLoggedIn = true, userId = userId, userName = name, userEmail = userEmail)
                _authState.value = state
                Result.success(state)
            } else {
                val msg = result.message ?: "เข้าสู่ระบบไม่สำเร็จ"
                _authState.value = _authState.value.copy(isLoading = false, errorMessage = msg)
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            val msg = e.message ?: "เชื่อมต่อไม่ได้"
            _authState.value = _authState.value.copy(isLoading = false, errorMessage = msg)
            Result.failure(e)
        }
    }

    suspend fun register(name: String, email: String, password: String): Result<AuthState> = withContext(Dispatchers.IO) {
        _authState.value = _authState.value.copy(isLoading = true, errorMessage = "")
        try {
            val result = CloudApiClient.register(name, email, password, "Tping Android")
            if (result.success && result.data != null) {
                val token = result.data.get("token")?.asString ?: ""
                val user = result.data.getAsJsonObject("user")
                val userId = user?.get("id")?.asLong ?: 0
                val userName = user?.get("name")?.asString ?: ""
                val userEmail = user?.get("email")?.asString ?: ""

                saveAuth(token, userId, userName, userEmail)
                val state = AuthState(isLoggedIn = true, userId = userId, userName = userName, userEmail = userEmail)
                _authState.value = state
                Result.success(state)
            } else {
                val msg = result.message ?: "สมัครไม่สำเร็จ"
                _authState.value = _authState.value.copy(isLoading = false, errorMessage = msg)
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            val msg = e.message ?: "เชื่อมต่อไม่ได้"
            _authState.value = _authState.value.copy(isLoading = false, errorMessage = msg)
            Result.failure(e)
        }
    }

    /**
     * Auto-authenticate using license key + machine ID.
     * No email/password required — server creates device user automatically.
     * Returns true if auth succeeded, false otherwise.
     */
    suspend fun deviceAuth(licenseKey: String, machineId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("CloudAuth", "deviceAuth: key=${licenseKey.take(8)}..., machineId=${machineId.take(16)}...")
            val result = CloudApiClient.deviceAuth(licenseKey, machineId)
            if (result.success && result.data != null) {
                val token = result.data.get("token")?.asString ?: ""
                val user = result.data.getAsJsonObject("user")
                val userId = user?.get("id")?.asLong ?: 0
                val name = user?.get("name")?.asString ?: ""
                val email = user?.get("email")?.asString ?: ""

                saveAuth(token, userId, name, email)
                _authState.value = AuthState(isLoggedIn = true, userId = userId, userName = name, userEmail = email)
                android.util.Log.d("CloudAuth", "deviceAuth succeeded: userId=$userId")
                true
            } else {
                android.util.Log.w("CloudAuth", "deviceAuth failed: ${result.message}")
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("CloudAuth", "deviceAuth exception: ${e.message}")
            false
        }
    }

    fun logout() {
        prefs?.edit()?.clear()?.apply()
        _authState.value = AuthState()
    }

    fun isLoggedIn(): Boolean = _authState.value.isLoggedIn

    fun getToken(): String? = prefs?.getString(KEY_AUTH_TOKEN, null)

    private fun saveAuth(token: String, userId: Long, name: String, email: String) {
        prefs?.edit()?.apply {
            putString(KEY_AUTH_TOKEN, token)
            putLong(KEY_USER_ID, userId)
            putString(KEY_USER_NAME, name)
            putString(KEY_USER_EMAIL, email)
            apply()
        }
    }
}
