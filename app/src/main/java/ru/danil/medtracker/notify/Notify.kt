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
import ru.danil.medtracker.calc.CalcService
import ru.danil.medtracker.data.AppSettings
import ru.danil.medtracker.util.Dates
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Ежедневная проверка запасов: AlarmManager с точным временем (п. 7.1). */
object AlarmScheduler {

    private const val WORK_FALLBACK = "medtracker_daily_check_fallback"

    fun scheduleNext(context: Context, settings: AppSettings) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atStartOfDay().plusMinutes(settings.checkTimeMinutes.toLong())
        if (!next.isAfter(now)) next = next.plusDays(1)
        val millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val intent = Intent(context, AlarmReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context, 1001, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val canExact = if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true
        runCatching {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            }
        }
        // Запасной вариант — WorkManager с допуском (п. 7.1)
        val work = PeriodicWorkRequestBuilder<CheckWorker>(12, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_FALLBACK, ExistingPeriodicWorkPolicy.KEEP, work)
    }

    fun onBoot(context: Context) {
        val app = context.applicationContext as App
        runBlocking {
            val settings = app.container.settings.current()
            if (settings.notificationsEnabled) scheduleNext(context, settings)
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

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AlarmScheduler.onBoot(context)
        }
    }
}

/** WorkManager-страховка: если будильник пропущен, проверка выполнится здесь. */
class CheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val app = applicationContext as App
        runBlocking { DailyCheck.perform(app.container, applicationContext) }
        return Result.success()
    }
}

/** Сама проверка: одно сводное уведомление в день (п. 7.2, 7.3). */
object DailyCheck {

    const val CHANNEL_ID = "medtracker_urgent"

    data class Events(
        val lowStock: List<String>,
        val rxBelow: List<String>,
    )

    suspend fun compute(container: ru.danil.medtracker.data.AppContainer, today: LocalDate = Dates.today()): Events {
        val repo = container.repo
        val settings = container.settings.current()
        return withContext(Dispatchers.Default) {
            val patients = repo.patientDao.all().filter { !it.archived }.associateBy { it.id }
            val drugs = repo.drugDao.all().associateBy { it.id }
            val normsByP = repo.normDao.all().groupBy { it.prescriptionId }
            val issuesByP = repo.issueDao.all().groupBy { it.prescriptionId }
            val low = mutableListOf<String>()
            val threshold = settings.drugThresholdDays
            for (p in repo.prescriptionDao.allActive()) {
                val patient = patients[p.patientId] ?: continue
                val drug = drugs[p.drugId] ?: continue
                val ns = normsByP[p.id].orEmpty()
                if (CalcService.isNoCalc(ns, today)) continue
                val supply = CalcService.supplyFor(issuesByP[p.id].orEmpty(), ns, drug)
                val until = supply.coveredUntil() ?: continue
                if (!until.isAfter(today.plusDays(threshold.toLong()))) {
                    low.add("${patient.fio} — ${drug.name}, обеспечен до ${Dates.formatRu(until)}")
                }
            }
            val rxThreshold = settings.rxThresholdDays
            val rxLow = repo.rxDao.allIssued().mapNotNull { rx ->
                val patient = patients[rx.patientId] ?: return@mapNotNull null
                val until = CalcService.rxValidUntil(rx.issueDate, rx.validDays, rx.validUntil)
                    ?: return@mapNotNull null
                if (!until.isAfter(today.plusDays(rxThreshold.toLong()))) {
                    "Пора выписать новый рецепт: ${patient.fio}"
                } else null
            }
            Events(lowStock = low, rxBelow = rxLow)
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
        // Напоминание о копиях: не было дольше 7 дней (п. 9.4)
        val backupReminder = if (settings.lastBackupAt > 0 &&
            System.currentTimeMillis() - settings.lastBackupAt > 7L * 24 * 3600 * 1000
        ) {
            listOf("Резервная копия не создавалась более 7 дней")
        } else emptyList()
        if (events.lowStock.isEmpty() && events.rxBelow.isEmpty() && backupReminder.isEmpty()) {
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
        val lines = (events.lowStock + events.rxBelow + backupReminder)
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
