package im.manus.universalhost

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.io.File

/**
 * Экран модулей: только значки, без подписей. Тап по модулю открывает его настройки,
 * тап по папке открывает папку. Значок можно перетащить: на другой значок — папка.
 *
 * Справа вверху три значка обновления, у каждого свой смысл: «приложение» (APK), «модули» и «скрипты» браузера.
 * Нажатие на значок обновляет именно то, что он обозначает. Индикация: у значка, для которого есть обновление,
 * появляется метка (у приложения точка, у модулей и скриптов число устаревших) и значок становится ярче; у
 * актуального значок тусклый. Нажатие на тусклый значок приложения проверяет обновления заново, долгое нажатие
 * на значок приложения ставит его принудительно. Если сборка меняла хост, сначала нужно обновить приложение.
 */
class MainActivity : Activity() {

    private lateinit var grid: ModuleGridView
    private lateinit var scroll: ScrollView
    private lateinit var back: GlyphView
    private lateinit var gear: GlyphView
    private lateinit var updBox: LinearLayout
    private lateinit var btnApk: GlyphView
    private lateinit var btnMods: GlyphView
    private lateinit var btnScripts: GlyphView
    private var busyApk = false
    private var busyMods = false
    private var busyScripts = false
    private var checking = false
    private var apkNeed: Boolean? = null
    private var modsPending: Int? = null
    private var scriptsPending: Int? = null

