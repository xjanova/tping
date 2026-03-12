package com.xjanova.tping.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xjanova.tping.data.dao.DataProfileDao
import com.xjanova.tping.data.dao.WorkflowDao
import com.xjanova.tping.data.entity.DataProfile
import com.xjanova.tping.data.entity.Workflow

@Database(
    entities = [DataProfile::class, Workflow::class],
    version = 3,
    exportSchema = false
)
abstract class TpingDatabase : RoomDatabase() {

    abstract fun dataProfileDao(): DataProfileDao
    abstract fun workflowDao(): WorkflowDao

    companion object {
        @Volatile
        private var INSTANCE: TpingDatabase? = null

        // Migration 1→2: Add targetAppName column to workflows table
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workflows ADD COLUMN targetAppName TEXT NOT NULL DEFAULT ''")
            }
        }

        // Migration 2→3: Add category column to data_profiles table
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE data_profiles ADD COLUMN category TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getDatabase(context: Context): TpingDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TpingDatabase::class.java,
                    "tping_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }

    }
}
