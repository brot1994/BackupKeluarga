package id.bram.backupkeluarga.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import id.bram.backupkeluarga.Prefs
import id.bram.backupkeluarga.Scheduler

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BKTheme { AppRoot() } }
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    var signedIn by remember { mutableStateOf(Prefs(context).isSignedIn) }

    LaunchedEffect(signedIn) {
        if (signedIn) Scheduler.start(context)
    }

    if (!signedIn) {
        SetupFlow(onDone = { signedIn = true })
    } else {
        HomeScreen(onSignedOut = { signedIn = false })
    }
}
