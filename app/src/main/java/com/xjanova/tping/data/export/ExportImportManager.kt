package com.xjanova.tping.data.export

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xjanova.tping.BuildConfig
import com.xjanova.tping.data.dao.DataProfileDao
import com.xjanova.tping.data.dao.WorkflowDao
import com.xjanova.tping.data.entity.DataProfile
import com.xjanova.tping.data.entity.Workflow

/**
 * Handles export/import of workflows and data profiles as JSON files.
 */
object ExportImportManager {

    private val gson = Gson()

    /**
     * Export selected workflows and profiles to a JSON string.
     */
    fun exportToJson(
        workflows: List<Workflow>,
        profiles: List<DataProfile>
    ): String {
        val exportData = TpingExportData(
            version = 1,
            exportedAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            workflows = workflows.map { w ->
                ExportedWorkflow(
                    name = w.name,
                    targetAppPackage = w.targetAppPackage,
                    targetAppName = w.targetAppName,
                    stepsJson = w.stepsJson,
                    createdAt = w.createdAt
                )
            },
            dataProfiles = profiles.map { p ->
                ExportedDataProfile(
                    name = p.name,
                    category = p.category,
                    fieldsJson = p.fieldsJson,
                    createdAt = p.createdAt
                )
            }
        )
        return gson.toJson(exportData)
    }

    /**
     * Parse a JSON string into TpingExportData.
     */
    fun parseImportJson(json: String): TpingExportData {
        return try {
            val type = object : TypeToken<TpingExportData>() {}.type
            gson.fromJson(json, type) ?: throw IllegalArgumentException("Invalid JSON")
        } catch (e: Exception) {
            throw IllegalArgumentException("ไม่สามารถอ่านไฟล์ได้: ${e.message}")
        }
    }

    /**
     * Import data into the database with duplicate handling.
     */
    suspend fun importData(
        workflowDao: WorkflowDao,
        profileDao: DataProfileDao,
        data: TpingExportData,
        existingWorkflows: List<Workflow>,
        existingProfiles: List<DataProfile>,
        strategy: DuplicateStrategy
    ): ImportResult {
        var workflowsImported = 0
        var profilesImported = 0
        var skipped = 0

        // Import workflows
        for (exportedWf in data.workflows) {
            val existingNames = existingWorkflows.map { it.name }
            val duplicate = existingNames.contains(exportedWf.name)

            if (duplicate) {
                when (strategy) {
                    DuplicateStrategy.SKIP -> {
                        skipped++
                        continue
                    }
                    DuplicateStrategy.REPLACE -> {
                        val existing = existingWorkflows.find { it.name == exportedWf.name }
                        if (existing != null) {
                            workflowDao.update(
                                existing.copy(
                                    targetAppPackage = exportedWf.targetAppPackage,
                                    targetAppName = exportedWf.targetAppName,
                                    stepsJson = exportedWf.stepsJson
                                )
                            )
                            workflowsImported++
                        }
                        continue
                    }
                    DuplicateStrategy.RENAME -> {
                        val newName = generateUniqueName(exportedWf.name, existingNames)
                        workflowDao.insert(
                            Workflow(
                                name = newName,
                                targetAppPackage = exportedWf.targetAppPackage,
                                targetAppName = exportedWf.targetAppName,
                                stepsJson = exportedWf.stepsJson,
                                createdAt = exportedWf.createdAt
                            )
                        )
                        workflowsImported++
                        continue
                    }
                }
            }

            workflowDao.insert(
                Workflow(
                    name = exportedWf.name,
                    targetAppPackage = exportedWf.targetAppPackage,
                    targetAppName = exportedWf.targetAppName,
                    stepsJson = exportedWf.stepsJson,
                    createdAt = exportedWf.createdAt
                )
            )
            workflowsImported++
        }

        // Import data profiles
        for (exportedProfile in data.dataProfiles) {
            val existingNames = existingProfiles.map { it.name }
            val duplicate = existingNames.contains(exportedProfile.name)

            if (duplicate) {
                when (strategy) {
                    DuplicateStrategy.SKIP -> {
                        skipped++
                        continue
                    }
                    DuplicateStrategy.REPLACE -> {
                        val existing = existingProfiles.find { it.name == exportedProfile.name }
                        if (existing != null) {
                            profileDao.update(existing.copy(category = exportedProfile.category, fieldsJson = exportedProfile.fieldsJson))
                            profilesImported++
                        }
                        continue
                    }
                    DuplicateStrategy.RENAME -> {
                        val newName = generateUniqueName(exportedProfile.name, existingNames)
                        profileDao.insert(
                            DataProfile(
                                name = newName,
                                category = exportedProfile.category,
                                fieldsJson = exportedProfile.fieldsJson,
                                createdAt = exportedProfile.createdAt
                            )
                        )
                        profilesImported++
                        continue
                    }
                }
            }

            profileDao.insert(
                DataProfile(
                    name = exportedProfile.name,
                    category = exportedProfile.category,
                    fieldsJson = exportedProfile.fieldsJson,
                    createdAt = exportedProfile.createdAt
                )
            )
            profilesImported++
        }

        return ImportResult(
            workflowsImported = workflowsImported,
            profilesImported = profilesImported,
            skipped = skipped
        )
    }

    /**
     * Generate a unique name by appending "(2)", "(3)", etc.
     */
    private fun generateUniqueName(baseName: String, existingNames: List<String>): String {
        var counter = 2
        var newName = "$baseName ($counter)"
        while (existingNames.contains(newName)) {
            counter++
            newName = "$baseName ($counter)"
        }
        return newName
    }
}
