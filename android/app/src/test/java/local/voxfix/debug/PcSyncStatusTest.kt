package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电脑同步状态换算：心跳时间戳 → 可显示状态。
 *
 * 电脑端每 5 秒广播一次 PC_STATE，手机侧 15 秒没收到就当断开。边界值（从未连接、整 15 秒、
 * 超过 15 秒）都要固定下来，状态页与设置页胶囊读的是同一份结果。
 */
class PcSyncStatusTest {

    @Test
    fun `never connected keeps age negative`() {
        val status = PcSyncStatus.of(now = 100_000L, beat = 0L, transport = "", clip = "", addresses = "")
        assertFalse(status.connected)
        assertEquals(-1L, status.ageMs)
        assertEquals("从未连接", status.ageLabel)
        assertEquals("—", status.transportLabel)
    }

    @Test
    fun `heartbeat within fifteen seconds counts as connected`() {
        val status = PcSyncStatus.of(
            now = 100_000L, beat = 85_000L, transport = "usb", clip = "1", addresses = "192.168.1.4"
        )
        assertTrue(status.connected)
        assertEquals(15_000L, status.ageMs)
        assertEquals("USB", status.transportLabel)
        assertTrue(status.clipboardReady)
        assertEquals("192.168.1.4", status.addresses)
    }

    @Test
    fun `one millisecond past the timeout is disconnected`() {
        val status = PcSyncStatus.of(
            now = 100_001L, beat = 85_000L, transport = "wifi", clip = "0", addresses = ""
        )
        assertFalse(status.connected)
        assertEquals("Wi-Fi", status.transportLabel)
        assertFalse(status.clipboardReady)
        assertEquals("15 秒前", status.ageLabel)
    }

    @Test
    fun `transport and clip are normalised before display`() {
        val status = PcSyncStatus.of(
            now = 10_000L, beat = 9_000L, transport = " WIFI ", clip = " 1 ", addresses = " 10.0.0.2 "
        )
        assertEquals("wifi", status.transport)
        assertEquals("Wi-Fi", status.transportLabel)
        assertTrue(status.clipboardReady)
        assertEquals("10.0.0.2", status.addresses)
    }

    @Test
    fun `age label switches to minutes after sixty seconds`() {
        val status = PcSyncStatus.of(
            now = 200_000L, beat = 20_000L, transport = "usb", clip = "1", addresses = ""
        )
        assertFalse(status.connected)
        assertEquals("3 分钟前", status.ageLabel)
    }
}
