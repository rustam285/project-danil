package ru.danil.medtracker.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PatientDao {
    @Query("SELECT * FROM patients WHERE archived = 0 ORDER BY fio COLLATE NOCASE")
    fun observeActive(): Flow<List<Patient>>

    @Query("SELECT * FROM patients ORDER BY fio COLLATE NOCASE")
    fun observeAll(): Flow<List<Patient>>

    @Query("SELECT * FROM patients WHERE id = :id")
    suspend fun byId(id: Long): Patient?

    @Query("SELECT * FROM patients")
    suspend fun all(): List<Patient>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(p: Patient): Long

    @Update
    suspend fun update(p: Patient)

    @Query("DELETE FROM patients WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface DrugDao {
    @Query("SELECT * FROM drugs WHERE archived = 0 ORDER BY name COLLATE NOCASE")
    fun observeActive(): Flow<List<Drug>>

    @Query("SELECT * FROM drugs ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<Drug>

    @Query("SELECT * FROM drugs ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Drug>>

    @Query("SELECT * FROM drugs WHERE id = :id")
    suspend fun byId(id: Long): Drug?

    @Query("SELECT * FROM drugs WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<Drug>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(d: Drug): Long

    @Update
    suspend fun update(d: Drug)

    @Query("DELETE FROM drugs WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PrescriptionDao {
    @Query("SELECT * FROM prescriptions WHERE patientId = :patientId AND status = 'ACTIVE'")
    fun observeActiveForPatient(patientId: Long): Flow<List<Prescription>>

    @Query("SELECT * FROM prescriptions WHERE status = 'ACTIVE'")
    fun observeAllActive(): Flow<List<Prescription>>

    @Query("SELECT * FROM prescriptions WHERE status = 'ACTIVE'")
    suspend fun allActive(): List<Prescription>

    @Query("SELECT * FROM prescriptions")
    suspend fun all(): List<Prescription>

    @Query("SELECT * FROM prescriptions WHERE id = :id")
    suspend fun byId(id: Long): Prescription?

    @Query("SELECT COUNT(*) FROM prescriptions WHERE drugId = :drugId")
    suspend fun countForDrug(drugId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(p: Prescription): Long

    @Update
    suspend fun update(p: Prescription)

    @Query("DELETE FROM prescriptions WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface NormDao {
    @Query("SELECT * FROM norms WHERE prescriptionId = :prescriptionId ORDER BY validFrom")
    fun observeForPrescription(prescriptionId: Long): Flow<List<Norm>>

    @Query("SELECT * FROM norms WHERE prescriptionId = :prescriptionId ORDER BY validFrom")
    suspend fun forPrescription(prescriptionId: Long): List<Norm>

    @Query("SELECT * FROM norms")
    suspend fun all(): List<Norm>

    @Query("SELECT * FROM norms")
    fun observeAll(): Flow<List<Norm>>

    @Query("SELECT * FROM norms WHERE prescriptionId IN (:ids)")
    suspend fun forPrescriptions(ids: List<Long>): List<Norm>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(n: Norm): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<Norm>)

    @Update
    suspend fun update(n: Norm)

    @Query("DELETE FROM norms WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM norms WHERE prescriptionId = :prescriptionId")
    suspend fun deleteForPrescription(prescriptionId: Long)
}

@Dao
interface IssueDao {
    @Query("SELECT * FROM issues WHERE prescriptionId = :prescriptionId ORDER BY date DESC, id DESC")
    fun observeForPrescription(prescriptionId: Long): Flow<List<Issue>>

    @Query("SELECT * FROM issues WHERE prescriptionId = :prescriptionId ORDER BY date, id")
    suspend fun forPrescription(prescriptionId: Long): List<Issue>

    @Query("SELECT * FROM issues WHERE prescriptionId IN (:ids) ORDER BY date, id")
    suspend fun forPrescriptions(ids: List<Long>): List<Issue>

    @Query("SELECT * FROM issues")
    suspend fun all(): List<Issue>

    @Query("SELECT * FROM issues")
    fun observeAll(): Flow<List<Issue>>

    @Query("SELECT * FROM issues WHERE id = :id")
    suspend fun byId(id: Long): Issue?

    @Query("SELECT * FROM issues WHERE rxId = :rxId")
    suspend fun forRx(rxId: Long): List<Issue>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(i: Issue): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<Issue>)

    @Update
    suspend fun update(i: Issue)

    @Query("DELETE FROM issues WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM issues WHERE prescriptionId = :prescriptionId")
    suspend fun deleteForPrescription(prescriptionId: Long)
}

@Dao
interface RxDao {
    @Query("SELECT * FROM rxs WHERE patientId = :patientId ORDER BY issueDate DESC, id DESC")
    fun observeForPatient(patientId: Long): Flow<List<Rx>>

    @Query("SELECT * FROM rxs")
    suspend fun all(): List<Rx>

    @Query("SELECT * FROM rxs WHERE id = :id")
    suspend fun byId(id: Long): Rx?

    @Query("SELECT * FROM rxs")
    fun observeAllRxs(): Flow<List<Rx>>

    @Query("SELECT * FROM rxs WHERE status = 'ISSUED'")
    suspend fun allIssued(): List<Rx>

    @Query("SELECT * FROM rxs WHERE status = 'ISSUED'")
    fun observeAllIssued(): Flow<List<Rx>>

    /** Ранее введённые врачи — подсказки для поля «Врач» (Ф4.3). */
    @Query("SELECT DISTINCT doctor FROM rxs WHERE doctor IS NOT NULL AND TRIM(doctor) != '' ORDER BY doctor COLLATE NOCASE")
    suspend fun distinctDoctors(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(r: Rx): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<Rx>)

    @Update
    suspend fun update(r: Rx)

    @Query("DELETE FROM rxs WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM rxs WHERE patientId = :patientId")
    suspend fun deleteForPatient(patientId: Long)
}

@Dao
interface AnalysisTypeDao {
    @Query("SELECT * FROM analysis_types ORDER BY isBuiltIn DESC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<AnalysisType>>

    @Query("SELECT * FROM analysis_types ORDER BY isBuiltIn DESC, name COLLATE NOCASE")
    suspend fun all(): List<AnalysisType>

    @Query("SELECT * FROM analysis_types WHERE id = :id")
    suspend fun byId(id: Long): AnalysisType?

    @Query("SELECT * FROM analysis_types WHERE isBuiltIn = 1 LIMIT 1")
    suspend fun builtIn(): AnalysisType?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(t: AnalysisType): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<AnalysisType>)

    @Update
    suspend fun update(t: AnalysisType)

    @Query("DELETE FROM analysis_types WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface AnalysisRecordDao {
    @Query("SELECT * FROM analysis_records WHERE patientId = :patientId ORDER BY date DESC, id DESC")
    fun observeForPatient(patientId: Long): Flow<List<AnalysisRecord>>

    @Query("SELECT * FROM analysis_records WHERE patientId = :patientId ORDER BY date DESC, id DESC")
    suspend fun forPatient(patientId: Long): List<AnalysisRecord>

    @Query("SELECT * FROM analysis_records")
    suspend fun all(): List<AnalysisRecord>

    @Query("SELECT * FROM analysis_records")
    fun observeAll(): Flow<List<AnalysisRecord>>

    @Query("SELECT * FROM analysis_records WHERE id = :id")
    suspend fun byId(id: Long): AnalysisRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(r: AnalysisRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<AnalysisRecord>)

    @Update
    suspend fun update(r: AnalysisRecord)

    @Query("DELETE FROM analysis_records WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM analysis_records WHERE patientId = :patientId")
    suspend fun deleteForPatient(patientId: Long)

    @Query("DELETE FROM analysis_records WHERE typeId = :typeId")
    suspend fun deleteForType(typeId: Long)
}

@Dao
interface TombstoneDao {
    @Insert
    suspend fun insert(t: Tombstone): Long

    @Query("SELECT * FROM tombstones WHERE deletedAt > :since ORDER BY deletedAt")
    suspend fun since(since: Long): List<Tombstone>

    @Query("SELECT * FROM tombstones")
    suspend fun all(): List<Tombstone>

    @Query("DELETE FROM tombstones WHERE deletedAt <= :before")
    suspend fun trimBefore(before: Long)
}

/** Транзакционные операции, затрагивающие несколько таблиц (п. 11.3). */
@Dao
abstract class CoreDao {
    @Insert
    protected abstract suspend fun insertTombstoneRaw(t: Tombstone): Long

    @Transaction
    open suspend fun deletePatientCascade(
        patientDao: PatientDao,
        prescriptionDao: PrescriptionDao,
        normDao: NormDao,
        issueDao: IssueDao,
        rxDao: RxDao,
        analysisRecordDao: AnalysisRecordDao,
        patientId: Long,
    ) {
        val now = System.currentTimeMillis()
        val prescriptions = prescriptionDao.all().filter { it.patientId == patientId }
        for (p in prescriptions) {
            issueDao.deleteForPrescription(p.id)
            normDao.deleteForPrescription(p.id)
            insertTombstoneRaw(Tombstone(entityType = "prescription", entityId = p.id, deletedAt = now))
        }
        val rxs = rxDao.all().filter { it.patientId == patientId }
        for (r in rxs) {
            insertTombstoneRaw(Tombstone(entityType = "rx", entityId = r.id, deletedAt = now))
        }
        rxDao.deleteForPatient(patientId)
        // записи анализов пациента — с tombstones для дельта-копий (Ф3/§9)
        for (r in analysisRecordDao.forPatient(patientId)) {
            insertTombstoneRaw(Tombstone(entityType = "analysis_record", entityId = r.id, deletedAt = now))
        }
        analysisRecordDao.deleteForPatient(patientId)
        patientDao.delete(patientId)
        insertTombstoneRaw(Tombstone(entityType = "patient", entityId = patientId, deletedAt = now))
    }

    @Transaction
    open suspend fun deletePrescriptionCascade(
        prescriptionDao: PrescriptionDao,
        normDao: NormDao,
        issueDao: IssueDao,
        prescriptionId: Long,
    ) {
        val now = System.currentTimeMillis()
        issueDao.deleteForPrescription(prescriptionId)
        normDao.deleteForPrescription(prescriptionId)
        prescriptionDao.delete(prescriptionId)
        insertTombstoneRaw(Tombstone(entityType = "prescription", entityId = prescriptionId, deletedAt = now))
    }

    /** Удаление вида анализа: записи каскадно, всё — с tombstones (Ф3.3, §9). */
    @Transaction
    open suspend fun deleteAnalysisTypeCascade(
        analysisTypeDao: AnalysisTypeDao,
        analysisRecordDao: AnalysisRecordDao,
        typeId: Long,
    ) {
        val now = System.currentTimeMillis()
        for (r in analysisRecordDao.all().filter { it.typeId == typeId }) {
            insertTombstoneRaw(Tombstone(entityType = "analysis_record", entityId = r.id, deletedAt = now))
        }
        analysisRecordDao.deleteForType(typeId)
        analysisTypeDao.delete(typeId)
        insertTombstoneRaw(Tombstone(entityType = "analysis_type", entityId = typeId, deletedAt = now))
    }
}
