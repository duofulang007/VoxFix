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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import local.voxfix.debug.ui.Palette
import local.voxfix.debug.ui.VoxFixTheme
import local.voxfix.debug.ui.VoxFixUi
import local.voxfix.debug.update.UpdateChecker

/**
 * App 外壳：底部四栏（首页 / 屏幕 / 输入 / 设置）+ 模块管理子页。
 *
 * 设计依据 docs/App外壳设计-2026-11-13.md。
 * 这里只做总览与入口，具体工具仍由既有页面承担：
 * ScreenTextSettingsActivity / ScreenshotToolbarSettingsActivity /
 * DictionaryActivity / LexiconActivity / MainActivity（试用与自检）。
 */
class HomeActivity : Activity() {

    private lateinit var palette: Palette
    private lateinit var titleView: TextView
    private lateinit var backView: ImageView
    private lateinit var content: FrameLayout
    private lateinit var navBar: LinearLayout

    private val cache = HashMap<String, View>()
    private val navIcons = ArrayList<ImageView>()
    private val navLabels = ArrayList<TextView>()
    private val moduleBindings = ArrayList<() -> Unit>()

    private var tabIndex = 0
    private var showingModules = false

    /** 回写开关状态时置位，避免 Switch 的监听再次触发级联逻辑。 */
    private var syncingModules = false

