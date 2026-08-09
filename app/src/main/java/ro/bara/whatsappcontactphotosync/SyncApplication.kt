package ro.bara.whatsappcontactphotosync

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class SyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
    }
}
