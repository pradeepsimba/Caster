package mano.algo.caster

import android.app.Application

class CasterApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // First thing, so a crash in the sharing service (which can start without the screen) is recorded too.
        CrashLog.install(this)
    }
}
