package ru.danil.medtracker.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Настройки приложения (п. 5.7 ТЗ, Ф5 ТЗ 1.2.0). В копию входят только переносимые (Ф5.3). */
data class AppSettings(
    val onboardingDone: Boolean = false,
    val notificationsEnabled: Boolean = true,
    /** Минуты от полуночи, по умолчанию 08:00 */
    val checkTimeMinutes: Int = 8 * 60,
    /** Время напоминаний об анализах (Ф3.7/Ф5.1), по умолчанию 08:00 */
    val analysisTimeMinutes: Int = 8 * 60,
    val drugThresholdDays: Int = 7,
    val rxThresholdDays: Int = 14,
    /** URI каталога копий (SAF, п. 9.1) */
    val backupTreeUri: String = "",
    val backupTimeMinutes: Int = 21 * 60,
    /** Последняя полная копия: UUID цепочки и время (для быстрых копий, п. 9.2) */
    val lastFullId: String = "",
    val lastFullAt: Long = 0,
    val lastBackupAt: Long = 0,
    /** Чтобы сводное уведомление было одно в день (п. 7.2) */
    val lastNotifiedDay: Long = 0,
    /** Чтобы напоминаний об анализах было не больше серии в день (Ф3.7) */
    val lastAnalysisNotifiedDay: Long = 0,
    /** patientId → epochDay последнего напоминания «нет данных» (Ф3.7 п.2: не чаще раза в 7 дней). Служебное, в копию не входит. */
    val analysisNoDataRemindedJson: String = "{}",
    val hyperOsHintShown: Boolean = false,
    /** Порог напоминания о давности копии, дней; 0 = не напоминать (Ф8). Переносимая — входит в копию. */
    val backupReminderDays: Int = 14,
    /** Крупный шрифт для всего приложения (Ф11). Переносимая — входит в копию. */
    val largeFont: Boolean = false,
    /** Подписи под значками нижних вкладок (доп. пожелание к v1.3.0). Переносимая — входит в копию. */
    val showTabLabels: Boolean = true,
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val ONBOARDING = booleanPreferencesKey("onboarding_done")
        val NOTIF_ENABLED = booleanPreferencesKey("notif_enabled")
        val CHECK_TIME = intPreferencesKey("check_time")
        val ANALYSIS_TIME = intPreferencesKey("analysis_time")
        val DRUG_THRESHOLD = intPreferencesKey("drug_threshold")
        val RX_THRESHOLD = intPreferencesKey("rx_threshold")
        val BACKUP_TREE = stringPreferencesKey("backup_tree")
        val BACKUP_TIME = intPreferencesKey("backup_time")
        val LAST_FULL_ID = stringPreferencesKey("last_full_id")
        val LAST_FULL_AT = longPreferencesKey("last_full_at")
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
        val LAST_NOTIFIED_DAY = longPreferencesKey("last_notified_day")
        val ANALYSIS_NOTIFIED_DAY = longPreferencesKey("analysis_notified_day")
        val ANALYSIS_NODATA_REMINDED = stringPreferencesKey("analysis_nodata_reminded")
        val HYPEROS_SHOWN = booleanPreferencesKey("hyperos_shown")
        val BACKUP_REMINDER_DAYS = intPreferencesKey("backup_reminder_days")
        val LARGE_FONT = booleanPreferencesKey("large_font")
        val SHOW_TAB_LABELS = booleanPreferencesKey("show_tab_labels")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            onboardingDone = p[Keys.ONBOARDING] ?: false,
            notificationsEnabled = p[Keys.NOTIF_ENABLED] ?: true,
            checkTimeMinutes = p[Keys.CHECK_TIME] ?: 8 * 60,
            analysisTimeMinutes = p[Keys.ANALYSIS_TIME] ?: 8 * 60,
            drugThresholdDays = p[Keys.DRUG_THRESHOLD] ?: 7,
            rxThresholdDays = p[Keys.RX_THRESHOLD] ?: 14,
            backupTreeUri = p[Keys.BACKUP_TREE] ?: "",
            backupTimeMinutes = p[Keys.BACKUP_TIME] ?: 21 * 60,
            lastFullId = p[Keys.LAST_FULL_ID] ?: "",
            lastFullAt = p[Keys.LAST_FULL_AT] ?: 0,
            lastBackupAt = p[Keys.LAST_BACKUP_AT] ?: 0,
            lastNotifiedDay = p[Keys.LAST_NOTIFIED_DAY] ?: 0,
            lastAnalysisNotifiedDay = p[Keys.ANALYSIS_NOTIFIED_DAY] ?: 0,
            analysisNoDataRemindedJson = p[Keys.ANALYSIS_NODATA_REMINDED] ?: "{}",
            hyperOsHintShown = p[Keys.HYPEROS_SHOWN] ?: false,
            backupReminderDays = p[Keys.BACKUP_REMINDER_DAYS] ?: 14,
            largeFont = p[Keys.LARGE_FONT] ?: false,
            showTabLabels = p[Keys.SHOW_TAB_LABELS] ?: true,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setOnboardingDone() = context.dataStore.edit { it[Keys.ONBOARDING] = true }
    suspend fun setNotificationsEnabled(v: Boolean) = context.dataStore.edit { it[Keys.NOTIF_ENABLED] = v }
    suspend fun setCheckTime(minutes: Int) = context.dataStore.edit { it[Keys.CHECK_TIME] = minutes }
    suspend fun setAnalysisTime(minutes: Int) = context.dataStore.edit { it[Keys.ANALYSIS_TIME] = minutes }
    suspend fun setDrugThreshold(days: Int) = context.dataStore.edit { it[Keys.DRUG_THRESHOLD] = days }
    suspend fun setRxThreshold(days: Int) = context.dataStore.edit { it[Keys.RX_THRESHOLD] = days }
    suspend fun setBackupReminderDays(days: Int) = context.dataStore.edit { it[Keys.BACKUP_REMINDER_DAYS] = days }
    suspend fun setLargeFont(v: Boolean) = context.dataStore.edit { it[Keys.LARGE_FONT] = v }
    suspend fun setShowTabLabels(v: Boolean) = context.dataStore.edit { it[Keys.SHOW_TAB_LABELS] = v }
    suspend fun setBackupTree(uri: String) = context.dataStore.edit { it[Keys.BACKUP_TREE] = uri }
    suspend fun setBackupTime(minutes: Int) = context.dataStore.edit { it[Keys.BACKUP_TIME] = minutes }
    suspend fun setHyperOsShown() = context.dataStore.edit { it[Keys.HYPEROS_SHOWN] = true }
    suspend fun setLastNotifiedDay(day: Long) = context.dataStore.edit { it[Keys.LAST_NOTIFIED_DAY] = day }
    suspend fun setLastAnalysisNotifiedDay(day: Long) = context.dataStore.edit { it[Keys.ANALYSIS_NOTIFIED_DAY] = day }

    /** patientId → epochDay последнего напоминания «нет данных» (Ф3.7 п.2). */
    fun analysisNoDataReminded(s: AppSettings): Map<Long, Long> = runCatching {
        kotlinx.serialization.json.Json.decodeFromString<Map<Long, Long>>(s.analysisNoDataRemindedJson)
    }.getOrDefault(emptyMap())

    suspend fun setAnalysisNoDataReminded(map: Map<Long, Long>) = context.dataStore.edit {
        it[Keys.ANALYSIS_NODATA_REMINDED] = kotlinx.serialization.json.Json.encodeToString(map)
    }

    suspend fun markFullBackup(fullId: String, at: Long) = context.dataStore.edit {
        it[Keys.LAST_FULL_ID] = fullId
        it[Keys.LAST_FULL_AT] = at
        it[Keys.LAST_BACKUP_AT] = at
    }

    suspend fun markDeltaBackup(at: Long) = context.dataStore.edit { it[Keys.LAST_BACKUP_AT] = at }

    fun newFullId(): String = UUID.randomUUID().toString()
}
