package local.tethering.dedicated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 小さな確認で数えるときの範囲の計算（v0.10.0 の Android 13 用の版） */
class SubnetTest {
    private fun ip(vararg b: Int) = ByteArray(4) { b[it].toByte() }
    private fun text(a: ByteArray) = a.joinToString(".") { (it.toInt() and 0xff).toString() }

    @Test
    fun excludesNetworkBroadcastAndSelf() {
        val hosts = Subnet.hosts(ip(192, 168, 43, 1), 24, 1022)!!.map(::text)
        assertEquals("/24 は 254 個から自分を除いた 253 個", 253, hosts.size)
        assertEquals("192.168.43.2", hosts.first())
        assertEquals("192.168.43.254", hosts.last())
        assertFalse(hosts.contains("192.168.43.0"))
        assertFalse(hosts.contains("192.168.43.255"))
        assertFalse(hosts.contains("192.168.43.1"))
    }

    @Test
    fun selfInTheMiddleAndOtherPrefixes() {
        val hosts = Subnet.hosts(ip(10, 0, 2, 15), 24, 1022)!!.map(::text)
        assertTrue(hosts.contains("10.0.2.2"))
        assertFalse(hosts.contains("10.0.2.15"))
        assertEquals(listOf("172.16.0.2"), Subnet.hosts(ip(172, 16, 0, 1), 30, 1022)!!.map(::text))
        assertEquals("/22 は 1022 個から自分を除く", 1021, Subnet.hosts(ip(192, 168, 44, 10), 22, 1022)!!.size)
    }

    @Test
    fun refusesTooWideOrInvalid() {
        assertNull("広すぎる範囲は数えない", Subnet.hosts(ip(10, 0, 0, 1), 16, 1022))
        assertNull(Subnet.hosts(ip(10, 0, 0, 1), 31, 1022))
        assertNull("IPv4 でない", Subnet.hosts(ByteArray(16), 64, 1022))
    }
}
