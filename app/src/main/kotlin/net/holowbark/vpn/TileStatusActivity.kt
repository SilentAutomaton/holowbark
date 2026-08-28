package net.holowbark.vpn

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import net.holowbark.MainActivity

/** Opened on QS tile long-press — immediately forwards to MainActivity. */
class TileStatusActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
