package local.voxfix.debug

import android.app.Application
import java.io.File

/**
 * 进程启动时把「待恢复的系统偏好」落位。
 *
 * 为什么不在恢复当下直接写 `shared_prefs/`：SharedPreferences 一旦被读过就带进程内缓存，
 * 运行中覆盖 xml 既不反映到内存，也可能被内存里的旧值回写覆盖。所以备份恢复只把 xml
 * 放进 `files/prefs-restore/`，由这里在 `Application.onCreate`（任何 prefs 读取之前）
 * 复制进 `shared_prefs/`，下一次读取拿到的就是恢复后的值。
 *
 * 这里的文件操作都很小（8 个几十 KB 的 xml），放在 Application.onCreate 不会有感知。
 */
class VoxFixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        applyPendingPrefs()
    }

    private fun applyPendingPrefs() {
        val staging = File(filesDir, "prefs-restore")
        if (!staging.isDirectory) return
        val target = File(applicationInfo.dataDir, "shared_prefs")
        if (!target.isDirectory && !target.mkdirs()) return
        for (file in staging.listFiles().orEmpty()) {
            if (!file.isFile || !file.name.endsWith(".xml")) continue
            runCatching { file.copyTo(File(target, file.name), overwrite = true) }
        }
        staging.deleteRecursively()
    }
}
