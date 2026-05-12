package net.yggawg.mobile.vpn

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import net.yggawg.mobile.AppLogger

/**
 * Quick Settings tile for toggling the Holowbark VPN from the Android notification panel.
 *
 * Add to the device's Quick Settings by long-pressing the panel → Edit → drag "Holowbark" tile.
 *
 * Behaviour:
 *   - Tap when connected/connecting → disconnect immediately
 *   - Tap when inactive, config+peers saved, VPN permission granted → start VPN directly
 *   - Tap when inactive, missing config or permission not granted → open the app
 *   - Long press → opens the app (via android:settingsActivity in the manifest)
 */
class VpnTileService : TileService() {

    companion object {
        private const val TAG = "VpnTileService"
    }

    @Volatile private var currentState = VpnState.IDLE
    private var receiver: BroadcastReceiver? = null

    // -------------------------------------------------------------------------
    // TileService lifecycle
    // -------------------------------------------------------------------------

    override fun onStartListening() {
        // Restore last known VPN state from SharedPrefs so the tile
        // shows the correct state immediately without waiting for a broadcast.
        val savedState = getSharedPreferences("yggawg", Context.MODE_PRIVATE)
            .getString("vpn_state", null)
        if (savedState != null) {
            runCatching { VpnState.valueOf(savedState) }.getOrNull()?.let { state ->
                currentState = state
                qsTile?.let { tile ->
                    tile.state = when (state) {
                        VpnState.CONNECTED, VpnState.CONNECTING -> Tile.STATE_ACTIVE
                        VpnState.ERROR -> Tile.STATE_UNAVAILABLE
                        else -> Tile.STATE_INACTIVE
                    }
                    tile.updateTile()
                }
            }
        }

        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val status = TunnelStatus.fromIntent(intent) ?: return
                currentState = status.overall
                updateTile(status)
            }
        }
        ContextCompat.registerReceiver(
            this, r,
            IntentFilter(YggVpnService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiver = r
        AppLogger.d(TAG,"onStartListening, current=$currentState")
    }

    override fun onStopListening() {
        receiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        receiver = null
    }

    // -------------------------------------------------------------------------
    // Click handler
    // -------------------------------------------------------------------------

    override fun onClick() {
        unlockAndRun {
            when (currentState) {
                VpnState.CONNECTED, VpnState.CONNECTING -> {
                    AppLogger.d(TAG,"Tile: stopping VPN")
                    startForegroundService(
                        Intent(this, YggVpnService::class.java)
                            .setAction(YggVpnService.ACTION_STOP)
                    )
                }
                else -> tryStartVpnOrOpenApp()
            }
        }
    }

    /**
     * If VPN permission is granted and saved config+peers exist, start the VPN directly.
     * Otherwise open the app so the user can grant permission or configure the VPN.
     */
    private fun tryStartVpnOrOpenApp() {
        val prefs = getSharedPreferences("yggawg", Context.MODE_PRIVATE)
        val awgConf = prefs.getString("awg_conf", null)
        val peers   = prefs.getStringSet("selected_peers", null)?.toList() ?: emptyList()

        val permissionNeeded = VpnService.prepare(this) != null

        if (!permissionNeeded && awgConf != null && peers.isNotEmpty()) {
            AppLogger.d(TAG,"Tile: starting VPN directly (${peers.size} peers)")
            startForegroundService(Intent(this, YggVpnService::class.java).apply {
                action = YggVpnService.ACTION_START
                putStringArrayListExtra(YggVpnService.EXTRA_YGG_PEERS, ArrayList(peers))
                putExtra(YggVpnService.EXTRA_AWG_CONF, awgConf)
            })
        } else {
            AppLogger.d(TAG,"Tile: opening app (permNeeded=$permissionNeeded conf=${awgConf != null} peers=${peers.size})")
            openApp()
        }
    }

    private fun openApp() {
        val intent = Intent(this, net.yggawg.mobile.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    // -------------------------------------------------------------------------
    // Tile rendering
    // -------------------------------------------------------------------------

    private fun updateTile(status: TunnelStatus) {
        val tile = qsTile ?: return
        tile.state = when (status.overall) {
            VpnState.CONNECTED  -> Tile.STATE_ACTIVE
            VpnState.CONNECTING -> Tile.STATE_ACTIVE
            VpnState.ERROR      -> Tile.STATE_UNAVAILABLE
            else                -> Tile.STATE_INACTIVE
        }
        tile.label = "Holowbark"
        tile.contentDescription = when (status.overall) {
            VpnState.CONNECTED  -> "Holowbark VPN connected"
            VpnState.CONNECTING -> "Holowbark VPN connecting"
            VpnState.ERROR      -> "Holowbark VPN error"
            else                -> "Holowbark VPN off"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (status.overall) {
                VpnState.CONNECTED  -> if (status.yggPeers > 0) "${status.yggPeers} peers" else "ON"
                VpnState.CONNECTING -> "…"
                else                -> "OFF"
            }
        }
        tile.updateTile()
    }
}
