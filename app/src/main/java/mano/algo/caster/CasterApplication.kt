package mano.algo.caster

import android.app.Application
import java.io.File

class CasterApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // First thing, so a crash in the sharing service (which can start without the screen) is recorded too.
        CrashLog.install(this)
        // Older versions kept re-encoded copies of videos here (a feature that was removed). They can be large and were
        // only cleaned up when sharing stopped normally, so clear whatever a killed app left behind.
        // A widget left saying "on" after the app was ended by the system is corrected here.
        mano.algo.caster.quick.ShareWidget.update(this)
        val leftovers = File(cacheDir, "converted")
        if (leftovers.exists()) Thread { runCatching { leftovers.deleteRecursively() } }.start()
    }
}
