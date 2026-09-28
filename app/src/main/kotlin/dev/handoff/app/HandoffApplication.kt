package dev.handoff.app

import android.app.Application
import dev.handoff.app.di.appModule
import dev.handoff.app.service.Notifications
import dev.handoff.bluetooth.AndroidBluetoothAudioController
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class HandoffApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@HandoffApplication)
            modules(appModule)
        }
        get<Notifications>().createChannels()
        // Observation only (receivers + A2DP proxy); nothing connects or disconnects here.
        get<AndroidBluetoothAudioController>().start()
    }
}
