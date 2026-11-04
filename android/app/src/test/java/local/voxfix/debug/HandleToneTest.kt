package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandleToneTest {

    @Test
    fun luminanceMatchesWcagEndpoints() {
        assertEquals(1.0, HandleToneResolver.luminance(0xFFFFFFFF.toInt()), 0.002)
        assertEquals(0.0, HandleToneResolver.luminance(0xFF000000.toInt()), 0.002)
    }

    @Test
    fun luminanceOfMidGreyIsAboutPointThreeOne() {
        // #979797 是中间档待机色，WCAG 亮度约 0.31
        val l = HandleToneResolver.luminance(0xFF979797.toInt())
        assertTrue("luminance(#979797)=$l", l in 0.28..0.34)
    }

    @Test
    fun toneFollowsLuminance() {
        assertEquals(HandleTone.LIGHT, HandleToneResolver.toneForLuminance(0.88))  // 浅壁纸 #f4f1ea
        assertEquals(HandleTone.MID, HandleToneResolver.toneForLuminance(0.24))    // 本机绿壁纸 #5a8c8d
        assertEquals(HandleTone.DARK, HandleToneResolver.toneForLuminance(0.01))  // 深色壁纸 #1a1d22
    }

    @Test
    fun toneBoundariesAreLowerExclusive() {
        assertEquals(HandleTone.LIGHT, HandleToneResolver.toneForLuminance(0.5501))
        assertEquals(HandleTone.MID, HandleToneResolver.toneForLuminance(0.55))
        assertEquals(HandleTone.MID, HandleToneResolver.toneForLuminance(0.1201))
        assertEquals(HandleTone.DARK, HandleToneResolver.toneForLuminance(0.12))
    }

    @Test
    fun toneColorsAreLockedToApprovedValues() {
        // 防止有人把色值改回旧的 #407f7f7f / #b3505660
        assertEquals(0xff979797.toInt(), HandleTone.MID.idle)
        assertEquals(0xb3c7c7c7.toInt(), HandleTone.MID.active)
        assertEquals(0x6b6b6b6b, HandleTone.LIGHT.idle)
        assertEquals(0xd16b6b6b.toInt(), HandleTone.LIGHT.active)
        assertEquals(0x8cffffff.toInt(), HandleTone.DARK.idle)
        assertEquals(0xd1ffffff.toInt(), HandleTone.DARK.active)
    }

    @Test
    fun lightAndDarkRaiseAlphaOnPress() {
        // 亮/暗档同色相，靠不透明度分状态
        for (tone in listOf(HandleTone.LIGHT, HandleTone.DARK)) {
            val idleAlpha = tone.idle ushr 24
            val activeAlpha = tone.active ushr 24
            assertTrue("${tone.name} idleAlpha=$idleAlpha activeAlpha=$activeAlpha",
                activeAlpha >= idleAlpha)
        }
    }

    @Test
    fun midGoesBrighterOnPress() {
        // 中档靠明度分状态：激活 #b3c7c7c7(199) 比待机 #ff979797(151) 更亮更跳。
        // 注意此时激活的 alpha 反而更低（179 < 255），所以不能用 alpha 判断"醒目"。
        fun gray(c: Int) = (c shr 16) and 0xFF
        assertTrue(gray(HandleTone.MID.active) > gray(HandleTone.MID.idle))
    }

    @Test
    fun colorForPicksStateColor() {
        assertEquals(HandleTone.MID.idle, HandleTone.MID.colorFor(pressed = false))
        assertEquals(HandleTone.MID.active, HandleTone.MID.colorFor(pressed = true))
    }
}
