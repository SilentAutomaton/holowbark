package net.holowbark.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import net.holowbark.ui.screens.*
import java.net.URLDecoder
import java.net.URLEncoder

private object Routes {
    const val CONNECT   = "connect"
    const val SETTINGS  = "settings"
    const val SERVER    = "server"
    const val SELECTED  = "selected"
    const val COUNTRIES = "countries"
    const val PEERS     = "peers/{countryKey}"
    const val NETWORK   = "network"
    const val LOGS      = "logs"
    fun peers(key: String) = "peers/${URLEncoder.encode(key, "UTF-8")}"
}

/**
 * One screen the app opens on, and a settings stack behind it. There is no tab bar:
 * connecting is the only thing most sessions do, and everything else is reached by
 * going deeper and coming back.
 */
@Composable
fun AppNavHost(vm: TunnelViewModel, onRequestVpnPermission: () -> Unit) {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Routes.CONNECT) {
        composable(Routes.CONNECT) {
            ConnectScreen(
                vm = vm,
                onRequestVpnPermission = onRequestVpnPermission,
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onOpenServer = { nav.navigate(Routes.SERVER) },
                onOpenPeers = { nav.navigate(Routes.SELECTED) },
                onOpenNetwork = { nav.navigate(Routes.NETWORK) },
                onOpenLogs = { nav.navigate(Routes.LOGS) },
            )
        }
        composable(Routes.SERVER) {
            ImportScreen(vm = vm, onImported = { nav.popBackStack() })
        }
        composable(Routes.SELECTED) {
            SelectedPeersScreen(
                vm = vm,
                onBrowsePublic = { nav.navigate(Routes.COUNTRIES) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.COUNTRIES) {
            CountryBrowserScreen(
                vm = vm,
                onCountrySelected = { key -> nav.navigate(Routes.peers(key)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.PEERS) { backStack ->
            val key = URLDecoder.decode(backStack.arguments?.getString("countryKey") ?: "", "UTF-8")
            PeerListScreen(vm = vm, countryKey = key, onBack = { nav.popBackStack() })
        }
        composable(Routes.NETWORK) {
            YggNetworkScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable(Routes.LOGS) {
            LogsScreen(onBack = { nav.popBackStack() })
        }
    }
}
