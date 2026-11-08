package im.manus.universalhost

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Главное окно модуля (PWA-стиль): отдельная карточка в недавних со значком и именем модуля, внутри только
 * вид модуля (IMainViewProvider): сетка списка загрузок или сетка треков плеера. Настройки открываются
 * шестерёнкой сверху. Окно записывается в реестр ModuleWindows, поэтому его видит и модуль «Недавние».
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

        val plugin = try { PluginRegistry.find(this, t) } catch (e: Throwable) { null }
        val view = try { (plugin as? IMainViewProvider)?.createMainView(this) } catch (e: Throwable) { null }
        if (view == null) {
            openSettings()
            finish()
            return
        }

        val d = resources.displayMetrics.density
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.BLACK)

        val bar = FrameLayout(this)
        val gear = GlyphView(this, GlyphView.GEAR)
        gear.alpha = 0.6f
        gear.contentDescription = "Настройки модуля"
        gear.setOnClickListener { openSettings() }
        bar.addView(gear, FrameLayout.LayoutParams((56 * d).toInt(), (56 * d).toInt(), Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(bar, LinearLayout.LayoutParams(-1, (56 * d).toInt()))

        val body: View = if (view is ScrollView) view else ScrollView(this).apply { addView(view) }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
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
        if (target.isNotEmpty()) {
            ModuleWindows.record(this, target, Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        HostRecents.hideHostCard(this)
        CoreAccessibilityService.instance?.refreshIfStale()
    }
}
