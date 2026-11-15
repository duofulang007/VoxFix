package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Test

/** 录屏设置条从 9 个 chip 收敛成「主行 + 更多」，这里锁住结构。 */
class ScreenRecordingChipsTest {
    @Test fun primaryHasFourWithAudio() {
        assertEquals(listOf("格式", "清晰度", "声音", "更多"), ScreenRecordingChips.primary(hasAudio = true))
    }

    @Test fun primaryDropsAudioForAnimated() {
        assertEquals(listOf("格式", "清晰度", "更多"), ScreenRecordingChips.primary(hasAudio = false))
    }

    @Test fun moreOnlyOffersLoopForAnimated() {
        assertEquals(listOf("循环", "文件夹", "恢复默认"), ScreenRecordingChips.more(animated = true))
        assertEquals(listOf("文件夹", "恢复默认"), ScreenRecordingChips.more(animated = false))
    }
}
