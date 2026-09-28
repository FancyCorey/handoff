package dev.handoff.app.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.handoff.app.MainActivity
import dev.handoff.app.R
import dev.handoff.app.persistence.SettingsRepository
import dev.handoff.core.overview.DeviceOverview
import dev.handoff.app.runtime.HandoffActions
import dev.handoff.core.overview.OverviewRepository
import dev.handoff.core.handoff.TransferTrigger
import dev.handoff.core.ownership.Ownership
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Quick Settings tile for the preferred headset.
 *
 *  - Setup incomplete / no headset chosen: tap opens Handoff.
 *  - Headset connected here: shows "Connected here" (active).
 *  - Headset elsewhere: tap runs "Move here" through the normal coordinator.
 *  - Long press opens Handoff (QS_TILE_PREFERENCES intent filter on MainActivity).
 */
class MoveHereTileService : TileService() {
    private val overview: OverviewRepository by inject()
    private val settings: SettingsRepository by inject()
    private val actions: HandoffActions by inject()
    private var scope: CoroutineScope? = null
    private var current: DeviceOverview? = null
    private var setupComplete = false

    override fun onStartListening() {
        super.onStartListening()
        val listeningScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = listeningScope
        listeningScope.launch {
            combine(settings.settings, overview.devices) { s, devices ->
                setupComplete = s.onboardingComplete
                s.preferredDevice?.let { id -> devices.firstOrNull { it.device.logicalId == id } }
            }.collect { device ->
                current = device
                render(device)
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val device = current
        when {
            !setupComplete || device == null || device.device.localDeviceId == null -> openApp()
            device.connectedHere || device.transferRunning -> render(device)
            else -> {
                actions.moveHere(device.device.logicalId, TransferTrigger.QUICK_SETTINGS_TILE)
                qsTile?.apply {
                    subtitle = getString(R.string.tile_moving)
                    updateTile()
                }
            }
        }
    }

    private fun render(device: DeviceOverview?) {
        val tile = qsTile ?: return
        if (!setupComplete || device == null) {
            tile.label = getString(R.string.tile_label)
            tile.subtitle = getString(R.string.tile_setup)
            tile.state = Tile.STATE_INACTIVE
        } else {
            tile.label = device.device.displayName
            tile.subtitle = when {
                device.transferRunning -> getString(R.string.tile_moving)
                device.connectedHere -> getString(R.string.tile_connected_here)
                device.ownership is Ownership.Peer -> getString(R.string.tile_on_peer, device.holders.firstOrNull() ?: "")
                device.ownership is Ownership.Unknown -> getString(R.string.tile_label)
                else -> getString(R.string.tile_not_connected)
            }
            tile.state = if (device.connectedHere) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