    private var layoutModel = ModuleLayout()
    private var currentFolder: String? = null
    private var plugins: List<IPlugin> = emptyList()
    private var cells: List<ModuleLayout.Cell> = emptyList()
    private val bitmaps = LruCache<String, Bitmap>(96)

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentFolder = savedInstanceState?.getString("folder")

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.BLACK)

        val bar = FrameLayout(this)
        back = GlyphView(this, GlyphView.ARROW)
        back.alpha = 0.6f
        back.setOnClickListener { leaveFolder() }
        bar.addView(back, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.START or Gravity.CENTER_VERTICAL))
        gear = GlyphView(this, GlyphView.GEAR)
        gear.setOnClickListener {
            currentFolder?.let { openSettings(ModuleStorage.folderTarget(it), it) }
        }
        bar.addView(gear, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.END or Gravity.CENTER_VERTICAL))
        updBox = LinearLayout(this)
        updBox.orientation = LinearLayout.HORIZONTAL
        btnApk = GlyphView(this, GlyphView.APK)
        btnApk.setOnClickListener { onApkTap() }
        btnApk.setOnLongClickListener {
            updateApp()
            true
        }
        updBox.addView(btnApk, LinearLayout.LayoutParams(dp(56), dp(56)))
        btnMods = GlyphView(this, GlyphView.MODULES)
        btnMods.setOnClickListener { updateModules() }
        updBox.addView(btnMods, LinearLayout.LayoutParams(dp(56), dp(56)))
        btnScripts = GlyphView(this, GlyphView.SCRIPTS)
        btnScripts.setOnClickListener { updateScripts() }
        updBox.addView(btnScripts, LinearLayout.LayoutParams(dp(56), dp(56)))
        bar.addView(updBox, FrameLayout.LayoutParams(-2, dp(56), Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(56)))

        scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        grid = ModuleGridView(this)
        grid.scroller = scroll
        grid.listener = gridListener
        scroll.addView(grid, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val ctx = applicationContext
        apkNeed = ModuleUpdater.cachedHostNeed(ctx)
        modsPending = ModuleUpdater.cachedModules(ctx)
        scriptsPending = ModuleUpdater.cachedScripts(ctx)
        applyStatus()
        hideSystemUI()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("folder", currentFolder)
    }

    override fun onResume() {
        super.onResume()
        HostRecents.showHostCard(this)
        CoreAccessibilityService.instance?.refreshIfStale()      // .dex обновили: работающие модули перезагрузятся
        hideSystemUI()
        render()
        checkUpdates(false)
        // по одному недостающему разрешению за раз; после возврата из настроек покажется следующее
        PermissionGate.next(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentFolder != null) leaveFolder() else super.onBackPressed()
    }

    private fun leaveFolder() {
        currentFolder = null
        render()
    }

    private fun openSettings(target: String, folderId: String? = null) {
        startActivity(
            Intent(this, ModuleSettingsActivity::class.java)
                .putExtra(ModuleSettingsActivity.EXTRA_TARGET, target)
                .putExtra(ModuleSettingsActivity.EXTRA_FOLDER, folderId)
        )
    }

    /** Выставляет метки и подписи (для экранного диктора) трёх значков по последним известным данным. */
    private fun applyStatus() {
        val a = apkNeed
        btnApk.badge = if (a == true) 1 else 0
        btnApk.busy = busyApk
        btnApk.contentDescription = when (a) {
            true -> "Приложение: есть новая версия. Нажмите, чтобы скачать и установить"
            false -> "Приложение: версия актуальна. Нажмите, чтобы проверить обновления"
            null -> "Приложение: проверить обновления"
        }
        val m = modsPending
        btnMods.badge = m ?: 0
        btnMods.busy = busyMods
        btnMods.contentDescription = when {
            m != null && m > 0 -> "Модули: есть обновления, $m. Нажмите, чтобы обновить"
            m != null -> "Модули: всё актуально. Нажмите, чтобы проверить и обновить"
            else -> "Модули: обновить"
        }
        val sc = scriptsPending
        btnScripts.badge = sc ?: 0
        btnScripts.busy = busyScripts
        btnScripts.contentDescription = when {
            sc != null && sc > 0 -> "Скрипты: есть обновления, $sc. Нажмите, чтобы обновить"
            sc != null -> "Скрипты: всё актуально. Нажмите, чтобы проверить и обновить"
            else -> "Скрипты: обновить"
        }
    }

    /**
     * Сверяет приложение, модули и скрипты с GitHub, ничего не скачивая. При открытии экрана это делается не чаще
     * раза в 10 минут; [force] (нажатие на актуальный значок приложения) проверяет сразу и сообщает итог.
     */
    private fun checkUpdates(force: Boolean) {
        val ctx = applicationContext
        apkNeed = ModuleUpdater.cachedHostNeed(ctx)
        modsPending = ModuleUpdater.cachedModules(ctx)
        scriptsPending = ModuleUpdater.cachedScripts(ctx)
        applyStatus()
        if (checking) return
        if (!force && !ModuleUpdater.hostCheckDue(ctx)) return
        checking = true
        if (force) Toast.makeText(this, "Проверяю обновления…", Toast.LENGTH_SHORT).show()
        Thread {
            val s = ModuleUpdater.checkAll(ctx)
            runOnUiThread {
                checking = false
                if (!isFinishing && !isDestroyed) {
                    if (s.host != null) apkNeed = s.host
                    if (s.modules != null) modsPending = s.modules
                    if (s.scripts != null) scriptsPending = s.scripts
                    applyStatus()
                }
                if (force) Toast.makeText(ctx, statusText(s), Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun statusText(s: ModuleUpdater.Status): String {
        if (s.host == null && s.modules == null && s.scripts == null) return "Не удалось проверить: нет связи с GitHub"
        val parts = ArrayList<String>()
        if (s.host == true) parts.add("новая версия приложения")
        if ((s.modules ?: 0) > 0) parts.add("модулей: " + s.modules)
        if ((s.scripts ?: 0) > 0) parts.add("скриптов: " + s.scripts)
        return if (parts.isEmpty()) "Всё актуально" else "Есть обновления: " + parts.joinToString(", ")
    }

    /** Нажатие на значок приложения: есть новая версия, ставим её; иначе заново проверяем. */
    private fun onApkTap() {
        if (apkNeed == true) updateApp() else checkUpdates(true)
    }

    /** Модули: скачать с GitHub изменившиеся в Загрузки/UniversalPlugins и перезагрузить работающие. */
    private fun updateModules() {
        if (busyMods) return
        busyMods = true
        applyStatus()
        Toast.makeText(this, "Обновляю модули…", Toast.LENGTH_SHORT).show()
        val ctx = applicationContext
        Thread {
            val r = ModuleUpdater.run(ctx)
            runOnUiThread {
                busyMods = false
                if (!isFinishing && !isDestroyed) {
                    if (r.error == null) modsPending = r.failed.size
                    if (r.hostNeedsUpdate != null) apkNeed = r.hostNeedsUpdate
                    applyStatus()
                    if (r.updated.isNotEmpty()) {
                        CoreAccessibilityService.instance?.refreshIfStale()
                        render()
                    }
                }
                Toast.makeText(ctx, ModuleUpdater.summary(r), Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    /** Скрипты браузера: скачать новые версии по @downloadURL / @updateURL. Браузер подхватит их при следующей загрузке страницы. */
    private fun updateScripts() {
        if (busyScripts) return
        busyScripts = true
        applyStatus()
        Toast.makeText(this, "Обновляю скрипты…", Toast.LENGTH_SHORT).show()
        val ctx = applicationContext
        Thread {
            val r = try {
                ScriptUpdater.run()
            } catch (t: Throwable) {
                null
            }
            // не скачавшиеся остаются в счётчике; если не скачался ни один, это скорее нет сети: прежнее число не трогаем
            val left = if (r == null || (r.total > 0 && r.failed.size == r.total)) null else r.failed.size
            if (left != null) ModuleUpdater.storeScriptsPending(ctx, left)
            runOnUiThread {
                busyScripts = false
                if (!isFinishing && !isDestroyed) {
                    if (left != null) scriptsPending = left
                    applyStatus()
                }
                val text = if (r != null) ScriptUpdater.summary(r) else "Не удалось обновить скрипты"
                Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    /** Приложение: скачать новую версию и открыть системный установщик. */
    private fun updateApp() {
        if (busyApk) return
        busyApk = true
        applyStatus()
        Toast.makeText(this, "Скачиваю приложение…", Toast.LENGTH_SHORT).show()
        val ctx = applicationContext
        Thread {
            val err = ModuleUpdater.updateApp(ctx)
            runOnUiThread {
                busyApk = false
                if (!isFinishing && !isDestroyed) applyStatus()
                if (err != null) Toast.makeText(ctx, "Не удалось обновить приложение: $err", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun render() {
        plugins = PluginRegistry.scan(this)
        layoutModel = ModuleStorage.loadLayout(this)
        layoutModel.sync(plugins.map { it.name })
        ModuleStorage.saveLayout(this, layoutModel)
        val folder = currentFolder
        if (folder != null && layoutModel.folder(folder) == null) currentFolder = null

        cells = layoutModel.cells(currentFolder)
        grid.setCells(cells.map { c ->
            val enabled = c.members.any { ModuleStorage.isEnabled(this, it) }
            if (c.isFolder) {
                val custom = ModuleStorage.imagePath(this, ModuleStorage.folderTarget(c.id), ModuleStorage.KEY_ICON)
                if (custom != null) {
                    ModuleGridView.Cell(c.id, true, listOf(cachedFile(custom)), !enabled, true)
                } else {
                    ModuleGridView.Cell(c.id, true, c.members.take(4).map { moduleBitmap(it) }, !enabled, false)
                }
            } else {
                ModuleGridView.Cell(c.id, false, listOf(moduleBitmap(c.id)), !enabled, false)
            }
        })

        val inFolder = currentFolder != null
        back.visibility = if (inFolder) View.VISIBLE else View.INVISIBLE
        gear.visibility = if (inFolder) View.VISIBLE else View.GONE
        // в папке значки обновления стоят левее шестерёнки
        (updBox.layoutParams as FrameLayout.LayoutParams).rightMargin = if (inFolder) dp(56) else 0
        updBox.requestLayout()
        grid.dropOutView = if (inFolder) back else null
    }

    private fun cachedFile(path: String): Bitmap? {
        val key = "f:" + path + ":" + File(path).lastModified()
        bitmaps.get(key)?.let { return it }
        val b = ModuleStorage.loadBitmap(path, 192) ?: return null
        bitmaps.put(key, b)
        return b
    }

    private fun moduleBitmap(name: String): Bitmap? {
        val custom = ModuleStorage.imagePath(this, name, ModuleStorage.KEY_ICON)
        if (custom != null) cachedFile(custom)?.let { return it }
        val key = "g:$name"
        bitmaps.get(key)?.let { return it }
        val b = IconFactory.forModule(this, plugins.firstOrNull { it.name == name }, name, 192)
        bitmaps.put(key, b)
        return b
    }

    private val gridListener = object : ModuleGridView.Listener {
        override fun onTap(index: Int) {
            val c = cells.getOrNull(index) ?: return
            if (c.isFolder) {
                currentFolder = c.id
                render()
            } else {
                openSettings(c.id)
            }
        }

        override fun onReorder(from: Int, to: Int) {
            if (layoutModel.reorder(currentFolder, from, to)) commit()
        }

        override fun onMerge(from: Int, target: Int) {
            if (currentFolder == null && layoutModel.merge(from, target)) commit()
        }

        override fun onDropOut(from: Int) {
            val f = currentFolder ?: return
            if (layoutModel.moveOut(f, from)) {
                if (layoutModel.folder(f) == null) currentFolder = null
                commit()
            }
        }
    }

    private fun commit() {
        ModuleStorage.saveLayout(this, layoutModel)
        render()
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }
}

/**
 * Значок-кнопка без подписи: стрелка «назад», шестерёнка и три значка обновления: приложение (телефон со стрелкой
 * вниз), модули (плитки) и скрипты (угловые скобки). У значков обновления [badge] больше нуля значит «есть
 * обновление»: рисуется метка (у приложения точка, у модулей и скриптов число) и значок становится ярче;
 * [busy] приглушает значок на время обновления.
 */
class GlyphView(context: Context, private val kind: Int) : View(context) {
    companion object {
        const val ARROW = 0
        const val GEAR = 1
        const val APK = 2
        const val MODULES = 3
        const val SCRIPTS = 4
    }

    var badge: Int = 0
        set(value) {
            if (field != value) {
                field = value
                refresh()
            }
        }

    var busy: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                refresh()
            }
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(0x4C, 0xD9, 0x64)
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    init {
        isClickable = true
        if (kind >= APK) alpha = 0.6f
    }

    private fun refresh() {
        if (kind >= APK) alpha = if (busy) 0.25f else if (badge > 0) 1f else 0.6f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        p.strokeWidth = 2.5f * d
        val cx = width / 2f
        val cy = height / 2f
        val s = 10 * d
        when (kind) {
            ARROW -> {
                canvas.drawLine(cx + s, cy, cx - s, cy, p)
                canvas.drawLine(cx - s, cy, cx - s * 0.3f, cy - s * 0.7f, p)
                canvas.drawLine(cx - s, cy, cx - s * 0.3f, cy + s * 0.7f, p)
            }
            APK -> {
                canvas.drawRoundRect(cx - s * 0.8f, cy - s * 1.2f, cx + s * 0.8f, cy + s * 1.2f, s * 0.3f, s * 0.3f, p)
                canvas.drawLine(cx, cy - s * 0.5f, cx, cy + s * 0.5f, p)
                canvas.drawLine(cx, cy + s * 0.5f, cx - s * 0.4f, cy + s * 0.1f, p)
                canvas.drawLine(cx, cy + s * 0.5f, cx + s * 0.4f, cy + s * 0.1f, p)
            }
            MODULES -> {
                val gap = s * 0.25f
                val side = (2 * s - gap) / 2f
                val r = s * 0.15f
                val x0 = cx - s
                val x1 = cx + gap / 2f
                val y0 = cy - s
                val y1 = cy + gap / 2f
                val o = s * 0.3f
                canvas.drawRoundRect(x0, y0, x0 + side, y0 + side, r, r, p)
                canvas.drawRoundRect(x0, y1, x0 + side, y1 + side, r, r, p)
                canvas.drawRoundRect(x1, y1, x1 + side, y1 + side, r, r, p)
                // четвёртая плитка сдвинута: «ещё один модуль»
                canvas.drawRoundRect(x1 + o, y0 - o, x1 + o + side, y0 - o + side, r, r, p)
            }
            SCRIPTS -> {
                canvas.drawLine(cx - s * 0.45f, cy - s * 0.8f, cx - s * 1.15f, cy, p)
                canvas.drawLine(cx - s * 1.15f, cy, cx - s * 0.45f, cy + s * 0.8f, p)
                canvas.drawLine(cx + s * 0.45f, cy - s * 0.8f, cx + s * 1.15f, cy, p)
                canvas.drawLine(cx + s * 1.15f, cy, cx + s * 0.45f, cy + s * 0.8f, p)
                canvas.drawLine(cx + s * 0.2f, cy - s * 0.9f, cx - s * 0.2f, cy + s * 0.9f, p)
            }
            else -> {
                canvas.drawCircle(cx, cy, s * 0.7f, p)
                canvas.drawCircle(cx, cy, s * 0.25f, p)
                for (i in 0 until 8) {
                    val a = i * Math.PI / 4
                    val c = Math.cos(a).toFloat()
                    val sn = Math.sin(a).toFloat()
                    canvas.drawLine(cx + c * s * 0.85f, cy + sn * s * 0.85f, cx + c * s * 1.15f, cy + sn * s * 1.15f, p)
                }
            }
        }
        if (kind >= APK && badge > 0) drawBadge(canvas, cx + s * 1.35f, cy - s * 1.3f, d)
    }

    private fun drawBadge(canvas: Canvas, bx: Float, by: Float, d: Float) {
        if (kind == APK) {
            canvas.drawCircle(bx, by, 7f * d, ring)
            canvas.drawCircle(bx, by, 5f * d, accent)
        } else {
            canvas.drawCircle(bx, by, 10f * d, ring)
            canvas.drawCircle(bx, by, 8f * d, accent)
            digits.textSize = 11f * d
            val label = if (badge > 9) "9+" else badge.toString()
            canvas.drawText(label, bx, by - (digits.ascent() + digits.descent()) / 2f, digits)
        }
    }
}
