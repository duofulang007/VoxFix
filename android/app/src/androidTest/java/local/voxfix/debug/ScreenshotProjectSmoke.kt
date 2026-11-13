package local.voxfix.debug

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import java.io.File

/**
 * Project storage on a private sandbox directory: revisions, crop, delete and old flat records.
 * Nothing here touches the user's real image clipboard.
 */
object ScreenshotProjectSmoke {
    private class Sandbox(base: Context) : ContextWrapper(base) {
        val root = File(base.cacheDir, "screenshot-project-${System.currentTimeMillis()}")
        override fun getFilesDir() = root
    }

    fun run(test: Instrumentation): String {
        val sandbox = Sandbox(test.targetContext)
        var error: Throwable? = null
        test.runOnMainSync {
            try { projects(sandbox); reopen(test.targetContext) } catch (e: Throwable) { error = e } finally { sandbox.root.deleteRecursively() }
        }
        error?.let { throw it }
        return "SCREENSHOT_PROJECT_PASS: create,revision,reopen,crop,old-record,delete,budget,editor-reopen"
    }

    private fun bitmap(width: Int, height: Int, color: Int) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun projects(context: Context) {
        val store = ScreenshotStore(context)
        val source = bitmap(400, 600, Color.WHITE)
        val composite = bitmap(200, 300, Color.LTGRAY)
        val document = ScreenshotDocument(400, 600, ScreenshotDocRect(50, 60, 250, 360),
            listOf(ScreenshotDrawingStroke(ScreenshotDrawingTool.ARROW,
                listOf(ScreenshotDrawingPoint(60f, 70f), ScreenshotDrawingPoint(240f, 350f)), Color.RED, 4f, id = 1L)))
        val first = store.saveProject(document, composite, source)
        try {
            check(first.kind == ScreenshotStore.Kind.PROJECT && first.revision == 1) { "工程记录类型或修订号错误" }
            check(store.entries().size == 1) { "工程记录没有进入列表" }
            check(store.document(first.id) == document) { "工程无法重开：${store.document(first.id)}" }
            val compositeFile = store.file(first.id)
            val decoded = BitmapFactory.decodeFile(compositeFile.path)
            check(decoded != null && decoded.width == 200 && decoded.height == 300) { "成品图片不可读" }
            decoded.recycle()
            val stored = store.sourceBitmap(first.id)
            check(stored != null && stored.width == 400 && stored.height == 600) { "底图不是原始画布" }
            stored.recycle()

            // Second save replaces the record and supersedes the old revision directory.
            val moved = document.copy(objects = listOf(document.objects.single().transformed(60f, 70f, 120f, 150f)))
            val second = store.saveProject(moved, composite, null, replaceId = first.id)
            check(second.id == first.id && second.revision == 2) { "再次保存没有更新同一记录" }
            check(store.entries().size == 1) { "再次保存复制了记录" }
            check(store.document(first.id)?.objects?.single()?.points?.first() == ScreenshotDrawingPoint(120f, 150f)) { "重开读到的还是旧修订" }
            check(!File(File(File(context.filesDir, "screenshot-clipboard/projects"), first.id), "1").exists()) { "旧修订没有清理" }

            // Crop keeps the canvas, moves the viewport and leaves the original record alone.
            val cropped = store.cropProject(first.id, 10, 20, 60, 120, composite)
            check(cropped.id != first.id && cropped.kind == ScreenshotStore.Kind.PROJECT) { "裁剪另存没有生成新记录" }
            check(cropped.width == 50 && cropped.height == 100) { "裁剪尺寸错误：${cropped.width}x${cropped.height}" }
            val croppedDoc = store.document(cropped.id) ?: error("裁剪记录没有工程")
            check(croppedDoc.viewport == ScreenshotDocRect(60, 80, 110, 180)) { "裁剪视窗映射错误：${croppedDoc.viewport}" }
            check(croppedDoc.objects == moved.objects) { "裁剪改变了对象坐标" }
            check(store.document(first.id) == moved) { "裁剪改动了原记录" }
            check(store.entries().size == 2) { "裁剪后记录数量错误" }

            store.delete(cropped.id)
            check(store.entries().size == 1 && !File(File(context.filesDir, "screenshot-clipboard/projects"), cropped.id).exists()) { "删除没有清理工程目录" }

            // The stale revision guard refuses a save that would overwrite a newer one.
            val stale = runCatching { store.saveProject(document, composite, null, replaceId = first.id, expectedRevision = first.revision) }
            check(stale.isFailure) { "旧修订覆盖了较新的保存" }
            val gate=java.util.concurrent.CountDownLatch(1)
            val results=java.util.Collections.synchronizedList(mutableListOf<Boolean>())
            val writers=(1..2).map {
                Thread {
                    gate.await()
                    results.add(runCatching { ScreenshotStore(context).saveProject(moved,composite,
                        replaceId=first.id,expectedRevision=second.revision) }.isSuccess)
                }.apply { start() }
            }
            gate.countDown(); writers.forEach { it.join(10000) }
            check(writers.none { it.isAlive } && results.count { it }==1) { "并发保存没有保留唯一有效修订：$results" }
            check(ScreenshotStore(context).file(first.id).isFile && store.document(first.id)==moved) { "失败的并发保存删掉了有效修订" }
            val sourcePath=checkNotNull(store.sourceFile(first.id))
            val hidden=File(sourcePath.parentFile,"source-test-backup.png")
            check(sourcePath.renameTo(hidden))
            try { check(store.sourceBitmap(first.id)==null) { "底图缺失时错误地用合成图代替底图" } }
            finally { check(hidden.renameTo(sourcePath)) }

            // A record written by the previous version stays a plain image and can be annotated.
            val legacy = legacyRecord(store, context)
            val legacyEntry = store.entries().first { it.id == legacy }
            check(legacyEntry.kind == ScreenshotStore.Kind.IMAGE && store.document(legacy) == null) { "旧记录没有按普通图片读取" }
            val fromOld = store.saveProject(document, composite, null, copySourceFromId = legacy)
            check(fromOld.kind == ScreenshotStore.Kind.PROJECT) { "旧图片加标注没有生成工程" }
            val copiedSource = File(File(File(context.filesDir, "screenshot-clipboard/projects"), fromOld.id), "source.png")
            check(copiedSource.isFile && copiedSource.readBytes().contentEquals(store.file(legacy).readBytes())) { "旧图片底图没有原样复制" }

            // 长截图入库：PNG 直存也必须带工程，否则点「编辑」只能造空工程，之前的内容无从恢复。
            val longPng = File(context.filesDir, "longshot-test.png")
            val longImage = bitmap(120, 900, Color.WHITE)
            try { longPng.outputStream().use { check(longImage.compress(Bitmap.CompressFormat.PNG,100,it)) } }
            finally { longImage.recycle() }
            val longDoc = ScreenshotDocument.full(120, 900)
            val longEntry = store.savePng(longPng, 120, 900, document = longDoc) ?: error("长截图没有入库")
            check(longEntry.kind == ScreenshotStore.Kind.PROJECT && longEntry.revision == 1) { "长截图入库不是工程" }
            check(store.document(longEntry.id) == longDoc) { "长截图工程读不回" }
            check(store.file(longEntry.id).isFile && store.sourceFile(longEntry.id)?.isFile == true) { "长截图底图或合成图缺失" }
            val longMoved = longDoc.copy(objects = listOf(ScreenshotDrawingStroke(ScreenshotDrawingTool.PEN,
                listOf(ScreenshotDrawingPoint(10f,20f),ScreenshotDrawingPoint(80f,120f)),Color.RED,3f,id=21L)))
            val longComposite = bitmap(120, 900, Color.LTGRAY)
            try { store.saveProject(longMoved, longComposite, null, replaceId = longEntry.id, expectedRevision = 1) }
            finally { longComposite.recycle() }
            check(store.document(longEntry.id)?.objects?.single()?.id == 21L) { "长截图二次保存没有保留对象" }
            check(store.entry(longEntry.id)?.revision == 2) { "长截图二次保存没有递增修订" }

            // 普通图片原地升级：同一条记录变成工程、列表不新增，点它就能接着改上次的元素与工具。
            val plain = store.save(bitmap(300, 500, Color.WHITE))
            val plainDoc = ScreenshotDocument(300, 500, ScreenshotDocRect(0, 0, 300, 500),
                listOf(ScreenshotDrawingStroke(ScreenshotDrawingTool.PEN,
                    listOf(ScreenshotDrawingPoint(10f, 20f), ScreenshotDrawingPoint(120f, 260f)), Color.BLUE, 5f, id = 31L)),
                activeTool = "PEN", activeStyle = ScreenshotDrawingStyle(color = Color.BLUE, width = 5))
            val plainComposite = bitmap(300, 500, Color.LTGRAY)
            val upgraded = try {
                store.saveProject(plainDoc, plainComposite, null, replaceId = plain.id, expectedRevision = 0)
            } finally { plainComposite.recycle() }
            check(upgraded.id == plain.id) { "原地升级换了记录 id：${upgraded.id} != ${plain.id}" }
            check(upgraded.kind == ScreenshotStore.Kind.PROJECT && upgraded.revision == 1) { "普通图片升级后不是工程首版" }
            check(store.document(plain.id) == plainDoc) { "升级后读不回元素/工具/样式" }
            check(store.sourceFile(plain.id)?.isFile == true && store.file(plain.id).isFile) { "升级后底图或合成图缺失" }
            check(!File(File(context.filesDir, "screenshot-clipboard"), "${plain.id}.png").exists()) { "升级后旧 PNG 没有清理" }
            check(store.entries().count { it.id == plain.id } == 1) { "升级后出现重复条目" }


        } finally {
            runCatching { store.entries().forEach { store.delete(it.id) } }
            source.recycle(); composite.recycle()
        }
    }

