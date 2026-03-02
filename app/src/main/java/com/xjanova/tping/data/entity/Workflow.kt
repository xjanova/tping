package com.xjanova.tping.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workflows")
data class Workflow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val targetAppPackage: String = "",
    val stepsJson: String = "[]", // JSON array of RecordedAction
    val createdAt: Long = System.currentTimeMillis()
)
