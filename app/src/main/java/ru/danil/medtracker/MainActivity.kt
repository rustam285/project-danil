package ru.danil.medtracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.danil.medtracker.data.AppSettings
import ru.danil.medtracker.ui.screens.AnalysesScreen
import ru.danil.medtracker.ui.screens.IssueFormScreen
import ru.danil.medtracker.ui.screens.OnboardingScreen
import ru.danil.medtracker.ui.screens.PatientCardScreen
import ru.danil.medtracker.ui.screens.PatientEditScreen
import ru.danil.medtracker.ui.screens.PatientsScreen
import ru.danil.medtracker.ui.screens.PrescriptionEditScreen
import ru.danil.medtracker.ui.screens.PrescriptionScreen
import ru.danil.medtracker.ui.screens.RxEditScreen
import ru.danil.medtracker.ui.screens.RxScanScreen
import ru.danil.medtracker.ui.screens.SettingsScreen
import ru.danil.medtracker.ui.screens.UrgentScreen
import ru.danil.medtracker.ui.screens.CopyPrescriptionsScreen
import ru.danil.medtracker.ui.AutoSizeText
import ru.danil.medtracker.ui.theme.LARGE_FONT_SCALE
import ru.danil.medtracker.ui.theme.MedTrackerTheme

object Routes {
    const val ONBOARDING = "onboarding"
    const val PATIENTS = "patients"
    const val ANALYSES = "analyses"
    const val URGENT = "urgent"
    const val RX_SCAN = "rxScan"
    const val SETTINGS = "settings"
    const val PATIENT = "patient/{id}"
    const val PATIENT_EDIT = "patientEdit?patientId={patientId}"
    const val PRESCRIPTION = "prescription/{id}"
    const val PRESCRIPTION_EDIT = "prescriptionEdit?patientId={patientId}&prescriptionId={prescriptionId}"
    const val ISSUE_EDIT = "issueEdit/{prescriptionId}?issueId={issueId}&date={date}&rxId={rxId}"
    const val RX_EDIT = "rxEdit/{patientId}?rxId={rxId}&prescriptionId={prescriptionId}"
    const val COPY_PRESCRIPTIONS = "copyPrescriptions/{patientId}"

    fun patient(id: Long) = "patient/$id"
    fun patientEdit(patientId: Long?) = "patientEdit?patientId=${patientId ?: -1L}"
    fun prescription(id: Long) = "prescription/$id"
    fun prescriptionEdit(patientId: Long, prescriptionId: Long?) =
        "prescriptionEdit?patientId=$patientId&prescriptionId=${prescriptionId ?: -1L}"

    /**
     * [defaultDate] — дата рецепта для сценария «Выдать» из скан-потока (Ф1.4); null = сегодня.
     * [rxId] — рецепт для предвыбора чипа в форме выдачи (Ф13.5 §6.3); null = без рецепта.
     */
    fun issueEdit(prescriptionId: Long, issueId: Long?, defaultDate: Long? = null, rxId: Long? = null) =
        "issueEdit/$prescriptionId?issueId=${issueId ?: -1L}&date=${defaultDate ?: -1L}&rxId=${rxId ?: -1L}"

    /** Правка существующего назначения: patientId берётся из самого назначения. */
    fun rxEdit(patientId: Long, rxId: Long?, prescriptionId: Long? = null) =
        "rxEdit/$patientId?rxId=${rxId ?: -1L}&prescriptionId=${prescriptionId ?: -1L}"
    fun copyPrescriptions(patientId: Long) = "copyPrescriptions/$patientId"
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Запрос POST_NOTIFICATIONS с пояснением при первом запуске (п. 7.1)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        val openUrgent = intent?.getBooleanExtra("openUrgent", false) == true
        val openAnalyses = intent?.getBooleanExtra("openAnalyses", false) == true
        setContent {
            // Крупный шрифт (Ф11): настройка применяется ко всему приложению через тему
            val appSettings by (applicationContext as App).container.settings.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            MedTrackerTheme(fontScale = if (appSettings.largeFont) LARGE_FONT_SCALE else 1f) {
                MedTrackerApp(startAtUrgent = openUrgent, startAtAnalyses = openAnalyses)
            }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: @Composable () -> Unit)

@Composable
fun MedTrackerApp(startAtUrgent: Boolean, startAtAnalyses: Boolean = false) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val appSettings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val onboardingDone = appSettings.onboardingDone

