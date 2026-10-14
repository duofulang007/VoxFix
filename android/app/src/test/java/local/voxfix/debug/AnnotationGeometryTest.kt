package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test

class AnnotationGeometryTest {
    private fun box(left: Float, top: Float, right: Float, bottom: Float) = MarkBox(left, top, right, bottom)

    // 同一行的字要并成一个高亮块，否则会画成一字一个方块。
    @Test fun sameRowGlyphsMergeIntoOneHighlightRun() {
        val glyphs = mapOf(0 to box(0f, 10f, 20f, 40f), 1 to box(20f, 10f, 40f, 40f), 2 to box(40f, 10f, 60f, 40f))
        assertEquals(listOf(box(0f, 10f, 60f, 40f)), AnnotationGeometry.runs(0, 3, glyphs))
    }

    // 换行要分成两块，跨行合并会把两行之间的空白也涂上。
    @Test fun nextLineBecomesItsOwnRun() {
        val glyphs = mapOf(
            0 to box(0f, 10f, 20f, 40f), 1 to box(20f, 10f, 40f, 40f),
            2 to box(0f, 50f, 20f, 80f), 3 to box(20f, 50f, 40f, 80f),
        )
        assertEquals(listOf(box(0f, 10f, 40f, 40f), box(0f, 50f, 40f, 80f)), AnnotationGeometry.runs(0, 4, glyphs))
    }

    // 宿主观测窗口外的字拿不到边界框——缺口不能把整段挤成一个块，也不能抛异常。
    @Test fun missingGlyphsAreSimplySkipped() {
        val glyphs = mapOf(0 to box(0f, 10f, 20f, 40f), 2 to box(40f, 10f, 60f, 40f))
        assertEquals(listOf(box(0f, 10f, 20f, 40f), box(40f, 10f, 60f, 40f)), AnnotationGeometry.runs(0, 3, glyphs))
        assertEquals(emptyList<MarkBox>(), AnnotationGeometry.runs(0, 3, emptyMap()))
        assertEquals(emptyList<MarkBox>(), AnnotationGeometry.runs(2, 2, glyphs))
    }

    // 拖动反查：左半边取这个字，右半边取下一个偏移，和系统手柄一致。
    @Test fun offsetAtSnapsToTheNearerCharacterEdge() {
        val glyphs = mapOf(10 to box(100f, 200f, 120f, 220f), 11 to box(120f, 200f, 140f, 220f))
        assertEquals(10, AnnotationGeometry.offsetAt(105f, 210f, glyphs))
        assertEquals(11, AnnotationGeometry.offsetAt(115f, 210f, glyphs))
        assertEquals(12, AnnotationGeometry.offsetAt(135f, 210f, glyphs))
        assertEquals(10, AnnotationGeometry.offsetAt(90f, 210f, glyphs))
    }

    // 离得太远的行不该被吸过去，否则拖一枚水滴会跳到别的段落。
    @Test fun offsetAtIgnoresRowsOutsideTheSlack() {
        val glyphs = mapOf(0 to box(0f, 0f, 20f, 20f))
        assertNull(AnnotationGeometry.offsetAt(10f, 200f, glyphs, 8f))
        assertEquals(0, AnnotationGeometry.offsetAt(10f, 26f, glyphs, 8f))
    }

    // 参考图比例：单位长度＝字框高，水滴 0.95 宽 × 1.25 高，手柄挂在高亮下缘之下 0.58 个直径处。
    @Test fun markerSizesFollowTheReferenceRatiosAndStayTappable() {
        val unit = AnnotationGeometry.unit(20f, 18f, 34f)
        assertEquals(20f, unit, 0.001f)
        assertEquals(18f, AnnotationGeometry.unit(4f, 18f, 34f), 0.001f)
        assertEquals(34f, AnnotationGeometry.unit(90f, 18f, 34f), 0.001f)
        assertEquals(19f, unit * AnnotationGeometry.DROP_WIDTH, 0.001f)
        assertEquals(25f, unit * AnnotationGeometry.DROP_HEIGHT, 0.001f)
        assertEquals(30f, 20f + 17.24f * AnnotationGeometry.HANDLE_CENTER_DROP, 0.01f)
    }
}
