package local.voxfix.debug

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
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

        showTab(0)
    }

    override fun onResume() {
        super.onResume()
        // 权限与模块状态可能在别的页面被改过，回前台重读一次。
        cache.remove(keys[tabIndex])
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
        scroll.addView(col)

        val imeOn = imeEnabled()
        val a11yOn = ScreenTextSettingsActivity.connected(this)

        val ready = VoxFixUi.card(this, palette)
        ready.addView(VoxFixUi.row(
            this, palette, "输入法",
            value = if (imeOn) "已启用" else "未启用",
            valueColor = if (imeOn) palette.success else palette.warning,
            chevron = true, divider = false
        ) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })
        ready.addView(VoxFixUi.row(
            this, palette, "屏幕取字",
            value = if (a11yOn) "已连接" else "未授权",
            valueColor = if (a11yOn) palette.success else palette.warning,
            chevron = true
        ) { openAccessibility() })
        col.addView(ready)

        col.addView(VoxFixUi.section(this, palette, "快速开始", "从屏幕边缘滑入也可"))

        col.addView(tileRow(
            Triple(R.drawable.ic_vx_scan, "取字", "抓取屏幕文字") to Module.SCREENTEXT,
            Triple(R.drawable.ic_vx_crop, "截屏", "框选并标注") to Module.SCREENSHOT
        ))
        col.addView(tileRow(
            Triple(R.drawable.ic_vx_translate, "翻译", "取字后翻译") to Module.TRANSLATE,
            Triple(R.drawable.ic_vx_record, "录屏", "录制选区 MP4") to Module.RECORD
        ))

        col.addView(VoxFixUi.section(this, palette, "已启用模块", "管理") { showModules() })
        col.addView(chipsRow())

        col.addView(VoxFixUi.section(this, palette, "剪贴板"))
        val clip = VoxFixUi.card(this, palette)
        clip.addView(VoxFixUi.row(
            this, palette, "文本与图片历史",
            desc = "在键盘里打开「剪贴板」标签查看、引用或拆词",
            divider = false
        ))
        col.addView(clip)

        col.addView(VoxFixUi.note(
            this, palette,
            "截图与取字的结果先进剪贴板，不写入相册；需要留档时在图片页另存。"
        ))

        return scroll
    }

    private fun tileRow(
        first: Pair<Triple<Int, String, String>, Module>,
        second: Pair<Triple<Int, String, String>, Module>
    ): LinearLayout {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        grid.addView(tileFor(first), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        grid.addView(VoxFixUi.spacer(this, 10, 0))
        grid.addView(tileFor(second), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        grid.setPadding(0, 0, 0, dp(10))
        return grid
    }

    private fun tileFor(entry: Pair<Triple<Int, String, String>, Module>): View {
        val (spec, module) = entry
        val (iconRes, title, desc) = spec
        val locked = !Modules.isOn(this, module)
        return VoxFixUi.tile(this, palette, iconRes, title, desc, locked) {
            if (locked) {
                showModules()
            } else {
                Toast.makeText(this, "$title：请从贴边弧线或键盘进入", Toast.LENGTH_SHORT).show()
            }
        }
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

        val rec = VoxFixUi.card(this, palette)
        rec.addView(VoxFixUi.head(this, palette, "录屏", if (Modules.isOn(this, Module.RECORD)) "默认 MP4" else "已关闭"))
        rec.addView(VoxFixUi.row(this, palette, "格式", value = "MP4", chevron = true,
            dimmed = !Modules.isOn(this, Module.RECORD), divider = false) {
            Toast.makeText(this, "在截图工具条上开始录制", Toast.LENGTH_SHORT).show()
        })
        rec.addView(VoxFixUi.row(this, palette, "清晰度与帧率", value = "1080p · 30fps", chevron = true,
            dimmed = !Modules.isOn(this, Module.RECORD)))
        rec.addView(VoxFixUi.row(this, palette, "音轨", value = "系统 + 麦克风", chevron = true,
            dimmed = !Modules.isOn(this, Module.RECORD)))
        col.addView(rec)

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
            chevron = true,
            dimmed = !Modules.isOn(this, Module.DICTIONARY)) {
            startActivity(Intent(this, DictionaryActivity::class.java))
        })
        col.addView(voice)

        val clip = VoxFixUi.card(this, palette)
        clip.addView(VoxFixUi.head(this, palette, "剪贴板", if (Modules.isOn(this, Module.CLIPBOARD)) "文本 · 图片 · 常用" else "已关闭"))
        clip.addView(VoxFixUi.row(this, palette, "打开剪贴板",
            desc = "在键盘工具栏点「剪贴板」标签", divider = false,
            dimmed = !Modules.isOn(this, Module.CLIPBOARD)))
        clip.addView(VoxFixUi.row(this, palette, "电脑同步",
            desc = "需在电脑运行手机助手，USB 连接后自动投递",
            value = if (Modules.isOn(this, Module.PCSYNC)) "已开启" else "已关闭",
            dimmed = !Modules.isOn(this, Module.PCSYNC)))
        col.addView(clip)

        val emoji = VoxFixUi.card(this, palette)
        emoji.addView(VoxFixUi.head(this, palette, "表情与词库"))
        emoji.addView(VoxFixUi.row(this, palette, "表情搜索",
            desc = "图源搜狗 · 需联网",
            value = if (Modules.isOn(this, Module.EMOJI)) "已开启" else "已关闭",
            divider = false,
            dimmed = !Modules.isOn(this, Module.EMOJI)))
        col.addView(emoji)

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

        val look = VoxFixUi.card(this, palette)
        look.addView(VoxFixUi.head(this, palette, "外观"))
        look.addView(VoxFixUi.row(this, palette, "主题",
            desc = "跟随系统深浅色", value = "跟随系统", divider = false))
        col.addView(look)

        val perm = VoxFixUi.card(this, palette)
        perm.addView(VoxFixUi.head(this, palette, "权限"))
        perm.addView(VoxFixUi.row(this, palette, "输入法",
            value = if (imeEnabled()) "已启用" else "未启用",
            valueColor = if (imeEnabled()) palette.success else palette.warning,
            chevron = true, divider = false) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })
        perm.addView(VoxFixUi.row(this, palette, "无障碍服务",
            desc = "屏幕取字、截图与录屏依赖它",
            value = if (ScreenTextSettingsActivity.connected(this)) "已连接" else "未授权",
            valueColor = if (ScreenTextSettingsActivity.connected(this)) palette.success else palette.warning,
            chevron = true) { openAccessibility() })
        col.addView(perm)

        val about = VoxFixUi.card(this, palette)
        about.addView(VoxFixUi.head(this, palette, "关于"))
        about.addView(VoxFixUi.row(this, palette, "版本",
            value = versionName(), divider = false))
        about.addView(VoxFixUi.row(this, palette, "开源许可",
            desc = "Snow Shot · sherpa-onnx · librime 等", chevron = true) {
            Toast.makeText(this, "许可文件随 APK 资源提供", Toast.LENGTH_SHORT).show()
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

    private fun imeEnabled(): Boolean {
        val imm = getSystemService(InputMethodManager::class.java) ?: return false
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun openAccessibility() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "在「已下载的服务」里打开 VoxFix 截图工具", Toast.LENGTH_LONG).show()
    }
}
