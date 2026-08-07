package net.typeblog.socks

import android.app.Application
import androidx.preference.PreferenceManager

class SocksApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Ensure default preference values are set before reading
        PreferenceManager.setDefaultValues(this, R.xml.settings, true)
    }
}
