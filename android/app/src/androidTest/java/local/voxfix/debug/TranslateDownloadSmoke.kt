package local.voxfix.debug

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real HTTPS download in an isolated test directory; preserves the installed user model. */
internal object TranslateDownloadSmoke {
    fun run(test: Instrumentation, interrupt: Boolean): String {
        val directory=File(test.targetContext.cacheDir,"translation-download-smoke").apply { mkdirs() }
        val context=object: ContextWrapper(test.targetContext) {
            override fun getFilesDir(): File = directory
            override fun getApplicationContext(): Context = this
        }
        val store=TranslateModelStore(context)
        val before=store.partial.length()
        val done=CountDownLatch(1)
        var outcome=""
        test.runOnMainSync {
            TranslateDownloader.start(context) { state ->
                when(state) {
                    is TranslateDownloader.State.Running -> {
                        if (interrupt && state.downloaded>=8*1024*1024 && !state.verifying) TranslateDownloader.cancel()
                    }
                    is TranslateDownloader.State.Failed -> { outcome="FAILED ${state.message}"; done.countDown() }
                    TranslateDownloader.State.Ready -> { outcome="READY"; done.countDown() }
                    TranslateDownloader.State.Idle -> { outcome="INTERRUPTED"; done.countDown() }
                }
            }
        }
        check(done.await(15,TimeUnit.MINUTES)) { "download timeout" }
        test.runOnMainSync { TranslateDownloader.observe(null) }
        check(outcome==if(interrupt) "INTERRUPTED" else "READY") { outcome }
        if (interrupt) check(store.partial.length() in 8*1024*1024 until TranslateModelStore.BYTES)
        else check(store.ready())
        return "TRANSLATE_DOWNLOAD_PASS mode=${if(interrupt) "interrupt" else "resume"} before=$before partial=${store.partial.length()} installed=${store.installedBytes()}"
    }
}
