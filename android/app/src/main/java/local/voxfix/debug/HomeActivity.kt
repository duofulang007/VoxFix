package local.voxfix.debug

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import local.voxfix.debug.ui.ArcPreview
import local.voxfix.debug.ui.KeyboardPreview
import local.voxfix.debug.ui.MeterBar
import local.voxfix.debug.ui.Palette
import local.voxfix.debug.ui.StatusRing
import local.voxfix.debug.ui.VoxFixTheme
import local.voxfix.debug.ui.VoxFixUi
import local.voxfix.debug.ui.VxSwitch
import local.voxfix.debug.update.UpdateChecker

/**
 * App 外壳：底部四栏（首页 / 屏幕 / 输入 / 设置）+ 模块管理子页。
 *
 * 设计依据 docs/App外壳设计-2026-11-15.md（v2 视觉）。
 * 这里只做总览与入口，具体工具仍由既有页面承担：
 * ScreenTextSettingsActivity / ScreenshotToolbarSettingsActivity /
 * DictionaryActivity / LexiconActivity / MainActivity（试用与自检）。
 */
class HomeActivity : Activity() {

    private lateinit var palette: Palette
    private lateinit var headerHost: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var navBar: LinearLayout

    private val cache = HashMap<String, View>()
    private val navIcons = ArrayList<ImageView>()
    private val navLabels = ArrayList<TextView>()
    private val navPills = ArrayList<FrameLayout>()
    private val moduleBindings = ArrayList<() -> Unit>()

    private var tabIndex = 0
    private var showingModules = false

    /** 回写开关状态时置位，避免 Switch 的监听再次触发级联逻辑。 */
    private var syncingModules = false

    private val keys = arrayOf("home", "screen", "input", "settings")
    private val titles = arrayOf("VoxFix", "屏幕", "输入", "设置")
    private val subtitles = arrayOf(
        "输入法 · 本机离线",
        "贴边弧线 · 取字 · 截图",
        "键盘 · 语音 · 词库",
        "模块 · 权限 · 关于"
    )
    private val labels = arrayOf("首页", "屏幕", "输入", "设置")
    private val icons = intArrayOf(
        R.drawable.ic_vx_home, R.drawable.ic_vx_screen,
        R.drawable.ic_vx_keyboard, R.drawable.ic_vx_settings
    )

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        palette = VoxFixTheme.palette(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.bg)
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        headerHost = FrameLayout(this)
        content = FrameLayout(this).apply { setBackgroundColor(palette.bg) }

