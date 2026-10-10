package dev.handoff.app

import android.app.Application
import dev.handoff.app.di.appModule
import dev.handoff.app.di.editionModule
import dev.handoff.app.service.Notifications
import dev.handoff.app.ui.ThisDevice
import dev.handoff.core.text.HandoffTexts
import dev.handoff.bluetooth.AndroidBluetoothAudioController
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class HandoffApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // "This phone" or "This tablet", in every message and holder list.
        HandoffTexts.me = ThisDevice.label(this)
        startKoin {
            androidContext(this@HandoffApplication)
            modules(appModule, editionModule)
        }
        get<Notifications>().createChannels()
        // Observation only (receivers + A2DP proxy); nothing connects or disconnects here.
        get<AndroidBluetoothAudioController>().start()
    }
}
