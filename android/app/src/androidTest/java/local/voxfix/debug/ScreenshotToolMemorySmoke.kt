package local.voxfix.debug

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.*
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

internal object ScreenshotToolMemorySmoke {
    fun persistence(test: Instrumentation,write: Boolean): String {
        val prefs=test.targetContext.getSharedPreferences("screenshot-memory-process-test",Context.MODE_PRIVATE)
        val context=object : ContextWrapper(test.targetContext) {
            override fun getSharedPreferences(name:String,mode:Int)=prefs
        }
        val memory=ScreenshotToolMemory(context)
        if(write) {
            prefs.edit().clear().commit()
            memory.save(ScreenshotDrawingTool.PEN,ScreenshotDrawingStyle(color=Color.BLUE,width=8))
            memory.remember(ScreenshotAction.PEN); memory.startNumber=23
            check(prefs.edit().commit())
            return "SCREENSHOT_MEMORY_PROCESS_WRITE_READY"
        }
        try {
            check(memory.style(ScreenshotDrawingTool.PEN)?.width==8 && memory.style(ScreenshotDrawingTool.PEN)?.color==Color.BLUE)
            check(memory.entry(listOf(ScreenshotAction.ARROW,ScreenshotAction.PEN))==ScreenshotAction.PEN && memory.startNumber==23L)
            return "SCREENSHOT_MEMORY_PROCESS_RESTART_PASS"
        } finally { prefs.edit().clear().commit() }
    }
    fun run(test: Instrumentation): String {
        val base=test.targetContext
        val preferences=base.getSharedPreferences("screenshot-tool-memory-test",Context.MODE_PRIVATE)
        val context=object : ContextWrapper(base) {
            override fun getSharedPreferences(name:String,mode:Int)=preferences
        }
        var failure:Throwable?=null
        preferences.edit().clear().commit()
        try {
            test.runOnMainSync { try { check(context) } catch(error:Throwable) { failure=error } }
            failure?.let { throw it }
        } finally { preferences.edit().clear().commit() }
        return "SCREENSHOT_TOOL_MEMORY_PASS: reopen-group-entry,tool-style,area-mode,start-number,cancel-preview,corrupt-preferences"
    }
    private fun check(context: Context) {
        val memory=ScreenshotToolMemory(context)
        val bitmap=Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888)
        val translator=object : ScreenshotTranslator {
            override fun available()=false
            override fun cancel() {}
            override fun translate(text:String,pair:TranslatePair,onResult:(String)->Unit,onFailure:(String)->Unit,onUpdate:(String)->Unit) {}
        }
        fun field(target:Any,name:String)=target.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(target)
        fun overlay(): ScreenshotCaptureOverlay {
            val overlay=ScreenshotCaptureOverlay(context,bitmap,{_->},{},{},{},{},translator,ScreenshotToolMemory(context))
            val root=field(overlay,"root") as View
            root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
            root.layout(0,0,1080,2400)
            (field(overlay,"selector") as ScreenTextRegionView).selectRegion(Rect(100,100,1000,1600))
            return overlay
        }
        fun find(view:View,label:String):View? {
            if(view.contentDescription==label) return view
            if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i),label)?.let { return it }
            return null
        }
        val first=overlay()
        try {
            @Suppress("UNCHECKED_CAST") val actions=field(first,"drawingActions") as List<View>
            actions.first { it.contentDescription=="矩形" }.performClick()
            val layer=field(first,"drawing") as ScreenshotDrawingLayer
            layer.applyStyle(layer.style.copy(color=Color.BLUE,width=8))
            layer.beginStylePreview(); layer.previewStyle(layer.style.copy(color=Color.GREEN)); layer.finishStylePreview(false)
            check(memory.style(ScreenshotDrawingTool.RECTANGLE)?.color==Color.BLUE) { "cancelled preview became the creation default" }
        } finally { first.dismiss(context.getSystemService(WindowManager::class.java)) }
        val second=overlay()
        try {
            val toolbar=field(second,"toolbar") as View
            check(find(toolbar,"矩形")!=null && find(toolbar,"箭头")==null) { "reopened group reset to the old default" }
            find(toolbar,"矩形")!!.performClick()
            val layer=field(second,"drawing") as ScreenshotDrawingLayer
            check(layer.tool==ScreenshotDrawingTool.RECTANGLE && layer.style.color==Color.BLUE && layer.style.width==8)
            layer.tool=ScreenshotDrawingTool.FILTER
            layer.changeArea(ScreenshotAreaMode.PEN)
            layer.applyStyle(layer.style.copy(filter=ScreenshotFilter.BLUR,strength=32,width=40))
            layer.changeArea(ScreenshotAreaMode.RECTANGLE)
            layer.applyStyle(layer.style.copy(strength=52))
            layer.changeArea(ScreenshotAreaMode.PEN)
            layer.tool=ScreenshotDrawingTool.SERIAL; layer.setNumber(17)
            layer.tool=ScreenshotDrawingTool.RECTANGLE
            layer.history.add(ScreenshotDrawingStroke(ScreenshotDrawingTool.RECTANGLE,
                listOf(ScreenshotDrawingPoint(200f,200f),ScreenshotDrawingPoint(400f,400f)),Color.BLUE,8f,layer.style))
            layer.tool=ScreenshotDrawingTool.SELECT
            for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
                val event=android.view.MotionEvent.obtain(0,1,action,200f,300f,0)
                try { layer.onTouchEvent(event) } finally { event.recycle() }
            }
            check(layer.selectedIndex==0)
            layer.applyStyle(layer.inspectedStyle.copy(color=Color.GREEN))
            check(memory.style(ScreenshotDrawingTool.RECTANGLE)?.color==Color.BLUE) { "editing one object overwrote creation defaults" }
            val reopened=ScreenshotDrawingLayer(context,bitmap,ScreenshotToolMemory(context)) {}
            try {
                reopened.tool=ScreenshotDrawingTool.FILTER
                check(reopened.style.filter==ScreenshotFilter.BLUR && reopened.style.area==ScreenshotAreaMode.PEN && reopened.style.strength==32)
                reopened.changeArea(ScreenshotAreaMode.RECTANGLE); check(reopened.style.strength==52)
                check(reopened.nextNumber==17L)
            } finally { reopened.close() }
            val textStyle=ScreenshotDrawingStyle(color=Color.CYAN,fontFamily="serif",bold=true,fontSize=28f,align=2,verticalAlign=1,
                hasFill=true,fillColor=Color.YELLOW,outlineColor=Color.BLUE,outlineWidth=3,cornerRadius=12,opacity=.6f)
            memory.save(ScreenshotDrawingTool.TEXT,textStyle)
            check(ScreenshotToolMemory(context).style(ScreenshotDrawingTool.TEXT)==textStyle) { "text style did not round-trip" }
            val prefs=context.getSharedPreferences("screenshot-tool-memory",Context.MODE_PRIVATE)
            prefs.edit().putInt("style.TEXT.last",42).putString("start-number","bad").putString("recent","UNKNOWN,PEN,PEN").commit()
            check(memory.style(ScreenshotDrawingTool.TEXT)==null && memory.startNumber==1L)
            check(memory.entry(listOf(ScreenshotAction.PEN,ScreenshotAction.ARROW))==ScreenshotAction.PEN)
            prefs.edit().putString("style.TEXT.last","{\"width\":-1,\"fontSize\":9000,\"opacity\":\"NaN\",\"fontFamily\":\"invalid\"}").commit()
            val safe=memory.style(ScreenshotDrawingTool.TEXT)!!
            check(safe.width==1 && safe.fontSize==256f && safe.opacity==1f && safe.fontFamily=="sans-serif") { "Invalid style was not normalized: $safe" }
        } finally { second.dismiss(context.getSystemService(WindowManager::class.java)); bitmap.recycle() }
    }
}