        val navDivider = View(this).apply { setBackgroundColor(palette.border) }
        navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(palette.surface)
            setPadding(0, dp(7), 0, dp(7))
        }
        for (index in keys.indices) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                contentDescription = labels[index]
                setOnClickListener { showTab(index) }
            }
            // 图标必须显式给 FrameLayout 的居中参数：直接 addView 会沿用 ImageView 自带的
            // LinearLayout 参数并被转成默认左上角，导致图标相对胶囊底整体偏左上。
            val iconView = buildNavIcon(index)
            val pill = FrameLayout(this).apply {
                addView(iconView, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            }
            val label = VoxFixUi.text(this, labels[index], 11.5f, palette.textTertiary)
            item.addView(pill, LinearLayout.LayoutParams(dp(46), dp(28)))
            item.addView(label, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) })
            navBar.addView(item, LinearLayout.LayoutParams(0, dp(54), 1f))
            navPills.add(pill)
            navLabels.add(label)
        }

        root.addView(headerHost, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(navDivider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        root.addView(navBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        setContentView(root)

        // 更新状态变化（下载进度/校验完成/安装就绪）即时刷新首页横幅。
        UpdateChecker.observe { _ ->
            runOnUiThread {
                cache.remove("home")
                if (tabIndex == 0 && !showingModules) showTab(0)
            }
        }
        showTab(0)
    }

    /** 底栏图标：统一 22dp 线性图形，先建一次，切换时只改 tint。 */
    private fun buildNavIcon(index: Int): ImageView =
        VoxFixUi.icon(this, icons[index], 22, palette.textTertiary)

    override fun onDestroy() {
        // object 单例持引用会泄漏 Activity，页面销毁即摘监听。
        UpdateChecker.observe(null)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // 权限与模块状态可能在别的页面被改过，回前台重读一次；
        // 首页带输入法向导状态，从系统设置/选择弹层回来时必须一起失效。
        cache.remove(keys[tabIndex])
        cache.remove("home")
        // 推导横幅（READY/AVAILABLE），并按 ≥24h 节流做静默自动检查（失败不打扰）。
        UpdateChecker.refresh(this)
        if (UpdateChecker.shouldAutoCheck(this)) UpdateChecker.check(this, manual = false)
        if (showingModules) showModules() else showTab(tabIndex)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (showingModules) {
            showTab(tabIndex)
            return
        }
        if (tabIndex != 0) {
            showTab(0)
            return
        }
        super.onBackPressed()
    }

    private fun dp(value: Int) = VoxFixTheme.dp(this, value)

    // ───────────────────────── 外壳 ─────────────────────────

    private fun renderHeader(title: String, subtitle: String?, large: Boolean, onBack: (() -> Unit)?) {
        headerHost.removeAllViews()
        headerHost.addView(
            VoxFixUi.topBar(this, palette, title, onBack, subtitle = subtitle, large = large),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun showTab(index: Int) {
        tabIndex = index
        showingModules = false
        renderHeader(titles[index], subtitles[index], large = index == 0, onBack = null)
        content.removeAllViews()
        val page = cache.getOrPut(keys[index]) { buildPage(keys[index]) }
        content.addView(page, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        VoxFixUi.contentEnter(page)
        for (i in keys.indices) {
            val on = i == index
            val color = if (on) palette.primary else palette.textTertiary
            (navPills[i].getChildAt(0) as? ImageView)?.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
            navPills[i].background = if (on) {
                VoxFixTheme.round(palette.primarySoft, dp(14).toFloat())
            } else null
            // 选中胶囊轻微放大回弹，切换时不靠「瞬间变色」硬切。
            val pill = navPills[i]
            pill.animate().cancel()
            if (on) {
                pill.scaleX = 0.74f
                pill.scaleY = 0.74f
                pill.animate().scaleX(1f).scaleY(1f).setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            } else {
                pill.scaleX = 1f
                pill.scaleY = 1f
            }
            navLabels[i].setTextColor(color)
            navLabels[i].typeface = if (on) {
                android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            } else {
                android.graphics.Typeface.DEFAULT
            }
        }
    }

    private fun showModules() {
        showingModules = true
        renderHeader("模块管理", "只隐藏入口与释放内存，不删数据", large = false, onBack = { showTab(tabIndex) })
        moduleBindings.clear()
        content.removeAllViews()
        content.addView(buildModulesPage(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        refreshModules()
    }

    private fun buildPage(key: String): View = when (key) {
        "screen" -> buildScreenPage()
        "input" -> buildInputPage()
        "settings" -> buildSettingsPage()
        else -> buildHomePage()
    }

    // ───────────────────────── 首页 ─────────────────────────

    private fun buildHomePage(): View {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val col = VoxFixUi.page(this, palette)

        // 更新横幅只在首页出现：明显但不打断，键盘与输入过程永不弹窗。
        updateBanner()?.let { col.addView(it) }
        col.addView(heroCard())

        col.addView(VoxFixUi.section(this, palette, "快捷入口"))
        col.addView(tileRow())

        col.addView(VoxFixUi.section(this, palette, "就绪状态"))
        col.addView(setupCard())

        col.addView(VoxFixUi.section(this, palette, "功能"))
        col.addView(modulesCard())

        col.addView(VoxFixUi.hintRow(
            this, palette, R.drawable.ic_vx_lock,
            "识别、语音与翻译都在本机完成；屏幕内容与录音不上传。"
        ))

        scroll.addView(col)
        return scroll
    }

    /**
     * 首页 hero：就绪环 + 一句状态 + 主行动。
     * 状态在这里回答「现在能不能用」，下面的清单回答「还差什么」。
     */
    private fun heroCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = VoxFixTheme.gradient(palette.primarySoft, palette.surface, dp(20).toFloat())
            setPadding(dp(18), dp(18), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }

        val imeOn = imeEnabled()
        val imeNow = imeDefault()
        val a11yOn = ScreenTextHealth.bound(this)
        val done = listOf(imeOn, imeNow, a11yOn).count { it }

        val ring = StatusRing(this, palette, done, 3)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(ring, LinearLayout.LayoutParams(dp(62), dp(62)))
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        words.addView(VoxFixUi.text(
            this,
            if (done == 3) "已就绪" else "还有 ${3 - done} 步未完成",
            20f, palette.textPrimary, medium = true
        ))
        words.addView(VoxFixUi.text(
            this,
            if (done == 3) "长按空格说话，屏幕边缘滑出取字与截图" else "按下面的清单补齐，几步就能用",
            12.5f, palette.textSecondary
        ).apply { setPadding(0, dp(4), 0, 0) })
        top.addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { leftMargin = dp(14) })
        card.addView(top)

        val cta = when {
            !imeOn -> "启用输入法" to { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            !imeNow -> "设为默认输入法" to { pickOrSwitchDefault() }
            else -> "试用键盘" to { trialKeyboard() }
        }
        card.addView(VoxFixUi.primaryButton(this, palette, cta.first) { cta.second() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) })
        return card
    }

    /** 四个等宽快捷入口：全部指向真实页面，不做「点了没反应」的装饰砖。 */
    private fun tileRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val entries = listOf(
            Triple(R.drawable.ic_vx_scan, "屏幕取字") { startActivity(Intent(this, ScreenTextSettingsActivity::class.java)) },
            Triple(R.drawable.ic_vx_crop, "截图工具") { startActivity(Intent(this, ScreenshotToolbarSettingsActivity::class.java)) },
            Triple(R.drawable.ic_vx_book, "拼音词库") { startActivity(Intent(this, LexiconActivity::class.java)) },
            Triple(R.drawable.ic_vx_mic, "语音纠错") { startActivity(Intent(this, DictionaryActivity::class.java)) }
        )
        entries.forEachIndexed { index, entry ->
            val tile = VoxFixUi.actionTile(this, palette, entry.first, entry.second, entry.third)
            row.addView(tile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { if (index > 0) leftMargin = dp(9) })
        }
        return row
    }

    /**
     * 「就绪状态」卡：启用 → 设为默认 → 屏幕取字权限，三步各带状态徽标。
     * 主路径永远是系统两步（系统设置勾选 + 系统选择弹层），直写仅作电脑辅助的次级入口。
     */
    private fun setupCard(): View {
        val card = VoxFixUi.card(this, palette)
        val imeOn = imeEnabled()
        val imeNow = imeDefault()
        val a11yOn = ScreenTextHealth.bound(this)

        card.addView(VoxFixUi.stepRow(
            this, palette, 1, imeOn,
            title = "启用 VoxFix 输入法",
            desc = "在系统「输入法管理」里勾选 VoxFix",
            action = if (imeOn) null else "去设置",
            divider = false
        ) { if (!imeOn) startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })
        card.addView(VoxFixUi.stepRow(
            this, palette, 2, imeNow,
            title = "设为默认输入法",
            desc = if (imeNow) "系统当前默认输入法就是 VoxFix" else "在系统弹层里选 VoxFix，别在讯飞键盘的菜单里找",
            action = if (imeNow) null else "去设置"
        ) { if (!imeNow) pickOrSwitchDefault() })
        card.addView(VoxFixUi.stepRow(
            this, palette, 3, a11yOn,
            title = "屏幕取字权限",
            desc = "取字、截图与录屏共用一个无障碍服务",
            action = if (a11yOn) null else "去授权"
        ) { if (!a11yOn) openAccessibility() })

        // 直写路径只在电脑辅助场景出现：已授权直接切换；未授权给复制命令的入口。
        if (!imeNow) {
            card.addView(VoxFixUi.row(
                this, palette,
                if (AdbWireless.granted(this)) "一键切换（电脑辅助）" else "电脑辅助：一键切换",
                desc = if (AdbWireless.granted(this)) {
                    "已授权 WRITE_SECURE_SETTINGS，直接写系统设置"
                } else {
                    "电脑执行一次 pm grant 后可直接切换（点此复制命令）"
                },
                iconRes = R.drawable.ic_vx_plug, iconTone = VoxFixUi.Tone.NEUTRAL,
                chevron = true
            ) { if (AdbWireless.granted(this)) switchSelf() else copyGrantCommand() })
        }
        return card
    }

    /** 功能管理：一行摘要 + 开启比例进度条，比纯数字更一眼可读。 */
    private fun modulesCard(): View {
        val card = VoxFixUi.card(this, palette)
        val on = Modules.countOn(this)
        val total = Module.values().size
        card.addView(VoxFixUi.row(
            this, palette, "功能管理",
            desc = "语音 · 取字 · 截图 · 录屏 · 翻译",
            value = "已开 $on / $total",
            iconRes = R.drawable.ic_vx_settings,
            chevron = true, divider = false
        ) { showModules() })
        val meter = MeterBar(this, palette, on.toFloat() / total)
        card.addView(meter, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
        ).apply {
            leftMargin = dp(65); rightMargin = dp(18); bottomMargin = dp(14)
        })
        return card
    }

    /** 更新横幅：无新版本/已忽略时为 null（不占位）。点击即行动，不弹对话框。 */
    private fun updateBanner(): View? {
        val status = UpdateChecker.current()
        if (status.phase == UpdateChecker.Phase.HIDDEN) return null
        val card = VoxFixUi.card(this, palette)
        when (status.phase) {
            UpdateChecker.Phase.AVAILABLE -> {
                val m = status.manifest ?: return null
                card.addView(VoxFixUi.head(this, palette, "发现新版本 ${m.versionName}",
                    if (m.apkSizeBytes > 0) "约 ${m.apkSizeBytes / (1024 * 1024)}MB · 建议 Wi-Fi" else "建议 Wi-Fi"))
                if (m.changelog.isNotBlank()) {
                    card.addView(VoxFixUi.note(this, palette, m.changelog).apply {
                        setPadding(dp(18), 0, dp(18), dp(8))
                    })
                }
                card.addView(actionRow(
                    VoxFixUi.textButton(this, palette, "忽略这个版本", palette.textTertiary) {
                        UpdateChecker.dismiss(this)
                    },
                    VoxFixUi.softButton(this, palette, "下载更新") { UpdateChecker.startDownload(this) }
                ))
            }
            UpdateChecker.Phase.DOWNLOADING -> {
                val percent = if (status.total > 0) (status.downloaded * 100 / status.total).toInt() else 0
                card.addView(VoxFixUi.head(this, palette, "正在下载 ${status.manifest?.versionName.orEmpty()}",
                    "$percent% · ${status.downloaded / (1024 * 1024)}MB"))
                val meter = MeterBar(
                    this, palette,
                    if (status.total > 0) status.downloaded.toFloat() / status.total else 0f
                )
                card.addView(meter, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
                ).apply { leftMargin = dp(18); rightMargin = dp(18) })
                card.addView(actionRow(
                    VoxFixUi.textButton(this, palette, "取消下载", palette.textTertiary) {
                        UpdateChecker.cancelDownload()
                    }
                ))
            }
            UpdateChecker.Phase.READY -> {
                card.addView(VoxFixUi.head(this, palette, "${status.manifest?.versionName.orEmpty()} 已就绪",
                    "校验通过，点「安装」调起系统安装器"))
                card.addView(actionRow(
                    VoxFixUi.softButton(this, palette, "安装更新") { UpdateChecker.install(this) }
                ))
            }
            UpdateChecker.Phase.FAILED -> {
                card.addView(VoxFixUi.head(this, palette, "更新未完成", status.message.ifEmpty { "下载或检查失败" }))
                card.addView(actionRow(
                    VoxFixUi.textButton(this, palette, "关闭", palette.textTertiary) {
                        UpdateChecker.dismiss(this)
                    },
                    VoxFixUi.softButton(this, palette, "重试") {
                        if (status.manifest != null) UpdateChecker.startDownload(this)
                        else UpdateChecker.check(this, manual = true)
                    }
                ))
            }
            UpdateChecker.Phase.HIDDEN -> return null
        }
        return card
    }

    private fun actionRow(vararg views: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(18), dp(10), dp(18), dp(12))
            views.forEach { addView(it, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) }) }
        }

    /** 默认输入法主入口：已授权直写（回读确认），否则拉系统选择弹层。 */
    private fun pickOrSwitchDefault() {
        if (imeDefault()) return
        if (AdbWireless.granted(this)) switchSelf() else ImeSwitcher.pick(this)
    }

    private fun switchSelf() {
        Toast.makeText(this, "正在切换…", Toast.LENGTH_SHORT).show()
        ImeSwitcher.switchToSelf(this) { outcome ->
            cache.remove("home")
            if (showingModules) showModules() else showTab(tabIndex)
            val message = when (outcome) {
                ImeSwitcher.Outcome.OK -> "已切换为 VoxFix 输入法"
                ImeSwitcher.Outcome.DENIED -> "缺少 WRITE_SECURE_SETTINGS：电脑执行 pm grant 后可用一键切换"
                ImeSwitcher.Outcome.WRITE_FAILED -> "写入系统设置失败，请改用系统「选择输入法」弹层"
                ImeSwitcher.Outcome.REVERTED -> "系统回滚了写入，请改用系统「选择输入法」弹层"
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun copyGrantCommand() {
        val command = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
        getSystemService(android.content.ClipboardManager::class.java)
            .setPrimaryClip(android.content.ClipData.newPlainText("adb", command))
        Toast.makeText(this, "已复制授权命令，电脑上执行一次即可", Toast.LENGTH_LONG).show()
    }

    private fun trialKeyboard() {
        startActivity(Intent(this, MainActivity::class.java).putExtra("voxfix.autofocus", true))
    }

    // ───────────────────────── 屏幕 ─────────────────────────

    private fun buildScreenPage(): View {
        val scroll = ScrollView(this)
        val col = VoxFixUi.page(this, palette)
        val state = a11yState()
        val arcOn = ScreenTextPrefs.arcEnabled(this)

        val arc = VoxFixUi.card(this, palette)
        arc.addView(VoxFixUi.head(this, palette, "贴边弧线", if (arcOn) "已显示" else "已隐藏"))
        val preview = ArcPreview(this, palette, arcOn)
        arc.addView(preview, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(96)
        ).apply { leftMargin = dp(18); rightMargin = dp(18); bottomMargin = dp(6) })
        arc.addView(VoxFixUi.switchRow(
            this, palette, "显示弧线",
            desc = "关闭后暂时隐藏，随时可以从本页恢复",
            checked = arcOn,
            iconRes = R.drawable.ic_vx_screen,
            divider = false
        ) { value ->
            ScreenTextPrefs.setArcEnabled(this, value)
            preview.setActive(value)
            cache.remove("home")
            cache.remove("screen")
        })
        arc.addView(VoxFixUi.row(
            this, palette, "恢复贴边弧线",
            desc = "弧线不见了？点这里重新挂上",
            iconRes = R.drawable.ic_vx_reset,
            trailing = VoxFixUi.pill(this, palette, state.first, toneFor(state.second)),
            chevron = true
        ) { restoreArc() })
        arc.addView(VoxFixUi.row(
            this, palette, "屏幕取字权限",
            desc = "取字、截图与录屏共用",
            iconRes = R.drawable.ic_vx_lock,
            trailing = VoxFixUi.pill(this, palette, state.first, toneFor(state.second)),
            chevron = true,
            divider = false
        ) { openAccessibility() })
        col.addView(arc)

        col.addView(VoxFixUi.section(this, palette, "截图与取字"))
        val shot = VoxFixUi.card(this, palette)
        shot.addView(VoxFixUi.row(
            this, palette, "工具栏布局",
            desc = "拖动编排，可从选框外起笔",
            iconRes = R.drawable.ic_vx_crop, chevron = true
        ) { startActivity(Intent(this, ScreenshotToolbarSettingsActivity::class.java)) })
        shot.addView(VoxFixUi.row(
            this, palette, "截图与取字设置",
            desc = "权限、工具栏与离线翻译",
            iconRes = R.drawable.ic_vx_settings, chevron = true, divider = false
        ) { startActivity(Intent(this, ScreenTextSettingsActivity::class.java)) })
        col.addView(shot)

        col.addView(VoxFixUi.hintRow(
            this, palette, R.drawable.ic_vx_info,
            "截图结果先进剪贴板；长截屏、水印与录屏在截图工具条里使用。"
        ))

        scroll.addView(col)
        return scroll
    }

    /** 无障碍三态：没启用过 / 启用了但系统没绑上 / 真的连上了。 */
    private fun a11yState(): Pair<String, Int> = when {
        ScreenTextHealth.bound(this) -> "已连接" to palette.success
        ScreenTextHealth.listed(this) -> "已启用未连接" to palette.warning
        else -> "未授权" to palette.warning
    }

    /** 把状态色收敛回胶囊语义色，页面不再直接拿 success/warning 画文字。 */
    private fun toneFor(color: Int): VoxFixUi.Tone = when (color) {
        palette.success -> VoxFixUi.Tone.SUCCESS
        palette.warning -> VoxFixUi.Tone.WARNING
        palette.danger -> VoxFixUi.Tone.DANGER
        else -> VoxFixUi.Tone.NEUTRAL
    }

    /**
     * 恢复弧线：模块关着先确认（恢复会重新开启屏幕取字），
     * 之后交给 ArcRecovery 统一处理开关、位置与窗口重建。
     */
    private fun restoreArc() {
        if (!Modules.isOn(this, Module.SCREENTEXT)) {
            AlertDialog.Builder(this)
                .setTitle("恢复贴边弧线")
                .setMessage("会同时开启「屏幕取字」模块。")
                .setPositiveButton("恢复") { _, _ -> doRestoreArc() }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        doRestoreArc()
    }

    private fun doRestoreArc() {
        Toast.makeText(this, "正在恢复弧线…", Toast.LENGTH_SHORT).show()
        ArcRecovery.reset(this) { result ->
            runOnUiThread {
                cache.clear()
                if (showingModules) showModules() else showTab(1)
                when (result) {
                    ArcRecovery.Result.OK -> toast("弧线已恢复")
                    ArcRecovery.Result.BUSY -> toast("正在截图或录屏，结束后再试")
                    ArcRecovery.Result.DISABLED -> AlertDialog.Builder(this)
                        .setTitle("还没有启用屏幕取字权限")
                        .setMessage("在系统「无障碍」里打开 VoxFix 截图工具，再回来点一次恢复。")
                        .setPositiveButton("去设置") { _, _ -> ScreenTextHealth.openSystemSettings(this) }
                        .setNegativeButton("取消", null)
                        .show()
                    ArcRecovery.Result.DENIED -> toast("需要电脑一次性授权：在开发者工具里复制命令")
                    ArcRecovery.Result.REVERTED, ArcRecovery.Result.FAILED ->
                        toast("系统没有重新连上，去系统设置里关闭再打开一次")
                    ArcRecovery.Result.WINDOW_MISSING -> toast("服务已连上，但弧线窗口没出现，请再点一次")
                }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ───────────────────────── 输入 ─────────────────────────

    private fun buildInputPage(): View {
        val scroll = ScrollView(this)
        val col = VoxFixUi.page(this, palette)

        val kb = VoxFixUi.card(this, palette)
        kb.addView(VoxFixUi.head(this, palette, "键盘", "26 键 · 9 键 · 英文"))
        kb.addView(KeyboardPreview(this, palette), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(118)
        ).apply { leftMargin = dp(18); rightMargin = dp(18); bottomMargin = dp(12) })
        kb.addView(VoxFixUi.primaryButton(this, palette, "试用键盘") { trialKeyboard() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(18); rightMargin = dp(18); bottomMargin = dp(14) })
        col.addView(kb)

        val voiceOn = Modules.isOn(this, Module.VOICE)
        val voice = VoxFixUi.card(this, palette)
        voice.addView(VoxFixUi.head(this, palette, "语音输入",
            if (voiceOn) "本地离线 · 模型就绪" else "已关闭"))
        voice.addView(VoxFixUi.switchRow(
            this, palette, "长按空格说话",
            desc = "松手定稿，自动补标点",
            checked = Modules.isSelfOn(this, Module.VOICE),
            enabled = voiceOn,
            iconRes = R.drawable.ic_vx_mic,
            divider = false
        ) { value ->
            Modules.set(this, Module.VOICE, value)
            cache.remove("input"); cache.remove("home")
            showTab(2)
        })
        col.addView(voice)

        col.addView(VoxFixUi.section(this, palette, "词库"))
        val dict = VoxFixUi.card(this, palette)
        dict.addView(VoxFixUi.row(
            this, palette, "拼音词库",
            desc = "选词记忆与迁移备份",
            iconRes = R.drawable.ic_vx_book, chevron = true
        ) { startActivity(Intent(this, LexiconActivity::class.java)) })
        dict.addView(VoxFixUi.row(
            this, palette, "语音纠错词典",
            desc = "纠正常错词，只在语音结果上生效",
            iconRes = R.drawable.ic_vx_translate, chevron = true, divider = false
        ) { startActivity(Intent(this, DictionaryActivity::class.java)) })
        col.addView(dict)

        scroll.addView(col)
        return scroll
    }

    // ───────────────────────── 设置 ─────────────────────────

    private fun buildSettingsPage(): View {
        val scroll = ScrollView(this)
        val col = VoxFixUi.page(this, palette)

        // 应用头卡：图标 + 名称 + 版本，让设置页第一眼是「这个 App 是谁」。
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = VoxFixTheme.round(palette.surface, dp(18).toFloat())
            elevation = dp(1).toFloat()
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        // 品牌砖：自绘 V 字标记放在主色软底上，避免系统自适应图标在白色卡片里「白底套白底」看不出边界。
        val mark = FrameLayout(this).apply {
            background = VoxFixTheme.round(palette.primarySoft, dp(18).toFloat())
            addView(
                VoxFixUi.icon(this@HomeActivity, R.drawable.ic_vx_mark, 28, palette.primary),
                FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
            )
        }
        brand.addView(mark, LinearLayout.LayoutParams(dp(56), dp(56)))
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        words.addView(VoxFixUi.text(this, "VoxFix 输入法", 17f, palette.textPrimary, medium = true))
        words.addView(VoxFixUi.text(this, "版本 ${versionName()} · 本机离线", 12.5f, palette.textTertiary).apply {
            setPadding(0, dp(4), 0, 0)
        })
        brand.addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { leftMargin = dp(14) })
        col.addView(brand)

        val on = Modules.countOn(this)
        val total = Module.values().size
        val mods = VoxFixUi.card(this, palette)
        mods.addView(VoxFixUi.row(
            this, palette, "模块管理",
            desc = "关闭不用的功能，省内存也省心",
            value = "已开 $on / $total",
            iconRes = R.drawable.ic_vx_settings,
            chevron = true, divider = false
        ) { showModules() })
        val meter = MeterBar(this, palette, on.toFloat() / total)
        mods.addView(meter, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
        ).apply { leftMargin = dp(65); rightMargin = dp(18); bottomMargin = dp(14) })
        col.addView(mods)

        col.addView(VoxFixUi.section(this, palette, "权限"))
        val perm = VoxFixUi.card(this, palette)
        perm.addView(VoxFixUi.row(
            this, palette, "输入法",
            desc = "启用与默认状态",
            iconRes = R.drawable.ic_vx_keyboard,
            trailing = VoxFixUi.pill(
                this, palette,
                when {
                    imeDefault() -> "已设为默认"
                    imeEnabled() -> "已启用"
                    else -> "未启用"
                },
                if (imeDefault()) VoxFixUi.Tone.SUCCESS else VoxFixUi.Tone.WARNING
            ),
            chevron = true
        ) {
            if (imeDefault()) ImeSwitcher.pick(this) else pickOrSwitchDefault()
        })
        perm.addView(VoxFixUi.row(
            this, palette, "屏幕取字",
            desc = "无障碍服务：取字、截图与录屏共用",
            iconRes = R.drawable.ic_vx_lock,
            trailing = VoxFixUi.pill(this, palette, a11yState().first, toneFor(a11yState().second)),
            chevron = true, divider = false
        ) { if (!ScreenTextHealth.bound(this)) openAccessibility() })
        col.addView(perm)

        // 设备互联：电脑端心跳与手机侧接收开关集中在 PcSyncActivity。
        col.addView(VoxFixUi.section(this, palette, "设备"))
        val device = VoxFixUi.card(this, palette)
        device.addView(VoxFixUi.row(
            this, palette, "电脑同步",
            desc = "手机 ⇄ 电脑剪贴板 · USB 或 Wi-Fi",
            iconRes = R.drawable.ic_vx_plug,
            trailing = pcSyncPill(),
            chevron = true, divider = false
        ) { startActivity(Intent(this, PcSyncActivity::class.java)) })
        col.addView(device)

        // 数据卡放在「关于」之前：词库与词典是唯一不可重建的资产，要给到显眼位置。
        col.addView(VoxFixUi.section(this, palette, "数据"))
        val data = VoxFixUi.card(this, palette)
        data.addView(VoxFixUi.row(
            this, palette, "本地备份",
            desc = "卸载也不丢 · 存到下载目录，可手动删除",
            iconRes = R.drawable.ic_vx_storage,
            chevron = true, divider = false
        ) { startActivity(Intent(this, BackupActivity::class.java)) })
        col.addView(data)

        col.addView(VoxFixUi.section(this, palette, "关于"))
        val about = VoxFixUi.card(this, palette)
        about.addView(VoxFixUi.row(this, palette, "版本与更新",
            desc = "版本号、更新日志与项目说明",
            iconRes = R.drawable.ic_vx_info,
            value = versionName(), chevron = true) {
            startActivity(Intent(this, AboutActivity::class.java))
        })
        about.addView(VoxFixUi.row(this, palette, "开源许可",
            desc = "SenseVoice · sherpa-onnx · librime · rime-ice 等",
            iconRes = R.drawable.ic_vx_book, chevron = true, divider = false) {
            startActivity(Intent(this, LicenseActivity::class.java))
        })
        col.addView(about)

        // 开发者模式默认关闭；开启入口在关于页「当前版本」连点 7 次。
        if (DevMode.enabled(this)) {
            col.addView(VoxFixUi.section(this, palette, "开发者"))
            val dev = VoxFixUi.card(this, palette)
            dev.addView(VoxFixUi.row(this, palette, "开发者工具",
                desc = "试用自检、弧线诊断与截图高级设置",
                iconRes = R.drawable.ic_vx_plug, chevron = true, divider = false) {
                startActivity(Intent(this, DevToolsActivity::class.java))
            })
            col.addView(dev)
        }

        col.addView(VoxFixUi.hintRow(
            this, palette, R.drawable.ic_vx_lock,
            "VoxFix 输入法 · 识别、语音与翻译都在本机完成，不上传屏幕内容与录音。"
        ))

        scroll.addView(col)
        return scroll
    }

    /** 设置页「电脑同步」状态胶囊：关闭接收优先于连接状态，避免关了还显示「已连接」。 */
    private fun pcSyncPill(): TextView {
        val status = PcSyncPrefs.snapshot(this)
        val receiving = PcSyncPrefs.enabled(this)
        return VoxFixUi.pill(
            this, palette,
            when {
                !receiving -> "已关闭"
                status.connected -> "已连接"
                else -> "未连接"
            },
            when {
                !receiving -> VoxFixUi.Tone.WARNING
                status.connected -> VoxFixUi.Tone.SUCCESS
                else -> VoxFixUi.Tone.NEUTRAL
            }
        )
    }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    // ───────────────────────── 模块管理 ─────────────────────────

    private fun buildModulesPage(): View {
        val scroll = ScrollView(this)
        val col = VoxFixUi.page(this, palette)
        col.addView(VoxFixUi.hintRow(
            this, palette, R.drawable.ic_vx_info,
            "关闭的模块会从键盘与工具条隐藏，并停止占用内存。数据不会删除，随时可以开回。"
        ))

        for (group in ModuleGroup.values()) {
            col.addView(VoxFixUi.section(this, palette, group.label))
            val card = VoxFixUi.card(this, palette)
            var first = true
            for (module in Module.values()) {
                if (module.group != group) continue
                val rowView = VoxFixUi.moduleRow(
                    this, palette, module,
                    selfOn = Modules.isSelfOn(this, module),
                    // parent 是自定义 getter，Kotlin 不做 smart cast，先取到局部变量。
                    enabled = module.parent.let { it == null || Modules.isOn(this, it) },
                    divider = !first
                ) { value -> onModuleToggled(module, value) }
                card.addView(rowView)
                first = false
                moduleBindings.add {
                    val switch = rowView.tag as? VxSwitch ?: return@add
                    switch.isChecked = Modules.isSelfOn(this, module)
                    // 同上：parent 必须落到局部变量才能空判断通过。
                    val parentOn = module.parent.let { it == null || Modules.isOn(this, it) }
                    switch.isEnabled = parentOn
                    rowView.alpha = if (parentOn) 1f else 0.55f
                }
            }
            col.addView(card)
        }

        val reset = VoxFixUi.softButton(this, palette, "恢复默认") {
            Modules.reset(this@HomeActivity)
            cache.clear()
            showModules()
            Toast.makeText(this@HomeActivity, "已恢复默认", Toast.LENGTH_SHORT).show()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(4))
        }
        row.addView(reset)
        col.addView(row)

        scroll.addView(col)
        return scroll
    }

    private fun onModuleToggled(module: Module, value: Boolean) {
        if (syncingModules) return
        if (!value) {
            val kids = Modules.children(module)
            if (kids.isNotEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("关闭「${module.label}」")
                    .setMessage("会同时关闭：" + kids.joinToString("、") { it.label })
                    .setPositiveButton("关闭") { _, _ ->
                        Modules.set(this, module, false)
                        cache.clear()
                        refreshModules()
                    }
                    .setNegativeButton("取消") { _, _ -> refreshModules() }
                    .show()
                return
            }
            Modules.set(this, module, false)
        } else {
            var ancestor = module.parent
            while (ancestor != null) {
                if (!Modules.isSelfOn(this, ancestor)) {
                    Modules.set(this, ancestor, true)
                    Toast.makeText(this, "已同时开启「${ancestor.label}」", Toast.LENGTH_SHORT).show()
                }
                ancestor = ancestor.parent
            }
            Modules.set(this, module, true)
        }
        cache.clear()
        refreshModules()
    }

    private fun refreshModules() {
        syncingModules = true
        moduleBindings.forEach { it() }
        syncingModules = false
    }

    // ───────────────────────── 公共 ─────────────────────────

    private fun imeEnabled(): Boolean = ImeSwitcher.enabled(this)

    /** 两级检测的第二级：本输入法是否已是系统当前默认（向导与设置页共用）。 */
    private fun imeDefault(): Boolean = ImeSwitcher.isDefault(this)

    private fun openAccessibility() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "在「已下载的服务」里打开 VoxFix 截图工具", Toast.LENGTH_LONG).show()
    }
}
