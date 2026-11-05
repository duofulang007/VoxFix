package local.voxfix.debug

import android.content.Context
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

/** Phone controls for the Object / Grid modes exposed by the upstream drawing-engine demo. */
internal class ScreenshotSnapSettings(context: Context) : LinearLayout(context) {
    private val prefs = ScreenshotSnapConfig.preferences(context)

    init {
        orientation = VERTICAL
        val config = ScreenshotSnapConfig.fromPreferences(prefs)
        val grid = Switch(context).apply { text = "网格吸附"; isChecked = config.gridEnabled }
        val objects = Switch(context).apply { text = "对象吸附"; isChecked = config.snapEnabled && !grid.isChecked }
        fun syncModes() {
            objects.isEnabled = !grid.isChecked
            grid.isEnabled = !objects.isChecked
        }
        val listener = android.widget.CompoundButton.OnCheckedChangeListener { _, _ ->
            prefs.edit().putBoolean("snapEnabled", objects.isChecked).putBoolean("gridEnabled", grid.isChecked).apply()
            syncModes()
        }
        objects.setOnCheckedChangeListener(listener)
        grid.setOnCheckedChangeListener(listener)
        syncModes()
        addView(objects)
        addView(grid)

        val minimum = (ScreenshotSnapConfig.MIN_GRID_SIZE * 10).toInt()
        val maximum = (ScreenshotSnapConfig.MAX_GRID_SIZE * 10).toInt()
        val label = TextView(context)
        addView(label)
        addView(SeekBar(context).apply {
            contentDescription = "网格间距"
            max = maximum - minimum
            keyProgressIncrement = 10
            progress = (config.gridSize * 10).roundToInt().coerceIn(minimum, maximum) - minimum
            fun update(progress: Int, save: Boolean) {
                val size = (progress + minimum) / 10.0
                val value = "$size px"
                label.text = "网格间距 $value"
                stateDescription = value
                if (save) prefs.edit().putLong("gridSize", size.toRawBits()).apply()
            }
            update(progress, false)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) = update(progress, fromUser)
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        })
    }
}
