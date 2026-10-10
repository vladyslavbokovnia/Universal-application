package im.manus.universalhost

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Экран настроек модуля (или папки). Здесь единственное место, где у модулей есть текст:
 * на экране модулей остаются только значки.
 */
class ModuleSettingsActivity : Activity() {

    companion object {
        const val EXTRA_TARGET = "target"       // имя модуля либо ModuleStorage.folderTarget(...)
        const val EXTRA_FOLDER = "folder"       // id папки, если открыты настройки папки
        private const val REQ_IMAGE = 1
    }

    private lateinit var target: String
    private var folderId: String? = null
    private var pendingKey: String? = null
    private lateinit var content: LinearLayout

    // живые мониторы (SettingItem.MONITOR)
    private class MonitorRow(val key: String, val value: TextView, val status: TextView, val graph: MonitorView)
    private val monitors = ArrayList<MonitorRow>()
    private var monitorPlugin: IPlugin? = null
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refreshMonitors()
            if (monitors.isNotEmpty()) ui.postDelayed(this, 500)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t = intent.getStringExtra(EXTRA_TARGET)
        if (t == null) {
            finish()
            return
        }
        target = t
        folderId = intent.getStringExtra(EXTRA_FOLDER)
        pendingKey = savedInstanceState?.getString("pending")

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.BLACK)
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(16), dp(16), dp(16), dp(32))
        scroll.addView(content)
        setContentView(scroll)
        build()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pending", pendingKey)
    }

    // ------------------------------------------------------------ сборка экрана

    private fun build() {
        ui.removeCallbacks(tick)
        monitors.clear()
        content.removeAllViews()
        val fid = folderId
        if (fid != null) buildFolder(fid) else buildModule()
        if (monitors.isNotEmpty()) ui.post(tick)
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(tick)
        if (monitors.isNotEmpty()) ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun refreshMonitors() {
        val p = monitorPlugin ?: return
        for (m in monitors) {
            val raw = try { p.execute(mapOf("command" to "monitor:" + m.key)) } catch (t: Throwable) { null }
            val r = raw as? Map<*, *> ?: continue
            (r["values"] as? IntArray)?.let { m.graph.setSamples(it) }
            (r["value"] as? String)?.let { m.value.text = it }
            (r["status"] as? String)?.let { m.status.text = it }
        }
    }

    private fun monitorRow(item: SettingItem): View {
        val c = card()
        c.addView(labels(item.title, item.summary))
        val value = text("— dBm", 32f, Color.WHITE, true)
        value.gravity = Gravity.CENTER
        value.setPadding(0, dp(8), 0, 0)
        c.addView(value, LinearLayout.LayoutParams(-1, -2))
        val status = text("", 13f, Color.LTGRAY, false)
        status.gravity = Gravity.CENTER
        c.addView(status, LinearLayout.LayoutParams(-1, -2))
        val graph = MonitorView(this)
        c.addView(graph, LinearLayout.LayoutParams(-1, dp(180)).apply { topMargin = dp(8) })
        monitors.add(MonitorRow(item.key, value, status, graph))
        return c
    }

    private fun buildModule() {
        val plugin = PluginRegistry.find(this, target)
        monitorPlugin = plugin
        val prefs = ModuleStorage.prefs(this, target)
        header(IconFactory.forModule(this, plugin, target, 192), target,
            plugin?.let { "v" + it.version + "  " + it.description } ?: "Модуль не найден")

        val enabled = switchRow("Модуль включён", "Выключенный модуль не запускается", ModuleStorage.isEnabled(this, target)) { on ->
            ModuleStorage.setEnabled(this, target, on)
            CoreAccessibilityService.instance?.refreshPlugins()
        }
        content.addView(enabled)

        val schema = try {
            (plugin as? ISettingsProvider)?.getSettingsSchema() ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }
        if (schema.isEmpty()) {
            content.addView(hint("У этого модуля нет своих настроек"))
            return
        }
        for (item in schema) addItem(item, prefs)
    }

    private fun buildFolder(id: String) {
        val layout = ModuleStorage.loadLayout(this)
        val node = layout.folder(id)
        if (node == null) {
            finish()
            return
        }
        val custom = ModuleStorage.imagePath(this, target, ModuleStorage.KEY_ICON)
        val icon = custom?.let { ModuleStorage.loadBitmap(it, 192) } ?: IconFactory.monogram(node.name.ifEmpty { "Папка" }, 192)
        header(icon, node.name.ifEmpty { "Папка" }, "Модулей в папке: " + node.items.size)

        content.addView(clickRow("Название папки", node.name.ifEmpty { "не задано" }) {
            askText("Название папки", node.name) { value ->
                val l = ModuleStorage.loadLayout(this)
                l.rename(id, value)
                ModuleStorage.saveLayout(this, l)
                build()
            }
        })
        content.addView(clickRow("Расформировать папку", "Модули вернутся на главный экран") {
            val l = ModuleStorage.loadLayout(this)
            l.dissolve(id)
            ModuleStorage.saveLayout(this, l)
            finish()
        })
    }

    private fun header(icon: Bitmap, title: String, subtitle: String) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val iv = ImageView(this)
        iv.setImageBitmap(icon)
        iv.setBackgroundColor(Color.rgb(26, 26, 26))
        iv.setPadding(dp(8), dp(8), dp(8), dp(8))
        iv.setOnClickListener { pickImage(ModuleStorage.KEY_ICON) }
        row.addView(iv, LinearLayout.LayoutParams(dp(80), dp(80)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), 0, 0, 0)
        col.addView(text(title, 24f, Color.WHITE, true))
        col.addView(text(subtitle, 14f, Color.LTGRAY, false))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.addView(button("Заменить иконку") { pickImage(ModuleStorage.KEY_ICON) })
        buttons.addView(button("Сбросить иконку") {
            ModuleStorage.clearImage(this, target, ModuleStorage.KEY_ICON)
            build()
        })
        content.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
    }

    // ------------------------------------------------------------ элементы схемы

    private fun addItem(item: SettingItem, prefs: SharedPreferences) {
        when (item.type) {
            SettingItem.SECTION -> content.addView(
                text(item.title, 14f, Color.GRAY, true).apply { setPadding(dp(4), dp(16), 0, dp(6)) }
            )
            SettingItem.SWITCH -> content.addView(
                switchRow(item.title, item.summary, prefs.getBoolean(item.key, item.defInt == 1)) { on ->
                    prefs.edit().putBoolean(item.key, on).apply()
                    notifyModule(item.key)
                }
            )
            SettingItem.SLIDER -> content.addView(sliderRow(item, prefs))
            SettingItem.CHOICE -> {
                val current = prefs.getInt(item.key, item.defInt).coerceIn(0, maxOf(0, item.options.size - 1))
                val row = clickRow(item.title, item.options.getOrElse(current) { "" }) {
                    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(item.title)
                        .setItems(item.options.toTypedArray()) { _, which ->
                            prefs.edit().putInt(item.key, which).apply()
                            notifyModule(item.key)
                            build()
                        }.show()
                }
                content.addView(row)
            }
            SettingItem.TEXT -> {
                val cur = prefs.getString(item.key, item.defStr) ?: item.defStr
                content.addView(clickRow(item.title, cur.ifEmpty { item.summary }) {
                    askText(item.title, cur) { value ->
                        prefs.edit().putString(item.key, value).apply()
                        notifyModule(item.key)
                        build()
                    }
                })
            }
            SettingItem.IMAGE -> content.addView(imageRow(item))
            SettingItem.APP_IMAGES -> content.addView(clickRow(item.title, item.summary) {
                startActivity(
                    Intent(this, AppImagesActivity::class.java)
                        .putExtra(AppImagesActivity.EXTRA_MODULE, target)
                        .putExtra(AppImagesActivity.EXTRA_KEY, item.key)
                )
            })
            SettingItem.APP_PICKER -> {
                val cur = prefs.getString(item.key, item.defStr) ?: item.defStr
                content.addView(clickRow(item.title, pickerSummary(cur, item.summary)) {
                    pickApps(item, prefs)
                })
            }
            SettingItem.MONITOR -> content.addView(monitorRow(item))
            SettingItem.CUSTOM -> (monitorPlugin as? ISettingsViewProvider)?.let { provider ->
                try {
                    content.addView(provider.createSettingsView(this, item.key))
                } catch (t: Throwable) {
                    content.addView(hint("Не удалось построить вид: " + t.message))
                }
            }
            SettingItem.ACTION -> content.addView(clickRow(item.title, item.summary) {
                notifyModule(item.key)
                Toast.makeText(this, "Готово", Toast.LENGTH_SHORT).show()
            })
        }
    }

    // ------------------------------------------------------------ выбор приложений (APP_PICKER)

    private fun pickerPackages(csv: String): List<String> =
        csv.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** Названия выбранных приложений; если пакет не установлен, показывается сам пакет. */
    private fun pickerSummary(csv: String, fallback: String): String {
        val pkgs = pickerPackages(csv)
        if (pkgs.isEmpty()) return fallback
        val pm = packageManager
        return pkgs.joinToString(", ") { p ->
            try {
                pm.getApplicationInfo(p, 0).loadLabel(pm).toString()
            } catch (e: Exception) {
                p
            }
        }
    }

    /** Список установленных приложений с галочками; выбранные сверху. Результат — пакеты через запятую. */
    private fun pickApps(item: SettingItem, prefs: SharedPreferences) {
        val chosen = LinkedHashSet<String>(pickerPackages(prefs.getString(item.key, item.defStr) ?: item.defStr))
        Thread {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val seen = HashSet<String>()
            val apps = ArrayList<Pair<String, String>>()        // пакет, название
            for (ri in pm.queryIntentActivities(launcher, 0)) {
                val pkg = ri.activityInfo.packageName
                if (seen.add(pkg)) apps.add(Pair(pkg, ri.loadLabel(pm).toString()))
            }
            apps.sortWith(compareBy<Pair<String, String>>({ !chosen.contains(it.first) }, { it.second.lowercase() }))
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val labels = apps.map { it.second }.toTypedArray()
                val checked = BooleanArray(apps.size) { chosen.contains(apps[it].first) }
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(item.title)
                    .setMultiChoiceItems(labels, checked) { _, which, on -> checked[which] = on }
                    .setPositiveButton("Готово") { _, _ ->
                        val result = ArrayList<String>()
                        // пакеты, которых нет в списке запуска (например, введённые раньше), не теряем
                        for (p in chosen) if (apps.none { it.first == p }) result.add(p)
                        for (i in apps.indices) if (checked[i]) result.add(apps[i].first)
                        prefs.edit().putString(item.key, result.joinToString(",")).apply()
                        notifyModule(item.key)
                        build()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        }.start()
    }

    private fun sliderRow(item: SettingItem, prefs: SharedPreferences): View {
        val steps = (item.max - item.min) / item.step
        val cur = prefs.getInt(item.key, item.defInt).coerceIn(item.min, item.max)
        val valueText = text(cur.toString(), 16f, Color.WHITE, true)
        val bar = SeekBar(this)
        bar.max = steps
        bar.progress = (cur - item.min) / item.step
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                valueText.text = (item.min + progress * item.step).toString()
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}

            override fun onStopTrackingTouch(sb: SeekBar) {
                prefs.edit().putInt(item.key, item.min + sb.progress * item.step).apply()
                notifyModule(item.key)      // применяем один раз, когда палец отпущен
            }
        })
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.addView(labels(item.title, item.summary), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(valueText)
        val col = card()
        col.addView(top)
        col.addView(bar)
        return col
    }

    private fun imageRow(item: SettingItem): View {
        val path = ModuleStorage.imagePath(this, target, item.key)
        val row = card()
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val thumb = ImageView(this)
        thumb.setBackgroundColor(Color.rgb(40, 40, 40))
        path?.let { ModuleStorage.loadBitmap(it, 96) }?.let { thumb.setImageBitmap(it) }
        row.addView(thumb, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) })
        row.addView(labels(item.title, item.summary), LinearLayout.LayoutParams(0, -2, 1f))
        if (path != null) {
            row.addView(button("Сбросить") {
                ModuleStorage.clearImage(this, target, item.key)
                notifyModule(item.key)
                build()
            })
        }
        row.setOnClickListener { pickImage(item.key) }
        return row
    }

    // ------------------------------------------------------------ вспомогательные виды

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(14), dp(12), dp(14), dp(12))
        c.setBackgroundColor(Color.rgb(26, 26, 26))
        c.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
        return c
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        return t
    }

    private fun hint(s: String) = text(s, 14f, Color.GRAY, false).apply { setPadding(dp(4), dp(12), 0, 0) }

    private fun labels(title: String, summary: String): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(text(title, 17f, Color.WHITE, false))
        if (summary.isNotEmpty()) col.addView(text(summary, 13f, Color.LTGRAY, false))
        return col
    }

    private fun clickRow(title: String, summary: String, onClick: () -> Unit): View {
        val c = card()
        c.addView(labels(title, summary))
        c.setOnClickListener { onClick() }
        return c
    }

    private fun switchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val c = card()
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.addView(labels(title, summary), LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(this)
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, on -> onChange(on) }
        c.addView(sw)
        c.setOnClickListener { sw.toggle() }
        return c
    }

    private fun button(label: String, onClick: () -> Unit): TextView {
        val b = text(label, 14f, Color.WHITE, false)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(12), dp(8), dp(12), dp(8))
        val bg = GradientDrawable()
        bg.setColor(Color.TRANSPARENT)
        bg.setStroke(dp(1), Color.WHITE)
        bg.cornerRadius = dp(8).toFloat()
        b.background = bg
        b.setOnClickListener { onClick() }
        b.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }
        return b
    }

    private fun askText(title: String, current: String, onOk: (String) -> Unit) {
        val input = EditText(this)
        input.setText(current)
        input.setSelection(input.text.length)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun notifyModule(key: String) {
        if (folderId == null) CoreAccessibilityService.instance?.notifySettingChanged(target, key)
    }

    // ------------------------------------------------------------ выбор картинки

    private fun pickImage(key: String) {
        pendingKey = key
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_IMAGE)
        } catch (e: Exception) {
            Toast.makeText(this, "Не найдено приложение для выбора картинки", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data
        val key = pendingKey
        if (uri == null || key == null) return
        if (ModuleStorage.saveImage(this, target, key, uri)) {
            notifyModule(key)
        } else {
            Toast.makeText(this, "Не удалось прочитать картинку", Toast.LENGTH_LONG).show()
        }
        build()
    }
}
