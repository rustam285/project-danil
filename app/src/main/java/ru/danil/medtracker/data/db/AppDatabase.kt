package ru.danil.medtracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Засев встроенного HbA1c (Ф3.2) — идемпотентно. Миграция v2→v3, onCreate БД и восстановление копий. */
internal object AnalysisSeed {
    fun seed(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()
        db.execSQL(
            "INSERT INTO analysis_types(name, unit, periodMonths, isBuiltIn, createdAt, updatedAt) " +
                "SELECT ?, ?, ?, 1, ?, ? WHERE NOT EXISTS (SELECT 1 FROM analysis_types WHERE isBuiltIn = 1)",
            arrayOf(
                AnalysisType.HBA1C_NAME,
                AnalysisType.HBA1C_UNIT,
                AnalysisType.HBA1C_PERIOD_MONTHS,
                now,
                now,
            ),
        )
    }
}

@Database(
    entities = [
        Patient::class, Drug::class, Prescription::class,
        Norm::class, Issue::class, Rx::class, Tombstone::class,
        AnalysisType::class, AnalysisRecord::class,
    ],
    version = 3,
    exportSchema = true,
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
    abstract fun analysisTypeDao(): AnalysisTypeDao
    abstract fun analysisRecordDao(): AnalysisRecordDao

    companion object {
        /** Схема БД для копий (п. 9.7, 9.11). Растёт при миграциях. */
        const val SCHEMA_VERSION = 3

        /** v2: prescriptions.awaitingRestock — «В ожидании поступления». */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE prescriptions ADD COLUMN awaitingRestock INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v3 (ТЗ 1.2.0 §9, единая неразрушающая миграция):
         * резервные схемы (isReserve), вид позиции (kind), врач в рецепте (doctor),
         * пометка «диабет» (isDiabetic), таблицы анализов + встроенный HbA1c.
         * internal — используется инструментальным тестом миграции.
         */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE prescriptions ADD COLUMN isReserve INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE drugs ADD COLUMN kind TEXT NOT NULL DEFAULT 'DRUG'")
                db.execSQL("ALTER TABLE rxs ADD COLUMN doctor TEXT")
                db.execSQL("ALTER TABLE patients ADD COLUMN isDiabetic INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `analysis_types` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `unit` TEXT, `periodMonths` INTEGER NOT NULL, " +
                        "`isBuiltIn` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `analysis_records` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`patientId` INTEGER NOT NULL, `typeId` INTEGER NOT NULL, `date` INTEGER NOT NULL, " +
                        "`value` REAL NOT NULL, `note` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`patientId`) REFERENCES `patients`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`typeId`) REFERENCES `analysis_types`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_analysis_records_patientId` ON `analysis_records` (`patientId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_analysis_records_typeId` ON `analysis_records` (`typeId`)")
                AnalysisSeed.seed(db)
            }
        }

        /** БД с нуля — тоже засеваем HbA1c (Ф3.2). */
        private val SEED_CALLBACK = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                AnalysisSeed.seed(db)
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        /** Сборка БД с миграциями и засевом HbA1c при создании; имя файла — параметр для тестов. */
        private fun builder(context: Context, name: String) =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .addCallback(SEED_CALLBACK)

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: builder(context, "medtracker.db").build().also { instance = it }
            }

        /** Инструментальный тест «БД с нуля»: та же конфигурация, отдельный файл. */
        internal fun buildForTest(context: Context, name: String): AppDatabase =
            builder(context, name).allowMainThreadQueries().build()
    }
}
