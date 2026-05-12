package net.yggawg.mobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import net.yggawg.mobile.ui.VpnStateViewModel
import net.yggawg.mobile.ui.screens.*

class MainActivity : ComponentActivity() {

    private lateinit var vm: VpnStateViewModel

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vm.connect()
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored — best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        setContent {
            vm = viewModel()
            HolowbarkTheme {
                AppNavHost(
                    vm = vm,
                    onRequestVpnPermission = ::requestVpnPermission,
                )
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            // Permission already granted
            vm.connect()
        }
    }
}

// ─── Navigation ──────────────────────────────────────────────────────────────

private object Routes {
    const val HOME      = "home"
    const val IMPORT    = "import"
    const val COUNTRIES = "countries"
    const val PEERS     = "peers/{countryKey}"
    const val NETWORK   = "network"
    const val LOGS      = "logs"
    fun peers(key: String) = "peers/${java.net.URLEncoder.encode(key, "UTF-8")}"
}

@Composable
fun AppNavHost(
    vm: VpnStateViewModel,
    onRequestVpnPermission: () -> Unit,
) {
    val navController = rememberNavController()
    val currentRoute  = navController.currentBackStackEntryAsState().value?.destination?.route
    val awgConfig     by vm.awgConfig.collectAsState()
    val confTabLabel  = if (awgConfig?.isAwg == true) "AWG" else "WG"

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentRoute == Routes.HOME,
                    onClick  = { navController.popBackStack(Routes.HOME, inclusive = false) },
                    icon     = { Icon(Icons.Default.Home, "Home") },
                    label    = { Text("Home") },
                )
                NavigationBarItem(
                    selected = currentRoute == Routes.COUNTRIES,
                    onClick  = { navController.navigateSingleTop(Routes.COUNTRIES) },
                    icon     = { Icon(Icons.Default.Language, "Peers") },
                    label    = { Text("Peers") },
                )
                NavigationBarItem(
                    selected = currentRoute == Routes.NETWORK,
                    onClick  = { navController.navigateSingleTop(Routes.NETWORK) },
                    icon     = { Icon(Icons.Default.Lan, "Network") },
                    label    = { Text("Network") },
                )
                NavigationBarItem(
                    selected = currentRoute == Routes.IMPORT,
                    onClick  = { navController.navigateSingleTop(Routes.IMPORT) },
                    icon     = { Icon(Icons.Default.Settings, "$confTabLabel Config") },
                    label    = { Text(confTabLabel) },
                )
                NavigationBarItem(
                    selected = currentRoute == Routes.LOGS,
                    onClick  = { navController.navigateSingleTop(Routes.LOGS) },
                    icon     = { Icon(Icons.Default.Terminal, "Logs") },
                    label    = { Text("Logs") },
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    vm = vm,
                    onRequestVpnPermission = onRequestVpnPermission,
                    onNavigateImport    = { navController.navigate(Routes.IMPORT) },
                    onNavigateCountries = { navController.navigate(Routes.COUNTRIES) },
                    onRestartAwg        = vm::restartAwg,
                )
            }
            composable(Routes.IMPORT) {
                ImportScreen(vm = vm, onImported = { navController.popBackStack() })
            }
            composable(Routes.COUNTRIES) {
                CountryBrowserScreen(
                    vm = vm,
                    onCountrySelected = { key ->
                        navController.navigate(Routes.peers(key))
                    }
                )
            }
            composable(Routes.NETWORK) {
                YggNetworkScreen(vm = vm)
            }
            composable(Routes.LOGS) {
                LogsScreen()
            }
            composable(Routes.PEERS) { backStack ->
                val rawKey = backStack.arguments?.getString("countryKey") ?: ""
                val key    = java.net.URLDecoder.decode(rawKey, "UTF-8")
                PeerListScreen(
                    vm         = vm,
                    countryKey = key,
                    onBack     = { navController.popBackStack() },
                )
            }
        }
    }
}

private fun NavHostController.navigateSingleTop(route: String) {
    navigate(route) {
        launchSingleTop = true
        popUpTo(Routes.HOME) { saveState = true }
        restoreState = true
    }
}

// ─── Theme ───────────────────────────────────────────────────────────────────

@Composable
fun HolowbarkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(),
        content = content,
    )
}
