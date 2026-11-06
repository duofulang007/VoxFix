package local.voxfix.debug

import kotlin.math.min

/**
 * Per-corner rectangle radii, ported from Snow Shot 1.1.8 fixed commit `afdc340b`:
 * `snow-draw-engine-editor/src/geometry/corner_radius.rs` (radius/handle mapping, adjacent-corner
 * limits) and `snow-draw-engine-scene/src/selection_visuals.rs` constants (handle 8px, min inset
 * 12px, handle half-size 4px). Radii are style units exactly like the old uniform `cornerRadius`;
 * the handle geometry works in canvas pixels, so the zoom factor is `unit()`.
 */
internal object ScreenshotCornerRadii {
    const val TOP_LEFT = 0
    const val TOP_RIGHT = 1
    const val BOTTOM_RIGHT = 2
    const val BOTTOM_LEFT = 3

    fun radius(radii: List<Float>?,corner: Int,uniform: Int): Float =
        radii?.getOrNull(corner) ?: uniform.toFloat()

    fun set(radii: List<Float>?,corner: Int,value: Float): List<Float> =
        List(4) { if(it==corner) value else radii?.getOrNull(it) ?: 0f }

    /**
     * `flipped_corner_radii` (geometry/resize.rs): a negative resize scale mirrors the rect,
     * so the stored radii swap corners — flip_x swaps TL↔TR and BL↔BR, flip_y then swaps the
     * two top corners with the two bottom ones. `resized_selection_rect` applies this whenever
     * scale_x/scale_y < -epsilon, before the render-side min(w,h)/2 cap.
     */
    fun flipped(radii: List<Float>?,flipX: Boolean,flipY: Boolean): List<Float>? {
        if(radii==null) return null
        var r=radii
        if(flipX) r=listOf(r[TOP_RIGHT],r[TOP_LEFT],r[BOTTOM_LEFT],r[BOTTOM_RIGHT])
        if(flipY) r=listOf(r[BOTTOM_LEFT],r[BOTTOM_RIGHT],r[TOP_RIGHT],r[TOP_LEFT])
        return r
    }

    /** `max_corner_radius_for`: each adjacent corner consumes width/height. */
    fun maxFor(width: Float,height: Float,radii: List<Float>?,uniform: Int,corner: Int): Float {
        fun r(i: Int)=radius(radii,i,uniform)
        val widthNeighbour=when(corner) {
            TOP_LEFT->r(TOP_RIGHT); TOP_RIGHT->r(TOP_LEFT)
            BOTTOM_RIGHT->r(BOTTOM_LEFT); else->r(BOTTOM_RIGHT)
        }
        val heightNeighbour=when(corner) {
            TOP_LEFT->r(BOTTOM_LEFT); TOP_RIGHT->r(BOTTOM_RIGHT)
            BOTTOM_RIGHT->r(TOP_RIGHT); else->r(TOP_LEFT)
        }
        return min(width-widthNeighbour,height-heightNeighbour).coerceAtLeast(0f)
    }

    /** `uniform_corner_radius_limit`. */
    fun uniformLimit(width: Float,height: Float): Float = min(width,height)/2f.coerceAtLeast(0f)

    fun maxInset(width: Float,height: Float,unit: Float): Float =
        (min(width,height)/2f-4f*unit).coerceAtLeast(0f)

    fun minInset(width: Float,height: Float,unit: Float): Float =
        min(12f*unit,maxInset(width,height,unit))

    /** `corner_radius_handle_inset`: radius -> inward diagonal distance of the handle. */
    fun inset(width: Float,height: Float,unit: Float,radius: Float,maxRadius: Float): Float {
        val max=maxInset(width,height,unit); val min=minInset(width,height,unit)
        if(maxRadius<=1e-6f || max<=min) return min
        val t=(radius/maxRadius).coerceIn(0f,1f)
        return min+(max-min)*t
    }

    /** `corner_radius_for_handle_inset`: inward distance -> radius. */
    fun radiusForInset(width: Float,height: Float,unit: Float,inset: Float,maxRadius: Float): Float {
        val max=maxInset(width,height,unit); val min=minInset(width,height,unit)
        if(maxRadius<=1e-6f || max<=min) return 0f
        val t=(inset.coerceIn(min,max)-min)/(max-min)
        return maxRadius*t
    }

    private fun signX(corner: Int)=if(corner==TOP_RIGHT||corner==BOTTOM_RIGHT) 1f else -1f
    private fun signY(corner: Int)=if(corner==BOTTOM_RIGHT||corner==BOTTOM_LEFT) 1f else -1f

    /** `corner_radius_handle_local_point`: signed offset from the rect centre. */
    fun handlePoint(width: Float,height: Float,unit: Float,corner: Int,
        radii: List<Float>?,uniform: Int): ScreenshotDrawingPoint {
        val maxR=maxFor(width,height,radii,uniform,corner)
        val inset=inset(width,height,unit,radius(radii,corner,uniform),maxR)
        return ScreenshotDrawingPoint(signX(corner)*(width/2f-inset),signY(corner)*(height/2f-inset))
    }

    /** `preview_rect_for_corner_radius`: drag point -> radius; Shift sets all four corners. */
    fun dragRadius(width: Float,height: Float,unit: Float,corner: Int,
        radii: List<Float>?,uniform: Int,local: ScreenshotDrawingPoint,shift: Boolean): Float {
        val cornerX=signX(corner)*width/2f; val cornerY=signY(corner)*height/2f
        val inwardX=if(signX(corner)<0f) local.x-cornerX else cornerX-local.x
        val inwardY=if(signY(corner)<0f) local.y-cornerY else cornerY-local.y
        val maxR=if(shift) uniformLimit(width,height) else maxFor(width,height,radii,uniform,corner)
        return radiusForInset(width,height,unit,min(inwardX,inwardY),maxR)
    }
}
