package ru.danil.medtracker.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import ru.danil.medtracker.App
import ru.danil.medtracker.MainActivity
import ru.danil.medtracker.data.AnalysisReminder
import ru.danil.medtracker.data.AnalysisReminders
import ru.danil.medtracker.data.AppSettings
import ru.danil.medtracker.util.Dates
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Ежедневные проверки: сводка запасов (п. 7.1) и напоминания об анализах (Ф3.7) —
 * два независимых будильника с разными request code и временем (Ф3.7 п.6).
 * Планирование идемпотентно: cancel → set, PendingIntent с FLAG_UPDATE_CURRENT.
 */
object AlarmScheduler {

    const val RC_DAILY = 1001
    const val RC_ANALYSIS = 1002

    private const val WORK_FALLBACK = "medtracker_daily_check_fallback"

    /** Следующий момент срабатывания: сегодня в [minutes], если ещё не прошло — завтра (чистая функция, покрыта тестом). */
    fun nextTriggerTime(now: LocalDateTime, minutes: Int): LocalDateTime {
        var next = now.toLocalDate().atStartOfDay().plusMinutes(minutes.toLong())
        if (!next.isAfter(now)) next = next.plusDays(1)
        return next
    }

    private fun at(context: Context, millis: Long, requestCode: Int, receiver: Class<*>): PendingIntent {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, receiver)
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // идемпотентность (Ф3.7 п.6): сначала снимаем предыдущий, затем ставим заново
        am.cancel(pi)
        val canExact = if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true
        runCatching {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            }
        }
        return pi
    }

    private fun millisOf(t: LocalDateTime): Long = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun scheduleNext(context: Context, settings: AppSettings) {
        val millis = millisOf(nextTriggerTime(LocalDateTime.now(), settings.checkTimeMinutes))
        at(context, millis, RC_DAILY, AlarmReceiver::class.java)
        // Запасной вариант — WorkManager с допуском (п. 7.1); выполняет обе проверки
        val work = PeriodicWorkRequestBuilder<CheckWorker>(12, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_FALLBACK, ExistingPeriodicWorkPolicy.KEEP, work)
    }

    fun scheduleNextAnalysis(context: Context, settings: AppSettings) {
        val millis = millisOf(nextTriggerTime(LocalDateTime.now(), settings.analysisTimeMinutes))
        at(context, millis, RC_ANALYSIS, AnalysisAlarmReceiver::class.java)
    }

    fun scheduleAll(context: Context, settings: AppSettings) {
        scheduleNext(context, settings)
        scheduleNextAnalysis(context, settings)
    }

    fun onBoot(context: Context) {
        val app = context.applicationContext as App
        runBlocking {
            val settings = app.container.settings.current()
            if (settings.notificationsEnabled) scheduleAll(context, settings)
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as App
        val result = goAsync()
        Thread {
            try {
                runBlocking { DailyCheck.perform(app.container, context) }
            } finally {
                result.finish()
            }
        }.start()
    }
}

/** Отдельный будильник напоминаний об анализах (Ф3.7 п.6). */
class AnalysisAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as App
        val result = goAsync()
        Thread {
            try {
                runBlocking { AnalysisCheck.perform(app.container, context) }
            } finally {
                result.finish()
            }
        }.start()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AlarmScheduler.onBoot(context)
        }
    }
}

/** WorkManager-страховка: если будильник пропущен, обе проверки выполнятся здесь. */
class CheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val app = applicationContext as App
        runBlocking {
            DailyCheck.perform(app.container, applicationContext)
            AnalysisCheck.perform(app.container, applicationContext)
        }
        return Result.success()
    }
}

/** Сама проверка: одно сводное уведомление в день (п. 7.2, 7.3). Логика — в [DailyEvents]. */
object DailyCheck {

    const val CHANNEL_ID = "medtracker_urgent"

    suspend fun compute(container: ru.danil.medtracker.data.AppContainer, today: LocalDate = Dates.today()): DailyEvents.Events {
        val repo = container.repo
        val settings = container.settings.current()
        return withContext(Dispatchers.Default) {
            DailyEvents.compute(
                patients = repo.patientDao.all(),
                prescriptions = repo.prescriptionDao.allActive(),
                drugs = repo.drugDao.all(),
                norms = repo.normDao.all(),
                issues = repo.issueDao.all(),
                rxs = repo.rxDao.allIssued(),
                settings = settings,
                today = today,
            )
        }
    }

