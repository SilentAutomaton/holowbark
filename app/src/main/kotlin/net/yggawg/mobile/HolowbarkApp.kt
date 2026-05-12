package net.yggawg.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class HolowbarkApp : Application() {

    companion object {
        // v2: IMPORTANCE_DEFAULT (visible in main section), no sound set on channel
        const val VPN_NOTIF_CHANNEL = "vpn_status_v2"
        private const val VPN_NOTIF_CHANNEL_OLD = "vpn_status"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val mgr = getSystemService(NotificationManager::class.java)
        // Remove old low-importance channel so it doesn't linger
        mgr.deleteNotificationChannel(VPN_NOTIF_CHANNEL_OLD)

        val channel = NotificationChannel(
            VPN_NOTIF_CHANNEL,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
            setSound(null, null)    // no sound, but still visible in main section
            enableVibration(false)
        }
        mgr.createNotificationChannel(channel)
    }
}
