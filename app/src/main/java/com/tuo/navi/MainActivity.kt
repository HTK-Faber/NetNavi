package com.tuo.navi

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tuo.navi.core.NotifLog
import com.tuo.navi.data.ProfileStore
import com.tuo.navi.ui.*

private val traitsChildren = setOf(Screen.NOTES, Screen.MEMORY, Screen.SPRITES, Screen.ABILITIES)

class MainActivity : ComponentActivity() {
    private val vm: NaviViewModel by viewModels()
    private var summon by mutableStateOf(false)
    private var openScreen by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        summon = intent?.getBooleanExtra("summon", false) == true
        openScreen = intent?.getStringExtra("openScreen")
        setContent {
            NaviTheme {
                val profile by ProfileStore.profile.collectAsState()
                var screen by remember { mutableStateOf(Screen.HOME) }
                LaunchedEffect(openScreen) { openScreen?.let { o -> runCatching { Screen.valueOf(o) }.getOrNull()?.let { screen = it }; openScreen = null } }
                var guideFromCreation by remember { mutableStateOf(false) }
                var setupDone by remember { mutableStateOf(com.tuo.navi.data.SettingsStore.setupDone) }
                var fromSettings by remember { mutableStateOf(false) }
                BackHandler(enabled = screen != Screen.HOME || guideFromCreation) {
                    screen = when {
                        screen in traitsChildren -> Screen.TRAITS
                        screen == Screen.GUIDE && fromSettings -> Screen.SETTINGS
                        else -> Screen.HOME
                    }
                    if (screen != Screen.GUIDE) fromSettings = false
                    guideFromCreation = false
                }
                Box(Modifier.fillMaxSize().background(NaviBg)) {
                    if (profile == null) {
                        if (guideFromCreation) GuideScreen { guideFromCreation = false }
                        else Box(Modifier.statusBarsPadding().navigationBarsPadding()) { SetupWizard(onGuide = { guideFromCreation = true }) }
                    } else if (!setupDone) {
                        Box(Modifier.statusBarsPadding().navigationBarsPadding()) { PermissionsSetup { setupDone = true; vm.onHomeVisible() } }
                    } else NaviBackdrop(strong = screen != Screen.HOME) {
                        // le sottosezioni di "Le mie caratteristiche" tornano lì, le altre alla dashboard
                        val back: () -> Unit = { screen = if (screen in traitsChildren) Screen.TRAITS else if (screen == Screen.GUIDE && fromSettings) Screen.SETTINGS else Screen.HOME }
                        when (screen) {
                            Screen.HOME -> HomeScreen(vm, summon, { summon = false }) { screen = it }
                            Screen.NOTES -> NotesScreen(back)
                            Screen.SPRITES -> SpritesScreen(back)
                            Screen.GUIDE -> GuideScreen(back)
                            Screen.SETTINGS -> SettingsScreen(back, { fromSettings = true; screen = Screen.GUIDE }) { screen = Screen.CALLS }
                            Screen.MEMORY -> MemoryScreen(back)
                            Screen.ABILITIES -> AbilitiesScreen(back)
                            Screen.NOTIFS -> NotificationsScreen(back)
                            Screen.UNUSED -> UnusedAppsScreen(vm, back)
                            Screen.TRAITS -> TraitsScreen(back) { screen = it }
                            Screen.FILES -> FilesScreen(vm, back)
                            Screen.SECURITY -> com.tuo.navi.ui.SecurityScreen(vm, back) { screen = it }
                            Screen.CALLS -> com.tuo.navi.ui.CallsScreen(back)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("summon", false)) summon = true
        intent.getStringExtra("openScreen")?.let { openScreen = it }
    }

    override fun onResume() {
        super.onResume()
        if (ProfileStore.exists()) { vm.onHomeVisible(); vm.refreshApps(); vm.checkUnused(verbose = false) }
    }

    override fun onPause() { super.onPause(); vm.onHomeHidden() }

    override fun onStart() { super.onStart(); com.tuo.navi.ai.LocalLlm.cancelRelease() }
    /** Pannello chiuso: il modello resta in memoria ancora un po', poi viene liberato per non rallentare il telefono. */
    override fun onStop() { super.onStop(); com.tuo.navi.ai.LocalLlm.scheduleRelease() }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) com.tuo.navi.ai.LocalLlm.release()
    }
}
