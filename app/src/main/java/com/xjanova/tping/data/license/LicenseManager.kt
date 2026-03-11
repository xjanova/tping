package com.xjanova.tping.data.license

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.xjanova.tping.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Central license state manager for Tping.
 * Tries server-based trial/license verification first.
 * Falls back to LOCAL trial (24h) when server is unreachable — app must always be usable.
 */
object LicenseManager {

    private const val TAG = "LicenseManager"
    private const val PREFS_NAME = "tping_license"
    private const val KEY_LICENSE_KEY = "license_key"
    private const val KEY_LICENSE_TYPE = "license_type"
    private const val KEY_LICENSE_STATUS = "license_status"
    private const val KEY_LICENSE_EXPIRES_AT = "license_expires_at"
    private const val KEY_DEVICE_REGISTERED = "device_registered"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_MACHINE_ID = "machine_id"
    private const val KEY_FIRST_LAUNCH_AT = "first_launch_at"
    private const val KEY_LOCAL_TRIAL_USED = "local_trial_used"
    private const val KEY_LAST_VERIFIED_AT = "last_verified_at"
    private const val KEY_SERVER_TIME_DRIFT_MS = "server_time_drift_ms"
    private const val KEY_CLOCK_TAMPER_COUNT = "clock_tamper_count"

    // Local trial: 24 hours (fallback when server unreachable)
    private const val LOCAL_TRIAL_DURATION_MS = 24L * 60 * 60 * 1000

    // Max allowed clock drift before flagging (5 minutes)
    private const val MAX_CLOCK_DRIFT_MS = 5L * 60 * 1000

    // Max clock tamper events before forcing server-only mode
    private const val MAX_TAMPER_COUNT = 3

    // Purchase URL (base for plan-specific URLs)
    private const val PURCHASE_BASE_URL = "https://xman4289.com/tping/buy"

    private val _state = MutableStateFlow(LicenseState())
    val state: StateFlow<LicenseState> = _state

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    /**
     * Get machine ID for server API calls.
     * Uses hardware hash (SHA-256, 64 chars) to satisfy server min:32 requirement.
     */
    private fun getMachineId(context: Context): String {
        return try {
            DeviceManager.getHardwareHash(context)
        } catch (e: Exception) {
            Log.e(TAG, "getMachineId failed", e)
            "unknown-device"
        }
    }

