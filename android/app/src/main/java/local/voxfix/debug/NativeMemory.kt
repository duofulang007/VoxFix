package local.voxfix.debug

import android.os.Build

internal object NativeMemory {
    init { System.loadLibrary("voxfix_rime") }
    fun purge(): Boolean = nativePurge(Build.VERSION.SDK_INT >= 34)
    private external fun nativePurge(all: Boolean): Boolean
}
