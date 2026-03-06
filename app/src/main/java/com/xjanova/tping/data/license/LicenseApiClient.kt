package com.xjanova.tping.data.license

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * HTTP client for xmanstudio license API.
 * Uses the generic product license API: /api/v1/product/{slug}/...
 */
object LicenseApiClient {

    private const val BASE_URL = "https://xmanstudio.com/api/v1/product/tping"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Register device on first app launch.
     */
    fun registerDevice(
        machineId: String,
        machineName: String,
        osVersion: String,
        appVersion: String,
        hardwareHash: String = ""
    ): ApiResult {
        val body = JsonObject().apply {
            addProperty("machine_id", machineId)
            addProperty("machine_name", machineName)
            addProperty("os_version", osVersion)
            addProperty("app_version", appVersion)
            if (hardwareHash.isNotEmpty()) addProperty("hardware_hash", hardwareHash)
        }
        return post("$BASE_URL/register-device", body)
    }

    /**
     * Start demo/trial period.
     */
    fun startDemo(
        machineId: String,
        hardwareHash: String = ""
    ): ApiResult {
        val body = JsonObject().apply {
            addProperty("machine_id", machineId)
            if (hardwareHash.isNotEmpty()) addProperty("hardware_hash", hardwareHash)
        }
        return post("$BASE_URL/demo", body)
    }

    /**
     * Check demo/trial status.
     */
    fun checkDemo(machineId: String): ApiResult {
        val body = JsonObject().apply {
            addProperty("machine_id", machineId)
        }
        return post("$BASE_URL/demo/check", body)
    }

    /**
     * Activate a license key on this machine.
     */
    fun activateLicense(
        licenseKey: String,
        machineId: String,
        machineFingerprint: String
    ): ApiResult {
        val body = JsonObject().apply {
            addProperty("license_key", licenseKey)
            addProperty("machine_id", machineId)
            addProperty("machine_fingerprint", machineFingerprint)
        }
        return post("$BASE_URL/activate", body)
    }

    /**
     * Validate an existing license.
     */
    fun validateLicense(licenseKey: String, machineId: String): ApiResult {
        val body = JsonObject().apply {
            addProperty("license_key", licenseKey)
            addProperty("machine_id", machineId)
        }
        return post("$BASE_URL/validate", body)
    }

    /**
     * Get license status info.
     */
    fun getLicenseStatus(licenseKey: String): ApiResult {
        return get("$BASE_URL/status/$licenseKey")
    }

    /**
     * Get pricing info.
     */
    fun getPricing(): ApiResult {
        return get("$BASE_URL/pricing")
    }

    // ---- Internal HTTP methods ----

    private fun post(url: String, body: JsonObject): ApiResult {
        return try {
            val requestBody = gson.toJson(body).toRequestBody(JSON_TYPE)
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Accept", "application/json")
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"
            val json = try { gson.fromJson(responseBody, JsonObject::class.java) } catch (_: Exception) { JsonObject() }
            ApiResult(
                success = response.isSuccessful,
                statusCode = response.code,
                data = json,
                message = json.get("message")?.asString ?: ""
            )
        } catch (e: Exception) {
            ApiResult(success = false, statusCode = 0, message = "เชื่อมต่อไม่ได้: ${e.localizedMessage}")
        }
    }

    private fun get(url: String): ApiResult {
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("Accept", "application/json")
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"
            val json = try { gson.fromJson(responseBody, JsonObject::class.java) } catch (_: Exception) { JsonObject() }
            ApiResult(
                success = response.isSuccessful,
                statusCode = response.code,
                data = json,
                message = json.get("message")?.asString ?: ""
            )
        } catch (e: Exception) {
            ApiResult(success = false, statusCode = 0, message = "เชื่อมต่อไม่ได้: ${e.localizedMessage}")
        }
    }
}

data class ApiResult(
    val success: Boolean,
    val statusCode: Int = 0,
    val data: JsonObject = JsonObject(),
    val message: String = ""
)
