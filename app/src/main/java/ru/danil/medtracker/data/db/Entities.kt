package ru.danil.medtracker.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Сущности Room — Приложение В ТЗ.
 * Даты — epochDay (Long), метки времени — epochMillis (Long).
 * Удаление физическое; удаления фиксируются в таблице tombstones (п. 9.8).
 */

@Serializable
data class Benefit(
    /** federal / regional */
    val type: String,
    /** epochDay, с которого действует */
    val since: Long,
)

@Entity(tableName = "patients")
@Serializable
data class Patient(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fio: String,
    val birthDate: Long,
    val address: String,
    val school: String? = null,
    val illnessStart: Long? = null,
    /** JSON-список [Benefit] (п. 3.1: допускаются обе льготы) */
    val benefitsJson: String = "[]",
    val repName: String? = null,
    val phone1: String? = null,
    val phone2: String? = null,
    val cardNo: String? = null,
    val diagnosis: String? = null,
    val doctor: String? = null,
    val notes: String = "",
    val archived: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    fun benefits(): List<Benefit> = runCatching {
        kotlinx.serialization.json.Json.decodeFromString<List<Benefit>>(benefitsJson)
    }.getOrDefault(emptyList())

    companion object {
        fun benefitsToJson(list: List<Benefit>): String =
            kotlinx.serialization.json.Json.encodeToString(ListSerializer(Benefit.serializer()), list)
    }
}

@Entity(tableName = "drugs", indices = [Index("name")])
@Serializable
data class Drug(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Лекарство / Расходник / Питание — свободный выбор (п. 3.2) */
    val category: String? = null,
    /** Упаковка / Флакон / Банка / Штука */
    val unit: String = "упаковка",
    /** Штук в упаковке: «уп. №5» = 5; для штуки = 1 */
    val piecesPerPack: Int = 1,
    /** Концентрация МЕ/мл (инсулины) */
    val concMePerMl: Int? = null,
    /** Объём, мл (картридж/флакон) */
    val volumeMl: Int? = null,
    /** Концентрация % (спирт) */
    val concPercent: Int? = null,
    /** Название штуки в 3 форматах для документов: «картридж/картриджа/картриджей» */
    val pieceNounForms: String? = null,
    val archived: Boolean = false,
    val updatedAt: Long = 0,
) {
    /** МЕ в штуке = концентрация × объём (п. 3.2), если оба заданы. */
    fun mePerPiece(): Int? =
        if (concMePerMl != null && volumeMl != null && concMePerMl > 0 && volumeMl > 0) concMePerMl * volumeMl else null
}

@Entity(tableName = "prescriptions", indices = [Index("patientId"), Index("drugId")])
@Serializable
data class Prescription(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patientId: Long,
    val drugId: Long,
    /** ACTIVE / ARCHIVE (п. 3.3) */
    val status: String = "ACTIVE",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Entity(tableName = "norms", indices = [Index("prescriptionId")])
@Serializable
data class Norm(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val prescriptionId: Long,
    /** epochDay, с которого действует запись (п. 3.3) */
    val validFrom: Long,
    /** PER_DAY / PER_MONTH / PER_YEAR / EVERY_X_DAYS */
    val pieceKind: String? = null,
    val pieceAmount: Int? = null,
    val everyXDays: Int? = null,
    /** МЕ (ЕД) в сутки — только при заданной концентрации и объёме */
    val meAmount: Int? = null,
    /** «без расчёта срока» — резерв/разовые (вид е) */
    val noCalc: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Entity(tableName = "issues", indices = [Index("prescriptionId"), Index("rxId")])
@Serializable
data class Issue(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val prescriptionId: Long,
    /** epochDay */
    val date: Long,
    val enteredPacks: Int = 0,
    val enteredPieces: Int = 0,
    /** Снимок: штук в упаковке на момент выдачи (п. 3.4) */
    val piecesPerPackAtIssue: Int,
    /** enteredPacks × piecesPerPackAtIssue + enteredPieces; у начального остатка заполнен всегда */
    val totalPieces: Int,
    val note: String? = null,
    val rxId: Long? = null,
    /** Начальный остаток (п. 3.4, п. 10) */
    val isInitialBalance: Boolean = false,
    /** Отображаемая дата «обеспечен до» у начального остатка — только для показа */
    val shownUntil: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Entity(tableName = "rxs", indices = [Index("patientId"), Index("prescriptionId")])
@Serializable
data class Rx(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patientId: Long,
    val prescriptionId: Long? = null,
    val series: String = "",
    val number: String = "",
    /** epochDay выдачи рецепта */
    val issueDate: Long? = null,
    /** 30 / 60 / 90 дней */
    val validDays: Int? = null,
    /** точная дата «действителен до» (альтернатива validDays) */
    val validUntil: Long? = null,
    /** ISSUED (выписан) / REDEEMED (отоварен); «истёк» вычисляется на лету (п. 3.5) */
    val status: String = "ISSUED",
    val ocrText: String? = null,
    /** JSON-список путей фото (относительно filesDir) */
    val photoPaths: String = "[]",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    fun photos(): List<String> = runCatching {
        kotlinx.serialization.json.Json.decodeFromString<List<String>>(photoPaths)
    }.getOrDefault(emptyList())

    companion object {
        fun photosToJson(list: List<String>): String =
            kotlinx.serialization.json.Json.encodeToString(ListSerializer(String.serializer()), list)
    }
}

/** Удаления для быстрых копий (п. 9.8): id, тип, время удаления. */
@Entity(tableName = "tombstones", indices = [Index("entityType", "entityId")])
@Serializable
data class Tombstone(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String,
    val entityId: Long,
    val deletedAt: Long,
)
