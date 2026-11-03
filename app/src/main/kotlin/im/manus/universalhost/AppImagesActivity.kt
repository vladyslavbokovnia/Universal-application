package im.manus.universalhost

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Замена иконок приложений: список установленных приложений, по нажатию выбирается картинка.
 * Путь к картинке лежит в настройках модуля под ключом "<key>:<пакет>".
 */
class AppImagesActivity : Activity() {

    companion object {
        const val EXTRA_MODULE = "module"
        const val EXTRA_KEY = "key"
        private const val REQ_IMAGE = 2
    }

    private class App(val pkg: String, val label: String, val icon: Drawable?)

    private lateinit var module: String
    private lateinit var key: String
    private var pendingPkg: String? = null
    private var apps: List<App> = emptyList()
    private val bitmaps = LruCache<String, Bitmap>(64)
    private val adapter = Adapter()

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val m = intent.getStringExtra(EXTRA_MODULE)
        val k = intent.getStringExtra(EXTRA_KEY)
        if (m == null || k == null) {
            finish()
            return
        }
        module = m
        key = k
        pendingPkg = savedInstanceState?.getString("pending")

        val list = ListView(this)
        list.setBackgroundColor(Color.BLACK)
        list.divider = null
        val head = TextView(this)
        head.text = "Замена иконок приложений\nНажмите на приложение, чтобы выбрать картинку"
        head.setTextColor(Color.WHITE)
        head.textSize = 16f
        head.setPadding(dp(16), dp(16), dp(16), dp(12))
        list.addHeaderView(head, null, false)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val app = apps.getOrNull(position - 1) ?: return@setOnItemClickListener
            pickImage(app.pkg)
        }
        setContentView(list)

        Thread {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val seen = HashSet<String>()
            val out = ArrayList<App>()
            for (ri in pm.queryIntentActivities(launcher, 0)) {
                val pkg = ri.activityInfo.packageName
                if (seen.add(pkg)) out.add(App(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm)))
            }
            out.sortBy { it.label.lowercase() }
            runOnUiThread {
                apps = out
                adapter.notifyDataSetChanged()
            }
        }.start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pending", pendingPkg)
    }

    private fun entryKey(pkg: String) = "$key:$pkg"

    private fun notifyModule() {
        CoreAccessibilityService.instance?.notifySettingChanged(module, key)
    }

    private fun pickImage(pkg: String) {
        pendingPkg = pkg
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
        val pkg = pendingPkg
        if (uri == null || pkg == null) return
        if (ModuleStorage.saveImage(this, module, entryKey(pkg), uri)) {
            notifyModule()
        } else {
            Toast.makeText(this, "Не удалось прочитать картинку", Toast.LENGTH_LONG).show()
        }
        adapter.notifyDataSetChanged()
    }

    private fun customBitmap(pkg: String): Bitmap? {
        val path = ModuleStorage.imagePath(this, module, entryKey(pkg)) ?: return null
        val cacheKey = path + ":" + File(path).lastModified()
        bitmaps.get(cacheKey)?.let { return it }
        val b = ModuleStorage.loadBitmap(path, 96) ?: return null
        bitmaps.put(cacheKey, b)
        return b
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val app = apps[position]
            val row = LinearLayout(this@AppImagesActivity)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(16), dp(8), dp(16), dp(8))

            val iv = ImageView(this@AppImagesActivity)
            val custom = customBitmap(app.pkg)
            if (custom != null) iv.setImageBitmap(custom) else iv.setImageDrawable(app.icon)
            row.addView(iv, LinearLayout.LayoutParams(dp(48), dp(48)))

            val tv = TextView(this@AppImagesActivity)
            tv.text = app.label
            tv.setTextColor(Color.WHITE)
            tv.textSize = 18f
            tv.setPadding(dp(14), 0, 0, 0)
            row.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))

            if (custom != null) {
                val clear = TextView(this@AppImagesActivity)
                clear.text = "Сбросить"
                clear.setTextColor(Color.WHITE)
                clear.textSize = 14f
                clear.setPadding(dp(12), dp(10), dp(12), dp(10))
                clear.setOnClickListener {
                    ModuleStorage.clearImage(this@AppImagesActivity, module, entryKey(app.pkg))
                    notifyModule()
                    notifyDataSetChanged()
                }
                row.addView(clear)
            }
            return row
        }
    }
}
