package local.voxfix.debug

import kotlin.math.hypot

/** Fixed free_draw_workflow.rs: nearest open endpoint, topmost wins ties, start reverses output. */
internal class ScreenshotPenContinuation private constructor(val index: Int,val original: ScreenshotDrawingStroke,
    private val press: ScreenshotDrawingPoint,val endpoint: ScreenshotDrawingPoint,
    val builder: PenStrokeBuilder,private val touch: Boolean,private val slop: Float) {
    var moved=false
        private set
    fun sample(point: ScreenshotDrawingPoint): ScreenshotDrawingPoint {
        val dx=point.x-press.x; val dy=point.y-press.y
        moved=moved || hypot(dx,dy)>(if(touch) slop.toDouble() else 1e-9)
        return if(!moved) endpoint else if(touch) ScreenshotDrawingPoint(endpoint.x+dx,endpoint.y+dy) else point
    }
    fun valid(history: ScreenshotDrawing)=history.items.getOrNull(index)?.let { it.id==original.id && it==original }==true
    companion object {
        fun endpoints(stroke: ScreenshotDrawingStroke,renderer: ScreenshotDrawingRenderer): List<ScreenshotDrawingPoint> {
            // Legacy unsmoothed polylines must keep their geometry; they have no curve-mode metadata.
            if(stroke.tool!=ScreenshotDrawingTool.PEN || !stroke.smooth || stroke.closedPath || stroke.points.size<2) return emptyList()
            val box=renderer.localBounds(stroke)
            val center=ScreenshotDrawingPoint(box.centerX(),box.centerY())
            return listOf(stroke.points.last(),stroke.points.first()).map { ScreenshotObjectTransform.rotate(it,center,stroke.rotation) }
        }
        fun find(history: ScreenshotDrawing,renderer: ScreenshotDrawingRenderer,point: ScreenshotDrawingPoint,unit: Float,
            touch: Boolean=false,slop: Float=0f): ScreenshotPenContinuation? {
            var best: ScreenshotPenContinuation?=null
            var distance=(if(touch) 24f else 8f)*unit
            for(index in history.items.indices.reversed()) {
                val stroke=history.items[index]
                val endpoints=endpoints(stroke,renderer)
                if(endpoints.isEmpty()) continue
                for((i,endpoint) in endpoints.withIndex()) {
                    val d=hypot(point.x-endpoint.x,point.y-endpoint.y)
                    if(d<=distance && (best==null || d<distance)) {
                        distance=d
                        val points=renderer.worldPoints(stroke)
                        best=ScreenshotPenContinuation(index,stroke,point,endpoint,PenStrokeBuilder.continueFrom(points,stroke.width,i==1),touch,slop)
                    }
                }
            }
            return best
        }
    }
}
