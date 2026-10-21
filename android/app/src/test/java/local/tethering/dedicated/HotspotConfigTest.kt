package local.tethering.dedicated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android 16 用の版の、アクセスポイントの名前とパスワードの確かめ（v0.10.0） */
class HotspotConfigTest {
    @Test
    fun checksName() {
        assertNotNull("空は不可", HotspotConfig.checkSsid(""))
        assertNull(HotspotConfig.checkSsid("a"))
        assertNull("32 バイトまで", HotspotConfig.checkSsid("a".repeat(32)))
        assertNotNull(HotspotConfig.checkSsid("a".repeat(33)))
        assertNull("日本語は 1 文字 3 バイト（10 文字 = 30 バイト）", HotspotConfig.checkSsid("テ".repeat(10)))
        assertNotNull("11 文字 = 33 バイト", HotspotConfig.checkSsid("テ".repeat(11)))
        assertNull("空白・記号も使える", HotspotConfig.checkSsid("My Hotspot #1"))
    }

    @Test
    fun checksPassword() {
        assertNotNull("7 文字は短い", HotspotConfig.checkPass("a".repeat(7)))
        assertNull(HotspotConfig.checkPass("a".repeat(8)))
        assertNull(HotspotConfig.checkPass("a".repeat(63)))
        assertNotNull("64 文字は長い", HotspotConfig.checkPass("a".repeat(64)))
        assertNull("空白・記号も使える", HotspotConfig.checkPass("pass word!~"))
        assertNotNull("全角は不可", HotspotConfig.checkPass("パスワード12345"))
        assertNotNull("制御文字は不可", HotspotConfig.checkPass("password\t1"))
    }

    @Test
    fun configIsValidOnlyWithBoth() {
        assertTrue(HotspotConfig.Config("TD", "12345678").valid)
        assertFalse("名前がない", HotspotConfig.Config("", "12345678").valid)
        assertFalse("パスワードがない", HotspotConfig.Config("TD", "").valid)
        assertEquals("名前の誤りを先に返す", HotspotConfig.checkSsid(""), HotspotConfig.check("", ""))
    }

    @Test
    fun settingsAllowEmptyAndStaySecret() {
        // 設定画面では空にできる（Android 15 以前では使わない）。入れたら確かめる
        assertNull(Defs.AP_SSID.check(""))
        assertNull(Defs.AP_PASS.check(""))
        assertNotNull(Defs.AP_PASS.check("short"))
        // 設定のファイル保存・診断情報には含めない
        assertTrue(Defs.secretKeys.containsAll(listOf(Defs.AP_SSID.key, Defs.AP_PASS.key)))
    }
}
