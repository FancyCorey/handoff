package dev.handoff.app.di

import dev.handoff.app.ads.AdMobAdService
import dev.handoff.app.ads.AdService
import dev.handoff.app.service.HandoffService
import dev.handoff.app.update.PlayStoreUpdates
import dev.handoff.app.update.UpdateService
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.binds
import org.koin.dsl.module

/** Google Play edition: updates through Google Play, one banner on the home screen. */
val editionModule = module {
    single<UpdateService> { PlayStoreUpdates(androidContext()) }
    single { AdMobAdService(androidContext(), get(named(HandoffService.APP_SCOPE))) } binds arrayOf(AdService::class)
}