    suspend fun perform(container: ru.danil.medtracker.data.AppContainer, context: Context) {
        val settings = container.settings.current()
        AlarmScheduler.scheduleNext(context, settings)
        if (!settings.notificationsEnabled) return
        val today = Dates.today()
        val todayEpoch = Dates.toEpochDay(today)
        // одно сводное в день
        if (settings.lastNotifiedDay == todayEpoch) return

        val events = compute(container, today)
        // Напоминание о давности копии — строкой в сводке, порог настраивается (Ф8.1)
        val backupReminder = DailyEvents.backupReminderLine(
            lastBackupAt = settings.lastBackupAt,
            thresholdDays = settings.backupReminderDays,
            now = System.currentTimeMillis(),
        )?.let { listOf(it) } ?: emptyList()
        // Просроченные рецепты — строкой в сводке (Ф12.1)
        val expiredRxLine = if (events.expiredRxCount > 0) {
            listOf("Просрочено рецептов: ${events.expiredRxCount}")
        } else emptyList()
        if (events.lowStock.isEmpty() && events.rxBelow.isEmpty() &&
            backupReminder.isEmpty() && expiredRxLine.isEmpty()
        ) {
            container.settings.setLastNotifiedDay(todayEpoch)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Напоминания о запасах", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val lines = (events.lowStock + events.rxBelow + backupReminder + expiredRxLine)
        val open = Intent(context, MainActivity::class.java).apply {
            putExtra("openUrgent", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 2001, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val style = NotificationCompat.InboxStyle()
            .setBigContentTitle("Med Tracker: требует внимания")
        lines.take(5).forEach { style.addLine(it) }
        if (lines.size > 5) style.setSummaryText("…и ещё ${lines.size - 5}")
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Med Tracker: требует внимания")
            .setContentText(if (lines.size == 1) lines.first() else "Событий: ${lines.size}")
            .setStyle(style)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setNumber(lines.size)
            .build()
        NotificationManagerCompat.from(context).notify(3001, notif)
        container.settings.setLastNotifiedDay(todayEpoch)
    }
}

/**
 * Напоминания об анализах (Ф3.7): отдельные уведомления, отдельный канал, своё время.
 * Одно уведомление на пациента в день; правила — в [AnalysisReminders] (чистая функция).
 */
object AnalysisCheck {

    const val CHANNEL_ID = "medtracker_analyses"
    private const val NOTIF_ID_BASE = 4000

    suspend fun compute(
        container: ru.danil.medtracker.data.AppContainer,
        today: LocalDate = Dates.today(),
    ): List<AnalysisReminder> {
        val repo = container.repo
        return withContext(Dispatchers.Default) {
            AnalysisReminders.compute(
                patients = repo.patientDao.all(),
                types = repo.analysisTypeDao.all(),
                records = repo.analysisRecordDao.all(),
                today = today,
            )
        }
    }

    suspend fun perform(container: ru.danil.medtracker.data.AppContainer, context: Context) {
        val settings = container.settings.current()
        AlarmScheduler.scheduleNextAnalysis(context, settings)
        if (!settings.notificationsEnabled) return
        val today = Dates.today()
        val todayEpoch = Dates.toEpochDay(today)
        if (settings.lastAnalysisNotifiedDay == todayEpoch) return

        val reminders = compute(container, today)
        if (reminders.isEmpty()) {
            container.settings.setLastAnalysisNotifiedDay(todayEpoch)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Анализы", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val noDataDays = container.settings.analysisNoDataReminded(settings).toMutableMap()
        for (r in reminders) {
            // заголовок и текст — по образцу ТЗ: «Пора сдавать анализ» / «ФИО — вид, последний 12.08»
            val open = Intent(context, MainActivity::class.java).apply {
                putExtra("openAnalyses", true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context, (2002 + r.patientId % 1000).toInt(), open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val style = NotificationCompat.InboxStyle().setBigContentTitle("Пора сдавать анализ: ${r.fio}")
            r.lines.take(5).forEach { style.addLine(it) }
            if (r.lines.size > 5) style.setSummaryText("…и ещё ${r.lines.size - 5}")
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("Пора сдавать анализ")
                .setContentText("${r.fio} — ${r.lines.first()}")
                .setStyle(style)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setNumber(r.lines.size)
                .build()
            NotificationManagerCompat.from(context).notify((NOTIF_ID_BASE + r.patientId).toInt(), notif)
            if (r.hasNoData) noDataDays[r.patientId] = todayEpoch
        }
        container.settings.setAnalysisNoDataReminded(noDataDays)
        container.settings.setLastAnalysisNotifiedDay(todayEpoch)
    }
}