    /**
     * Get raw DRM ID (Widevine) for server-side device lookup.
     * This is stable across reinstall & factory reset, sent as secondary identifier.
     */
    private fun getDrmId(): String {
        return try {
            DeviceManager.getDrmId() ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "getDrmId failed", e)
            ""
        }
    }

    /**
     * Initialize on app start. Must be called from MainActivity LaunchedEffect.
     * NEVER throws — all errors are caught and handled gracefully.
     */
    suspend fun initialize(context: Context) {
        try {
            appContext = context.applicationContext
            prefs = getPrefs(context)

            val displayId = try {
                DeviceManager.getDeviceId(context)
            } catch (e: Exception) {
                Log.e(TAG, "getDeviceId failed", e)
                "unknown"
            }

            val machineId = getMachineId(context)

            // Read old machineId BEFORE overwriting — needed for HWID migration detection
            val oldMachineId = try { prefs?.getString(KEY_MACHINE_ID, "") ?: "" } catch (_: Exception) { "" }

            try {
                prefs?.edit()
                    ?.putString(KEY_DEVICE_ID, displayId)
                    ?.putString(KEY_MACHINE_ID, machineId)
                    ?.apply()
            } catch (e: Exception) {
                Log.e(TAG, "prefs write failed", e)
            }

            // Set loading state
            _state.value = LicenseState(
                status = LicenseStatus.CHECKING,
                deviceId = displayId,
                isLoading = true
            )

            // Anti-clock-tampering: check if time went backward
            detectClockTampering()

            // Runtime integrity check (APK signature, root, Frida, debugger)
            val integrityResult = IntegrityChecker.runAllChecks(context)
            if (integrityResult.hasAnyFlag) {
                try {
                    com.xjanova.tping.data.diagnostic.DiagnosticReporter.logEvent(
                        "integrity",
                        "Integrity flags: ${integrityResult.details.joinToString(", ")}",
                        "tampered=${integrityResult.isApkTampered} " +
                                "root=${integrityResult.isRooted} " +
                                "frida=${integrityResult.isFridaDetected} " +
                                "debugger=${integrityResult.isDebuggerAttached}"
                    )
                } catch (_: Exception) {}
            }
            if (integrityResult.shouldBlockLicense) {
                Log.w(TAG, "Integrity check FAILED — blocking license")
                _state.value = LicenseState(
                    status = LicenseStatus.EXPIRED,
                    deviceId = displayId,
                    isLoading = false,
                    errorMessage = "ตรวจพบการแก้ไขแอป กรุณาดาวน์โหลดจากแหล่งที่ถูกต้อง"
                )
                return
            }

            val licenseKey = try {
                prefs?.getString(KEY_LICENSE_KEY, "") ?: ""
            } catch (e: Exception) {
                Log.e(TAG, "read license key failed", e)
                ""
            }

            // HWID migration: detect if hardware hash changed (e.g. after update from ANDROID_ID to MediaDrm)
            val hwidChanged = oldMachineId.isNotEmpty() && oldMachineId != machineId
            if (hwidChanged) {
                Log.i(TAG, "HWID migration detected: old=${oldMachineId.take(16)}..., new=${machineId.take(16)}...")
            }

            // Diagnostic: log the full initialization path
            val initPath = when {
                licenseKey.isNotEmpty() && hwidChanged -> "migrate-hwid"
                licenseKey.isNotEmpty() -> "validate-paid"
                else -> "check-machine"
            }
            try {
                com.xjanova.tping.data.diagnostic.DiagnosticReporter.logEvent(
                    "license", "init path=$initPath",
                    "hasKey=${licenseKey.isNotEmpty()}, hwidChanged=$hwidChanged, " +
                        "oldHwid=${oldMachineId.take(16).ifEmpty { "empty" }}, " +
                        "newHwid=${machineId.take(16)}, machineIdLen=${machineId.length}"
                )
            } catch (_: Exception) {}

            if (licenseKey.isNotEmpty()) {
                if (hwidChanged) {
                    // HWID changed but we have a license key — re-activate to bind new HWID
                    Log.i(TAG, "Re-activating license with new HWID")
                    migrateHwid(context, licenseKey, machineId, displayId)
                } else {
                    // Has license key — validate with server (paid license)
                    validatePaidLicense(context, licenseKey, machineId, displayId)
                }
            } else {
                // No saved key — check if this machine already has a license on server (HWID auto-check)
                val found = checkMachineForExistingLicense(context, machineId, displayId)
                if (!found) {
                    // No existing license — check demo/trial from server, fallback to local trial
                    checkOrStartDemo(context, machineId, displayId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "initialize failed completely", e)
            // CRITICAL: Never block the app. Use local trial as absolute fallback.
            useLocalTrialFallback(context)
        }
    }

    /**
     * Validate paid license (with offline cached fallback).
     * If validation fails due to machine_id mismatch, auto re-activate to rebind HWID.
     */
    private suspend fun validatePaidLicense(
        context: Context,
        licenseKey: String,
        machineId: String,
        displayId: String
    ) {
        try {
            val result = withContext(Dispatchers.IO) {
                LicenseApiClient.validateLicense(licenseKey, machineId)
            }
            if (result.success) {
                val valid = result.data.get("is_valid")?.asBoolean ?: false
                val data = result.data.getAsJsonObject("data") ?: result.data
                val type = data.get("license_type")?.asString ?: ""
                val expiresAtStr = data.get("expires_at")?.asString
                val daysRemaining = data.get("days_remaining")?.asInt ?: 0
                val expiresAt = parseIsoTimestamp(expiresAtStr)

                try {
                    com.xjanova.tping.data.diagnostic.DiagnosticReporter.logEvent(
                        "license", "validate result",
                        "valid=$valid, type=$type, status=${result.statusCode}, msg=${result.message}"
                    )
                } catch (_: Exception) {}

                if (valid) {
                    prefs?.edit()
                        ?.putString(KEY_LICENSE_TYPE, type)
                        ?.putString(KEY_LICENSE_STATUS, "active")
                        ?.putLong(KEY_LICENSE_EXPIRES_AT, expiresAt)
                        ?.apply()

                    _state.value = LicenseState(
                        status = LicenseStatus.ACTIVE,
                        licenseType = type,
                        expiresAt = expiresAt,
                        remainingDays = daysRemaining,
                        deviceId = displayId,
                        isLoading = false
                    )
                } else {
                    // Validate returned is_valid=false — likely machine_id mismatch.
                    // Try re-activating to rebind HWID (handles migration from ANDROID_ID → MediaDrm)
                    Log.w(TAG, "Validate failed: ${result.message}, attempting re-activate...")
                    val reActivated = tryReActivate(context, licenseKey, machineId, displayId)
                    if (!reActivated) {
                        prefs?.edit()?.putString(KEY_LICENSE_STATUS, "expired")?.apply()
                        _state.value = LicenseState(
                            status = LicenseStatus.EXPIRED,
                            licenseType = type,
                            deviceId = displayId,
                            isLoading = false,
                            errorMessage = result.message.ifEmpty { "License หมดอายุ" }
                        )
                    }
                }
            } else {
                // Server returned error — try re-activate, then fall back to cached state
                Log.w(TAG, "Validate request failed: ${result.message}, attempting re-activate...")
                val reActivated = tryReActivate(context, licenseKey, machineId, displayId)
                if (!reActivated) {
                    useCachedState(displayId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "validatePaidLicense failed", e)
            // Network error — use cached state (paid license only)
            useCachedState(displayId)
        }
    }

    /**
     * Try to re-activate license with current machineId.
     * Used when validate fails (possibly due to HWID migration).
     * Returns true if re-activation succeeded.
     */
    private suspend fun tryReActivate(
        context: Context,
        licenseKey: String,
        machineId: String,
        displayId: String
    ): Boolean {
        return try {
            val hardwareHash = try { DeviceManager.getHardwareHash(context) } catch (_: Exception) { "" }
            val drmId = getDrmId()
            val androidId = try { DeviceManager.getDeviceId(context) } catch (_: Exception) { "" }
            val result = withContext(Dispatchers.IO) {
                LicenseApiClient.activateLicense(licenseKey, machineId, hardwareHash, drmId, androidId)
            }
            if (result.success) {
                val data = result.data.getAsJsonObject("data") ?: result.data
                val type = data.get("license_type")?.asString ?: "unknown"
                val expiresAtStr = data.get("expires_at")?.asString
                val expiresAt = parseIsoTimestamp(expiresAtStr)
                val daysRemaining = data.get("days_remaining")?.asInt ?: 0

                prefs?.edit()
                    ?.putString(KEY_LICENSE_TYPE, type)
                    ?.putString(KEY_LICENSE_STATUS, "active")
                    ?.putLong(KEY_LICENSE_EXPIRES_AT, expiresAt)
                    ?.apply()

                _state.value = LicenseState(
                    status = LicenseStatus.ACTIVE,
                    licenseType = type,
                    expiresAt = expiresAt,
                    remainingDays = daysRemaining,
                    deviceId = displayId,
                    isLoading = false
                )
                Log.i(TAG, "Re-activate succeeded — HWID rebound to current device")
                try {
                    com.xjanova.tping.data.diagnostic.DiagnosticReporter.logEvent(
                        "license", "tryReActivate OK", "type=$type, days=$daysRemaining"
                    )
                } catch (_: Exception) {}
                true
            } else {
                Log.w(TAG, "Re-activate failed: ${result.message}")
                try {
                    com.xjanova.tping.data.diagnostic.DiagnosticReporter.logEvent(
                        "license", "tryReActivate FAILED",
                        "status=${result.statusCode}, msg=${result.message}"
                    )
                } catch (_: Exception) {}
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "tryReActivate failed", e)
            try {
                com.xjanova.tping.data.diagnostic.DiagnosticReporter.logEvent(
                    "license", "tryReActivate EXCEPTION", "error=${e.message}"
                )
            } catch (_: Exception) {}
            false
        }
    }

    /**
     * Migrate HWID: re-activate the stored license key with the new machine ID.
     * This handles the transition from ANDROID_ID-based HWID to MediaDrm-based HWID.
     * If re-activation fails, fall back to normal validation with the new HWID.
     */
    private suspend fun migrateHwid(
        context: Context,
        licenseKey: String,
        newMachineId: String,
        displayId: String
    ) {
        try {
            val hardwareHash = try { DeviceManager.getHardwareHash(context) } catch (_: Exception) { "" }
            val drmId = getDrmId()
            val androidId = try { DeviceManager.getDeviceId(context) } catch (_: Exception) { "" }
            val result = withContext(Dispatchers.IO) {
                LicenseApiClient.activateLicense(licenseKey, newMachineId, hardwareHash, drmId, androidId)
            }
            if (result.success) {
                val data = result.data.getAsJsonObject("data") ?: result.data
                val type = data.get("license_type")?.asString ?: "unknown"
                val expiresAtStr = data.get("expires_at")?.asString
                val expiresAt = parseIsoTimestamp(expiresAtStr)
                val daysRemaining = data.get("days_remaining")?.asInt ?: 0

                prefs?.edit()
                    ?.putString(KEY_MACHINE_ID, newMachineId)
                    ?.putString(KEY_LICENSE_TYPE, type)
                    ?.putString(KEY_LICENSE_STATUS, "active")
                    ?.putLong(KEY_LICENSE_EXPIRES_AT, expiresAt)
                    ?.apply()

                _state.value = LicenseState(
                    status = LicenseStatus.ACTIVE,
                    licenseType = type,
                    expiresAt = expiresAt,
                    remainingDays = daysRemaining,
                    deviceId = displayId,
                    isLoading = false
                )
                Log.i(TAG, "HWID migration successful — license re-bound to new HWID")
                try {
                    com.xjanova.tping.data.diagnostic.DiagnosticReporter.logEvent(
                        "license", "migrateHwid OK", "type=$type, days=$daysRemaining"
                    )
                } catch (_: Exception) {}
            } else {
                Log.w(TAG, "HWID migration failed: ${result.message}, falling back to validate")
                try {
                    com.xjanova.tping.data.diagnostic.DiagnosticReporter.logEvent(
                        "license", "migrateHwid FAILED",
                        "status=${result.statusCode}, msg=${result.message}"
                    )
                } catch (_: Exception) {}
                // Migration failed — try normal validation (server may already accept new HWID)
                validatePaidLicense(context, licenseKey, newMachineId, displayId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "migrateHwid failed", e)
            // Network error — try normal validation which has offline cache fallback
            validatePaidLicense(context, licenseKey, newMachineId, displayId)
        }
    }

    /**
     * Check if this machine already has an active license on server (HWID auto-check).
     * Returns true if license found and auto-activated, false otherwise.
     */
    private suspend fun checkMachineForExistingLicense(
        context: Context,
        machineId: String,
        displayId: String
    ): Boolean {
        return try {
            Log.d(TAG, "checkMachine: machineId=${machineId.take(16)}... (${machineId.length} chars)")
            try {
                com.xjanova.tping.data.diagnostic.DiagnosticReporter.logEvent(
                    "license", "checkMachine start",
                    "machineId=${machineId.take(16)}..., len=${machineId.length}"
                )
            } catch (_: Exception) {}
            val drmId = getDrmId()
            // Also send ANDROID_ID for server-side cross-reference lookup after reinstall
            val result = withContext(Dispatchers.IO) {
                LicenseApiClient.checkMachine(machineId, drmId, androidId = displayId)
            }
            if (result.success) {
                val hasLicense = result.data.get("has_license")?.asBoolean ?: false
                Log.d(TAG, "checkMachine: hasLicense=$hasLicense")
                try {
                    com.xjanova.tping.data.diagnostic.DiagnosticReporter.logEvent(
                        "license", "checkMachine result",
                        "hasLicense=$hasLicense, status=${result.statusCode}"
                    )
                } catch (_: Exception) {}
                if (hasLicense) {
                    val data = result.data.getAsJsonObject("data") ?: return false
                    val key = data.get("license_key")?.asString ?: return false
                    val type = data.get("license_type")?.asString ?: ""
                    val expiresAtStr = data.get("expires_at")?.asString
                    val expiresAt = parseIsoTimestamp(expiresAtStr)
                    val daysRemaining = data.get("days_remaining")?.asInt ?: 0

                    // Save license key locally
                    prefs?.edit()
                        ?.putString(KEY_LICENSE_KEY, key)
                        ?.putString(KEY_LICENSE_TYPE, type)
                        ?.putString(KEY_LICENSE_STATUS, "active")
                        ?.putLong(KEY_LICENSE_EXPIRES_AT, expiresAt)
                        ?.apply()

                    _state.value = LicenseState(
                        status = LicenseStatus.ACTIVE,
                        licenseType = type,
                        expiresAt = expiresAt,
                        remainingDays = daysRemaining,
                        deviceId = displayId,
                        isLoading = false
                    )
                    Log.d(TAG, "Auto-activated license from HWID: $key")
                    true
                } else {
                    Log.d(TAG, "checkMachine: no license for this HWID on server")
                    false
                }
            } else {
                Log.w(TAG, "checkMachine failed: ${result.statusCode} ${result.message}")
                false // Server error — fall through to demo check
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkMachineForExistingLicense failed", e)
            false // Network error — fall through to demo check
        }
    }

    /**
     * Check demo/trial status from server. If server unreachable, use LOCAL trial fallback.
     */
    private suspend fun checkOrStartDemo(
        context: Context,
        machineId: String,
        displayId: String
    ) {
        try {
            val checkResult = withContext(Dispatchers.IO) {
                LicenseApiClient.checkDemo(machineId)
            }

            if (!checkResult.success) {
                Log.w(TAG, "checkDemo failed: ${checkResult.statusCode} ${checkResult.message}")
                // Server error — use local trial fallback instead of blocking
                useLocalTrialFallback(context)
                return
            }

            // Detect clock drift from server_time
            val serverTimeStr = checkResult.data.get("server_time")?.asString
            updateServerTimeDrift(serverTimeStr)

            val data = checkResult.data.getAsJsonObject("data") ?: checkResult.data
            val hasUsedDemo = data.get("has_used_demo")?.asBoolean ?: false
            val canStartDemo = data.get("can_start_demo")?.asBoolean ?: false
            val isTrialActive = data.get("is_trial_active")?.asBoolean ?: false

            if (isTrialActive) {
                // Trial still active on server — use server's precise values
                val trialInfo = data.getAsJsonObject("trial_info")
                val daysRemaining = trialInfo?.get("days_remaining")?.asInt ?: 0
                val serverHoursRemaining = trialInfo?.get("hours_remaining")?.asInt
                val serverSecondsRemaining = trialInfo?.get("seconds_remaining")?.asInt
                val expiresAtStr = trialInfo?.get("expires_at")?.asString
                val expiresAt = parseIsoTimestamp(expiresAtStr)

                // Use server's precise hours if available, otherwise calculate from expires_at
                val hoursRemaining = serverHoursRemaining
                    ?: if (expiresAt > 0) {
                        ((expiresAt - getAdjustedCurrentTimeMs()) / (60 * 60 * 1000)).toInt().coerceAtLeast(0)
                    } else {
                        daysRemaining * 24
                    }

                // Record verification time
                recordVerification()

                _state.value = LicenseState(
                    status = LicenseStatus.TRIAL,
                    licenseType = "trial",
                    expiresAt = expiresAt,
                    remainingDays = daysRemaining,
                    remainingHours = hoursRemaining,
                    deviceId = displayId,
                    isLoading = false
                )
            } else if (!hasUsedDemo && canStartDemo) {
                // First time — start demo on server
                startDemoOnServer(context, machineId, displayId)
            } else {
                // Trial expired or used up — show gate
                _state.value = LicenseState(
                    status = LicenseStatus.EXPIRED,
                    licenseType = "trial",
                    deviceId = displayId,
                    isLoading = false
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkOrStartDemo failed", e)
            // Network error — use local trial fallback instead of blocking
            useLocalTrialFallback(context)
        }
    }

    /**
     * Start demo/trial on server for new device.
     */
    private suspend fun startDemoOnServer(
        context: Context,
        machineId: String,
        displayId: String
    ) {
        try {
            val drmId = getDrmId()
            // Register device first
            withContext(Dispatchers.IO) {
                val deviceName = DeviceManager.getDeviceName()
                val osVersion = DeviceManager.getOsVersion()
                val appVersion = BuildConfig.VERSION_NAME
                val hardwareHash = DeviceManager.getHardwareHash(context)

                LicenseApiClient.registerDevice(
                    machineId = machineId,
                    machineName = deviceName,
                    osVersion = osVersion,
                    appVersion = appVersion,
                    hardwareHash = hardwareHash,
                    drmId = drmId
                )
            }

            // Start demo
            val demoResult = withContext(Dispatchers.IO) {
                LicenseApiClient.startDemo(machineId = machineId, drmId = drmId)
            }

            if (demoResult.success) {
                prefs?.edit()?.putBoolean(KEY_DEVICE_REGISTERED, true)?.apply()

                // Record server time drift
                val serverTimeStr = demoResult.data.get("server_time")?.asString
                updateServerTimeDrift(serverTimeStr)

                val data = demoResult.data.getAsJsonObject("data") ?: demoResult.data
                val daysRemaining = data.get("days_remaining")?.asInt ?: 7
                val serverHoursRemaining = data.get("hours_remaining")?.asInt
                val expiresAtStr = data.get("expires_at")?.asString
                val expiresAt = parseIsoTimestamp(expiresAtStr)
                val hoursRemaining = serverHoursRemaining ?: (daysRemaining * 24)

                // Record verification time
                recordVerification()

                _state.value = LicenseState(
                    status = LicenseStatus.TRIAL,
                    licenseType = "trial",
                    expiresAt = expiresAt,
                    remainingDays = daysRemaining,
                    remainingHours = hoursRemaining,
                    deviceId = displayId,
                    isLoading = false
                )
            } else {
                Log.w(TAG, "startDemo rejected: ${demoResult.message}")
                // Server rejected demo — use local trial fallback
                useLocalTrialFallback(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startDemoOnServer failed", e)
            // Network error — use local trial fallback
            useLocalTrialFallback(context)
        }
    }

    /**
     * LOCAL trial fallback: 24-hour trial stored locally.
     * Used when server is unreachable, to ensure the app is always usable on first launch.
     * If clock tampering detected too many times, refuse local trial.
     */
    private fun useLocalTrialFallback(context: Context?) {
        try {
            val p = prefs ?: context?.let { getPrefs(it) }
            val displayId = p?.getString(KEY_DEVICE_ID, "") ?: ""

            // If clock tampered too many times, don't trust local trial
            val tamperCount = try { p?.getInt(KEY_CLOCK_TAMPER_COUNT, 0) ?: 0 } catch (_: Exception) { 0 }
            if (tamperCount >= MAX_TAMPER_COUNT) {
                Log.w(TAG, "Clock tampered $tamperCount times — refusing local trial")
                _state.value = LicenseState(
                    status = LicenseStatus.EXPIRED,
                    licenseType = "trial",
                    deviceId = displayId,
                    isLoading = false,
                    errorMessage = "กรุณาเชื่อมต่ออินเทอร์เน็ตเพื่อตรวจสอบสิทธิ์"
                )
                return
            }

            val firstLaunch = p?.getLong(KEY_FIRST_LAUNCH_AT, 0L) ?: 0L
            val now = System.currentTimeMillis()

            if (firstLaunch == 0L) {
                // First launch ever — start local trial
                p?.edit()
                    ?.putLong(KEY_FIRST_LAUNCH_AT, now)
                    ?.putBoolean(KEY_LOCAL_TRIAL_USED, true)
                    ?.apply()

                val expiresAt = now + LOCAL_TRIAL_DURATION_MS
                val hoursRemaining = (LOCAL_TRIAL_DURATION_MS / (60 * 60 * 1000)).toInt()

                _state.value = LicenseState(
                    status = LicenseStatus.TRIAL,
                    licenseType = "trial",
                    expiresAt = expiresAt,
                    remainingDays = 1,
                    remainingHours = hoursRemaining,
                    deviceId = displayId,
                    isLoading = false
                )
                Log.d(TAG, "Started local trial (24h)")
            } else {
                val expiresAt = firstLaunch + LOCAL_TRIAL_DURATION_MS
                if (now < expiresAt) {
                    // Local trial still active
                    val hoursRemaining = ((expiresAt - now) / (60 * 60 * 1000)).toInt().coerceAtLeast(0)
                    val daysRemaining = (hoursRemaining / 24).coerceAtLeast(0)

                    _state.value = LicenseState(
                        status = LicenseStatus.TRIAL,
                        licenseType = "trial",
                        expiresAt = expiresAt,
                        remainingDays = daysRemaining,
                        remainingHours = hoursRemaining,
                        deviceId = displayId,
                        isLoading = false
                    )
                    Log.d(TAG, "Local trial active ($hoursRemaining hours remaining)")
                } else {
                    // Local trial expired
                    _state.value = LicenseState(
                        status = LicenseStatus.EXPIRED,
                        licenseType = "trial",
                        deviceId = displayId,
                        isLoading = false,
                        errorMessage = "ทดลองใช้หมดแล้ว กรุณาซื้อ License Key"
                    )
                    Log.d(TAG, "Local trial expired")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "useLocalTrialFallback failed", e)
            // ABSOLUTE FALLBACK: Allow usage with trial status
            _state.value = LicenseState(
                status = LicenseStatus.TRIAL,
                licenseType = "trial",
                remainingHours = 24,
                remainingDays = 1,
                isLoading = false
            )
        }
    }

    /**
     * Activate a license key.
     */
    suspend fun activateKey(context: Context, key: String): Result<String> {
        val machineId = getMachineId(context)
        val displayId = try { DeviceManager.getDeviceId(context) } catch (_: Exception) { "unknown" }
        val hardwareHash = try { DeviceManager.getHardwareHash(context) } catch (_: Exception) { "" }
        val drmId = getDrmId()
        val androidId = if (displayId != "unknown") displayId else ""

        _state.value = _state.value.copy(isLoading = true)

        return try {
            val result = withContext(Dispatchers.IO) {
                LicenseApiClient.activateLicense(key, machineId, hardwareHash, drmId, androidId)
            }
            if (result.success) {
                val data = result.data.getAsJsonObject("data") ?: result.data
                val type = data.get("license_type")?.asString ?: "unknown"
                val expiresAtStr = data.get("expires_at")?.asString
                val expiresAt = parseIsoTimestamp(expiresAtStr)
                val daysRemaining = data.get("days_remaining")?.asInt ?: 0

                prefs?.edit()
                    ?.putString(KEY_LICENSE_KEY, key)
                    ?.putString(KEY_LICENSE_TYPE, type)
                    ?.putString(KEY_LICENSE_STATUS, "active")
                    ?.putLong(KEY_LICENSE_EXPIRES_AT, expiresAt)
                    ?.apply()

                _state.value = LicenseState(
                    status = LicenseStatus.ACTIVE,
                    licenseType = type,
                    expiresAt = expiresAt,
                    remainingDays = daysRemaining,
                    deviceId = displayId,
                    isLoading = false
                )
                Result.success(getLicenseTypeDisplay(type))
            } else {
                _state.value = _state.value.copy(isLoading = false, errorMessage = result.message)
                Result.failure(Exception(result.message.ifEmpty { "ไม่สามารถเปิดใช้งานคีย์ได้" }))
            }
        } catch (e: Exception) {
            Log.e(TAG, "activateKey failed", e)
            _state.value = _state.value.copy(isLoading = false, errorMessage = e.localizedMessage ?: "เกิดข้อผิดพลาด")
            Result.failure(e)
        }
    }

    // ---- Queries ----

    fun isLicenseValid(): Boolean {
        val s = _state.value
        return s.status == LicenseStatus.ACTIVE || s.status == LicenseStatus.TRIAL
    }

    fun shouldShowGate(): Boolean {
        val s = _state.value
        return s.status == LicenseStatus.EXPIRED || s.status == LicenseStatus.NONE
    }

    fun isTrialActive(): Boolean = _state.value.status == LicenseStatus.TRIAL

    fun isNearExpiry(): Boolean {
        val s = _state.value
        return s.status == LicenseStatus.ACTIVE && s.remainingDays in 1..7
    }

    fun getPurchaseUrl(plan: String = ""): String {
        return if (plan.isNotEmpty()) {
            "$PURCHASE_BASE_URL?plan=$plan"
        } else {
            PURCHASE_BASE_URL
        }
    }

    fun getLicenseTypeDisplay(type: String = _state.value.licenseType): String {
        return when (type) {
            "lifetime" -> "ตลอดชีพ"
            "yearly" -> "รายปี"
            "monthly" -> "รายเดือน"
            "demo", "trial" -> "ทดลองใช้"
            else -> type
        }
    }

    fun getDeviceId(): String = try { prefs?.getString(KEY_DEVICE_ID, "") ?: "" } catch (_: Exception) { "" }

    fun getLicenseKey(): String? = try {
        val key = prefs?.getString(KEY_LICENSE_KEY, "") ?: ""
        key.ifEmpty { null }
    } catch (_: Exception) { null }

    fun getMachineId(): String? = try {
        val id = prefs?.getString(KEY_MACHINE_ID, "") ?: ""
        id.ifEmpty { null }
    } catch (_: Exception) { null }

    // ---- Private helpers ----

    /**
     * Use cached state — only for paid licenses (offline fallback).
     */
    private fun useCachedState(displayId: String) {
        val cachedStatus = prefs?.getString(KEY_LICENSE_STATUS, "") ?: ""
        val cachedType = prefs?.getString(KEY_LICENSE_TYPE, "") ?: ""
        val cachedExpiry = prefs?.getLong(KEY_LICENSE_EXPIRES_AT, 0L) ?: 0L
        val now = System.currentTimeMillis()

        // Only allow offline for paid licenses (not demo/trial)
        if (cachedStatus == "active" && cachedType != "demo" && cachedType != "trial" &&
            (cachedExpiry == 0L || cachedExpiry > now)) {
            val days = if (cachedExpiry > 0) ((cachedExpiry - now) / (24 * 60 * 60 * 1000)).toInt() else 999
            _state.value = LicenseState(
                status = LicenseStatus.ACTIVE,
                licenseType = cachedType,
                expiresAt = cachedExpiry,
                remainingDays = days,
                deviceId = displayId,
                isLoading = false
            )
        } else {
            // No valid cached paid license — use local trial fallback
            useLocalTrialFallback(appContext)
        }
    }

    /**
     * Parse ISO 8601 timestamp to epoch millis. Returns 0 if null/invalid.
     */
    private fun parseIsoTimestamp(isoString: String?): Long {
        if (isoString.isNullOrBlank()) return 0L
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .parse(isoString.replace(Regex("\\.[0-9]+Z$"), "").replace("Z", ""))
                ?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    // ---- Anti-cheat: Clock tampering detection ----

    /**
     * Detect if the system clock was set backward (time travel).
     * Compares current time with last verified time.
     * If clock went backward significantly, increment tamper count.
     */
    private fun detectClockTampering() {
        try {
            val lastVerified = prefs?.getLong(KEY_LAST_VERIFIED_AT, 0L) ?: 0L
            val now = System.currentTimeMillis()

            if (lastVerified > 0 && now < lastVerified - 60_000) {
                // Clock went backward by more than 1 minute — suspicious
                val tamperCount = (prefs?.getInt(KEY_CLOCK_TAMPER_COUNT, 0) ?: 0) + 1
                prefs?.edit()?.putInt(KEY_CLOCK_TAMPER_COUNT, tamperCount)?.apply()
                Log.w(TAG, "Clock tampering detected! Count: $tamperCount, " +
                    "now=$now, lastVerified=$lastVerified, diff=${lastVerified - now}ms")
            }
        } catch (e: Exception) {
            Log.e(TAG, "detectClockTampering failed", e)
        }
    }

    /**
     * Update server time drift measurement.
     * Called when we receive a server_time in API response.
     */
    private fun updateServerTimeDrift(serverTimeIso: String?) {
        if (serverTimeIso.isNullOrBlank()) return
        try {
            val serverTimeMs = parseIsoTimestamp(serverTimeIso)
            if (serverTimeMs <= 0) return

            val localTimeMs = System.currentTimeMillis()
            val driftMs = localTimeMs - serverTimeMs

            prefs?.edit()?.putLong(KEY_SERVER_TIME_DRIFT_MS, driftMs)?.apply()

            if (Math.abs(driftMs) > MAX_CLOCK_DRIFT_MS) {
                Log.w(TAG, "Significant clock drift detected: ${driftMs}ms " +
                    "(local=${localTimeMs}, server=${serverTimeMs})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateServerTimeDrift failed", e)
        }
    }

    /**
     * Get adjusted current time (corrected for known server drift).
     * If clock drift is significant, use server-based time instead.
     */
    private fun getAdjustedCurrentTimeMs(): Long {
        val now = System.currentTimeMillis()
        try {
            val driftMs = prefs?.getLong(KEY_SERVER_TIME_DRIFT_MS, 0L) ?: 0L
            if (Math.abs(driftMs) > MAX_CLOCK_DRIFT_MS) {
                // Significant drift — adjust local time to match server
                return now - driftMs
            }
        } catch (_: Exception) {}
        return now
    }

    /**
     * Record the last time we successfully verified with server.
     */
    private fun recordVerification() {
        try {
            prefs?.edit()?.putLong(KEY_LAST_VERIFIED_AT, System.currentTimeMillis())?.apply()
        } catch (_: Exception) {}
    }

    /**
     * Check if clock has been tampered with too many times.
     * If so, do not trust local trial — force server verification.
     */
    fun isClockTampered(): Boolean {
        val count = try { prefs?.getInt(KEY_CLOCK_TAMPER_COUNT, 0) ?: 0 } catch (_: Exception) { 0 }
        return count >= MAX_TAMPER_COUNT
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                PREFS_NAME,
                masterKeyAlias,
                context.applicationContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences failed, using fallback", e)
            // Fallback to regular SharedPreferences if encryption fails
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
}
