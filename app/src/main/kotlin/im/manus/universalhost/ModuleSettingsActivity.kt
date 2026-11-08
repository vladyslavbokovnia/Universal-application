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
import android.speech.tts.TextToSpeech
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
import java.util.Locale

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
        title = t // карточка в недавних подписана именем модуля (PWA-стиль)
        applyModuleTaskDescription(t)
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

    /** Карточка в недавних со значком самого модуля, а не хоста; окно из ярлыка (module://) попадает в модуль «Недавние». */
    @Suppress("DEPRECATION")
    private fun applyModuleTaskDescription(name: String) {
        if (intent.getStringExtra(EXTRA_FOLDER) != null) return
        try {
            setTaskDescription(android.app.ActivityManager.TaskDescription(name, ModuleWindows.icon(this, name, 192), Color.BLACK))
        } catch (t: Throwable) { }
    }

    // ------------------------------------------------------------ сборка экрана

    private fun build() {
        ui.removeCallbacks(tick)
        monitors.clear()
        content.removeAllViews()
        gridRow = null
        val fid = folderId
        if (fid != null) buildFolder(fid) else buildModule()
        if (monitors.isNotEmpty()) ui.post(tick)
    }

    override fun onResume() {
        super.onResume()
        if (intent.data?.scheme == "module" && intent.getStringExtra(EXTRA_FOLDER) == null) {
            ModuleWindows.record(this, target, Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        // окно модуля из ярлыка или «Недавних»: карточку хоста в системных «Недавних» прячем
        if (intent.data?.scheme == "module") HostRecents.hideHostCard(this)
        CoreAccessibilityService.instance?.refreshIfStale()
        ui.removeCallbacks(tick)
        if (monitors.isNotEmpty()) ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick)
        tts?.shutdown()
        tts = null
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

        val enabled = switchRow("⏻ Модуль включён", "Выключенный модуль не запускается", ModuleStorage.isEnabled(this, target)) { on ->
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
        flushGrid()
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

        content.addView(valueRow("✏️ Название папки", "", node.name.ifEmpty { "не задано" }) {
            askText("Название папки", node.name) { value ->
                val l = ModuleStorage.loadLayout(this)
                l.rename(id, value)
                ModuleStorage.saveLayout(this, l)
                build()
            }
        })
        content.addView(valueRow("📤 Расформировать папку", "Модули вернутся на главный экран", "") {
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
        iv.setOnClickListener { speak("$title. $subtitle") }
        row.addView(iv, LinearLayout.LayoutParams(dp(80), dp(80)))
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(smallBtn("🖼") { pickImage(ModuleStorage.KEY_ICON) })
        row.addView(smallBtn("↺") {
            ModuleStorage.clearImage(this, target, ModuleStorage.KEY_ICON)
            build()
        })
        content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    // ------------------------------------------------------------ логотипы и озвучка
    // Экран настроек без текста: у каждой настройки логотип и элемент управления. Текст настройки
    // озвучивается по нажатию на логотип. Логотип берётся из эмодзи в начале заголовка, иначе это монограмма.

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private fun speak(text: String) {
        val s = text.trim()
        if (s.isEmpty()) return
        val t = tts
        if (t == null) {
            tts = TextToSpeech(applicationContext) { st ->
                if (st == TextToSpeech.SUCCESS) {
                    tts?.language = Locale("ru", "RU")
                    ttsReady = true
                    tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "setting")
                }
            }
        } else if (ttsReady) {
            t.speak(s, TextToSpeech.QUEUE_FLUSH, null, "setting")
        }
    }

    /** Эмодзи в начале заголовка («🎧 Дорожка» даёт «🎧»); null, если заголовок начинается с буквы или цифры. */
    private fun logoOf(title: String): String? {
        val first = title.trimStart().substringBefore(' ')
        if (first.isEmpty()) return null
        val cp = first.codePointAt(0)
        return if (cp >= 0x2190 && !Character.isLetterOrDigit(cp)) first else null
    }

    private fun plain(title: String): String {
        val t = title.trim()
        val logo = logoOf(t) ?: return t
        return t.removePrefix(logo).trim()
    }

    private fun logoView(title: String, sizeDp: Int, say: () -> String): View {
        val logo = logoOf(title)
        val v: View
        if (logo != null) {
            val tv = TextView(this)
            tv.text = logo
            tv.textSize = sizeDp * 0.5f
            tv.gravity = Gravity.CENTER
            tv.setTextColor(Color.WHITE)
            val bg = GradientDrawable()
            bg.setColor(Color.rgb(34, 34, 34))
            bg.setStroke(dp(1), Color.GRAY)
            bg.cornerRadius = dp(12).toFloat()
            tv.background = bg
            v = tv
        } else {
            val iv = ImageView(this)
            iv.setImageBitmap(IconFactory.monogram(plain(title), 128))
            iv.setPadding(dp(4), dp(4), dp(4), dp(4))
            v = iv
        }
        v.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        v.contentDescription = plain(title)
        v.setOnClickListener { speak(say()) }
        return v
    }

    /** Строка настройки: слева логотип, дальше элементы управления. */
    private fun iconCard(title: String, say: () -> String): LinearLayout {
        val c = card()
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.addView(logoView(title, 52, say))
        return c
    }

    private fun smallBtn(glyph: String, onClick: () -> Unit): TextView {
        val b = text(glyph, 24f, Color.WHITE, false)
        b.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.setColor(Color.rgb(34, 34, 34))
        bg.setStroke(dp(1), Color.GRAY)
        bg.cornerRadius = dp(12).toFloat()
        b.background = bg
        b.setOnClickListener { onClick() }
        b.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) }
        return b
    }

    private fun sectionRow(title: String): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(4), dp(14), dp(4), dp(4))
        row.addView(logoView(title, 32) { plain(title) })
        val line = View(this)
        line.setBackgroundColor(Color.DKGRAY)
        row.addView(line, LinearLayout.LayoutParams(0, dp(1), 1f).apply { leftMargin = dp(10) })
        return row
    }

    /** Строка с кнопкой: [value] показывается на кнопке, пустое значение даёт «▶». */
    private fun valueRow(title: String, summary: String, value: String, compact: Boolean = false, onClick: () -> Unit): View {
        val c = iconCard(title) {
            plain(title) + (if (summary.isNotEmpty()) ". $summary" else "") +
                (if (value.isNotEmpty()) ". Сейчас: $value" else "")
        }
        c.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val b = button(value.ifEmpty { "▶" }) { onClick() }
        b.maxLines = 1
        b.ellipsize = android.text.TextUtils.TruncateAt.END
        b.maxWidth = dp(if (compact) 84 else 180)
        c.addView(b)
        c.setOnClickListener { onClick() }
        return c
    }

    // ------------------------------------------------------------ сетка
    // Переключатели, кнопки, выбор и текст идут по две в ряд (меньше прокручивать); ползунки, картинки,
    // секции и виды модуля занимают всю строку.

    private var gridRow: LinearLayout? = null

    private fun flushGrid() {
        gridRow?.let { if (it.childCount == 1) it.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
        gridRow = null
    }

    private fun addCompact(v: View) {
        var row = gridRow
        if (row == null || row.childCount >= 2) {
            flushGrid()
            row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            content.addView(row, LinearLayout.LayoutParams(-1, -2))
            gridRow = row
        }
        v.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        row.addView(v)
    }

    // ------------------------------------------------------------ элементы схемы

    private fun addItem(item: SettingItem, prefs: SharedPreferences) {
        when (item.type) {
            SettingItem.SECTION -> {
                flushGrid()
                content.addView(sectionRow(item.title))
            }
            SettingItem.SWITCH -> addCompact(
                switchRow(item.title, item.summary, prefs.getBoolean(item.key, item.defInt == 1)) { on ->
                    prefs.edit().putBoolean(item.key, on).apply()
                    notifyModule(item.key)
                }
            )
            SettingItem.SLIDER -> {
                flushGrid()
                content.addView(sliderRow(item, prefs))
            }
            SettingItem.CHOICE -> {
                val current = prefs.getInt(item.key, item.defInt).coerceIn(0, maxOf(0, item.options.size - 1))
                addCompact(valueRow(item.title, item.summary, item.options.getOrElse(current) { "" }, true) {
                    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(plain(item.title))
                        .setItems(item.options.toTypedArray()) { _, which ->
                            prefs.edit().putInt(item.key, which).apply()
                            notifyModule(item.key)
                            build()
                        }.show()
                })
            }
            SettingItem.TEXT -> {
                val cur = prefs.getString(item.key, item.defStr) ?: item.defStr
                addCompact(valueRow(item.title, item.summary, cur, true) {
                    askText(plain(item.title), cur) { value ->
                        prefs.edit().putString(item.key, value).apply()
                        notifyModule(item.key)
                        build()
                    }
                })
            }
            SettingItem.IMAGE -> {
                flushGrid()
                content.addView(imageRow(item))
            }
            SettingItem.APP_IMAGES -> addCompact(valueRow(item.title, item.summary, "", true) {
                startActivity(
                    Intent(this, AppImagesActivity::class.java)
                        .putExtra(AppImagesActivity.EXTRA_MODULE, target)
                        .putExtra(AppImagesActivity.EXTRA_KEY, item.key)
                )
            })
            SettingItem.APP_PICKER -> {
                val cur = prefs.getString(item.key, item.defStr) ?: item.defStr
                addCompact(valueRow(item.title, item.summary, pickerSummary(cur, ""), true) {
                    pickApps(item, prefs)
                })
            }
            SettingItem.MONITOR -> {
                flushGrid()
                content.addView(monitorRow(item))
            }
            SettingItem.CUSTOM -> {
                flushGrid()
                (monitorPlugin as? ISettingsViewProvider)?.let { provider ->
                    try {
                        // вид модуля на всю ширину экрана: отступы списка (16 dp) компенсируются отрицательными полями
                        content.clipToPadding = false
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(-dp(16), 0, -dp(16), 0) }
                        content.addView(provider.createSettingsView(this, item.key), lp)
                    } catch (t: Throwable) {
                        content.addView(hint("Не удалось построить вид: " + t.message))
                    }
                }
            }
            SettingItem.ACTION -> addCompact(valueRow(item.title, item.summary, "", true) {
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
        val c = iconCard(item.title) {
            plain(item.title) + (if (item.summary.isNotEmpty()) ". " + item.summary else "") + ". Сейчас: " + valueText.text
        }
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
        c.addView(bar, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        c.addView(valueText)
        return c
    }

    private fun imageRow(item: SettingItem): View {
        val path = ModuleStorage.imagePath(this, target, item.key)
        val row = iconCard(item.title) {
            plain(item.title) + (if (item.summary.isNotEmpty()) ". " + item.summary else "")
        }
        val thumb = ImageView(this)
        thumb.setBackgroundColor(Color.rgb(40, 40, 40))
        path?.let { ModuleStorage.loadBitmap(it, 96) }?.let { thumb.setImageBitmap(it) }
        thumb.setOnClickListener { pickImage(item.key) }
        row.addView(thumb, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(12) })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (path != null) {
            row.addView(button("↺") {
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

    private fun switchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        lateinit var sw: Switch
        val c = iconCard(title) {
            plain(title) + (if (summary.isNotEmpty()) ". $summary" else "") +
                ". " + (if (sw.isChecked) "Включено" else "Выключено")
        }
        sw = Switch(this)
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, on -> onChange(on) }
        c.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
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
            if (key == ModuleStorage.KEY_ICON) ModuleShortcuts.refresh(this, target)   // ярлык на рабочем столе тоже
            notifyModule(key)
        } else {
            Toast.makeText(this, "Не удалось прочитать картинку", Toast.LENGTH_LONG).show()
        }
        build()
    }
}
