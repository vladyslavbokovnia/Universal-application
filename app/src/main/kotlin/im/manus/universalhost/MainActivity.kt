package im.manus.universalhost

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {
    
    private lateinit var pluginListContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pluginListContainer = findViewById(R.id.plugin_list_container)
        
        hideSystemUI()
        requestPermissions()
        refreshUI()
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }

    private fun refreshUI() {
        pluginListContainer.removeAllViews()
        
        val pluginDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "UniversalPlugins")
        if (!pluginDir.exists()) pluginDir.mkdirs()
        
        val loader = PluginLoader(this)
        val allPlugins = loader.getPluginsFromFolder(pluginDir.absolutePath)
        
        val prefs = getSharedPreferences("plugin_prefs", MODE_PRIVATE)
        val orderPrefs = getSharedPreferences("plugin_order", MODE_PRIVATE)
        
        // Получаем сохраненный порядок
        val savedOrder = orderPrefs.getString("order", "")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
        
        // Сортируем плагины согласно сохраненному порядку
        val sortedPlugins = allPlugins.sortedBy { plugin ->
            val index = savedOrder.indexOf(plugin.name)
            if (index != -1) index else Int.MAX_VALUE
        }

        sortedPlugins.forEachIndexed { index, plugin ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(30, 30, 30, 30)
                setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
                val params = LinearLayout.LayoutParams(-1, -2)
                params.setMargins(0, 0, 0, 10)
                layoutParams = params
            }
            
            val nameText = TextView(this).apply {
                text = plugin.name
                textSize = 20f
                setTextColor(android.graphics.Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            
            val upButton = Button(this).apply {
                text = "↑"
                layoutParams = LinearLayout.LayoutParams(120, 120)
                setOnClickListener { movePlugin(index, -1, sortedPlugins) }
            }

            val downButton = Button(this).apply {
                text = "↓"
                layoutParams = LinearLayout.LayoutParams(120, 120)
                setOnClickListener { movePlugin(index, 1, sortedPlugins) }
            }
            
            val toggle = Switch(this).apply {
                isChecked = prefs.getBoolean(plugin.name, true)
                setOnCheckedChangeListener { _, isChecked ->
                    prefs.edit().putBoolean(plugin.name, isChecked).apply()
                    CoreAccessibilityService.instance?.refreshPlugins()
                }
            }
            
            row.addView(nameText)
            row.addView(upButton)
            row.addView(downButton)
            row.addView(toggle)
            pluginListContainer.addView(row)
        }
    }

    private fun movePlugin(currentIndex: Int, direction: Int, plugins: List<IPlugin>) {
        val newIndex = currentIndex + direction
        if (newIndex in plugins.indices) {
            val mutablePlugins = plugins.toMutableList()
            val item = mutablePlugins.removeAt(currentIndex)
            mutablePlugins.add(newIndex, item)
            
            // Сохраняем новый порядок
            val orderString = mutablePlugins.joinToString(",") { it.name }
            getSharedPreferences("plugin_order", MODE_PRIVATE).edit().putString("order", orderString).apply()
            
            refreshUI()
            CoreAccessibilityService.instance?.refreshPlugins()
        }
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
        val appOps = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
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
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            }
        }
    }
}
