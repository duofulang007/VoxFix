package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class ScreenshotSelectionResizeTest {
    private val box=SelBox(0f,0f,100f,80f)
    private fun p(x: Float,y: Float)=ScreenshotDrawingPoint(x,y)
    private fun stroke(tool: ScreenshotDrawingTool=ScreenshotDrawingTool.RECTANGLE,size: Float=30f)=
        ScreenshotDrawingStroke(tool,listOf(p(0f,0f),p(100f,80f)),17,4f,ScreenshotDrawingStyle(fontSize=size),id=7,rotation=.4f)
    private fun geometry(h: ScreenshotResizeHandle,dx: Float,dy: Float,shift: Boolean=false,alt: Boolean=false,
        members: List<Pair<ScreenshotDrawingStroke,SelBox>> = listOf(stroke() to box))=
        ScreenshotSelectionResize.geometry(box,h,p(dx,dy),shift,alt,members,1f)
    @Test fun cornersUsePaddedTwelvePixelHitSquaresAndEdgesSpanTheFrame() {
        val h=ScreenshotResizeHandle.TOP_LEFT
        assertEquals(p(-4f,-4f),ScreenshotSelectionResize.handlePoint(box,h,4f))
        assertEquals(h,ScreenshotSelectionResize.hit(box,p(-10f,2f),4f,0f,1f,false))
        assertNull(ScreenshotSelectionResize.hit(box,p(-10.01f,2f),4f,0f,1f,false))
        assertEquals(ScreenshotResizeHandle.TOP,ScreenshotSelectionResize.hit(box,p(30f,-4f),4f,0f,1f,false))
        assertEquals(ScreenshotResizeHandle.RIGHT,ScreenshotSelectionResize.hit(box,p(104f,60f),4f,0f,1f,false))
        assertEquals(ScreenshotResizeHandle.BOTTOM,ScreenshotSelectionResize.hit(box,p(40f,84f),4f,0f,1f,false))
        assertEquals(ScreenshotResizeHandle.LEFT,ScreenshotSelectionResize.hit(box,p(-4f,30f),4f,0f,1f,false))
        assertNull(ScreenshotSelectionResize.hit(box,p(40f,40f),4f,0f,1f,false))
    }
    @Test fun arrowCornerOutsetLeavesVerticesEditableAndTightEdgesHaveTwoPixelInnerHit() {
        assertEquals(p(-8f,-8f),ScreenshotSelectionResize.handlePoint(box,ScreenshotResizeHandle.TOP_LEFT,0f,8f))
        assertEquals(ScreenshotResizeHandle.TOP,ScreenshotSelectionResize.hit(box,p(30f,2f),0f,8f,1f,true))
        assertNull(ScreenshotSelectionResize.hit(box,p(30f,2.01f),0f,8f,1f,true))
    }
    @Test fun allEightHandlesScaleTheirOwnAxesAndKeepTheOppositeAnchor() {
        for(h in ScreenshotResizeHandle.values()) {
            val g=geometry(h,50*h.x,40*h.y)
            assertEquals(if(h.x==0f) 1f else 1.5f,g.sx,0f)
            assertEquals(if(h.y==0f) 1f else 1.5f,g.sy,0f)
            val fixed=p(box.centerX+g.anchor.x,box.centerY+g.anchor.y)
            assertEquals(fixed,ScreenshotSelectionResize.worldPoint(fixed,box,0f,g))
        }
    }
    @Test fun pointerOffsetDoesNotJumpAndAltUsesHalfLengthAboutTheCentre() {
        val g=geometry(ScreenshotResizeHandle.BOTTOM_RIGHT,0f,0f)
        assertEquals(1f,g.sx,0f); assertEquals(1f,g.sy,0f)
        val alt=geometry(ScreenshotResizeHandle.RIGHT,25f,0f,alt=true)
        assertEquals(1.5f,alt.sx,0f); assertEquals(1f,alt.sy,0f)
        assertEquals(p(50f,40f),ScreenshotSelectionResize.worldPoint(p(50f,40f),box,0f,alt))
        val resized=ScreenshotSelectionResize.frame(box,0f,alt)
        assertEquals(SelBox(-25f,0f,125f,80f),resized)
    }
    @Test fun crossingAnchorFlipsOnlyTheCrossedAxesAndZeroUsesPositiveMinimum() {
        val flip=geometry(ScreenshotResizeHandle.RIGHT,-150f,0f)
        assertEquals(-.5f,flip.sx,0f)
        assertEquals(SelBox(-50f,0f,0f,80f),ScreenshotSelectionResize.frame(box,0f,flip))
        val zero=geometry(ScreenshotResizeHandle.RIGHT,-100f,0f)
        assertEquals(.01f,zero.sx,0f)
        val locked=geometry(ScreenshotResizeHandle.BOTTOM_RIGHT,-150f,80f,shift=true)
        assertEquals(-2f,locked.sx,0f); assertEquals(2f,locked.sy,0f)
    }
    @Test fun smallestGroupMemberSetsMinimumForTheWholeGroup() {
        val members=listOf(stroke() to box,stroke() to SelBox(20f,20f,22f,24f))
        val g=geometry(ScreenshotResizeHandle.BOTTOM_RIGHT,-99f,-79f,members=members)
        assertEquals(.5f,g.sx,0f); assertEquals(.25f,g.sy,0f)
    }
    @Test fun serialAndGroupedTextForceUniformNonFlippingFontMinimum() {
        val serial=geometry(ScreenshotResizeHandle.RIGHT,-200f,0f,members=listOf(stroke(ScreenshotDrawingTool.SERIAL,24f) to box))
        assertEquals(.25f,serial.sx,0f); assertEquals(.25f,serial.sy,0f)
        val grouped=listOf(stroke(ScreenshotDrawingTool.TEXT,12f) to box,stroke() to box)
        val text=geometry(ScreenshotResizeHandle.BOTTOM,0f,-160f,members=grouped)
        assertEquals(.5f,text.sx,0f); assertEquals(.5f,text.sy,0f)
        val single=geometry(ScreenshotResizeHandle.RIGHT,50f,0f,members=listOf(stroke(ScreenshotDrawingTool.TEXT) to box))
        assertEquals(1.5f,single.sx,0f); assertEquals(1f,single.sy,0f)
    }
    @Test fun penFilterOuterContourCannotCollapseUnderItsUnchangedStrokeWidth() {
        val s=stroke(ScreenshotDrawingTool.FILTER).copy(style=ScreenshotDrawingStyle(area=ScreenshotAreaMode.PEN),width=20f)
        val g=geometry(ScreenshotResizeHandle.BOTTOM_RIGHT,-100f,-80f,members=listOf(s to box))
        assertEquals(.25f,g.sx,0f); assertEquals(.25f,g.sy,0f)
        val raw=s.copy(points=listOf(p(10f,10f),p(90f,70f)))
        val next=ScreenshotSelectionResize.member(raw,box,box,0f,g)
        assertEquals(raw.width,next.width)
        assertEquals(5f,next.points.last().x-next.points.first().x,.001f)
        assertEquals(0f,next.points.last().y-next.points.first().y,.001f)
    }
    @Test fun rotatedFrameMovesMemberCentresWithoutShearingMemberAxesOrChangingStyle() {
        val s=stroke().copy(points=listOf(p(10f,10f),p(30f,30f)))
        val own=SelBox(10f,10f,30f,30f)
        val g=ScreenshotSelectionResize.Geometry(p(0f,0f),2f,.5f)
        val next=ScreenshotSelectionResize.member(s,own,box,(PI/2).toFloat(),g)
        assertEquals(40f,next.points.last().x-next.points.first().x,.001f)
        assertEquals(10f,next.points.last().y-next.points.first().y,.001f)
        assertEquals(s.style,next.style); assertEquals(s.width,next.width); assertEquals(s.rotation,next.rotation); assertEquals(s.id,next.id)
    }
    @Test fun textCornerScalesFontByHeightAndWidthOnlyKeepsIt() {
        // `text_resize_font_size_from_height`: a corner follows the height ratio, not the wider axis.
        assertEquals(1.2f,ScreenshotSelectionResize.textScale(ScreenshotResizeHandle.BOTTOM_RIGHT,
            ScreenshotSelectionResize.Geometry(p(0f,0f),.5f,1.2f),30f),0f)
        assertEquals(.2f,ScreenshotSelectionResize.textScale(ScreenshotResizeHandle.TOP,
            ScreenshotSelectionResize.Geometry(p(0f,0f),1f,.01f),30f),0f)
        // A horizontal side handle keeps the font: the text rewraps instead of scaling.
        assertEquals(1f,ScreenshotSelectionResize.textScale(ScreenshotResizeHandle.RIGHT,
            ScreenshotSelectionResize.Geometry(p(0f,0f),3f,1f),30f),0f)
        // The 6 px floor wins over a collapsed height.
        assertEquals(.2f,ScreenshotSelectionResize.textScale(ScreenshotResizeHandle.BOTTOM,
            ScreenshotSelectionResize.Geometry(p(0f,0f),1f,.01f),30f),0f)
        assertEquals(SelBox(0f,0f,200f,160f),ScreenshotSelectionResize.textFrame(box,ScreenshotResizeHandle.BOTTOM,0f,200f,160f,false))
        assertEquals(SelBox(-50f,-40f,150f,120f),ScreenshotSelectionResize.textFrame(box,ScreenshotResizeHandle.BOTTOM,0f,200f,160f,true))
    }
    @Test fun pathFramesExcludeHeadsAndUseActualCubicExtrema() {
        val s=stroke(ScreenshotDrawingTool.ARROW).copy(points=listOf(p(0f,0f),p(100f,0f),p(100f,100f)))
        val bounds=ScreenshotSelectionGeometry.bounds(s)
        assertTrue(bounds.top<0f && bounds.right>100f)
        assertTrue(bounds.top> -17f && bounds.right<117f)
        assertEquals(SelBox(0f,0f,100f,100f),ScreenshotSelectionGeometry.bounds(s.copy(style=s.style.copy(arrowType=ScreenshotArrowType.STRAIGHT))))
    }
    @Test fun resizedFontsBeyondManualInputRangeStayVisibleInInspector() {
        val s=stroke(ScreenshotDrawingTool.TEXT,600f)
        assertEquals(600f,ScreenshotSelectionStyle.inspected(s,1f).fontSize,0f)
    }
}
