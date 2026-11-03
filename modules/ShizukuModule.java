package im.manus.plugins;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.accessibilityservice.AccessibilityService;
import android.widget.Toast;
import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.SettingItem;
import im.manus.universalhost.ShizukuBridge;
import im.manus.universalhost.ShizukuResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Настройки и состояние Shizuku (форк thedjchi/Shizuku, запускается сам после перезагрузки).
 * Вся работа с Shizuku выполняется в хосте (ShizukuBridge), модуль даёт её экран настроек.
 * Другие модули могут вызывать ShizukuBridge.exec("команда") из фонового потока.
 */
public class ShizukuModule implements IPlugin, ISettingsProvider {

    private static final String PREFS = "module_Shizuku";

    private Context ctx;
    private Handler main;

    @Override public String getName() { return "Shizuku"; }
    @Override public int getVersion() { return 1; }
    @Override public String getDescription() {
        return "Доступ к системе через Shizuku: состояние, разрешения, автовключение службы";
    }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override
    public void init(Context context) {
        ctx = context;
        main = new Handler(Looper.getMainLooper());
    }

    @Override
    public void stop() {
        ctx = null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event, AccessibilityService service) { }

    /** execute({"command": "..."}) — выполняет команду через Shizuku и возвращает вывод (не из главного потока). */
    @Override
    public Object execute(Map<String, ?> data) {
        if (data == null) return null;
        Object cmd = data.get("command");
        if (!(cmd instanceof String)) return null;
        ShizukuResult r = ShizukuBridge.exec((String) cmd);
        return r.getOk() ? r.getOut() : r.getErr();
    }

    // ---------------------------------------------------------------- настройки

    @Override
    public List<SettingItem> getSettingsSchema() {
        List<SettingItem> l = new ArrayList<SettingItem>();
        l.add(SettingItem.section("Shizuku"));
        l.add(SettingItem.action("status", "Состояние Shizuku", "Нажмите, чтобы проверить"));
        l.add(SettingItem.action("request", "Запросить доступ у Shizuku", "Откроется окно подтверждения"));
        l.add(SettingItem.section("Служба специальных возможностей"));
        l.add(SettingItem.action("grant_secure", "Выдать WRITE_SECURE_SETTINGS", "Через Shizuku, один раз"));
        l.add(SettingItem.toggle("keep_accessibility", "Включать службу самому",
                "После перезагрузки, обновления приложения и запуска Shizuku", false));
        return l;
    }

    @Override
    public void onSettingChanged(final String key) {
        final Context c = ctx;
        if (c == null) return;
        if ("status".equals(key)) {
            toast(ShizukuBridge.status(c));
        } else if ("request".equals(key)) {
            ShizukuBridge.requestPermission();
            toast(ShizukuBridge.isRunning()
                    ? "Подтвердите доступ в окне Shizuku"
                    : "Shizuku не запущен: сначала запустите приложение Shizuku");
        } else if ("grant_secure".equals(key)) {
            new Thread(new Runnable() {
                @Override public void run() {
                    ShizukuResult r = ShizukuBridge.grantSecureSettings(c);
                    toast(r.getOk() ? "WRITE_SECURE_SETTINGS: " + r.getOut() : "Не удалось: " + r.getErr());
                }
            }).start();
        } else if ("keep_accessibility".equals(key)) {
            boolean on = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("keep_accessibility", false);
            if (on) {
                new Thread(new Runnable() {
                    @Override public void run() { ShizukuBridge.ensureAccessibility(c); }
                }).start();
            }
        }
    }

    @Override
    public Bitmap createIcon(int sizePx) {
        return null;        // хост нарисует монограмму
    }

    private void toast(final String text) {
        final Context c = ctx;
        if (c == null || main == null) return;
        main.post(new Runnable() {
            @Override public void run() { Toast.makeText(c, text, Toast.LENGTH_LONG).show(); }
        });
    }
}
