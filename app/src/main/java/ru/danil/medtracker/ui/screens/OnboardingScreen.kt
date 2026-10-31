package ru.danil.medtracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.danil.medtracker.ui.appViewModel

/** Экран приветствия первого запуска (п. 10.1). */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val vm = appViewModel { OnboardingViewModel(it) }
    Scaffold { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Med Tracker", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(
                "Карманный помощник фельдшера: пациенты, назначения, автоматический расчёт «обеспечен до», фото рецептов и документы Word. Всё работает без интернета, данные хранятся только на этом телефоне.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 24.dp),
            )
            Card(Modifier.padding(bottom = 24.dp)) {
                Text(
                    "Начните с добавления первого пациента — дальше подскажем.",
                    modifier = Modifier.padding(16.dp),
                )
            }
            Button(onClick = {
                scope.launch {
                    vm.finish()
                    onDone()
                }
            }) { Text("Начать") }
        }
    }
}

class OnboardingViewModel(container: ru.danil.medtracker.data.AppContainer) : androidx.lifecycle.ViewModel() {
    private val settings = container.settings
    suspend fun finish() = settings.setOnboardingDone()
}
