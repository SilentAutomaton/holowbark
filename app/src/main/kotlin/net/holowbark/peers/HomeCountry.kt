package net.holowbark.peers

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/** The network's country, then the SIM's, then the one in the system locale. */
fun detectCountryIso(context: Context): String? {
    val tm = context.getSystemService(TelephonyManager::class.java)
    return listOfNotNull(tm?.networkCountryIso, tm?.simCountryIso, Locale.getDefault().country)
        .firstOrNull { it.isNotBlank() }
}
