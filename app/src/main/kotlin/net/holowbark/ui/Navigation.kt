package net.holowbark.ui

import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
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
    const val SPLIT     = "split"
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
        screen(Routes.CONNECT) {
            ConnectScreen(
                vm = vm,
                onRequestVpnPermission = onRequestVpnPermission,
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        screen(Routes.SETTINGS) {
            SettingsScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onOpenServer = { nav.navigate(Routes.SERVER) },
                onOpenPeers = { nav.navigate(Routes.SELECTED) },
                onOpenSplit = { nav.navigate(Routes.SPLIT) },
                onOpenLogs = { nav.navigate(Routes.LOGS) },
            )
        }
        screen(Routes.SERVER) {
            ImportScreen(vm = vm, onImported = { nav.popBackStack() })
        }
        screen(Routes.SELECTED) {
            SelectedPeersScreen(
                vm = vm,
                onBrowsePublic = { nav.navigate(Routes.COUNTRIES) },
                onBack = { nav.popBackStack() },
            )
        }
        screen(Routes.COUNTRIES) {
            CountryBrowserScreen(
                vm = vm,
                onCountrySelected = { key -> nav.navigate(Routes.peers(key)) },
                onBack = { nav.popBackStack() },
            )
        }
        screen(Routes.PEERS) { backStack ->
            val key = URLDecoder.decode(backStack.arguments?.getString("countryKey") ?: "", "UTF-8")
            PeerListScreen(vm = vm, countryKey = key, onBack = { nav.popBackStack() })
        }
        screen(Routes.SPLIT) {
            SplitTunnelScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        screen(Routes.LOGS) {
            LogsScreen(onBack = { nav.popBackStack() })
        }
    }
}

// The leaving screen stays composed through the fade; without this a tap lands on it.
private fun NavGraphBuilder.screen(route: String, content: @Composable (NavBackStackEntry) -> Unit) =
    composable(route) { entry ->
        Box {
            content(entry)
            if (transition.targetState == EnterExitState.PostExit) {
                Box(Modifier.matchParentSize().pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
                })
            }
        }
    }
