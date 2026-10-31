package ru.danil.medtracker

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.danil.medtracker.data.AppContainer
import ru.danil.medtracker.notify.AlarmScheduler

class App : Application() {
    lateinit var container: AppContainer
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Перепланируем ежедневную проверку при каждом запуске (п. 7.1)
        appScope.launch {
            val settings = container.settings.current()
            if (settings.notificationsEnabled) {
                AlarmScheduler.scheduleNext(this@App, settings)
            }
        }
    }
}
