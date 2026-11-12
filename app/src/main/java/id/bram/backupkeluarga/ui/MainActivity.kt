package id.bram.backupkeluarga.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import id.bram.backupkeluarga.GalleryItem
import id.bram.backupkeluarga.Prefs
import id.bram.backupkeluarga.R
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
        MainShell(onSignedOut = { signedIn = false })
    }
}

@Composable
private fun MainShell(onSignedOut: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var viewer by remember { mutableStateOf<Pair<List<GalleryItem>, Int>?>(null) }
    val gridState = rememberLazyGridState()

    val open = viewer
    if (open != null) {
        ViewerScreen(open.first, open.second, onClose = { viewer = null })
    } else {
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    val colors = NavigationBarItemDefaults.colors(indicatorColor = AccentSoft, selectedIconColor = Accent, selectedTextColor = Accent)
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text("Beranda") },
                        colors = colors,
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(painterResource(R.drawable.ic_gallery), contentDescription = null) },
                        label = { Text("Galeri") },
                        colors = colors,
                    )
                }
            },
        ) { pad ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .consumeWindowInsets(pad)
            ) {
                if (tab == 0) {
                    HomeScreen(onSignedOut = onSignedOut)
                } else {
                    GalleryScreen(
                        gridState = gridState,
                        filter = filter,
                        onFilter = { filter = it },
                        onOpen = { list, index -> viewer = list to index },
                    )
                }
            }
        }
    }
}
