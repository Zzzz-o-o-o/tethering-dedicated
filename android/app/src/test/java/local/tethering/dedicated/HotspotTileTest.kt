package local.tethering.dedicated

import android.service.quicksettings.Tile
import org.junit.Assert.assertEquals
import org.junit.Test

/** クイック設定のタイルの表示（v0.10.0） */
class HotspotTileTest {
    @Test
    fun showsStateAndDeviceCount() {
        assertEquals(HotspotTile.View(Tile.STATE_ACTIVE, "ON・端末2台"), HotspotTile.view(TetherInfo.AP_ENABLED, 2, true))
        assertEquals("台数が分からなければ ON だけ", HotspotTile.View(Tile.STATE_ACTIVE, "ON"), HotspotTile.view(TetherInfo.AP_ENABLED, null, true))
        assertEquals(HotspotTile.View(Tile.STATE_INACTIVE, "OFF"), HotspotTile.view(TetherInfo.AP_DISABLED, null, true))
        assertEquals("許可がないと分かるように", HotspotTile.View(Tile.STATE_INACTIVE, "OFF・許可が必要"), HotspotTile.view(TetherInfo.AP_DISABLED, null, false))
        assertEquals("切り替えの途中は押せない", Tile.STATE_UNAVAILABLE, HotspotTile.view(TetherInfo.AP_ENABLING, null, true).state)
        assertEquals(Tile.STATE_UNAVAILABLE, HotspotTile.view(TetherInfo.AP_DISABLING, null, true).state)
        assertEquals("失敗したら押し直せる", Tile.STATE_INACTIVE, HotspotTile.view(TetherInfo.AP_FAILED, null, true).state)
        assertEquals("状態が分からなければ OFF と同じ", Tile.STATE_INACTIVE, HotspotTile.view(null, null, true).state)
    }
}
