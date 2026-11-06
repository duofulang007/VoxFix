package local.voxfix.debug

import android.app.Activity
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper

/** Main-thread rendezvous. Consent grants are single-use and stay within the app process. */
internal object ScreenshotProjection {
    private class Request(
        val id: Int, val title: String, val microphone: Boolean, val ready: (MediaProjection, () -> Unit) -> Unit,
        val failed: (String) -> Unit
    ) {
        var uiClosed = false
        var projection: MediaProjection? = null
        var stop: (() -> Unit)? = null
    }
    private var next = 0
    private var pending: Request? = null

    fun request(context: Context, title: String, ready: (MediaProjection, () -> Unit) -> Unit,
                failed: (String) -> Unit, microphone: Boolean = false): Int {
        // A request can outlive the service instance that asked for it (the accessibility service
        // is rebuilt by the system). Dropping the stale one keeps a late consent from being
        // delivered into a destroyed service and from crashing the caller.
        pending?.let { stale ->
            pending = null
            runCatching { stale.stop?.invoke() }
        }
        val id = ++next
        pending = Request(id, title, microphone, ready, failed)
        try {
            context.startActivity(Intent(context, ScreenshotProjectionActivity::class.java)
                .putExtra("request", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: Exception) {
            pending = null
            throw error
        }
        return id
    }

    /**
     * The owning service is being destroyed.
     *
     * While the consent UI is still open the request is kept: the system may recreate the service
     * before the grant arrives, and the callback now routes to whichever instance is alive. Only a
     * request that can no longer be delivered is dropped.
     */
    fun abandonUnlessUiOpen() {
        val request = pending ?: return
        if (!request.uiClosed) {
            android.util.Log.i("VoxFix", "PROJECTION_KEPT id=${request.id}")
            return
        }
        android.util.Log.i("VoxFix", "PROJECTION_ABANDON id=${request.id}")
        pending = null
        runCatching { request.stop?.invoke() }
    }

    fun title(id: Int) = pending?.takeIf { it.id == id }?.title
    fun microphone(id: Int) = pending?.takeIf { it.id == id }?.microphone == true

    fun closeUi(id: Int) {
        val request = pending?.takeIf { it.id == id } ?: return
        request.uiClosed = true
        deliver(request)
    }

    fun connect(id: Int, projection: MediaProjection, stop: () -> Unit) {
        val request = pending?.takeIf { it.id == id }
        if (request == null) {
            android.util.Log.w("VoxFix", "PROJECTION_CONNECT_STALE id=$id pending=${pending?.id}")
            stop(); return
        }
        request.projection = projection
        request.stop = stop
        deliver(request)
    }

    private fun deliver(request: Request) {
        android.util.Log.i("VoxFix", "PROJECTION_DELIVER id=${request.id} closed=${request.uiClosed} " +
            "projection=${request.projection != null} stop=${request.stop != null}")
        if (!request.uiClosed) return
        val projection = request.projection ?: return
        val stop = request.stop ?: return
        pending = null
        request.ready(projection, stop)
    }

    fun cancel(id: Int) {
        val request = pending?.takeIf { it.id == id } ?: return
        pending = null
        request.stop?.invoke()
    }

    fun fail(id: Int, message: String) {
        val request = pending?.takeIf { it.id == id } ?: return
        cancel(id)
        request.failed(message)
    }
}

class ScreenshotProjectionActivity : Activity() {
    private val id get() = intent.getIntExtra("request", 0)
    private var accepted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ScreenshotProjection.title(id) == null) { finish(); return }
        if (savedInstanceState != null) return
        if (ScreenshotProjection.microphone(id) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
        } else requestProjection()
    }

    private fun requestProjection() {
        if (ScreenshotProjection.title(id) == null) { finish(); return }
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val consent = if (Build.VERSION.SDK_INT >= 34)
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else manager.createScreenCaptureIntent()
            startActivityForResult(consent, 1)
        } catch (_: Exception) {
            ScreenshotProjection.fail(id, "无法打开屏幕采集授权")
            finish()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 2) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestProjection()
        else {
            ScreenshotProjection.fail(id, "未允许使用麦克风，可重新选择无声录屏")
            finish()
        }
    }

    @Deprecated("Activity result is handled in the single-use permission host")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1) return
        if (resultCode == RESULT_OK && data != null && ScreenshotProjection.title(id) != null) try {
            startForegroundService(Intent(this, ScreenshotProjectionService::class.java)
                .putExtra("request", id).putExtra("consent", data))
            accepted = true
        } catch (_: Exception) {
            ScreenshotProjection.fail(id, "无法启动屏幕采集")
        } else ScreenshotProjection.fail(id, "未开启屏幕采集")
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            if (accepted) Handler(Looper.getMainLooper()).post { ScreenshotProjection.closeUi(id) }
            else ScreenshotProjection.fail(id, "屏幕采集已取消")
        }
    }
}

class ScreenshotProjectionService : Service() {
    private var request = 0
    private var projection: MediaProjection? = null
    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        request = intent?.getIntExtra("request", 0) ?: 0
        val title = ScreenshotProjection.title(request)
        val token = intent?.getParcelableExtra<Intent>("consent")
        if (title == null || token == null) { stopSelf(startId); return START_NOT_STICKY }
        try {
            val channel = "voxfix-screen-projection"
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channel, "VoxFix 屏幕采集", NotificationManager.IMPORTANCE_LOW))
            val microphone = if (ScreenshotProjection.microphone(request)) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            startForeground(4241, Notification.Builder(this, channel)
                .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle(title).setOngoing(true).build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or microphone)
            val active = checkNotNull(getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(Activity.RESULT_OK, token))
            projection = active
            ScreenshotProjection.connect(request, active) { stopSelf(startId) }
        } catch (_: Exception) {
            ScreenshotProjection.fail(request, "屏幕采集授权失效")
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        projection?.stop()
        projection = null
        ScreenshotProjection.fail(request, "屏幕采集已结束")
        super.onDestroy()
    }
}
