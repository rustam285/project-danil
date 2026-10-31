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

    @Query("SELECT * FROM rxs WHERE status = 'ISSUED'")
    suspend fun allIssued(): List<Rx>

    @Query("SELECT * FROM rxs WHERE status = 'ISSUED'")
    fun observeAllIssued(): Flow<List<Rx>>

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
interface TombstoneDao {
    @Insert
    suspend fun insert(t: Tombstone): Long

    @Query("SELECT * FROM tombstones WHERE deletedAt > :since ORDER BY deletedAt")
    suspend fun since(deletedAt: Long): List<Tombstone>

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
}
