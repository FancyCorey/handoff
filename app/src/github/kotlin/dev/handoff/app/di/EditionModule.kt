package dev.handoff.app.di

import dev.handoff.app.ads.AdService
import dev.handoff.app.ads.NoOpAdService
import dev.handoff.app.service.HandoffService
import dev.handoff.app.update.AppUpdates
import dev.handoff.app.update.UpdateService
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.binds
import org.koin.dsl.module

/** GitHub edition: signed updates from GitHub Releases, no advertising. */
val editionModule = module {
    single { AppUpdates(androidContext(), get(named(HandoffService.APP_SCOPE)), get()) } binds arrayOf(UpdateService::class)
    single<AdService> { NoOpAdService }
}
