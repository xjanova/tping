package com.xjanova.tping.data.export

/**
 * Data classes for Tping workflow export/import JSON format.
 */
data class TpingExportData(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val appVersion: String = "",
    val workflows: List<ExportedWorkflow> = emptyList(),
    val dataProfiles: List<ExportedDataProfile> = emptyList()
)

data class ExportedWorkflow(
    val name: String,
    val targetAppPackage: String = "",
    val targetAppName: String = "",
    val stepsJson: String = "[]",
    val createdAt: Long = System.currentTimeMillis()
)

data class ExportedDataProfile(
    val name: String,
    val category: String = "",
    val fieldsJson: String = "[]",
    val createdAt: Long = System.currentTimeMillis()
)

enum class DuplicateStrategy {
    SKIP,       // ข้ามถ้าชื่อซ้ำ
    REPLACE,    // แทนที่ถ้าชื่อซ้ำ
    RENAME      // เปลี่ยนชื่อเป็น "ชื่อ (2)" ถ้าซ้ำ
}

data class ImportResult(
    val workflowsImported: Int = 0,
    val profilesImported: Int = 0,
    val skipped: Int = 0,
    val error: String = ""
)
