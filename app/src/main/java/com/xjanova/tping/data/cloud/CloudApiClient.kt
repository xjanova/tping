package com.xjanova.tping.data.cloud

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.xjanova.tping.data.license.CertPinning
import java.util.concurrent.TimeUnit

data class ApiResult(
    val success: Boolean,
    val data: JsonObject? = null,
    val message: String? = null
)

/**
 * HTTP client for xmanstudio cloud sync API.
 */
object CloudApiClient {

    private const val BASE_URL = "https://xman4289.com/api/v1"
    private const val PRODUCT_URL = "$BASE_URL/product/tping"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .certificatePinner(CertPinning.pinner)
        .build()

    // ===== Auth =====

    fun login(email: String, password: String, deviceName: String): ApiResult {
        val body = JsonObject().apply {
            addProperty("email", email)
            addProperty("password", password)
            addProperty("device_name", deviceName)
        }
        return post("$BASE_URL/auth/login", body)
    }

    fun register(name: String, email: String, password: String, deviceName: String): ApiResult {
        val body = JsonObject().apply {
            addProperty("name", name)
            addProperty("email", email)
            addProperty("password", password)
            addProperty("password_confirmation", password)
            addProperty("device_name", deviceName)
        }
        return post("$BASE_URL/auth/register", body)
    }

    fun deviceAuth(licenseKey: String, machineId: String): ApiResult {
        val body = JsonObject().apply {
            addProperty("license_key", licenseKey)
            addProperty("machine_id", machineId)
        }
        return post("$BASE_URL/auth/device", body)
    }

    fun logout(token: String): ApiResult {
        return authenticatedPost("$BASE_URL/auth/logout", JsonObject(), token)
    }

    // ===== Workflows =====

    fun getWorkflows(token: String): ApiResult {
        return authenticatedGet("$PRODUCT_URL/workflows", token)
    }

    fun uploadWorkflow(token: String, workflow: JsonObject): ApiResult {
        return authenticatedPost("$PRODUCT_URL/workflows", workflow, token)
    }

    fun updateWorkflow(token: String, id: Long, workflow: JsonObject): ApiResult {
        return authenticatedPut("$PRODUCT_URL/workflows/$id", workflow, token)
    }

    fun deleteWorkflow(token: String, id: Long): ApiResult {
        return authenticatedDelete("$PRODUCT_URL/workflows/$id", token)
    }

    fun bulkImportWorkflows(token: String, workflows: JsonArray): ApiResult {
        val body = JsonObject().apply { add("workflows", workflows) }
        return authenticatedPost("$PRODUCT_URL/workflows/import", body, token)
    }

    // ===== Data Profiles =====

    fun getDataProfiles(token: String): ApiResult {
        return authenticatedGet("$PRODUCT_URL/data-profiles", token)
    }

    fun uploadDataProfile(token: String, profile: JsonObject): ApiResult {
        return authenticatedPost("$PRODUCT_URL/data-profiles", profile, token)
    }

    fun deleteDataProfile(token: String, id: Long): ApiResult {
        return authenticatedDelete("$PRODUCT_URL/data-profiles/$id", token)
    }

    fun bulkImportProfiles(token: String, profiles: JsonArray): ApiResult {
        val body = JsonObject().apply { add("profiles", profiles) }
        return authenticatedPost("$PRODUCT_URL/data-profiles/import", body, token)
    }

    // ===== Internal HTTP helpers =====

    private fun post(url: String, body: JsonObject): ApiResult {
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .post(gson.toJson(body).toRequestBody(JSON_TYPE))
                .build()
            executeRequest(request)
        } catch (e: Exception) {
            ApiResult(false, message = e.message)
        }
    }

    private fun authenticatedPost(url: String, body: JsonObject, token: String): ApiResult {
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("Authorization", "Bearer $token")
                .post(gson.toJson(body).toRequestBody(JSON_TYPE))
                .build()
            executeRequest(request)
        } catch (e: Exception) {
            ApiResult(false, message = e.message)
        }
    }

    private fun authenticatedGet(url: String, token: String): ApiResult {
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            executeRequest(request)
        } catch (e: Exception) {
            ApiResult(false, message = e.message)
        }
    }

    private fun authenticatedPut(url: String, body: JsonObject, token: String): ApiResult {
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("Authorization", "Bearer $token")
                .put(gson.toJson(body).toRequestBody(JSON_TYPE))
                .build()
            executeRequest(request)
        } catch (e: Exception) {
            ApiResult(false, message = e.message)
        }
    }

    private fun authenticatedDelete(url: String, token: String): ApiResult {
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("Authorization", "Bearer $token")
                .delete()
                .build()
            executeRequest(request)
        } catch (e: Exception) {
            ApiResult(false, message = e.message)
        }
    }

    private fun executeRequest(request: Request): ApiResult {
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: "{}"
        return try {
            val json = gson.fromJson(responseBody, JsonObject::class.java)
            val success = json?.get("success")?.asBoolean ?: response.isSuccessful
            val message = json?.get("message")?.asString
            ApiResult(success, json, message)
        } catch (e: Exception) {
            ApiResult(response.isSuccessful, message = responseBody)
        }
    }
}
