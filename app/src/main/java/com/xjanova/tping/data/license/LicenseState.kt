package com.xjanova.tping.data.license

data class LicenseState(
    val status: LicenseStatus = LicenseStatus.CHECKING,
    val licenseType: String = "",
    val expiresAt: Long = 0,
    val remainingDays: Int = 0,
    val remainingHours: Int = 0,
    val deviceId: String = "",
    val isLoading: Boolean = true,
    val errorMessage: String = ""
)

enum class LicenseStatus {
    CHECKING,   // Initial state — verifying
    TRIAL,      // Free trial active
    ACTIVE,     // Valid license
    EXPIRED,    // Trial or license expired
    NONE        // No license, no trial
}