    private val keys = arrayOf("home", "screen", "input", "settings")
    private val titles = arrayOf("VoxFix 输入法", "屏幕", "输入", "设置")
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
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(16), dp(6))
        }
        backView = VoxFixUi.icon(this, R.drawable.ic_vx_back, 22, palette.textSecondary).apply {
            visibility = View.GONE
            isClickable = true
            setPadding(dp(9), dp(9), dp(9), dp(9))
            setOnClickListener { showTab(tabIndex) }
        }
        header.addView(backView, LinearLayout.LayoutParams(dp(40), dp(40)))
        titleView = VoxFixUi.text(this, titles[0], 22f, palette.textPrimary, medium = true)
        header.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { leftMargin = dp(6) })

        content = FrameLayout(this)

        val navDivider = View(this).apply { setBackgroundColor(palette.border) }
        navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(palette.surface)
            setPadding(0, dp(6), 0, dp(6))
        }
        for (index in keys.indices) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { showTab(index) }
            }
            val icon = VoxFixUi.icon(this, icons[index], 23, palette.textTertiary)
            val label = VoxFixUi.text(this, labels[index], 11f, palette.textTertiary)
            item.addView(icon)
            item.addView(label, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) })
            navBar.addView(item, LinearLayout.LayoutParams(0, dp(52), 1f))
            navIcons.add(icon)
            navLabels.add(label)
        }

        root.addView(header, LinearLayout.LayoutParams(
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

    private fun showTab(index: Int) {
        tabIndex = index
        showingModules = false
        backView.visibility = View.GONE
        titleView.text = titles[index]
        val key = keys[index]
        content.removeAllViews()
        val page = cache.getOrPut(key) { buildPage(key) }
        content.addView(page, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        for (i in keys.indices) {
            val color = if (i == index) palette.primary else palette.textTertiary
            navIcons[i].setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
            navLabels[i].setTextColor(color)
        }
    }

    private fun showModules() {
        showingModules = true
        backView.visibility = View.VISIBLE
        titleView.text = "模块管理"
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

        // 更新横幅只在首页与关于页出现：明显但不打断，键盘与输入过程永不弹窗。
        updateBanner()?.let { col.addView(it) }
        col.addView(setupCard())
        col.addView(VoxFixUi.section(this, palette, "已启用模块", "管理") { showModules() })
        col.addView(chipsRow())

        col.addView(VoxFixUi.note(
            this, palette,
            "截图与取字的结果先进剪贴板，不写入相册；需要留档时在图片页另存。剪贴板、表情与词典在键盘里使用。"
        ))

        scroll.addView(col)
        return scroll
    }

    /**
     * 「开始使用」卡：输入法启用 → 设为默认 → 试用，三步各带状态；
     * 屏幕取字的无障碍授权行保留在同一张卡里。
     * 主路径永远是系统两步（系统设置勾选 + 系统选择弹层），直写仅作电脑辅助的次级入口。
     */
    private fun setupCard(): View {
        val card = VoxFixUi.card(this, palette)
        card.addView(VoxFixUi.head(this, palette, "开始使用"))
        val imeOn = imeEnabled()
        val imeNow = imeDefault()
        val a11yOn = ScreenTextSettingsActivity.connected(this)

        card.addView(VoxFixUi.row(
            this, palette, "1 · 启用 VoxFix 输入法",
            desc = "在系统「输入法管理」里勾选 VoxFix",
            value = if (imeOn) "已启用" else "未启用",
            valueColor = if (imeOn) palette.success else palette.warning,
            chevron = !imeOn,
            divider = false
        ) { if (!imeOn) startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })
        card.addView(VoxFixUi.row(
            this, palette, "2 · 设为默认输入法",
            desc = if (imeNow) "在系统「选择输入法」弹层里选中的" else "在系统弹层里选 VoxFix；别在讯飞键盘的菜单里找",
            value = if (imeNow) "已设为默认" else "未设置",
            valueColor = if (imeNow) palette.success else palette.warning,
            chevron = !imeNow
        ) { if (!imeNow) pickOrSwitchDefault() })
        card.addView(VoxFixUi.row(
            this, palette, "3 · 试用键盘",
            desc = "在自带试用框里打字、长按空格说话",
            value = if (imeOn && imeNow) "可以开始" else "完成上面两步",
            valueColor = if (imeOn && imeNow) palette.success else palette.textTertiary,
            chevron = true
        ) { trialKeyboard() })

        // 直写路径只在电脑辅助场景出现：已授权直接切换；未授权给复制命令的入口。
        if (!imeNow && AdbWireless.granted(this)) {
            card.addView(VoxFixUi.row(
                this, palette, "一键切换（电脑辅助）",
                desc = "已授权 WRITE_SECURE_SETTINGS，直接写系统设置",
                chevron = true
            ) { switchSelf() })
        } else if (!imeNow) {
            card.addView(VoxFixUi.row(
                this, palette, "电脑辅助：一键切换",
                desc = "电脑执行一次 pm grant 后可直接切换（点此复制命令）",
                chevron = true
            ) { copyGrantCommand() })
        }

        card.addView(VoxFixUi.row(
            this, palette, "屏幕取字",
            desc = "无障碍服务：取字、截图与录屏共用",
            value = if (a11yOn) "已连接" else "未授权",
            valueColor = if (a11yOn) palette.success else palette.warning,
            chevron = !a11yOn,
            divider = false
        ) { if (!a11yOn) openAccessibility() })
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
                card.addView(VoxFixUi.row(this, palette, "更新到 ${m.versionName}",
                    desc = m.changelog.takeIf { it.isNotBlank() }, chevron = true, divider = false) {
                    UpdateChecker.startDownload(this)
                })
                card.addView(VoxFixUi.row(this, palette, "忽略这个版本", divider = false) {
                    UpdateChecker.dismiss(this)
                })
            }
            UpdateChecker.Phase.DOWNLOADING -> {
                val percent = if (status.total > 0) (status.downloaded * 100 / status.total).toInt() else 0
                card.addView(VoxFixUi.head(this, palette, "正在下载 ${status.manifest?.versionName.orEmpty()}",
                    "$percent% · ${status.downloaded / (1024 * 1024)}MB"))
                card.addView(VoxFixUi.row(this, palette, "取消下载", divider = false) {
                    UpdateChecker.cancelDownload()
                })
            }
            UpdateChecker.Phase.READY -> {
                card.addView(VoxFixUi.head(this, palette, "${status.manifest?.versionName.orEmpty()} 已就绪",
                    "校验通过，点「安装」调起系统安装器"))
                card.addView(VoxFixUi.row(this, palette, "安装更新", chevron = true, divider = false) {
                    UpdateChecker.install(this)
                })
            }
            UpdateChecker.Phase.FAILED -> {
                card.addView(VoxFixUi.head(this, palette, "更新未完成", status.message.ifEmpty { "下载或检查失败" }))
                card.addView(VoxFixUi.row(this, palette, "重试", divider = false) {
                    if (status.manifest != null) UpdateChecker.startDownload(this)
                    else UpdateChecker.check(this, manual = true)
                })
                card.addView(VoxFixUi.row(this, palette, "关闭", divider = false) {
                    UpdateChecker.dismiss(this)
                })
            }
            UpdateChecker.Phase.HIDDEN -> return null
        }
        return card
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

    private fun chipsRow(): View {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(4))
        }
        for (module in Module.values()) {
            if (!Modules.isOn(this, module)) continue
            val chip = VoxFixUi.text(this, module.label, 12.5f, palette.textSecondary).apply {
                background = VoxFixTheme.round(palette.surface, dp(14).toFloat(), dp(1), palette.border)
                setPadding(dp(10), dp(6), dp(10), dp(6))
            }
            row.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) })
        }
        if (row.childCount == 0) {
            row.addView(VoxFixUi.text(this, "全部模块已关闭", 12.5f, palette.textTertiary).apply {
                background = VoxFixTheme.round(palette.surface, dp(14).toFloat(), dp(1), palette.border)
                setPadding(dp(10), dp(6), dp(10), dp(6))
            })
        }
        scroll.addView(row)
        return scroll
    }

    // ───────────────────────── 屏幕 ─────────────────────────

    private fun buildScreenPage(): View {
        val scroll = ScrollView(this)
        val col = VoxFixUi.page(this, palette)

        val arc = VoxFixUi.card(this, palette)
        arc.addView(VoxFixUi.head(this, palette, "贴边弧线", "屏幕边缘的取字入口"))
        val arcRow = VoxFixUi.row(
            this, palette, "显示弧线",
            desc = "关闭后仍可用首页按钮取字",
            divider = false
        )
        val arcSwitch = VoxFixUi.toggle(this, palette, ScreenTextSettingsActivity.enabled(this)) { value ->
            setScreenTextEnabled(value)
            cache.remove("home")
        }
        addTrailing(arcRow, arcSwitch)
        arc.addView(arcRow)
        arc.addView(VoxFixUi.row(this, palette, "无障碍服务",
            desc = "屏幕取字、截图与录屏依赖它",
            value = if (ScreenTextSettingsActivity.connected(this)) "已连接" else "未授权",
            chevron = true) { openAccessibility() })
        col.addView(arc)

        val shot = VoxFixUi.card(this, palette)
        shot.addView(VoxFixUi.head(this, palette, "截图与标注", "绘制 12 · 截图 8"))
        shot.addView(VoxFixUi.row(this, palette, "工具栏布局",
            desc = "拖动编排，可从选框外起笔", chevron = true,
            divider = false) {
            startActivity(Intent(this, ScreenshotToolbarSettingsActivity::class.java))
        })
        shot.addView(VoxFixUi.row(this, palette, "截图设置",
            desc = "吸附、网格、贴边与翻译模型", chevron = true) {
            startActivity(Intent(this, ScreenTextSettingsActivity::class.java))
        })
        shot.addView(VoxFixUi.row(this, palette, "保存位置", value = "剪贴板 · 图片"))
        col.addView(shot)

        val translateReady = TranslateModelStore(this).ready()
        val tr = VoxFixUi.card(this, palette)
        tr.addView(VoxFixUi.head(this, palette, "识别与翻译"))
        tr.addView(VoxFixUi.row(this, palette, "识别引擎",
            desc = "ML Kit 行定位 · PP-OCRv6 small · 完全离线", divider = false))
        tr.addView(VoxFixUi.row(this, palette, "离线翻译模型",
            desc = if (translateReady) "已就绪" else "未下载 · 462MB",
            value = if (translateReady) "已就绪" else "去下载",
            valueColor = if (translateReady) palette.success else palette.primary,
            chevron = true,
            dimmed = !Modules.isOn(this, Module.TRANSLATE)) {
            startActivity(Intent(this, ScreenTextSettingsActivity::class.java))
        })
        tr.addView(VoxFixUi.row(this, palette, "翻译方向", value = "自动检测 ⇄ 简体中文",
            dimmed = !Modules.isOn(this, Module.TRANSLATE)))
        col.addView(tr)

        scroll.addView(col)
        return scroll
    }

    private fun setScreenTextEnabled(value: Boolean) {
        // 与 ScreenTextSettingsActivity 共用同一份偏好；服务监听该键即时收起或恢复弧线。
        getSharedPreferences("screen-text", MODE_PRIVATE).edit().putBoolean("enabled", value).apply()
    }

    private fun addTrailing(rowView: View, trailing: View) {
        val wrap = rowView as LinearLayout
        val row = wrap.getChildAt(wrap.childCount - 1) as LinearLayout
        row.addView(trailing)
    }

    // ───────────────────────── 输入 ─────────────────────────

    private fun buildInputPage(): View {
        val scroll = ScrollView(this)
        val col = VoxFixUi.page(this, palette)

        val kb = VoxFixUi.card(this, palette)
        kb.addView(VoxFixUi.head(this, palette, "键盘", "26 键 · 9 键 · 英文"))
        kb.addView(VoxFixUi.row(this, palette, "试用与自检",
            desc = "在这里试打、试语音", chevron = true, divider = false) {
            startActivity(Intent(this, MainActivity::class.java))
        })
        kb.addView(VoxFixUi.row(this, palette, "个人词库",
            desc = "选词记忆与迁移备份", chevron = true) {
            startActivity(Intent(this, LexiconActivity::class.java))
        })
        col.addView(kb)

        val voiceOn = Modules.isOn(this, Module.VOICE)
        val voice = VoxFixUi.card(this, palette)
        voice.addView(VoxFixUi.head(this, palette, "语音输入", if (voiceOn) "本地离线 · 模型就绪" else "已关闭"))
        val voiceRow = VoxFixUi.row(this, palette, "长按空格说话",
            desc = "松手定稿，自动补标点", divider = false,
            dimmed = !voiceOn)
        addTrailing(voiceRow, VoxFixUi.toggle(this, palette, Modules.isSelfOn(this, Module.VOICE)) { value ->
            Modules.set(this, Module.VOICE, value)
            cache.remove("input"); cache.remove("home")
            showTab(2)
        })
        voice.addView(voiceRow)
        voice.addView(VoxFixUi.row(this, palette, "个人词典",
            desc = "纠正常错词，只在语音结果上生效",
            chevron = true) {
            startActivity(Intent(this, DictionaryActivity::class.java))
        })
        col.addView(voice)

        scroll.addView(col)
        return scroll
    }

    // ───────────────────────── 设置 ─────────────────────────

    private fun buildSettingsPage(): View {
        val scroll = ScrollView(this)
        val col = VoxFixUi.page(this, palette)

        val mods = VoxFixUi.card(this, palette)
        mods.addView(VoxFixUi.row(
            this, palette, "模块管理",
            desc = "关闭不用的功能，省内存也省心",
            value = "已开 ${Modules.countOn(this)} / ${Module.values().size}",
            chevron = true, divider = false
        ) { showModules() })
        col.addView(mods)

        val perm = VoxFixUi.card(this, palette)
        perm.addView(VoxFixUi.head(this, palette, "权限"))
        perm.addView(VoxFixUi.row(this, palette, "输入法",
            value = when {
                imeDefault() -> "已设为默认"
                imeEnabled() -> "已启用 · 未选默认"
                else -> "未启用"
            },
            valueColor = if (imeDefault()) palette.success else palette.warning,
            chevron = true, divider = false) {
            if (imeDefault()) ImeSwitcher.pick(this) else pickOrSwitchDefault()
        })
        perm.addView(VoxFixUi.row(this, palette, "无障碍服务",
            desc = "屏幕取字、截图与录屏依赖它",
            value = if (ScreenTextSettingsActivity.connected(this)) "已连接" else "未授权",
            valueColor = if (ScreenTextSettingsActivity.connected(this)) palette.success else palette.warning,
            chevron = true) { openAccessibility() })
        col.addView(perm)

        val about = VoxFixUi.card(this, palette)
        about.addView(VoxFixUi.head(this, palette, "关于"))
        about.addView(VoxFixUi.row(this, palette, "版本与更新",
            desc = "版本号、更新日志与项目说明",
            value = versionName(), chevron = true, divider = false) {
            startActivity(Intent(this, AboutActivity::class.java))
        })
        about.addView(VoxFixUi.row(this, palette, "开源许可",
            desc = "SenseVoice · sherpa-onnx · librime · rime-ice 等", chevron = true) {
            startActivity(Intent(this, LicenseActivity::class.java))
        })
        col.addView(about)

        col.addView(VoxFixUi.note(
            this, palette,
            "VoxFix 输入法 · 识别、语音与翻译都在本机完成，不上传屏幕内容与录音。"
        ))

        scroll.addView(col)
        return scroll
    }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    // ───────────────────────── 模块管理 ─────────────────────────

    private fun buildModulesPage(): View {
        val scroll = ScrollView(this)
        val col = VoxFixUi.page(this, palette)
        col.addView(VoxFixUi.note(
            this, palette,
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
                    val switch = rowView.tag as? Switch ?: return@add
                    switch.isChecked = Modules.isSelfOn(this, module)
                    // 同上：parent 必须落到局部变量才能空判断通过。
                    val parentOn = module.parent.let { it == null || Modules.isOn(this, it) }
                    switch.isEnabled = parentOn
                    rowView.alpha = if (parentOn) 1f else 0.55f
                }
            }
            col.addView(card)
        }

        val reset = VoxFixUi.text(this, "恢复默认", 13.5f, palette.primary).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            setOnClickListener {
                Modules.reset(this@HomeActivity)
                cache.clear()
                showModules()
                Toast.makeText(this@HomeActivity, "已恢复默认", Toast.LENGTH_SHORT).show()
            }
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
