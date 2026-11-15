package local.voxfix.debug

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * 「恢复贴边弧线」：把用户从「弧线不见了，只能重启」里救出来。
 *
 * 只做三件事：把开关写回开启、把位置/配色兜底、让活着的服务把窗口拆掉重建；
 * 服务没绑上时复用 [ScreenTextHealth.repair] 的「摘出再放回」。
 *
 * 不做的：listed=false（用户从没启用或主动关掉无障碍）时不代用户打开权限，
 * 只返回 DISABLED，由界面深链到本服务的设置条目。
 */
object ArcRecovery {
    enum class Result { OK, BUSY, DISABLED, DENIED, REVERTED, FAILED, WINDOW_MISSING }

    fun reset(context: Context, done: (Result) -> Unit) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())

        // 正在截图/录屏/编辑：重绑会触发 onDestroy，中断用户正在做的事。
        if (ScreenTextService.busyForRepair()) {
            main.post { done(Result.BUSY) }
            return
        }

        // 开关是两套：模块总闸与弧线自身偏好，恢复时一起写，避免「开了还是不出来」。
        Modules.set(app, Module.SCREENTEXT, true)
        ScreenTextPrefs.setArcEnabled(app, true)
        val y = ScreenTextPrefs.handleY(app)
        if (y.isNaN() || y.isInfinite() || y !in 0f..1f) ScreenTextPrefs.resetHandleGeometry(app)
        ScreenTextPrefs.clearTone(app)
        HandleToneResolver.invalidate()

        if (!ScreenTextHealth.listed(app)) {
            main.post { done(Result.DISABLED) }
            return
        }
        if (!ScreenTextHealth.bound(app)) {
            ScreenTextHealth.repair(app) { outcome ->
                done(
                    when (outcome) {
                        ScreenTextHealth.Outcome.OK -> Result.OK
                        ScreenTextHealth.Outcome.BUSY -> Result.BUSY
                        ScreenTextHealth.Outcome.DISABLED -> Result.DISABLED
                        ScreenTextHealth.Outcome.DENIED -> Result.DENIED
                        ScreenTextHealth.Outcome.REVERTED -> Result.REVERTED
                        ScreenTextHealth.Outcome.FAILED -> Result.FAILED
                    }
                )
            }
            return
        }

        // 已绑上：窗口可能「挂着但全透明」或已被系统回收，必须重建，rebind 不该由这里发起。
        app.sendBroadcast(Intent(ScreenTextService.ACTION_RESET_ARC).setPackage(app.packageName))
        main.postDelayed({
            ScreenTextHealth.invalidate()
            done(if (ScreenTextService.windowVisible()) Result.OK else Result.WINDOW_MISSING)
        }, 1200)
    }
}