    // 5 вкладок (Ф3.1): Пациенты, Анализы, Срочное, Рецепт, Настройки
    val tabs = listOf(
        Tab(Routes.PATIENTS, "Пациенты") { Icon(Icons.Filled.Person, null) },
        Tab(Routes.ANALYSES, "Анализы") { Icon(Icons.Filled.Science, null) },
        Tab(Routes.URGENT, "Срочное") { Icon(Icons.Filled.NotificationsActive, null) },
        Tab(Routes.RX_SCAN, "Рецепт") { Icon(Icons.Filled.CameraAlt, null) },
        Tab(Routes.SETTINGS, "Настройки") { Icon(Icons.Filled.Settings, null) },
    )

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = currentRoute in tabs.map { it.route }

    androidx.compose.material3.Surface {
        Scaffold(
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = currentRoute == tab.route,
                                onClick = {
                                    nav.navigate(tab.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = tab.icon,
                                // Подписи вкладок: всегда в одну строку (автоуменьшение шрифта);
                                // скрываются целиком настройкой (v1.3.0)
                                label = if (appSettings.showTabLabels) {
                                    { AutoSizeText(tab.label) }
                                } else null,
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = when {
                    !onboardingDone -> Routes.ONBOARDING
                    startAtUrgent -> Routes.URGENT
                    startAtAnalyses -> Routes.ANALYSES
                    else -> Routes.PATIENTS
                },
                modifier = Modifier.padding(padding),
            ) {
                composable(Routes.ONBOARDING) {
                    OnboardingScreen(onDone = {
                        nav.navigate(Routes.PATIENTS) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                    })
                }
                composable(Routes.PATIENTS) {
                    PatientsScreen(
                        onOpenPatient = { nav.navigate(Routes.patient(it)) },
                        onAddPatient = { nav.navigate(Routes.patientEdit(null)) },
                    )
                }
                composable(Routes.ANALYSES) {
                    AnalysesScreen(
                        onOpenPatient = { nav.navigate(Routes.patient(it)) },
                    )
                }
                composable(Routes.URGENT) {
                    UrgentScreen(
                        onOpenPrescription = { nav.navigate(Routes.prescription(it)) },
                        onOpenRx = { patientId, _ -> nav.navigate(Routes.patient(patientId)) },
                        onOpenIssueForm = { pid -> nav.navigate(Routes.issueEdit(pid, null)) },
                    )
                }
                composable(Routes.RX_SCAN) {
                    RxScanScreen(
                        onIssue = { pid, rxDate, rxId ->
                            nav.navigate(Routes.issueEdit(pid, null, rxDate, rxId))
                        },
                        onOpenPrescription = { nav.navigate(Routes.prescription(it)) },
                    )
                }
                composable(Routes.SETTINGS) { SettingsScreen() }
                composable(Routes.PATIENT) {
                    PatientCardScreen(
                        patientId = it.arguments?.getString("id")?.toLongOrNull() ?: return@composable,
                        onEdit = { nav.navigate(Routes.patientEdit(it)) },
                        onAddPrescription = { nav.navigate(Routes.prescriptionEdit(it, null)) },
                        onOpenPrescription = { nav.navigate(Routes.prescription(it)) },
                        onAddIssue = { nav.navigate(Routes.issueEdit(it, null)) },
                        onAddRx = { patientId, prescriptionId -> nav.navigate(Routes.rxEdit(patientId, null, prescriptionId)) },
                        onCopyPrescriptions = { nav.navigate(Routes.copyPrescriptions(it)) },
                        onOpenRx = { patientId, rxId -> nav.navigate(Routes.rxEdit(patientId, rxId)) },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(
                    Routes.PATIENT_EDIT,
                    arguments = listOf(navArgument("patientId") {
                        type = NavType.StringType; defaultValue = "-1"
                    }),
                ) {
                    PatientEditScreen(
                        patientId = it.arguments?.getString("patientId")?.toLongOrNull()?.takeIf { v -> v > 0 },
                        onSaved = { nav.popBackStack() },
                        onCancel = { nav.popBackStack() },
                    )
                }
                composable(Routes.PRESCRIPTION) {
                    PrescriptionScreen(
                        prescriptionId = it.arguments?.getString("id")?.toLongOrNull() ?: return@composable,
                        onAddIssue = { pid -> nav.navigate(Routes.issueEdit(pid, null)) },
                        onEditIssue = { p, i -> nav.navigate(Routes.issueEdit(p, i)) },
                        onAddRx = { patientId, prescriptionId -> nav.navigate(Routes.rxEdit(patientId, null, prescriptionId)) },
                        onEdit = { patientId, prescriptionId ->
                            nav.navigate(Routes.prescriptionEdit(patientId, prescriptionId))
                        },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(
                    Routes.PRESCRIPTION_EDIT,
                    arguments = listOf(
                        navArgument("patientId") { type = NavType.StringType; defaultValue = "-1" },
                        navArgument("prescriptionId") { type = NavType.StringType; defaultValue = "-1" },
                    ),
                ) {
                    PrescriptionEditScreen(
                        patientId = it.arguments?.getString("patientId")?.toLongOrNull() ?: return@composable,
                        prescriptionId = it.arguments?.getString("prescriptionId")?.toLongOrNull()?.takeIf { v -> v > 0 },
                        onSaved = { nav.popBackStack() },
                        onCancel = { nav.popBackStack() },
                    )
                }
                composable(
                    Routes.ISSUE_EDIT,
                    arguments = listOf(
                        navArgument("prescriptionId") { type = NavType.StringType; defaultValue = "-1" },
                        navArgument("issueId") { type = NavType.StringType; defaultValue = "-1" },
                        navArgument("date") { type = NavType.StringType; defaultValue = "-1" },
                        navArgument("rxId") { type = NavType.StringType; defaultValue = "-1" },
                    ),
                ) {
                    IssueFormScreen(
                        prescriptionId = it.arguments?.getString("prescriptionId")?.toLongOrNull() ?: return@composable,
                        issueId = it.arguments?.getString("issueId")?.toLongOrNull()?.takeIf { v -> v > 0 },
                        defaultDate = it.arguments?.getString("date")?.toLongOrNull()?.takeIf { v -> v > 0 },
                        preselectRxId = it.arguments?.getString("rxId")?.toLongOrNull()?.takeIf { v -> v > 0 },
                        onSaved = { nav.popBackStack() },
                        onCancel = { nav.popBackStack() },
                    )
                }
                composable(
                    Routes.RX_EDIT,
                    arguments = listOf(
                        navArgument("patientId") { type = NavType.StringType; defaultValue = "-1" },
                        navArgument("rxId") { type = NavType.StringType; defaultValue = "-1" },
                        navArgument("prescriptionId") { type = NavType.StringType; defaultValue = "-1" },
                    ),
                ) {
                    RxEditScreen(
                        patientId = it.arguments?.getString("patientId")?.toLongOrNull() ?: return@composable,
                        rxId = it.arguments?.getString("rxId")?.toLongOrNull()?.takeIf { v -> v > 0 },
                        prescriptionId = it.arguments?.getString("prescriptionId")?.toLongOrNull()?.takeIf { v -> v > 0 },
                        onSaved = { nav.popBackStack() },
                        onCancel = { nav.popBackStack() },
                    )
                }
                composable(Routes.COPY_PRESCRIPTIONS) {
                    CopyPrescriptionsScreen(
                        patientId = it.arguments?.getString("patientId")?.toLongOrNull() ?: return@composable,
                        onDone = { nav.popBackStack() },
                    )
                }
            }
        }
    }
}
