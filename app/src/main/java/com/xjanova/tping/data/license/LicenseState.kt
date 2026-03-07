package com.xjanova.tping.data.license

data class LicenseState(
    val status: LicenseStatus = LicenseStatus.TRIAL,
    val licenseType: String = "trial",
    val expiresAt: Long = 0,
    val remainingDays: Int = 1,
    val remainingHours: Int = 24,
    val deviceId: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String = ""
)

enum class LicenseStatus {
    CHECKING,   // Initial state — verifying
    TRIAL,      // Free trial active
    ACTIVE,     // Valid license
    EXPIRED,    // Trial or license expired
    NONE        // No license, no trial
}
