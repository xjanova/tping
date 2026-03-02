package com.xjanova.tping.data.dao

import androidx.room.*
import com.xjanova.tping.data.entity.DataProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface DataProfileDao {
    @Query("SELECT * FROM data_profiles ORDER BY createdAt DESC")
    fun getAll(): Flow<List<DataProfile>>

    @Query("SELECT * FROM data_profiles WHERE id = :id")
    suspend fun getById(id: Long): DataProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: DataProfile): Long

    @Update
    suspend fun update(profile: DataProfile)

    @Delete
    suspend fun delete(profile: DataProfile)

    @Query("DELETE FROM data_profiles")
    suspend fun deleteAll()
}
