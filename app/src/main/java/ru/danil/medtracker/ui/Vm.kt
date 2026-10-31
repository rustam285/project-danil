package ru.danil.medtracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.danil.medtracker.App
import ru.danil.medtracker.data.AppContainer

/** Лёгкая фабрика ViewModel без DI-фреймворка. */
@Composable
inline fun <reified T : ViewModel> appViewModel(crossinline create: (AppContainer) -> T): T {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    return viewModel<T>(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create(container) as VM
        },
    )
}
