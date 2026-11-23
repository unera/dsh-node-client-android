package ru.uvw.dshnc

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import ru.uvw.dshnc.logic.ServerName
import ru.uvw.dshnc.service.NodeService
import ru.uvw.dshnc.service.Shade
import ru.uvw.dshnc.store.TokenStore

/**
 * The node as a quick settings tile.
 *
 * Same switch as the big button on the main screen, for the case the button is bad at: the phone
 * is in someone's hand and the app is not open. Active means the node is on and its service is
 * alive — a flag in the settings alone would keep the tile green over a dead process.
 */
class NodeTileService : TileService() {
    override fun onStartListening () {
        super.onStartListening()
        paint()
    }

    override fun onClick () {
        super.onClick()
        val store = TokenStore(this)
        if (store.enabled && NodeService.alive) {
            store.enabled = false
            store.addEvent("шторка", "выключено")
            NodeService.stop(this)
            Shade.showOff(this)
        } else {
            store.enabled = true
            // a press is intent: the "displaced by a newer instance" mark belongs to a previous
            // life and would otherwise keep this instance silent
            store.replaced = false
            store.addEvent("шторка", "включено")
            NodeService.start(this)
            Shade.dismissOff(this)
        }
        paint()
    }

    private fun paint () {
        val tile = qsTile ?: return
        val store = TokenStore(this)
        val on = store.enabled && NodeService.alive
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_node)
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !on -> "выключен"
                else -> ServerName.host(store.base) ?: "включён"
            }
        }
        tile.updateTile()
    }
}
