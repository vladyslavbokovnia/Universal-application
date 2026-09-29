package im.manus.universalhost

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File

/**
 * Экран модулей: только значки, без подписей. Тап по модулю открывает его настройки,
 * тап по папке открывает папку. Значок можно перетащить: на другой значок — папка.
 */
class MainActivity : Activity() {

    private lateinit var grid: ModuleGridView
    private lateinit var scroll: ScrollView
    private lateinit var back: GlyphView
    private lateinit var gear: GlyphView

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
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(56)))

        scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        grid = ModuleGridView(this)
        grid.scroller = scroll
        grid.listener = gridListener
        scroll.addView(grid, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        hideSystemUI()
        requestPermissions()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("folder", currentFolder)
    }

    override fun onResume() {
        super.onResume()
        render()
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
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun requestPermissions() {
        if (!hasUsageStatsPermission()) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
    }
}

/** Значок-кнопка без подписи: стрелка «назад» или шестерёнка. */
class GlyphView(context: Context, private val kind: Int) : View(context) {
    companion object {
        const val ARROW = 0
        const val GEAR = 1
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        p.strokeWidth = 2.5f * d
        val cx = width / 2f
        val cy = height / 2f
        val s = 10 * d
        if (kind == ARROW) {
            canvas.drawLine(cx + s, cy, cx - s, cy, p)
            canvas.drawLine(cx - s, cy, cx - s * 0.3f, cy - s * 0.7f, p)
            canvas.drawLine(cx - s, cy, cx - s * 0.3f, cy + s * 0.7f, p)
        } else {
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
}