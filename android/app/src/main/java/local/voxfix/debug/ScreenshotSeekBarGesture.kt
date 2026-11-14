package local.voxfix.debug

import android.view.MotionEvent
import android.widget.SeekBar

/**
 * 让处于 HorizontalScrollView/ScrollView 子树里的 SeekBar 能被横向拖动。
 *
 * 根因：HSV 在位移超过 touchSlop 后 `onInterceptTouchEvent` 拦截，SeekBar 收到
 * ACTION_CANCEL——而 Area/Spotlight/Watermark 三处的 CANCEL 分支正是预览回滚点，
 * 表现就是「按上去了但拖不动，数值还弹回去」。对照组成立：
 * `ScreenshotCornerRadiusButton` 与 `ScreenshotStepper` 在 DOWN 时调
 * `parent?.requestDisallowInterceptTouchEvent(true)`，所以这两个能拖。
 *
 * 为什么不是「StyleRow 关闭 HSV 拦截」：横滑是二级栏的主导航，整体关拦截等于废掉
 * 整行滚动；只对滑杆自身的本次手势声明「父级不得拦截」，精准、可逆。
 */
internal fun SeekBar.bindUninterruptedDrag(onCancel: (() -> Unit)? = null) {
    setOnTouchListener { view,event ->
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> view.parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                view.parent?.requestDisallowInterceptTouchEvent(false)
        }
        if(event.actionMasked==MotionEvent.ACTION_CANCEL) onCancel?.invoke()
        false   // 不消费：SeekBar.onTouchEvent 继续处理拖动与进度
    }
}
