package live.hanab.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val ui by vm.ui.collectAsStateWithLifecycle()
                Box(Modifier.fillMaxSize()) {
                    val game = ui.game
                    when {
                        !ui.loggedIn -> LoginScreen(ui, vm)
                        game != null -> GameScreen(ui, game, vm)
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
