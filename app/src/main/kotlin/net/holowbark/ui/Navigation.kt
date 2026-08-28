package net.holowbark.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import net.holowbark.ui.screens.*
import java.net.URLDecoder
import java.net.URLEncoder

private object Routes {
    const val HOME      = "home"
    const val IMPORT    = "import"
    const val COUNTRIES = "countries"
    const val PEERS     = "peers/{countryKey}"
    const val NETWORK   = "network"
    const val LOGS      = "logs"
    fun peers(key: String) = "peers/${URLEncoder.encode(key, "UTF-8")}"
}

@Composable
fun AppNavHost(
    vm: TunnelViewModel,
    onRequestVpnPermission: () -> Unit,
) {
    val navController = rememberNavController()
    val currentRoute  = navController.currentBackStackEntryAsState().value?.destination?.route
    val awgConfig     by vm.awgConfig.collectAsState()
    val confTabLabel  = if (awgConfig?.isAwg == true) "AWG" else "WG"   // the tab is narrow

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
                val key    = URLDecoder.decode(rawKey, "UTF-8")
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
