package ru.danil.medtracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Patient::class, Drug::class, Prescription::class,
        Norm::class, Issue::class, Rx::class, Tombstone::class,
    ],
    version = 2,
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
        const val SCHEMA_VERSION = 2

        /** v2: prescriptions.awaitingRestock — «В ожидании поступления». */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE prescriptions ADD COLUMN awaitingRestock INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "medtracker.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