    private fun legacyRecord(store: ScreenshotStore, context: Context): String {
        val id = "0123456789abcdef0123456789abcdef"
        val image = bitmap(120, 80, Color.GREEN)
        try {
            FileOutputStreamWriter(File(context.filesDir, "screenshot-clipboard").apply { mkdirs() }.resolve("$id.png"), image)
            File(context.filesDir, "screenshot-clipboard/$id.webp").writeBytes(byteArrayOf())
            val index = File(context.filesDir, "screenshot-clipboard/index.json")
            index.writeText("""{"version":1,"items":[{"id":"$id","createdAt":1,"width":120,"height":80,"bytes":1234}]}""")
        } finally { image.recycle() }
        check(store.entries().any { it.id == id }) { "旧版本索引无法读取" }
        return id
    }

    private fun FileOutputStreamWriter(file: File, image: Bitmap) {
        file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) { "旧图片写入失败" } }
    }

    /** Reopening a saved record loads its marks and text, keeps the viewport fixed and replaces it. */
    private fun reopen(context: Context) {
        val id = "a".repeat(32)
        val image = bitmap(400,600,Color.WHITE)
        val document = ScreenshotDocument(400,600,ScreenshotDocRect(50,60,250,360),
            listOf(ScreenshotDrawingStroke(ScreenshotDrawingTool.ARROW,
                listOf(ScreenshotDrawingPoint(80f,90f),ScreenshotDrawingPoint(200f,300f)),Color.RED,4f,id=7)),
            ScreenshotDocumentText(listOf(ScreenshotTextBlock(60,70,200,110,"原文")),emptyList(),ScreenshotTextMode.ORIGINAL,false))
        var saved: ScreenshotSaveRequest? = null
        val translator = object : ScreenshotTranslator {
            override fun available() = false
            override fun cancel() {}
            override fun translate(text: String,pair: TranslatePair,onResult:(String)->Unit,onFailure:(String)->Unit,onUpdate:(String)->Unit) {}
        }
        val overlay = ScreenshotCaptureOverlay(context,image,{ saved=it },{},{},{},{},translator,null,
            ScreenshotProject(document.copy(),id,1,null))
        try {
            val root = overlay.javaClass.getDeclaredField("root").apply { isAccessible=true }.get(overlay) as ViewGroup
            repeat(2) {
                root.forceLayout()
                root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
                root.layout(0,0,1080,2400)
            }
            check(overlay.isProjectEditor) { "重开的记录没有被识别为工程编辑" }
            val picked = overlay.javaClass.getDeclaredField("picked").apply { isAccessible=true }.get(overlay) as Rect
            check(picked==Rect(50,60,250,360)) { "重开后视窗不是保存时的选框：$picked" }
            val drawing = overlay.javaClass.getDeclaredField("drawing").apply { isAccessible=true }.get(overlay) as ScreenshotDrawingLayer
            check(drawing.history.items.size==1 && drawing.history.items.single().id==7L) { "重开后对象丢失或编号改变" }
            val layer = overlay.javaClass.getDeclaredField("textLayer").apply { isAccessible=true }.get(overlay) as ScreenshotTextLayer
            check(layer.currentBlocks().single().text=="原文") { "重开后识别文字丢失" }
            // The viewport of a saved record must not be re-drawn by a stray touch on the selector.
            overlay.javaClass.getDeclaredMethod("select",Rect::class.java).apply { isAccessible=true }
                .invoke(overlay,Rect(0,0,100,100))
            check(overlay.javaClass.getDeclaredField("picked").apply { isAccessible=true }.get(overlay)==Rect(50,60,250,360)) { "重开后视窗被改动" }
            val save = overlay.javaClass.getDeclaredMethod("beginSave").apply { isAccessible=true }
            save.invoke(overlay)
            val request = checkNotNull(saved) { "重开的工程无法保存" }
            check(request.replaceId==id && request.expectedRevision==1) { "保存没有更新原记录：${request.replaceId}/${request.expectedRevision}" }
            check(request.copySourceFromId==null) { "已有工程不应复制底图" }
            check(request.document.objects.single().id==7L) { "保存的工程丢了对象编号" }
            check(request.document.text?.blocks?.single()?.text=="原文") { "保存的工程丢了识别文字" }
            check(request.document.viewport==ScreenshotDocRect(50,60,250,360)) { "保存的工程丢了视窗" }
            request.composite?.recycle()
        } finally {
            saved?.composite?.takeIf { !it.isRecycled }?.recycle()
            overlay.dismiss(context.getSystemService(android.view.WindowManager::class.java))
            image.recycle()
        }
    }
}
