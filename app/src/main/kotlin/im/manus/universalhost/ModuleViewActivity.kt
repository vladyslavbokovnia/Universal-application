package im.manus.universalhost

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView

/**
 * Главное окно модуля (PWA-стиль): отдельная карточка в недавних со значком и именем модуля, внутри только
 * вид модуля (IMainViewProvider): сетка списка загрузок или сетка треков плеера. Настройки открываются
 * шестерёнкой в нижнем углу поверх списка. Окно записывается в реестр ModuleWindows, поэтому его видит и модуль «Недавние».
 */
class ModuleViewActivity : Activity() {

    companion object {
        const val EXTRA_TARGET = "target"

        /** Интент, который открывает (или поднимает) окно модуля [name]: одна карточка в недавних на модуль. */
        @JvmStatic
        fun intentFor(context: Context, name: String): Intent =
            Intent(context, ModuleViewActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("moduleview://" + Uri.encode(name))
                putExtra(EXTRA_TARGET, name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            }
    }

    private var target: String = ""
    private var plugin: IPlugin? = null

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t = intent.getStringExtra(EXTRA_TARGET) ?: intent.data?.host?.let { Uri.decode(it) }
        if (t == null) {
            finish()
            return
        }
        target = t
        title = t
        try {
            setTaskDescription(android.app.ActivityManager.TaskDescription(t, ModuleWindows.icon(this, t, 192), Color.BLACK))
        } catch (e: Throwable) { }

        plugin = try { PluginRegistry.find(this, t) } catch (e: Throwable) { null }
        val view = try { (plugin as? IMainViewProvider)?.createMainView(this) } catch (e: Throwable) { null }
        if (view == null) {
            openSettings()
            finish()
            return
        }

        val d = resources.displayMetrics.density
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        // список на всё окно; шестерёнка не занимает полосу сверху, а лежит в нижнем углу поверх списка
        val body: View = if (view is ScrollView) view else ScrollView(this).apply { addView(view) }
        (body as ScrollView).apply {
            clipToPadding = false
            setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom + (72 * d).toInt())   // последняя плитка не под кнопкой
        }
        root.addView(body, FrameLayout.LayoutParams(-1, -1))

        val gear = FrameLayout(this)
        val ring = GradientDrawable()
        ring.shape = GradientDrawable.OVAL
        ring.setColor(0xCC000000.toInt())
        ring.setStroke((1 * d).toInt().coerceAtLeast(1), Color.WHITE)
        gear.background = ring
        gear.contentDescription = "Настройки модуля"
        gear.setOnClickListener { openSettings() }
        val glyph = GlyphView(this, GlyphView.GEAR)
        glyph.alpha = 0.85f
        gear.addView(glyph, FrameLayout.LayoutParams(-1, -1))
        gear.setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
        root.addView(gear, FrameLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt(), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, (16 * d).toInt(), (16 * d).toInt())
        })
        setContentView(root)
    }

    private fun openSettings() {
        startActivity(
            Intent(this, ModuleSettingsActivity::class.java)
                .putExtra(ModuleSettingsActivity.EXTRA_TARGET, target)
        )
    }

    override fun onResume() {
        super.onResume()
        CoreAccessibilityService.instance?.refreshIfStale()
        if (reloadIfModuleUpdated()) return
        if (target.isNotEmpty()) {
            ModuleWindows.record(this, target, Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        HostRecents.hideHostCard(this)
    }

    /** .dex модуля заменили, пока окно было открыто: пересоздаём окно, чтобы вид строился из новой версии. */
    private fun reloadIfModuleUpdated(): Boolean {
        val held = plugin ?: return false
        if (target.isEmpty()) return false
        val current = try { PluginRegistry.find(this, target) } catch (e: Throwable) { null }
        if (current == null || current === held) return false
        recreate()
        return true
    }
}
