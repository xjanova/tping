package com.xjanova.tping.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "data_profiles")
data class DataProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val fieldsJson: String, // JSON array of DataField
    val createdAt: Long = System.currentTimeMillis()
)

data class DataField(
    val key: String,
    val value: String
)
