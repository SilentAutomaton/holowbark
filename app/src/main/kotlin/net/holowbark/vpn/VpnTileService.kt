package net.holowbark.vpn

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
import net.holowbark.AppLogger
import net.holowbark.MainActivity
import net.holowbark.R

/**
 * Quick Settings tile. Tapping it stops a running tunnel, starts one when the
 * config, the peers and the VPN permission are all already in place, and otherwise
 * opens the app so the user can supply what is missing.
 */
class VpnTileService : TileService() {

    companion object {
        private const val TAG = "VpnTileService"
    }

    @Volatile private var currentState = VpnState.IDLE
    private var receiver: BroadcastReceiver? = null

    override fun onStartListening() {
        // Prefs survive process death, so they are only trustworthy while the
        // service is still up in this process.
        currentState = if (TunnelService.isRunning) Prefs.of(this).vpnState() else VpnState.IDLE
        render(TunnelStatus(overall = currentState))

        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val status = TunnelStatus.fromIntent(intent) ?: return
                currentState = status.overall
                render(status)
            }
        }
        ContextCompat.registerReceiver(
            this, r,
            IntentFilter(TunnelService.ACTION_STATUS),
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

    override fun onClick() {
        unlockAndRun {
            if (TunnelService.isRunning) {
                AppLogger.d(TAG, "Tile: stopping VPN")
                // Plain startService, not startForegroundService: the service is
                // already running and its stop path never calls startForeground(),
                // which would then trip the FGS-did-not-start watchdog.
                startService(TunnelService.stopIntent(this))
            } else {
                startVpnOrOpenApp()
            }
        }
    }

    private fun startVpnOrOpenApp() {
        val prefs = Prefs.of(this)
        val awgConf = prefs.awgConf
        val peers = prefs.selectedPeers.toList()
        val needsPermission = VpnService.prepare(this) != null

        if (needsPermission || awgConf == null || peers.isEmpty()) {
            AppLogger.d(TAG, "Tile: opening app " +
                "(permission=$needsPermission conf=${awgConf != null} peers=${peers.size})")
            openApp()
            return
        }
        AppLogger.d(TAG, "Tile: starting VPN directly (${peers.size} peers)")
        startForegroundService(
            TunnelService.startIntent(this, peers, awgConf, prefs.yggPrivateKey())
        )
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
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

    private fun render(status: TunnelStatus) {
        val tile = qsTile ?: return
        // ERROR stays INACTIVE rather than UNAVAILABLE: an unavailable tile cannot
        // be tapped, which would trap the user with no way to stop a failed tunnel.
        tile.state = when (status.overall) {
            VpnState.CONNECTED, VpnState.CONNECTING -> Tile.STATE_ACTIVE
            else                                    -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.app_name)
        tile.contentDescription = getString(when (status.overall) {
            VpnState.CONNECTED  -> R.string.tile_connected
            VpnState.CONNECTING -> R.string.tile_connecting
            VpnState.ERROR      -> R.string.tile_error
            else                -> R.string.tile_off
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                status.overall == VpnState.CONNECTED && status.yggPeers > 0 ->
                    resources.getQuantityString(
                        R.plurals.tile_peers, status.yggPeers, status.yggPeers)
                status.overall == VpnState.CONNECTED  -> getString(R.string.tile_on_short)
                status.overall == VpnState.CONNECTING -> "…"
                status.overall == VpnState.ERROR      -> getString(R.string.tile_error_short)
                else                                  -> getString(R.string.tile_off_short)
            }
        }
        tile.updateTile()
    }
}
