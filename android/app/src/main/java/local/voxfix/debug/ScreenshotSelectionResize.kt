package local.voxfix.debug

import kotlin.math.*

/** Fixed upstream state.rs / geometry/{handles,selection_bounds,resize}.rs. */
internal enum class ScreenshotResizeHandle(val x: Float,val y: Float) {
    TOP_LEFT(-1f,-1f),TOP(0f,-1f),TOP_RIGHT(1f,-1f),RIGHT(1f,0f),
    BOTTOM_RIGHT(1f,1f),BOTTOM(0f,1f),BOTTOM_LEFT(-1f,1f),LEFT(-1f,0f);
    val corner get()=x!=0f && y!=0f
}

internal object ScreenshotSelectionResize {
    data class Geometry(val anchor: ScreenshotDrawingPoint,val sx: Float,val sy: Float) {
        fun point(p: ScreenshotDrawingPoint)=ScreenshotDrawingPoint(anchor.x+(p.x-anchor.x)*sx,anchor.y+(p.y-anchor.y)*sy)
    }
    fun handlePoint(box: SelBox,h: ScreenshotResizeHandle,padding: Float,outset: Float=0f)=ScreenshotDrawingPoint(
        box.centerX+h.x*(box.width/2+padding+if(h.corner) outset else 0f),
        box.centerY+h.y*(box.height/2+padding+if(h.corner) outset else 0f))
    fun hit(box: SelBox,p: ScreenshotDrawingPoint,padding: Float,outset: Float,unit: Float,tight: Boolean): ScreenshotResizeHandle? {
        val tolerance=6*unit
        for(h in ScreenshotResizeHandle.values().filter { it.corner }) {
            val c=handlePoint(box,h,padding,outset)
            if(abs(p.x-c.x)<=tolerance && abs(p.y-c.y)<=tolerance) return h
        }
        val inner=if(tight) 2*unit else tolerance
        val left=box.left-padding; val right=box.right+padding; val top=box.top-padding; val bottom=box.bottom+padding
        fun near(value: Float,edge: Float,inward: Float)=(value-edge)*inward in -tolerance..inner
        fun span(value: Float,a: Float,b: Float)=value>a+tolerance && value<b-tolerance
        return when {
            span(p.x,left,right) && near(p.y,top,1f) -> ScreenshotResizeHandle.TOP
            span(p.y,top,bottom) && near(p.x,right,-1f) -> ScreenshotResizeHandle.RIGHT
            span(p.x,left,right) && near(p.y,bottom,-1f) -> ScreenshotResizeHandle.BOTTOM
            span(p.y,top,bottom) && near(p.x,left,1f) -> ScreenshotResizeHandle.LEFT
            else -> null
        }
    }
    fun geometry(box: SelBox,h: ScreenshotResizeHandle,delta: ScreenshotDrawingPoint,shift: Boolean,alt: Boolean,
        members: List<Pair<ScreenshotDrawingStroke,SelBox>>,unit: Float): Geometry {
        val singleText=members.size==1 && members.single().first.tool==ScreenshotDrawingTool.TEXT
        val hasText=members.any { it.first.tool==ScreenshotDrawingTool.TEXT }
        val hasSerial=members.any { it.first.tool==ScreenshotDrawingTool.SERIAL }
        val forceAspect=hasSerial || (hasText && !singleText)
        val allowFlip=!hasText && !hasSerial
        var minimum=0f
        for((s,b) in members) {
            if(s.tool==ScreenshotDrawingTool.SERIAL || (s.tool==ScreenshotDrawingTool.TEXT && !singleText))
                minimum=max(minimum,6f/(s.style.fontSize*s.fontScale))
            if(s.tool==ScreenshotDrawingTool.FILTER && s.style.area==ScreenshotAreaMode.PEN)
                minimum=max(minimum,max(s.width/b.width.coerceAtLeast(unit),s.width/b.height.coerceAtLeast(unit)))
        }
        val minX=members.fold(minimum) { v,(_,b)->max(v,unit/b.width.coerceAtLeast(unit)) }
        val minY=members.fold(minimum) { v,(_,b)->max(v,unit/b.height.coerceAtLeast(unit)) }
        val anchor=ScreenshotDrawingPoint(if(alt || h.x==0f) 0f else -h.x*box.width/2,
            if(alt || h.y==0f) 0f else -h.y*box.height/2)
        fun raw(sign: Float,size: Float,change: Float,a: Float): Float {
            val ref=if(alt) size/2 else size
            return if(sign==0f || ref<=0f) 1f else (sign*size/2+change-a)/(sign*ref)
        }
        val rawX=raw(h.x,box.width,delta.x,anchor.x); val rawY=raw(h.y,box.height,delta.y,anchor.y)
        fun sign(v: Float)=if(v<0f) -1f else 1f
        fun clamp(v: Float,min: Float)=if(allowFlip) sign(v)*max(abs(v),min) else max(v,min)
        val scales=if(shift || forceAspect) {
            fun magnitude(v: Float)=if(allowFlip) abs(v) else max(0f,v)
            val size=max(max(minX,minY),when {
                h.x!=0f && h.y!=0f -> max(magnitude(rawX),magnitude(rawY))
                h.x!=0f -> magnitude(rawX)
                h.y!=0f -> magnitude(rawY)
                else -> 1f
            })
            (if(allowFlip && h.x!=0f) sign(rawX)*size else size) to
                (if(allowFlip && h.y!=0f) sign(rawY)*size else size)
        } else (if(h.x==0f) 1f else clamp(rawX,minX)) to (if(h.y==0f) 1f else clamp(rawY,minY))
        return Geometry(anchor,scales.first,scales.second)
    }
    fun worldPoint(p: ScreenshotDrawingPoint,frame: SelBox,angle: Float,g: Geometry): ScreenshotDrawingPoint {
        val c=ScreenshotDrawingPoint(frame.centerX,frame.centerY)
        val q=ScreenshotObjectTransform.rotate(p,c,-angle)
        val scaled=g.point(ScreenshotDrawingPoint(q.x-c.x,q.y-c.y))
        return ScreenshotObjectTransform.rotate(ScreenshotDrawingPoint(c.x+scaled.x,c.y+scaled.y),c,angle)
    }
    fun frame(box: SelBox,angle: Float,g: Geometry): SelBox {
        val c=worldPoint(ScreenshotDrawingPoint(box.centerX,box.centerY),box,angle,g)
        val w=max(box.width*abs(g.sx),1f); val h=max(box.height*abs(g.sy),1f)
        return SelBox(c.x-w/2,c.y-h/2,c.x+w/2,c.y+h/2)
    }
    /** Rectangular proxies retain member rotation; negative group scale moves their centres. */
    fun member(s: ScreenshotDrawingStroke,own: SelBox,frame: SelBox,angle: Float,g: Geometry): ScreenshotDrawingStroke {
        val center=ScreenshotDrawingPoint(own.centerX,own.centerY)
        val next=worldPoint(center,frame,angle,g)
        val sx=abs(g.sx); val sy=abs(g.sy)
        val filter=s.tool==ScreenshotDrawingTool.FILTER && s.style.area==ScreenshotAreaMode.PEN
        val rawW=(own.width-if(filter) s.width else 0f).coerceAtLeast(0f)
        val rawH=(own.height-if(filter) s.width else 0f).coerceAtLeast(0f)
        val scaleX=if(filter) { if(rawW>0f) (own.width*sx-s.width).coerceAtLeast(0f)/rawW else 1f } else sx
        val scaleY=if(filter) { if(rawH>0f) (own.height*sy-s.width).coerceAtLeast(0f)/rawH else 1f } else sy
        val points=s.points.map { ScreenshotDrawingPoint(next.x+(it.x-center.x)*scaleX,next.y+(it.y-center.y)*scaleY) }
        val fontScale=if(s.tool==ScreenshotDrawingTool.SERIAL) min(sx,sy) else if(s.tool==ScreenshotDrawingTool.TEXT) sx else 1f
        // resized_selection_rect: a negative axis scale mirrors the rect proxy, which swaps
        // the stored per-corner radii (flipped_corner_radii) before the render-side cap.
        val radii=ScreenshotCornerRadii.flipped(s.style.cornerRadii,g.sx<0f,g.sy<0f)
        // A multi-select resize always becomes a fixed-width text (resize.rs `_ =>` branch).
        return s.copy(points=points,style=s.style.copy(fontSize=s.style.fontSize*fontScale,cornerRadii=radii),
            textWidth=if(s.tool==ScreenshotDrawingTool.TEXT) own.width*sx else s.textWidth,
            textHeight=if(s.tool==ScreenshotDrawingTool.TEXT) own.height*sy else s.textHeight,
            autoResize=if(s.tool==ScreenshotDrawingTool.TEXT) false else s.autoResize)
    }
    /**
     * `text_with_selection_rect`: a width-only handle keeps the font and only rewraps the text
     * (`changes_width_only`), while any handle with a vertical component — a side or a corner —
     * scales it by the height ratio (`text_resize_font_size_from_height`). The result never drops
     * below the 6 px floor the inspector and the resize minimum already use.
     */
    fun textScale(h: ScreenshotResizeHandle,g: Geometry,fontSize: Float): Float {
        val scale=if(h.y!=0f) g.sy else 1f
        return max(scale,6f/fontSize)
    }
    fun textFrame(original: SelBox,h: ScreenshotResizeHandle,angle: Float,width: Float,height: Float,alt: Boolean): SelBox {
        val anchor=if(alt) ScreenshotDrawingPoint(0f,0f) else when(h) {
            ScreenshotResizeHandle.BOTTOM,ScreenshotResizeHandle.BOTTOM_RIGHT -> ScreenshotDrawingPoint(-1f,-1f)
            ScreenshotResizeHandle.BOTTOM_LEFT -> ScreenshotDrawingPoint(1f,-1f)
            ScreenshotResizeHandle.LEFT -> ScreenshotDrawingPoint(1f,0f)
            ScreenshotResizeHandle.RIGHT -> ScreenshotDrawingPoint(-1f,0f)
            ScreenshotResizeHandle.TOP,ScreenshotResizeHandle.TOP_LEFT -> ScreenshotDrawingPoint(1f,1f)
            ScreenshotResizeHandle.TOP_RIGHT -> ScreenshotDrawingPoint(-1f,1f)
        }
        val c=ScreenshotDrawingPoint(original.centerX,original.centerY)
        val next=ScreenshotObjectTransform.rotate(ScreenshotDrawingPoint(c.x+anchor.x*(original.width-width)/2,
            c.y+anchor.y*(original.height-height)/2),c,angle)
        return SelBox(next.x-width/2,next.y-height/2,next.x+width/2,next.y+height/2)
    }
}
