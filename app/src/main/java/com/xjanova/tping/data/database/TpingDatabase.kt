package com.xjanova.tping.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.xjanova.tping.data.dao.DataProfileDao
import com.xjanova.tping.data.dao.WorkflowDao
import com.xjanova.tping.data.entity.DataProfile
import com.xjanova.tping.data.entity.Workflow

@Database(
    entities = [DataProfile::class, Workflow::class],
    version = 1,
    exportSchema = false
)
abstract class TpingDatabase : RoomDatabase() {

    abstract fun dataProfileDao(): DataProfileDao
    abstract fun workflowDao(): WorkflowDao

    companion object {
        @Volatile
        private var INSTANCE: TpingDatabase? = null

        fun getDatabase(context: Context): TpingDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TpingDatabase::class.java,
                    "tping_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
