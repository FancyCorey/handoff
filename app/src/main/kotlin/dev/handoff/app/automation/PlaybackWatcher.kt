/*
 * Detecting playback start with AudioManager.registerAudioPlaybackCallback is adapted from
 * PodSwitch by Felip6499 (https://github.com/Felip6499/PodSwitch), MIT License,
 * Copyright (c) 2026 Felip6499. See THIRD_PARTY_NOTICES.md for the full license text.
 */
package dev.handoff.app.automation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import dev.handoff.app.persistence.SettingsRepository
import dev.handoff.app.runtime.HandoffActions
import dev.handoff.app.service.Notifications
import dev.handoff.bluetooth.AndroidBluetoothAudioController
import dev.handoff.core.diagnostics.EventLog
import dev.handoff.core.diagnostics.EventType
import dev.handoff.core.handoff.AudioReleaseHandler
import dev.handoff.core.handoff.AutoSwitchPolicy
import dev.handoff.core.handoff.TransferTrigger
import dev.handoff.core.mesh.transport.PeerDirectory
import dev.handoff.core.model.PeerId
import dev.handoff.core.ownership.Ownership
import dev.handoff.core.ownership.OwnershipRepository
import dev.handoff.core.ownership.OwnershipResolver
import dev.handoff.core.store.LogicalDeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Optional trigger: when media playback starts on this device,
 * ask (notification) or automatically run the normal Handoff transfer. It never switches by
 * itself — [AutoSwitchPolicy.Decision.Switch] goes through [HandoffActions] and therefore the
 * one coordinator and state machine.
 *
 * Uses the public [AudioManager.registerAudioPlaybackCallback]; no permission is required.
 */
class PlaybackWatcher(
    context: Context,
    private val appScope: CoroutineScope,
    private val settings: SettingsRepository,
    private val devices: LogicalDeviceRepository,
    private val bluetooth: AndroidBluetoothAudioController,
    private val ownership: OwnershipRepository,
    private val resolver: OwnershipResolver,
    private val directory: PeerDirectory,
    private val releaseHandler: AudioReleaseHandler,
    private val policy: AutoSwitchPolicy,
    private val actions: HandoffActions,
    private val notifications: Notifications,
    private val peerName: (PeerId) -> String,
    private val events: EventLog,
) {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var wasPlaying = false

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = evaluate(configs)
    }

    @Synchronized
    fun start() {
        if (registered || audio == null) return
        audio.registerAudioPlaybackCallback(callback, handler)
        registered = true
        wasPlaying = isMediaPlaying(audio.activePlaybackConfigurations)
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        runCatching { audio?.unregisterAudioPlaybackCallback(callback) }
        registered = false
        wasPlaying = false
    }

    private fun evaluate(configs: List<AudioPlaybackConfiguration>) {
        val playing = isMediaPlaying(configs)
        if (playing && !wasPlaying) appScope.launch { onPlaybackStarted() }
        wasPlaying = playing
    }

    private fun isMediaPlaying(configs: List<AudioPlaybackConfiguration>): Boolean = configs.any {
        it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA || it.audioAttributes.usage == AudioAttributes.USAGE_GAME
    }

    private suspend fun onPlaybackStarted() {
        val s = settings.settings.value
        val device = s.preferredDevice?.let { devices.find(it) }
        val local = device?.localDeviceId?.let { bluetooth.isConnected(it) } ?: false
        val owner = if (device == null) {
            Ownership.Unknown(null)
        } else {
            resolver.resolve(device, local, ownership.reportsFor(device.logicalId), directory.onlinePeers())
        }
        val decision = policy.onPlaybackStarted(
            mode = s.autoSwitchMode,
            hasPreferredDevice = device?.localDeviceId != null,
            localConnected = local,
            ownership = owner,
            lastReleaseToPeerMs = device?.let { releaseHandler.lastReleaseAtMs(it.logicalId) },
        )
        events.record(
            EventType.AUTO_SWITCH_TRIGGERED, device?.logicalId,
            details = mapOf("mode" to s.autoSwitchMode.name, "decision" to decision.toString()),
        )
        if (device == null) return
        when (decision) {
            AutoSwitchPolicy.Decision.Ask -> {
                val where = when (owner) {
                    is Ownership.Peer -> peerName(owner.peerId)
                    is Ownership.Unknown -> owner.lastKnownOwner?.let(peerName) ?: "another device"
                    else -> "another device"
                }
                notifications.showAsk(device.logicalId, device.displayName, where)
            }
            AutoSwitchPolicy.Decision.Switch -> actions.moveHere(device.logicalId, TransferTrigger.AUTOMATIC)
            is AutoSwitchPolicy.Decision.Ignore -> Unit
        }
    }
}
