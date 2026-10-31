package ru.danil.medtracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        Patient::class, Drug::class, Prescription::class,
        Norm::class, Issue::class, Rx::class, Tombstone::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun patientDao(): PatientDao
    abstract fun drugDao(): DrugDao
    abstract fun prescriptionDao(): PrescriptionDao
    abstract fun normDao(): NormDao
    abstract fun issueDao(): IssueDao
    abstract fun rxDao(): RxDao
    abstract fun tombstoneDao(): TombstoneDao
    abstract fun coreDao(): CoreDao

    companion object {
        /** Схема БД для копий (п. 9.7, 9.11). Растёт при миграциях. */
        const val SCHEMA_VERSION = 1

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "medtracker.db",
                ).build().also { instance = it }
            }
    }
}
