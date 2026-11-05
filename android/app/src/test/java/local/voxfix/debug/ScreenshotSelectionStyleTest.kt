package local.voxfix.debug

import local.voxfix.debug.ScreenshotStyleProperty.*
import org.junit.Assert.*
import org.junit.Test

class ScreenshotSelectionStyleTest {
    @Test fun elbowStyleRecomputesThePathAndClearsFixedSegmentsOnExit() {
        val arrow = stroke(ScreenshotDrawingTool.ARROW)
        val elbowStyle = arrow.style.copy(arrowType=ScreenshotArrowType.ELBOW)
        val elbow = ScreenshotSelectionStyle.patch(arrow,elbowStyle,setOf(ARROW_TYPE),2f)
        assertEquals(ScreenshotElbowRouter.route(arrow.points.first(),arrow.points.last()),elbow.points)
        assertEquals(arrow.rotation,elbow.rotation,0f)
        assertEquals(arrow.id,elbow.id)
        val fixed = elbow.copy(fixedElbowSegments=setOf(2))
        val back = ScreenshotSelectionStyle.patch(fixed,arrow.style,setOf(ARROW_TYPE),2f)
        assertEquals(arrow.points,back.points)
        assertTrue(back.fixedElbowSegments.isEmpty())
    }

    private fun stroke(tool: ScreenshotDrawingTool,style: ScreenshotDrawingStyle=ScreenshotDrawingStyle(),scale: Float=1f)=
        ScreenshotDrawingStroke(tool,listOf(ScreenshotDrawingPoint(20f,40f),ScreenshotDrawingPoint(80f,90f)),style.color,style.width*2f,style,
            text="原文",number=8,fontScale=scale,id=91,rotation=.7f,smooth=true)
    @Test fun heterogeneousSourceFollowsUpstreamPriorityAndPrimaryTextCategory() {
        val tools=listOf(ScreenshotDrawingTool.ARROW,ScreenshotDrawingTool.PEN,ScreenshotDrawingTool.RECTANGLE,ScreenshotDrawingTool.SERIAL,ScreenshotDrawingTool.TEXT)
        val items=tools.map { stroke(it) }
        assertEquals(listOf(4),ScreenshotSelectionStyle.indices(items,items.indices.toSet(),0))
        assertEquals(listOf(3),ScreenshotSelectionStyle.indices(items,items.indices.toSet(),3))
        assertEquals(listOf(2),ScreenshotSelectionStyle.indices(items,setOf(0,1,2),0))
        val filters=items+stroke(ScreenshotDrawingTool.FILTER)
        assertEquals(listOf(5),ScreenshotSelectionStyle.indices(filters,filters.indices.toSet(),4))
    }
    @Test fun highlightSubtypesStaySeparateButFilterSubtypesShareChanges() {
        val items=listOf(stroke(ScreenshotDrawingTool.HIGHLIGHT),stroke(ScreenshotDrawingTool.HIGHLIGHT,ScreenshotDrawingStyle(area=ScreenshotAreaMode.PEN)))
        assertEquals(listOf(0),ScreenshotSelectionStyle.indices(items,setOf(0,1),1))
        val filters=items.map { it.copy(tool=ScreenshotDrawingTool.FILTER) }
        assertEquals(listOf(1,0),ScreenshotSelectionStyle.indices(filters,setOf(0,1),1))
        assertEquals(filters[0].width,ScreenshotSelectionStyle.patch(filters[0],ScreenshotDrawingStyle(width=8),setOf(WIDTH),2f).width)
    }
    @Test fun sameAsPrimaryPatchResolvesMixedWithoutLosingUnrelatedDifferences() {
        val a=stroke(ScreenshotDrawingTool.RECTANGLE,ScreenshotDrawingStyle(color=12,width=2,fillColor=34,hasFill=true))
        val b=a.copy(color=56,width=14f,style=a.style.copy(color=56,width=7,fillColor=78,cornerRadius=11),id=92)
        assertEquals(setOf(COLOR,WIDTH,FILL_COLOR,CORNER_RADIUS),ScreenshotSelectionStyle.mixed(listOf(a,b),listOf(0,1),2f))
        val next=ScreenshotSelectionStyle.patch(b,a.style,setOf(COLOR),2f)
        assertEquals(b.copy(color=a.color,style=b.style.copy(color=a.color)),next)
        assertEquals(b.id,next.id)
        val noFill=ScreenshotSelectionStyle.patch(b,a.style.copy(hasFill=false),setOf(HAS_FILL),2f)
        assertEquals(78,noFill.style.fillColor)
        assertFalse(noFill.style.hasFill)
    }
    @Test fun scaledTextOnlyChangesRequestedFontOrColor() {
        val s=stroke(ScreenshotDrawingTool.TEXT,ScreenshotDrawingStyle(fontSize=30f,outlineWidth=4,cornerRadius=5),2f)
        val colored=ScreenshotSelectionStyle.patch(s,s.style.copy(color=13),setOf(COLOR),2f)
        assertEquals(s.fontScale,colored.fontScale)
        assertEquals(s.style.fontSize,colored.style.fontSize)
        val sized=ScreenshotSelectionStyle.patch(s,s.style.copy(fontSize=6f),setOf(FONT_SIZE),2f)
        assertEquals(6f,sized.style.fontSize*sized.fontScale,0f)
        assertEquals(4,sized.style.outlineWidth); assertEquals(5,sized.style.cornerRadius)
        assertEquals(s.fontScale,sized.fontScale)
    }
    @Test fun serialWithoutNumberRetainsHiddenFont() {
        val s=stroke(ScreenshotDrawingTool.SERIAL,ScreenshotDrawingStyle(serialShape=ScreenshotSerialShape.CIRCLE,fontFamily="serif"))
        val next=ScreenshotSelectionStyle.patch(s,s.style.copy(fontFamily="monospace"),setOf(FONT_FAMILY),2f)
        assertEquals(s,next)
    }
    @Test fun mixedWidthsKeepFractionalScaleAndIgnoreDisabledFillColors() {
        val a=stroke(ScreenshotDrawingTool.PEN).copy(width=4.1f)
        val b=a.copy(width=4.2f,style=a.style.copy(fillColor=123))
        val mixed=ScreenshotSelectionStyle.mixed(listOf(a,b),listOf(0,1),2f)
        assertTrue(WIDTH in mixed); assertFalse(FILL_COLOR in mixed)
    }
    @Test fun explicitBatchIsOneUndoAndKeepsObjectIdentity() {
        val history=ScreenshotDrawing()
        repeat(2) { history.add(stroke(ScreenshotDrawingTool.PEN).copy(color=it+1)) }
        val before=history.items
        history.replaceAll(before.indices.associateWith { ScreenshotSelectionStyle.patch(before[it],ScreenshotDrawingStyle(width=8),setOf(WIDTH),2f) })
        val after=history.items
        assertEquals(before.map { it.id },after.map { it.id })
        history.undo(); assertEquals(before,history.items)
        history.redo(); assertEquals(after,history.items)
    }
}
