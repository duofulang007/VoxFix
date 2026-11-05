package local.voxfix.debug

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Magnifier
import kotlin.math.abs
import kotlin.math.roundToInt

internal object ScreenshotPenFeedbackChecks {
    private fun field(target: Any,name: String)=target.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(target)
    private fun feedback(layer: ScreenshotDrawingLayer)=layer.javaClass.getDeclaredMethod("getPenFeedback").apply { isAccessible=true }.invoke(layer) as ScreenshotPenFeedback
    private fun finger(action: Int,x: Float,y: Float): MotionEvent {
        val count=if(action==MotionEvent.ACTION_POINTER_DOWN) 2 else 1
        val properties=Array(count) { i -> MotionEvent.PointerProperties().apply { id=i; toolType=MotionEvent.TOOL_TYPE_FINGER } }
        val coords=Array(count) { MotionEvent.PointerCoords().apply { this.x=x; this.y=y; pressure=1f; size=1f } }
        return MotionEvent.obtain(1,SystemClock.uptimeMillis(),action or (if(count==2) 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT else 0),
            count,properties,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
    }
    private fun touch(layer: ScreenshotDrawingLayer,action: Int,x: Float,y: Float) {
        val event=finger(action,x,y)
        try { check(layer.onTouchEvent(event)) } finally { event.recycle() }
    }
    fun run(context: Context) {
        val image=Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val renderer=ScreenshotDrawingRenderer(image) { 1f }
        val layer=ScreenshotDrawingLayer(context,image) {}
        val pen=ScreenshotDrawingStroke(ScreenshotDrawingTool.PEN,
            listOf(ScreenshotDrawingPoint(80f,200f),ScreenshotDrawingPoint(180f,240f),ScreenshotDrawingPoint(250f,300f)),Color.RED,4f,smooth=true,id=8101)
        try {
            layer.layout(0,0,600,900); layer.region=Rect(0,0,600,900); layer.tool=ScreenshotDrawingTool.PEN; layer.history.load(listOf(pen))
            val unit=context.resources.displayMetrics.density
            val press=ScreenshotDrawingPoint(250f+16*unit,300f)
            check(ScreenshotPenContinuation.find(layer.history,renderer,press,unit)==null) { "precision hit area changed" }
            val slop=ViewConfiguration.get(context).scaledTouchSlop.toFloat()
            val target=ScreenshotPenContinuation.find(layer.history,renderer,press,unit,true,slop) ?: error("finger cannot grab enlarged endpoint")
            check(target.endpoint==pen.points.last())
            target.sample(ScreenshotDrawingPoint(press.x+slop/2,press.y)); check(!target.moved)
            check(target.sample(ScreenshotDrawingPoint(press.x+slop+20,press.y)).x==250f+slop+20) { "grab offset lost" }

            touch(layer,MotionEvent.ACTION_DOWN,press.x,press.y)
            check(field(feedback(layer),"focus")==pen.points.last()) { "grab not highlighted" }
            touch(layer,MotionEvent.ACTION_MOVE,press.x+slop/2,press.y)
            touch(layer,MotionEvent.ACTION_UP,press.x+slop/2,press.y)
            check(!layer.history.canUndo && layer.history.items.single()==pen) { "finger jitter entered history" }
            check(field(feedback(layer),"focus")==null)
            touch(layer,MotionEvent.ACTION_DOWN,press.x,press.y)
            touch(layer,MotionEvent.ACTION_MOVE,press.x+slop+25,press.y+40)
            touch(layer,MotionEvent.ACTION_UP,press.x+slop+25,press.y+40)
            check(layer.history.items.single().id==pen.id)
            val end=layer.history.items.single().points.last()
            check(abs(end.x-(250f+slop+25))<.001f && abs(end.y-340f)<.001f)
            layer.undo(); check(layer.history.items.single()==pen && !layer.history.canUndo)
            for(action in listOf(MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN)) {
                touch(layer,MotionEvent.ACTION_DOWN,press.x,press.y)
                touch(layer,MotionEvent.ACTION_MOVE,press.x+slop+25,press.y+40)
                touch(layer,action,press.x+slop+25,press.y+40)
                touch(layer,MotionEvent.ACTION_UP,press.x+slop+25,press.y+40)
                check(layer.history.items.single()==pen && !layer.history.canUndo)
                check(field(feedback(layer),"focus")==null)
            }
            touch(layer,MotionEvent.ACTION_DOWN,press.x,press.y); layer.tool=ScreenshotDrawingTool.SELECT
            check(field(feedback(layer),"focus")==null)
            layer.tool=ScreenshotDrawingTool.PEN
            val shown=image.copy(Bitmap.Config.ARGB_8888,true)
            val clean=image.copy(Bitmap.Config.ARGB_8888,true)
            val exported=layer.render(Rect(0,0,600,900),null) ?: error("export failed")
            try {
                layer.draw(Canvas(shown)); renderer.draw(Canvas(clean),listOf(pen),Rect(0,0,600,900))
                check(!shown.sameAs(clean)) { "idle endpoints invisible" }
                check(exported.sameAs(clean)) { "feedback leaked into export" }
                context.cacheDir.resolve("pen-feedback-markers.png").outputStream().use { shown.compress(Bitmap.CompressFormat.PNG,100,it) }
            } finally { shown.recycle(); clean.recycle(); exported.recycle() }
            var calls=0
            val view=object: View(context) {
                override fun performHapticFeedback(constant: Int): Boolean { check(constant==HapticFeedbackConstants.GESTURE_START); calls++; return true }
            }
            val indicator=ScreenshotPenFeedback(view,600,900)
            try {
                indicator.grab(pen.points.last(),press)
                repeat(8) { indicator.move(pen.points.last(),press) }
                check(calls==1) { "haptic repeated during hold" }
            } finally { indicator.clear() }
            touch(layer,MotionEvent.ACTION_DOWN,press.x,press.y); layer.close()
            check(field(feedback(layer),"focus")==null)
        } finally { layer.close(); renderer.close(); image.recycle() }
    }

    fun run(test: Instrumentation): String {
        var failure: Throwable?=null
        test.runOnMainSync { try { run(test.targetContext) } catch(t: Throwable) { failure=t } }
        failure?.let { throw it }
        val automation=test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val monitor=test.addMonitor(MainActivity::class.java.name,null,false)
        val activity=try {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("am start -n local.voxfix.debug/.MainActivity"))
                .bufferedReader().use { check(!it.readText().contains("Error:")) }
            test.waitForMonitorWithTimeout(monitor,5000) ?: error("test host did not start within 5s")
        } finally { test.removeMonitor(monitor) }
        val image=Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        Canvas(image).apply {
            val paint=Paint().apply { color=0xFFD0D0D0.toInt(); strokeWidth=1f }
            for(x in 0 until 600 step 24) drawLine(x.toFloat(),0f,x.toFloat(),900f,paint)
            for(y in 0 until 900 step 24) drawLine(0f,y.toFloat(),600f,y.toFloat(),paint)
        }
        lateinit var root: FrameLayout
        lateinit var layer: ScreenshotDrawingLayer
        var createdLayer: ScreenshotDrawingLayer?=null
        try {
            test.runOnMainSync {
                root=FrameLayout(activity)
                root.addView(ImageView(activity).apply { setImageBitmap(image); scaleType=ImageView.ScaleType.FIT_XY },FrameLayout.LayoutParams(-1,-1))
                layer=ScreenshotDrawingLayer(activity,image) {}
                createdLayer=layer
                root.addView(layer,FrameLayout.LayoutParams(-1,-1)); activity.setContentView(root)
                layer.region=Rect(0,0,600,900); layer.tool=ScreenshotDrawingTool.PEN
                layer.history.load(listOf(ScreenshotDrawingStroke(ScreenshotDrawingTool.PEN,
                    listOf(ScreenshotDrawingPoint(15f,60f),ScreenshotDrawingPoint(160f,90f),ScreenshotDrawingPoint(240f,200f)),Color.RED,4f,smooth=true,id=8201)))
            }
            test.waitForIdleSync(); SystemClock.sleep(180)
            test.runOnMainSync {
                check(layer.width>0 && layer.isAttachedToWindow)
                val unit=activity.resources.displayMetrics.density*600/layer.width
                touch(layer,MotionEvent.ACTION_DOWN,(15f+16*unit)*layer.width/600,60f*layer.height/900)
            }
            test.waitForIdleSync(); SystemClock.sleep(240)
            lateinit var lensRect: Rect
            test.runOnMainSync {
                val lens=field(feedback(layer),"magnifier") as? Magnifier ?: error("native lens not created")
                val position=lens.position ?: error("native lens not shown")
                val location=IntArray(2); activity.window.decorView.getLocationOnScreen(location)
                lensRect=Rect(position.x+location[0],position.y+location[1],position.x+location[0]+lens.width,position.y+location[1]+lens.height)
                check(lens.sourcePosition!=null)
            }
            val screenshot=automation.takeScreenshot() ?: error("window screenshot unavailable")
            try {
                check(blueCount(screenshot,lensRect)>10) { "reticle not rendered in native window" }
                test.targetContext.cacheDir.resolve("pen-feedback-window.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG,100,it) }
            } finally { screenshot.recycle() }
            test.runOnMainSync { root.removeView(layer); check(field(feedback(layer),"magnifier")==null) }
            test.waitForIdleSync(); SystemClock.sleep(180)
            val dismissed=automation.takeScreenshot() ?: error("dismiss screenshot unavailable")
            try { check(blueCount(dismissed,lensRect)==0) { "lens remained after detach" } } finally { dismissed.recycle() }
        } finally {
            test.runOnMainSync { createdLayer?.let { it.close(); (it.parent as? ViewGroup)?.removeView(it) }; activity.finish() }
            image.recycle()
        }
        return "SCREENSHOT_PEN_FEEDBACK_PASS: hit-area,jitter,offset,markers,export,haptic-once,cancel,multitouch,tool,close,native-window,edge-reticle,detach"
    }
    private fun blueCount(image: Bitmap,rect: Rect): Int {
        var count=0
        for(y in rect.top.coerceAtLeast(0) until rect.bottom.coerceAtMost(image.height))
            for(x in rect.left.coerceAtLeast(0) until rect.right.coerceAtMost(image.width))
                if(image.getPixel(x,y)==0xFF4096FF.toInt()) count++
        return count
    }
}
