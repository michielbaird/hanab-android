package live.hanab.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import live.hanab.client.ui.GameScreen
import live.hanab.client.ui.LobbyScreen
import live.hanab.client.ui.PregameScreen
import live.hanab.client.ui.LoginScreen

class MainActivity : ComponentActivity() {
    private val vm: LobbyViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    // onPause rather than onStop: a foreground service may only be started while the app is still in front.
    override fun onPause() {
        super.onPause()
        // Turning the phone pauses and resumes at once; the app is not leaving.
        if (!isChangingConfigurations && vm.ui.value.loggedIn && !vm.ui.value.offline) KeepAliveService.start(this)
    }

    override fun onResume() {
        super.onResume()
        KeepAliveService.stop(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind the system bars on every Android version (it is already forced on 15+): the screens apply
        // the insets themselves, so the keyboard only moves what asks for it instead of resizing the window.
        enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // Lets the "staying connected" notification show; the app works the same if it is declined.
            registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
                .launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val ui by vm.ui.collectAsStateWithLifecycle()
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                ) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    if (AppSettings.backdrop) live.hanab.client.ui.FireworksBackdrop()
                    val game = ui.game
                    when {
                        !ui.loggedIn -> LoginScreen(ui, vm)
                        game != null -> GameScreen(ui, game, vm)
                        ui.profile != null && !ui.offline -> live.hanab.client.ui.ProfileScreen(
                            ui.profile!!, onBack = vm::closeProfile, onOpenReplay = vm::openReplay,
                        )
                        ui.offline -> androidx.compose.material3.Text(
                            "Dealing...", Modifier.align(androidx.compose.ui.Alignment.Center),
                        )
                        ui.joinedTableID != null -> PregameScreen(ui, vm)
                        else -> LobbyScreen(ui, vm)
                    }
                    if (ui.reconnecting) {
                        androidx.compose.material3.Surface(
                            color = androidx.compose.ui.graphics.Color(0xFFFFB300),
                            modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter).fillMaxWidth(),
                        ) {
                            androidx.compose.material3.Text(
                                "Connection lost - reconnecting...",
                                color = androidx.compose.ui.graphics.Color.Black,
                                modifier = Modifier.statusBarsPadding().padding(6.dp),
                            )
                        }
                    }
                }
                }
            }
        }
    }
}
