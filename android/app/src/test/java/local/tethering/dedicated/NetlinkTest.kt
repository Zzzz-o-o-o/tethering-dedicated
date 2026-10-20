package local.tethering.dedicated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** netlink の近隣テーブルの読み方（v0.10.0 の Android 11 用の版。架空のアドレスで電文を組み立てる） */
class NetlinkTest {
    private val le = ByteOrder.LITTLE_ENDIAN
    private val names = mapOf(5 to "ap0", 2 to "rmnet0")
    private val mac = byteArrayOf(0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte())
    private val v4 = byteArrayOf(192.toByte(), 168.toByte(), 43, 57)
    private val v6 = ByteArray(16).also { it[0] = 0xfe.toByte(); it[1] = 0x80.toByte(); it[15] = 1 }

    /** RTM_NEWNEIGH 1 件（nlmsghdr + ndmsg + NDA_DST + NDA_LLADDR） */
    private fun neigh(ifindex: Int, state: Int, ip: ByteArray, lladdr: ByteArray?): ByteArray {
        val attrs = listOfNotNull(attr(1, ip), lladdr?.let { attr(2, it) })
        val len = 16 + 12 + attrs.sumOf { it.size }
        val bb = ByteBuffer.allocate(len).order(le)
        bb.putInt(len).putShort(28).putShort(2).putInt(1).putInt(0)
        bb.put((if (ip.size == 4) 2 else 10).toByte()).put(0).putShort(0).putInt(ifindex).putShort(state.toShort()).put(0).put(1)
        attrs.forEach { bb.put(it) }
        return bb.array()
    }

    private fun attr(type: Int, data: ByteArray): ByteArray {
        val len = 4 + data.size
        return ByteBuffer.allocate((len + 3) and 3.inv()).order(le).putShort(len.toShort()).putShort(type.toShort()).put(data).array()
    }

    private fun done(): ByteArray = ByteBuffer.allocate(20).order(le).putInt(20).putShort(3).putShort(2).putInt(1).putInt(0).putInt(0).array()

    private fun error(code: Int): ByteArray =
        ByteBuffer.allocate(36).order(le).putInt(36).putShort(2).putShort(0).putInt(1).putInt(0).putInt(code).put(ByteArray(16)).array()

    @Test
    fun requestIsAnRtmGetneighDump() {
        val r = ByteBuffer.wrap(Netlink.neighRequest(7, le)).order(le)
        assertEquals("長さ（nlmsghdr + ndmsg）", 28, r.getInt(0))
        assertEquals("RTM_GETNEIGH", 30, r.getShort(4).toInt())
        assertEquals("NLM_F_REQUEST | NLM_F_DUMP", 0x301, r.getShort(6).toInt())
        assertEquals(7, r.getInt(8))
    }

    @Test
    fun readsEntriesLikeIpNeigh() {
        val multicast = byteArrayOf(1, 0, 0x5e, 0, 0, 0xfb.toByte())
        val buf = neigh(5, 0x02, v4, mac) +
            neigh(5, 0x04, v6, mac) +
            neigh(5, 0x40, byteArrayOf(224.toByte(), 0, 0, 251.toByte()), multicast) +
            neigh(5, 0x01, byteArrayOf(192.toByte(), 168.toByte(), 43, 60), null) +
            neigh(9, 0x02, v4, mac) +
            done()
        val c = Netlink.parse(buf, buf.size, le) { names[it] }
        assertTrue(c.done)
        assertFalse(c.error)
        assertEquals(
            "NOARP（マルチキャスト）と、名前の分からないインターフェースは出さない",
            listOf(
                "192.168.43.57 dev ap0 lladdr aa:bb:cc:dd:ee:ff REACHABLE",
                "${InetAddress.getByAddress(v6).hostAddress} dev ap0 lladdr aa:bb:cc:dd:ee:ff STALE",
                "192.168.43.60 dev ap0 INCOMPLETE",
            ),
            c.lines,
        )
        // 既存の数え方・一覧にそのまま渡せる（INCOMPLETE は数えない。IPv4 と IPv6 は同じ端末）
        val text = c.lines.joinToString("\n")
        assertEquals(1, Parsers.countNeigh(text, "ap0"))
        assertEquals(listOf(Parsers.Neighbor("192.168.43.57", "aa:bb:cc:dd:ee:ff", Parsers.Neighbor.State.REACHABLE)), Parsers.neighbors(text, "ap0"))
    }

    @Test
    fun reportsErrorsAndPartialDumps() {
        val e = error(-13)
        assertTrue("OS が拒否したとき", Netlink.parse(e, e.size, le) { names[it] }.error)
        val part = neigh(2, 0x08, v4, mac)
        val p = Netlink.parse(part, part.size, le) { names[it] }
        assertFalse("一覧の終わりがまだ来ていない", p.done)
        assertEquals(listOf("192.168.43.57 dev rmnet0 lladdr aa:bb:cc:dd:ee:ff DELAY"), p.lines)
        val broken = part.copyOf(part.size - 3)
        assertEquals("途中で切れた電文は読まない", emptyList<String>(), Netlink.parse(broken, broken.size, le) { names[it] }.lines)
    }
}
