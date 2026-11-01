package ru.danil.medtracker.ui.screens

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.danil.medtracker.App
import ru.danil.medtracker.MainActivity
import ru.danil.medtracker.data.db.Drug
import ru.danil.medtracker.data.db.Norm
import ru.danil.medtracker.data.db.Patient
import ru.danil.medtracker.data.db.Prescription
import ru.danil.medtracker.data.db.Rx
import ru.danil.medtracker.util.Dates
import java.time.LocalDate

/**
 * Сквозной сценарий «Сохранить и выдать» (Ф13, ТЗ 1.4.0 §8/§12.2–4) на уровне
 * ViewModel с реальной БД приложения (эмулятор). Все данные вымышленные.
 */
@RunWith(AndroidJUnit4::class)
class RxScanFlowTest {

    @Test
    fun saveAndIssue_reusesSavedRx_redeemsThenUndoReturnsIssued() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var app: App
            scenario.onActivity { app = it.application as App }
            val db = app.container.db

            runBlocking {
                // засев: пациент, позиция (упаковка 50), назначение, норма 1 шт/день;
                // ранее сохранённый рецепт этой же серии/номера БЕЗ выдачи — кандидат на переиспользование
                val t = System.currentTimeMillis()
                val pid = db.patientDao().insert(
                    Patient(fio = "Тестов Тест Тестович", birthDate = 5000, address = "г. Тест", createdAt = t, updatedAt = t),
                )
                val did = db.drugDao().insert(Drug(name = "Тест-полоски Ван Тач Селект", piecesPerPack = 50, updatedAt = t))
                val prescriptionId = db.prescriptionDao().insert(
                    Prescription(patientId = pid, drugId = did, createdAt = t, updatedAt = t),
                )
                db.normDao().insert(
                    Norm(prescriptionId = prescriptionId, validFrom = 0, pieceKind = "PER_DAY", pieceAmount = 1, createdAt = t, updatedAt = t),
                )
                val savedRxId = db.rxDao().insert(
                    Rx(
                        patientId = pid, series = "75464", number = "25 222 011 880",
                        issueDate = Dates.toEpochDay(LocalDate.of(2026, 10, 29)), validDays = 90,
                        createdAt = t, updatedAt = t,
                    ),
                )
                val patient = db.patientDao().byId(pid)!!

                val vm = RxScanViewModel(app.container)
                vm.state.value = vm.state.value.copy(
                    series = "75464",
                    number = "25222011880",
                    issueDate = LocalDate.of(2026, 10, 29),
                    validDays = 90,
                    units = 5,
                    packSize = 50,
                    patient = patient,
                )
                vm.selectPatient(patient)
                withTimeout(10_000) { vm.state.first { it.prescriptions.isNotEmpty() } }
                vm.setPrescription(prescriptionId)

                // диалог сверки: 5 × №50 = 250 шт упаковками, дубль переиспользуется
                vm.prepareIssue()
                val confirmState = withTimeout(10_000) { vm.state.first { it.confirm != null } }
                val confirm = confirmState.confirm!!
                assertEquals(5, confirm.plan.packs)
                assertEquals(0, confirm.plan.pieces)
                assertEquals(250, confirm.plan.total)
                assertTrue(confirm.plan.blocks.isEmpty())
                assertTrue(confirm.plan.warnings.isEmpty())
                assertTrue(confirm.extraWarnings.isEmpty())
                assertEquals(savedRxId, confirm.rx.id)
                assertNotNull(confirm.previewUntil)

                // выдача: та же запись рецепта (новой нет), REDEEMED, из «выписан» исчез
                vm.confirmIssue()
                val issuedState = withTimeout(10_000) { vm.state.first { it.issued != null } }
                val issued = issuedState.issued!!
                assertEquals(savedRxId, issued.rxId)
                assertNotNull(issued.until)
                assertEquals(1, db.issueDao().forRx(savedRxId).size)
                assertEquals("REDEEMED", db.rxDao().byId(savedRxId)!!.status)
                assertEquals(1, db.rxDao().all().size)
                assertTrue(db.rxDao().allIssued().none { it.id == savedRxId })

                // отмена: выдача удалена, статус вернулся «выписан»
                vm.undoIssue()
                withTimeout(10_000) { vm.state.first { it.issued == null } }
                assertTrue(db.issueDao().forRx(savedRxId).isEmpty())
                assertEquals("ISSUED", db.rxDao().byId(savedRxId)!!.status)
                assertTrue(db.rxDao().allIssued().any { it.id == savedRxId })
            }
        } finally {
            scenario.close()
        }
    }
}
