package com.xjanova.tping.data.dao

import androidx.room.*
import com.xjanova.tping.data.entity.Workflow
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkflowDao {
    @Query("SELECT * FROM workflows ORDER BY createdAt DESC")
    fun getAll(): Flow<List<Workflow>>

    @Query("SELECT * FROM workflows WHERE id = :id")
    suspend fun getById(id: Long): Workflow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(workflow: Workflow): Long

    @Update
    suspend fun update(workflow: Workflow)

    @Delete
    suspend fun delete(workflow: Workflow)

    @Query("SELECT * FROM workflows WHERE name = :name AND targetAppPackage = :pkg LIMIT 1")
    suspend fun findByNameAndPackage(name: String, pkg: String): Workflow?

    @Query("SELECT * FROM workflows")
    suspend fun getAllOnce(): List<Workflow>
}
