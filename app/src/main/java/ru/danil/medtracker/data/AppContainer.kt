package ru.danil.medtracker.data

import android.content.Context
import ru.danil.medtracker.data.db.AppDatabase

/** Простой контейнер зависимостей без фреймворков. */
class AppContainer(context: Context) {
    val db: AppDatabase = AppDatabase.get(context)
    val settings: SettingsStore = SettingsStore(context)
    val repo: Repo = Repo(db, settings)
}
