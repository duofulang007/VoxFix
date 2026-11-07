package local.voxfix.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.*

/** Live host remains usable around a movable, pinch-scalable reference image. */
object ScreenshotPins {
    private val pins=mutableSetOf<Pin>()
    /**
     * [document] and [source] keep the pin editable more than once: the source is the canvas without
     * the marks, so a second edit does not draw the first round's arrows twice.
     */
    fun show(context: Context,image: Bitmap,screenRect: Rect,document: ScreenshotDocument?=null,source: Bitmap?=null) {
        require(!screenRect.isEmpty)
        val pin=Pin(context,image,Rect(screenRect))
        pin.ready(document,(source ?: image).copy(Bitmap.Config.ARGB_8888,false))
        try { pin.show(); pins.add(pin) } catch(error: Exception) { pin.close(); throw error }
    }
    fun closeAll() { pins.toList().forEach { it.close() } }
    fun visible(value: Boolean) { pins.forEach { it.visible(value) } }
    fun relayout() { pins.forEach { it.relayout() } }
    private class Pin(private val context: Context,private var image: Bitmap,private val origin: Rect) {
        private var document: ScreenshotDocument?=null
        private var canvas: Bitmap?=null
        fun ready(document: ScreenshotDocument?,canvas: Bitmap?) { this.document=document; this.canvas=canvas }
        private val manager=context.getSystemService(WindowManager::class.java)
        private val density=context.resources.displayMetrics.density
        private fun dp(n: Int)=(n*density).toInt()
        private val root: FrameLayout=object : FrameLayout(context) {
            private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=0xFF4096FF.toInt() }
            override fun dispatchDraw(canvas: android.graphics.Canvas) {
                super.dispatchDraw(canvas)
                if(closeButton.visibility!=View.VISIBLE && edges==0) return
                val inset=dp(4).toFloat()
                val points=listOf(inset to inset,(width-inset) to inset,(width-inset) to (height-inset),inset to (height-inset))+
                    if(minOf(width,height)>=dp(64)) listOf(width/2f to inset,(width-inset) to height/2f,width/2f to (height-inset),inset to height/2f) else emptyList()
                for((x,y) in points) canvas.drawCircle(x,y,dp(3).toFloat(),paint)
            }
        }
        private val picture=ImageView(context).apply {
            setImageBitmap(image); scaleType=ImageView.ScaleType.FIT_CENTER
            contentDescription="悬浮截图，中间拖动移动，边角拖动或双指缩放，轻点显示操作"
        }
        private val closeButton=ImageView(context).apply {
            contentDescription="关闭贴图"; setImageResource(R.drawable.snow_close); setColorFilter(0xFFFF4D4F.toInt())
            setPadding(dp(4),dp(4),dp(4),dp(4)); visibility=View.GONE
            background=GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius=dp(8).toFloat() }
            setOnClickListener { close() }
            setOnLongClickListener { showMenu(); true }
        }
        private val menuButton=ImageView(context).apply {
            contentDescription="贴图操作"; tooltipText="编辑、缩略、比例与透明度"
            setImageResource(R.drawable.snow_edit); setColorFilter(0xFF1677FF.toInt())
            setPadding(dp(8),dp(8),dp(8),dp(8)); visibility=View.GONE
            background=GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius=dp(8).toFloat() }
            setOnClickListener { showMenu() }
        }
        private var popup: PopupWindow?=null
        private var editor: ScreenshotPinEditor?=null
        private var beforeThumbnail: Rect?=null
        private val params=WindowManager.LayoutParams(origin.width(),origin.height(),WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
                gravity=Gravity.TOP or Gravity.LEFT; x=origin.left; y=origin.top; setFitInsetsTypes(0); windowAnimations=0
                layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        private var downX=0f; private var downY=0f; private var startX=0; private var startY=0
        private var startWidth=0; private var startHeight=0; private var edges=0
        private var closed=false
        private val hideControls=Runnable { controls(false) }
        private fun controls(visible: Boolean) {
            root.removeCallbacks(hideControls)
            closeButton.visibility=if(visible) View.VISIBLE else View.GONE
            menuButton.visibility=if(visible && params.width>=dp(88) && params.height>=dp(44)) View.VISIBLE else View.GONE
            border(visible)
            root.invalidate()
            if(visible) root.postDelayed(hideControls,2500)
        }
        private fun border(active: Boolean) {
            root.foreground=GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(dp(1).coerceAtLeast(1),if(active) 0xFF69B1FF.toInt() else 0xFFDBDBDB.toInt())
            }
        }
        private val taps=GestureDetector(context,object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent)=true
            override fun onSingleTapUp(event: MotionEvent): Boolean { picture.performClick(); return true }
            override fun onLongPress(event: MotionEvent) { showMenu() }
        })
        private val scale=ScaleGestureDetector(context,object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { controls(false); border(true); return true }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val oldX=params.x; val oldY=params.y
                val focusX=detector.focusX; val focusY=detector.focusY
                val ratioX=(focusX/params.width).coerceIn(0f,1f); val ratioY=(focusY/params.height).coerceIn(0f,1f)
                resize((params.width*detector.scaleFactor).toInt())
                params.x=(oldX+focusX-ratioX*params.width).toInt(); params.y=(oldY+focusY-ratioY*params.height).toInt()
                clamp(); updateWindow(); return true
            }
        })
        init {
            border(false)
            root.elevation=dp(8).toFloat()
            root.addView(picture,FrameLayout.LayoutParams(-1,-1))
            root.addView(menuButton,FrameLayout.LayoutParams(dp(44),dp(44),Gravity.TOP or Gravity.LEFT))
            root.addView(closeButton,FrameLayout.LayoutParams(minOf(dp(44),params.width,params.height),minOf(dp(44),params.width,params.height),Gravity.TOP or Gravity.RIGHT))
            picture.setOnClickListener { controls(closeButton.visibility!=View.VISIBLE) }
            picture.accessibilityDelegate=object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View,info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host,info)
                    info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(0x01000004,"关闭贴图"))
                    info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(0x01000005,"贴图操作"))
                }
                override fun performAccessibilityAction(host: View,action: Int,args: android.os.Bundle?): Boolean {
                    if(action==0x01000004) { close(); return true }
                    if(action==0x01000005) { showMenu(); return true }
                    return super.performAccessibilityAction(host,action,args)
                }
            }
            val drag=View.OnTouchListener { _,event ->
                scale.onTouchEvent(event)
                taps.onTouchEvent(event)
                when(event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX=event.rawX; downY=event.rawY; startX=params.x; startY=params.y
                        startWidth=params.width; startHeight=params.height
                        val marginX=minOf(dp(20).toFloat(),params.width/4f); val marginY=minOf(dp(20).toFloat(),params.height/4f)
                        edges=(if(event.x<=marginX) 1 else if(event.x>=params.width-marginX) 2 else 0) or
                            (if(event.y<=marginY) 4 else if(event.y>=params.height-marginY) 8 else 0)
                        border(true)
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> { edges=0; controls(false) }
                    MotionEvent.ACTION_POINTER_UP -> {
                        val remaining=if(event.actionIndex==0 && event.pointerCount>1) 1 else 0
                        downX=event.getRawX(remaining); downY=event.getRawY(remaining); startX=params.x; startY=params.y; edges=0
                    }
                    MotionEvent.ACTION_MOVE -> if(event.pointerCount==1 && !scale.isInProgress) {
                        if(edges==0) {
                            params.x=startX+(event.rawX-downX).toInt(); params.y=startY+(event.rawY-downY).toInt()
                        } else resizeFromDrag(event.rawX-downX,event.rawY-downY)
                        clamp(); updateWindow()
                    }
                    MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> { edges=0; border(closeButton.visibility==View.VISIBLE) }
                }
                root.invalidate()
                true
            }
            picture.setOnTouchListener(drag); root.setOnTouchListener(drag)
        }
        private fun geometry()=Rect(params.x,params.y,params.x+params.width,params.y+params.height)
        private fun useGeometry(rect: Rect) {
            params.x=rect.left; params.y=rect.top; params.width=rect.width(); params.height=rect.height()
            fitButtons(); updateWindow()
        }
        private fun thumbnail() {
            val previous=beforeThumbnail
            if(previous!=null) {
                beforeThumbnail=null; useGeometry(previous)
                val bounds=manager.currentWindowMetrics.bounds
                if(params.width>bounds.width() || params.height>bounds.height()) resize(params.width)
                clamp(); updateWindow()
            } else {
                beforeThumbnail=geometry()
                val factor=minOf(1f,dp(83).toFloat()/maxOf(params.width,params.height))
                params.width=(params.width*factor).toInt().coerceAtLeast(1)
                params.height=(params.height*factor).toInt().coerceAtLeast(1)
                fitButtons(); clamp(); updateWindow()
            }
        }
        /** 上游 `applyScale`：百分比相对初始窗口宽度；手机窗口不能超出屏幕，由 [resize] 收口。 */
        private fun currentPercent()=Math.round(params.width*100f/origin.width())
        private fun scaleTo(percent: Int) {
            val clamped=percent.coerceIn(10,500)
            resize(Math.round(origin.width()*clamped/100f).coerceAtLeast(1))
            updateWindow()
        }
        /** 100%：回到初始窗口尺寸，同时解除缩略态（上游 100% 预设同样恢复初始宽高）。 */
        private fun resetSize() {
            beforeThumbnail=null
            params.width=origin.width(); params.height=origin.height(); clamp(); fitButtons(); updateWindow()
        }
        /** 未变换的裁切：旋转/翻转都以它为源重算，避免反复变换累积重采样损失。 */
        private val baseImage=image
        private var quarterTurns=0
        private var flippedX=false
        private var flippedY=false
        private fun baseWidthFor(turns: Int)=if(turns%2==0) origin.width() else origin.height()
        /**
         * 上游 `applyImageOperation`：对图像做 90° 整数次旋转／镜像，窗口尺寸按**同一缩放比例**
         * 换到新朝向（`orientedInitialWindowSize * scalePercent`）。
         * 编辑贴图仍在原始截图纸上工作（`canvas`＋`document`），所以编辑结果会把贴图换回未旋转的朝向，
         * 与原版一致；这里不把变换写进工程。
         */
        private fun applyTransform(turns: Int,h: Boolean,v: Boolean) {
            if(closed || editor!=null) return
            val percent=params.width*100f/baseWidthFor(quarterTurns)
            quarterTurns=((turns%4)+4)%4; flippedX=h; flippedY=v
            val matrix=android.graphics.Matrix().apply {
                postRotate(quarterTurns*90f)
                if(h || v) postScale(if(h) -1f else 1f,if(v) -1f else 1f)
            }
            val next=try { Bitmap.createBitmap(baseImage,0,0,baseImage.width,baseImage.height,matrix,true) }
                catch(error: Throwable) { return }
            val previous=image
            image=next; picture.setImageBitmap(next)
            if(previous!==baseImage && previous!==canvas && !previous.isRecycled) previous.recycle()
            resize(Math.round(baseWidthFor(quarterTurns)*percent/100f).coerceAtLeast(1))
            clamp(); updateWindow()
        }
        private fun showMenu() {
            if(closed || popup!=null || editor!=null) return
            controls(false)
            val body=LinearLayout(context).apply {
                orientation=LinearLayout.VERTICAL; setPadding(dp(8),dp(4),dp(8),dp(4))
                background=GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius=dp(8).toFloat() }
            }
            val window=PopupWindow(ScrollView(context).apply { addView(body) },minOf(dp(264),manager.currentWindowMetrics.bounds.width()-dp(16)),-2,true).apply {
                elevation=dp(8).toFloat(); setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)); isOutsideTouchable=true
            }
            fun item(label: String,run:()->Unit) { body.addView(TextView(context).apply {
                text=label; textSize=14f; gravity=Gravity.CENTER; setTextColor(0xFF1677FF.toInt()); contentDescription=label
                setOnClickListener { window.dismiss(); run() }
            },LinearLayout.LayoutParams(-1,dp(44))) }
            item("编辑贴图") { edit() }
            item(if(beforeThumbnail==null) "缩略贴图" else "还原贴图") { thumbnail() }
            // 上游 Process image → Scale：±10% 与 25/50/75/100 预设，百分比以初始窗口宽度为 100%。
            val percent=currentPercent()
            item("放大 10%（当前 $percent%）") { scaleTo(percent+10) }
            item("缩小 10%") { scaleTo(percent-10) }
            listOf(25,50,75).forEach { target -> item("缩放 $target%") { scaleTo(target) } }
            item("缩放 100%") { resetSize() }
            // 上游 Process image → Rotate／Flip／Reset transform：以未变换的裁切为源重算，窗口按同一比例换向。
            item("顺时针旋转") { applyTransform(quarterTurns+1,flippedX,flippedY) }
            item("逆时针旋转") { applyTransform(quarterTurns-1,flippedX,flippedY) }
            item("水平翻转") { applyTransform(quarterTurns,!flippedX,flippedY) }
            item("垂直翻转") { applyTransform(quarterTurns,flippedX,!flippedY) }
            if(quarterTurns!=0 || flippedX || flippedY) item("重置旋转/翻转") { applyTransform(0,false,false) }
            val readout=TextView(context).apply { text="透明度 ${(picture.alpha*100).toInt()}%"; textSize=13f; setTextColor(0xFF000000.toInt()) }
            body.addView(readout)
            body.addView(SeekBar(context).apply {
                max=75; progress=(picture.alpha*100).toInt()-25; contentDescription="贴图透明度"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(bar: SeekBar) {}
                    override fun onStopTrackingTouch(bar: SeekBar) {}
                    override fun onProgressChanged(bar: SeekBar,value: Int,fromUser: Boolean) {
                        if(fromUser) { picture.alpha=(value+25)/100f; readout.text="透明度 ${value+25}%" }
                    }
                })
            },LinearLayout.LayoutParams(-1,dp(44)))
            item("关闭贴图") { close() }
            popup=window; window.setOnDismissListener { popup=null }
            window.showAtLocation(root,Gravity.CENTER,0,0)
        }
        private fun edit() {
            if(editor!=null || closed) return
            val working=ScreenshotPinEditor(context,canvas ?: image,geometry(),ScreenshotToolMemory(context),document) { result ->
                editor=null
                if(closed || result==null) { result?.bitmap?.recycle(); if(!closed) { root.visibility=View.VISIBLE; controls(false) } }
                else {
                    document=result.document
                    // A recorded hardware frame may still reference the previous image.
                    picture.setImageBitmap(result.bitmap); image=result.bitmap
                    root.visibility=View.VISIBLE; controls(false)
                }
            }
            editor=working
            try { working.show(); root.visibility=View.INVISIBLE }
            catch(error: Exception) { working.close(); android.widget.Toast.makeText(context,"无法打开贴图编辑",Toast.LENGTH_SHORT).show() }
        }
        private fun resizeFromDrag(dx: Float,dy: Float) {
            val widthScale=(startWidth+(if(edges and 1!=0) -dx else dx))/startWidth
            val heightScale=(startHeight+(if(edges and 4!=0) -dy else dy))/startHeight
            // Match Snow Shot's proportional resize and fixed opposite anchor.
            val factor=if(edges and 3!=0 && edges and 12!=0) maxOf(widthScale,heightScale)
                else if(edges and 3!=0) widthScale else heightScale
            resize((startWidth*factor).toInt())
            params.x=if(edges and 1!=0) startX+startWidth-params.width else startX
            params.y=if(edges and 4!=0) startY+startHeight-params.height else startY
        }
        private fun updateWindow() { if(root.isAttachedToWindow) manager.updateViewLayout(root,params) }
        private fun resize(width: Int) {
            val metrics=manager.currentWindowMetrics
            val bounds=metrics.bounds
            val maxWidth=bounds.width().coerceAtLeast(1)
            val minimum=minOf(dp(48),origin.width(),maxWidth).coerceAtLeast(1)
            params.width=width.coerceIn(minimum,maxWidth)
            params.height=(params.width.toFloat()*image.height/image.width).toInt().coerceAtLeast(1)
            if(params.height>bounds.height()) {
                params.height=bounds.height().coerceAtLeast(1)
                params.width=(params.height.toFloat()*image.width/image.height).toInt().coerceAtLeast(1)
            }
            fitButtons()
            clamp()
        }
        private fun fitButtons() {
            val buttonSize=minOf(dp(44),params.width,params.height)
            closeButton.layoutParams=FrameLayout.LayoutParams(buttonSize,buttonSize,Gravity.TOP or Gravity.RIGHT)
        }
        private fun clamp() {
            val metrics=manager.currentWindowMetrics
            val bounds=metrics.bounds
            params.x=params.x.coerceIn(0,(bounds.width()-params.width).coerceAtLeast(0))
            params.y=params.y.coerceIn(0,(bounds.height()-params.height).coerceAtLeast(0))
        }
        fun show() { manager.addView(root,params) }
        fun visible(value: Boolean) { popup?.dismiss(); controls(false); root.visibility=if(value && editor==null) View.VISIBLE else View.INVISIBLE }
        fun relayout() { popup?.dismiss(); resize(params.width); editor?.relayout(geometry()); runCatching { manager.updateViewLayout(root,params) } }
        fun close() {
            if(closed) return
            closed=true; popup?.dismiss(); editor?.close(); root.removeCallbacks(hideControls)
            runCatching { manager.removeViewImmediate(root) }
            picture.setImageDrawable(null)
            val current=image
            if(!current.isRecycled) current.recycle()
            // 旋转/翻转后 image 是派生的显示图，未变换的裁切单独回收。
            if(baseImage!==current && !baseImage.isRecycled) baseImage.recycle()
            canvas?.let { if(!it.isRecycled) it.recycle() }; canvas=null
            pins.remove(this)
        }
    }
}
