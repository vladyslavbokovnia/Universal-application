package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Base64;
import android.view.DragEvent;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.SettingItem;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Браузер на WebView: вкладки, отдельные сессии (профили) для входа в разные аккаунты,
 * пользовательские скрипты в духе Tampermonkey.
 *
 * Окно. DEX-модуль не может объявить activity, поэтому браузер живёт внутри окна-контейнера хоста
 * ModuleBrowserActivity: хост зовёт execute("cmd"="createView"), мы возвращаем вид. Окно хоста отдельная задача
 * в списке недавних, без показа поверх блокировки, не пересоздаётся при повороте экрана; хост пробрасывает сюда
 * onActivityResult (выбор файла для загрузки на сайт). Команды хоста: createView, newIntent, back,
 * activityResult, destroyView.
 * Открыть браузер: пункт «Открыть браузер» в настройках модуля или execute({"cmd":"open","url":"...","profile":0}) из другого модуля.
 *
 * Профили. Платформенный WebView не умеет держать несколько хранилищ, поэтому используется Profile API
 * из androidx.webkit (WebViewCompat.setProfile), вызываемый по имени через рефлексию: нужен androidx.webkit
 * в самом приложении-хосте и WebView с поддержкой MULTI_PROFILE. Профиль 1 — обычное хранилище WebView.
 * Если изоляции нет, вкладки профилей 2-6 НЕ открываются (чтобы не смешать аккаунты в одном хранилище).
 *
 * Скрипты. Файлы *.user.js из Загрузки/UniversalPlugins/BrowserScripts, их можно править любым редактором,
 * изменения подхватываются при следующей загрузке страницы. Поддерживаются @match/@include/@exclude/@run-at/@grant/@connect,
 * GM_getValue/setValue/deleteValue/listValues/addValueChangeListener, GM_addStyle, GM_registerMenuCommand,
 * GM_openInTab, GM_setClipboard, GM_xmlhttpRequest (с проверкой @connect), GM_info, объект GM.* (промисы), unsafeWindow.
 * Дополнительно: GM_notification (всплывающая подсказка), GM_download, GM_addElement, GM_getTab/saveTab/getTabs (заглушки).
 * Не поддерживаются: @require, @resource, GM_cookie, выполнение внутри iframe. Скрипты с @run-at document-start
 * вставляются средствами WebView (addDocumentStartJavaScript) до скриптов страницы, а если WebView это не умеет или
 * мост ещё не готов, при завершении загрузки.
 *
 * Интерфейс. Панель вкладок: карточка со снимком страницы, под ним только значок сайта и значок профиля; вкладки можно
 * собирать в группы (долгое нажатие на карточку), группа показывается снимком своей первой вкладки. Профилей шесть.
 * Во всех меню слева значок, потом текст. Полноэкранный режим: системные панели скрыты, нижняя панель браузера прячется
 * при прокрутке вниз. Браузер по настройке открывается с панели вкладок. «Ярлык на рабочий стол» закрепляет значок
 * «Браузер»: окно открывается своей задачей со своим названием и значком в недавних.
 *
 * Карточки вкладок: снимок страницы с крупными значками сайта и профиля поверх него. Вкладки перетаскиваются: вкладка на
 * вкладку создаёт группу, вкладка на группу добавляется в неё, в открытой группе вкладку можно поставить на первое место
 * (её снимок станет обложкой группы) или вытащить из группы. Долгое нажатие и отпустить на месте открывает меню.
 * Закладки: ряд значков сайтов над карточками вкладок. Слева значок профиля, в котором откроются закладки (нажатие выбирает
 * другой профиль; по умолчанию профиль текущей вкладки), справа ⭐ добавляет текущую страницу. Нажатие на значок открывает
 * закладку в новой вкладке, долгое нажатие даёт меню (открыть в другом профиле, в фоне, переименовать, переместить, удалить).
 * Крестиков на карточках вкладок нет: вкладка закрывается смахиванием или из меню долгого нажатия.
 * Значок профиля меняется: эмодзи, номер или своя картинка (меню, «Значки профилей»). Цвет строки состояния берётся со
 * страницы (theme-color или фон). Окно браузера регистрируется в реестре окон хоста, поэтому модуль «Недавние» его показывает.
 *
 * Озвучка. WebView сам не озвучивает speechSynthesis, поэтому при включённой настройке «Озвучка для скриптов»
 * в страницы подставляются window.speechSynthesis и SpeechSynthesisUtterance поверх нативного Android TextToSpeech
 * (события start/end/error приходят из движка). Воспроизведение звука страницей разрешено без касания.
 */
public class Browser implements IPlugin, ISettingsProvider {

    private static final String PREFS = "module________";
    private static final String VALUES_PREFS = "module_________values";
    private static final String BROWSER_ACTIVITY = "im.manus.universalhost.BrowserApp";     // активити-псевдоним из манифеста: свой значок и название
    private static final int REQ_PROFILE_IMG = 7312;
    private static final int REQ_FILE = 7311;
    private static final int REQ_BM_IMG = 7313;
    private static final float HUB_ALPHA = 0.62f;
    /** Разрешает масштабирование щипком там, где страница запретила его через viewport. */
    private static final String FORCE_ZOOM_JS =
            "(function(){try{if(window.__zf)return;window.__zf=1;"
            + "function f(){try{var ms=document.querySelectorAll('meta[name=viewport i]');"
            + "for(var i=0;i<ms.length;i++){var m=ms[i],c=m.getAttribute('content')||'',n=c;"
            + "n=n.replace(/user-scalable *= *[^,; ]*/ig,'user-scalable=yes').replace(/maximum-scale *= *[^,; ]*/ig,'maximum-scale=10').replace(/minimum-scale *= *[^,; ]*/ig,'minimum-scale=0.1');"
            + "if(!/user-scalable/i.test(n))n+=(n?',':'')+'user-scalable=yes';"
            + "if(!/maximum-scale/i.test(n))n+=',maximum-scale=10';"
            + "if(n!==c)m.setAttribute('content',n);}}catch(e){}}"
            + "var pend=false;function g(){if(pend)return;pend=true;setTimeout(function(){pend=false;f();},50);}"
            + "f();var o=new MutationObserver(g);"
            + "o.observe(document,{childList:true,subtree:true,attributes:true,attributeFilter:['content']});"
            + "window.addEventListener('load',function(){f();setTimeout(function(){o.disconnect();},3000);});"
            + "}catch(e){}})();";
    private static final String SCRIPTS_SUBDIR = "UniversalPlugins/BrowserScripts";
    private static final String DEF_HOME = "https://www.google.com/";
    private static final String DEF_SEARCH = "https://www.google.com/search?q=%s";
    private static final String DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    private static final int PROFILES = 6;
    private static final String[] PROFILE_DEFAULT_NAMES = {"Основной", "Профиль 2", "Профиль 3", "Профиль 4", "Профиль 5", "Профиль 6"};
    private static final int[] PROFILE_COLORS = {0xFF4FC3F7, 0xFFFFB74D, 0xFF81C784, 0xFFBA68C8, 0xFFE57373, 0xFFFFF176};

    // тёмная тема, белый текст: читается и глазами, и экранным диктором
    private static final int C_BG = 0xFF121212;
    private static final int C_SURFACE = 0xFF1E1E1E;
    private static final int C_SURFACE2 = 0xFF2C2C2C;
    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_TEXT2 = 0xFFBDBDBD;
    private static final int C_ACCENT = 0xFF4FC3F7;

    private static final SecureRandom RNG = new SecureRandom();

    private volatile Context ctx;
    private volatile Handler main = new Handler(Looper.getMainLooper());
    private BrowserView view;

    // ---------------------------------------------------------------- IPlugin

    @Override public String getName() { return "Браузер"; }
    @Override public int getVersion() { return 1; }
    @Override public String getDescription() {
        return "Браузер: вкладки, отдельные сессии для разных аккаунтов, пользовательские скрипты";
    }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override
    public void init(Context context) {
        ctx = context;
        main = new Handler(Looper.getMainLooper());
    }

    @Override
    public void stop() {
        ctx = null;     // окно браузера живёт своей жизнью в ModuleWakeActivity и закрывается само
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event, AccessibilityService service) { }

    /**
     * Команды от ModuleWakeActivity: createView / newIntent / back / destroyView.
     * Команда другого модуля: {"cmd":"open","url":"https://...","profile":0}.
     */
    @Override
    public Object execute(Map<String, ?> data) {
        if (data == null) return null;
        Object cmd = data.get("cmd");
        if (!(cmd instanceof String)) return null;
        String c = (String) cmd;
        if ("createView".equals(c)) {
            Object a = data.get("activity");
            Object in = data.get("intent");
            if (!(a instanceof Activity)) return null;
            if (view != null) view.shutdown();
            BrowserView v = new BrowserView((Activity) a);
            view = v;
            v.start(in instanceof Intent ? (Intent) in : null);
            return v;
        }
        if ("newIntent".equals(c)) {
            Object in = data.get("intent");
            if (view != null && in instanceof Intent) view.handleIntent((Intent) in);
        } else if ("back".equals(c)) {
            if (view != null) view.saveTabs();
        } else if ("activityResult".equals(c)) {
            Object rc = data.get("requestCode");
            Object res = data.get("resultCode");
            Object d = data.get("data");
            if (view != null && rc instanceof Number && res instanceof Number) {
                view.activityResult(((Number) rc).intValue(), ((Number) res).intValue(), d instanceof Intent ? (Intent) d : null);
            }
        } else if ("destroyView".equals(c)) {
            if (view != null) view.shutdown();
            view = null;
        } else if ("open".equals(c)) {
            Object u = data.get("url");
            Object p = data.get("profile");
            return Boolean.valueOf(open(u instanceof String ? (String) u : null, p instanceof Number ? ((Number) p).intValue() : 0));
        }
        return null;
    }

    private boolean open(String url, int profile) {
        Context c = ctx;
        if (c == null) {
            toast("Модуль Browser выключен или не запущен");
            return false;
        }
        Intent i = new Intent().setClassName(c.getPackageName(), BROWSER_ACTIVITY)
                .putExtra("module", getName())
                .putExtra("stamp", SystemClock.elapsedRealtime())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (url != null && url.length() > 0) i.putExtra("url", url).putExtra("profile", profile);
        try {
            c.startActivity(i);
            return true;
        } catch (Throwable t) {
            toast("Не удалось открыть браузер: " + t);
            return false;
        }
    }

    /** Значок браузера: синий квадрат с глобусом. Нужен ярлыку и карточке окна в недавних. */
    static Bitmap makeIcon(int px) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF1E88E5);
        c.drawRoundRect(new android.graphics.RectF(0, 0, px, px), px * 0.22f, px * 0.22f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(Color.WHITE);
        p.setStrokeWidth(px * 0.06f);
        float cx = px / 2f, cy = px / 2f, r = px * 0.3f;
        c.drawCircle(cx, cy, r, p);
        c.drawOval(new android.graphics.RectF(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r), p);
        c.drawLine(cx - r, cy, cx + r, cy, p);
        return b;
    }

    /** Закрепляет на рабочем столе ярлык «Браузер»: окно хоста открывается своей задачей со своим значком и названием. */
    private void pinShortcut() {
        Context c = ctx;
        if (c == null) {
            toast("Модуль Browser выключен или не запущен");
            return;
        }
        try {
            if (Build.VERSION.SDK_INT < 26) {
                toast("Ярлыки на рабочем столе требуют Android 8 или новее");
                return;
            }
            android.content.pm.ShortcutManager sm = (android.content.pm.ShortcutManager) c.getSystemService(Context.SHORTCUT_SERVICE);
            if (sm == null || !sm.isRequestPinShortcutSupported()) {
                toast("Лаунчер не поддерживает закрепление ярлыков");
                return;
            }
            Intent i = new Intent(Intent.ACTION_VIEW).setClassName(c.getPackageName(), BROWSER_ACTIVITY)
                    .putExtra("module", getName())
                    .putExtra("shortcut", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            android.content.pm.ShortcutInfo info = new android.content.pm.ShortcutInfo.Builder(c, "browser_main")
                    .setShortLabel("Браузер")
                    .setLongLabel("Браузер")
                    .setIcon(android.graphics.drawable.Icon.createWithBitmap(makeIcon(192)))
                    .setIntent(i)
                    .build();
            sm.requestPinShortcut(info, null);
            toast("Подтвердите добавление ярлыка на рабочий стол");
        } catch (Throwable t) {
            toast("Не удалось создать ярлык: " + t);
        }
    }

    // ---------------------------------------------------------------- настройки (экран в хосте)

    @Override
    public List<SettingItem> getSettingsSchema() {
        List<SettingItem> l = new ArrayList<SettingItem>();
        l.add(SettingItem.section("Браузер"));
        l.add(SettingItem.action("open", "Открыть браузер",
                "Окно с вкладками, отдельными сессиями и скриптами. Назад: по истории, потом закрывает окно, вкладки сохраняются"));
        l.add(SettingItem.text("home_url", "Домашняя страница", "Открывается в новой вкладке", DEF_HOME));
        l.add(SettingItem.text("search_url", "Поиск", "Адрес поискового запроса, %s подставляется вместо запроса", DEF_SEARCH));
        l.add(SettingItem.text("user_agent", "User-Agent",
                "Пусто: стандартный (без метки «wv»). Если сайт, например вход Google, не пускает во встроенный браузер, впишите строку мобильного Chrome", ""));
        l.add(SettingItem.toggle("restore_tabs", "Восстанавливать вкладки", "При следующем открытии вернуть вкладки, которые были открыты", true));
        l.add(SettingItem.toggle("keep_screen_on", "Не гасить экран", "Пока открыт браузер", false));
        l.add(SettingItem.toggle("fullscreen", "Полноэкранный режим",
                "Системные панели скрыты", false));
        l.add(SettingItem.toggle("autohide_dock", "Прятать кнопку при прокрутке",
                "Кнопка прячется, когда страницу листают вниз, и возвращается при прокрутке вверх или в начале страницы", true));
        l.add(SettingItem.toggle("force_zoom", "Масштабирование на всех сайтах",
                "Разрешает масштаб щипком и там, где страница запретила его (user-scalable=no); применяется при загрузке страницы", true));
        l.add(SettingItem.toggle("open_tabs_first", "Открывать с панели вкладок", "При запуске браузера сначала показывать панель вкладок", true));
        l.add(SettingItem.action("pin_shortcut", "Ярлык на рабочий стол",
                "Отдельный значок «Браузер»: окно открывается как самостоятельное приложение со своей карточкой в недавних"));
        l.add(SettingItem.toggle("tts_bridge", "Озвучка для скриптов",
                "Подставляет в страницы speechSynthesis поверх голоса Android, иначе скрипты в WebView молчат. Применяется при следующей загрузке страницы", true));

        l.add(SettingItem.section("Профили: отдельные сессии для входа в разные аккаунты"));
        for (int i = 0; i < PROFILES; i++) {
            l.add(SettingItem.text("profile_name_" + i, "Профиль " + (i + 1),
                    i == 0 ? "Обычное хранилище WebView, работает всегда" : "Свои cookies и хранилище, нужна поддержка изоляции",
                    PROFILE_DEFAULT_NAMES[i]));
        }
        l.add(SettingItem.action("isolation_status", "Проверить изоляцию сессий", "Покажет, работают ли профили 2-6 на этом устройстве"));

        l.add(SettingItem.section("Скрипты"));
        File dir = ScriptStore.dir();
        List<UserScript> scripts = ScriptStore.list();
        l.add(SettingItem.action("scripts_info", "Папка скриптов",
                dir.getPath() + ", найдено скриптов: " + scripts.size() + ". Файлы *.user.js можно править любым редактором"));
        l.add(SettingItem.text("install_url", "Ссылка на скрипт", "Адрес файла .user.js, например raw.githubusercontent.com/…", ""));
        l.add(SettingItem.action("install", "Установить или обновить по ссылке", "Скачивает скрипт по ссылке выше в папку скриптов"));
        l.add(SettingItem.action("update_all", "Обновить все скрипты", "По @downloadURL / @updateURL, только https"));
        l.add(SettingItem.toggle("auto_update", "Обновлять скрипты сами", "Раз в сутки при открытии браузера", true));
        for (UserScript s : scripts) {
            StringBuilder sum = new StringBuilder(s.runAt);
            List<String> where = s.match.isEmpty() ? s.include : s.match;
            for (int i = 0; i < where.size() && i < 2; i++) sum.append(i == 0 ? ": " : ", ").append(where.get(i));
            if (where.isEmpty()) sum.append(": без @match, не запустится");
            l.add(SettingItem.toggle("script_on:" + s.fileName, s.name + (s.version.length() > 0 ? " " + s.version : ""),
                    sum.toString(), true));
        }
        return l;
    }

    @Override
    public void onSettingChanged(String key) {
        SharedPreferences p = prefs();
        if ("open".equals(key)) {
            open(null, 0);
        } else if ("pin_shortcut".equals(key)) {
            pinShortcut();
        } else if ("isolation_status".equals(key)) {
            toast(Profiles.status());
        } else if ("scripts_info".equals(key)) {
            toast("Папка скриптов: " + ScriptStore.dir().getPath());
        } else if ("install".equals(key)) {
            final String url = p == null ? "" : p.getString("install_url", "").trim();
            new Thread(new Runnable() {
                @Override public void run() { toast(ScriptStore.install(url)); }
            }).start();
        } else if ("update_all".equals(key)) {
            new Thread(new Runnable() {
                @Override public void run() { toast(ScriptStore.updateAll()); }
            }).start();
        } else if ("user_agent".equals(key) || "keep_screen_on".equals(key) || "tts_bridge".equals(key) || "fullscreen".equals(key)) {
            final BrowserView v = view;
            if (v != null) main.post(new Runnable() {
                @Override public void run() { v.applySettingsLive(); }
            });
        }
    }

    @Override
    public Bitmap createIcon(int sizePx) {
        return makeIcon(Math.max(48, sizePx));
    }

    private SharedPreferences prefs() {
        Context c = ctx;
        return c == null ? null : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void toast(final String text) {
        final Context c = ctx;
        final Handler h = main;
        if (c == null || h == null) return;
        h.post(new Runnable() {
            @Override public void run() { Toast.makeText(c, text, Toast.LENGTH_LONG).show(); }
        });
    }

    // ---------------------------------------------------------------- общие мелочи

    private static String randHex(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return sb.toString();
    }

    private static String hostOf(String url) {
        if (url == null) return "";
        try {
            String h = Uri.parse(url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String stripFragment(String url) {
        int i = url.indexOf('#');
        return i < 0 ? url : url.substring(0, i);
    }

    /** JSON, безопасный для вставки в JS: U+2028 и U+2029 в старых движках рвут строковый литерал. */
    private static String forJs(String json) {
        return json.replace(String.valueOf((char) 0x2028), "\\u2028").replace(String.valueOf((char) 0x2029), "\\u2029");
    }

    private static String q(String s) {
        return s == null ? "null" : forJs(JSONObject.quote(s));
    }

    private static int compareVersions(String a, String b) {
        String[] x = a.split("[^0-9]+");
        String[] y = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = 0, r = 0;
            try { if (i < x.length && x[i].length() > 0) p = Long.parseLong(x[i]); } catch (NumberFormatException ignored) { }
            try { if (i < y.length && y[i].length() > 0) r = Long.parseLong(y[i]); } catch (NumberFormatException ignored) { }
            if (p != r) return p > r ? 1 : -1;
        }
        return 0;
    }

    // ---------------------------------------------------------------- сеть

    private static final class Net {
        static byte[] readAll(InputStream in, int max) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > max) throw new IOException("ответ больше " + (max / 1024) + " КБ");
            }
            return out.toByteArray();
        }

        /** Скачивает текст (UTF-8), редиректы идут вручную, схема только http/https, при httpsOnly и на редиректах тоже. */
        static String fetchText(String url, int maxBytes, boolean httpsOnly) throws IOException {
            String cur = url;
            for (int hop = 0; hop < 6; hop++) {
                URL u = new URL(cur);
                String proto = u.getProtocol();
                if (!(proto.equals("https") || (!httpsOnly && proto.equals("http")))) throw new IOException("нужна ссылка https");
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                try {
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(20000);
                    c.setRequestProperty("User-Agent", "UniversalBrowser/1");
                    int code = c.getResponseCode();
                    if (code >= 300 && code < 400) {
                        String loc = c.getHeaderField("Location");
                        if (loc == null) throw new IOException("редирект без адреса");
                        cur = new URL(u, loc).toString();
                        continue;
                    }
                    if (code != 200) throw new IOException("HTTP " + code);
                    return new String(readAll(c.getInputStream(), maxBytes), Charset.forName("UTF-8"));
                } finally {
                    c.disconnect();
                }
            }
            throw new IOException("слишком много редиректов");
        }
    }

    // ---------------------------------------------------------------- профили (отдельные сессии)

    private static final class Profiles {
        private static Boolean supportedCache;

        static String backendName(int idx) { return "ub_acc" + idx; }

        /** WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) через рефлексию: androidx.webkit лежит в хосте, не в модуле. */
        static synchronized boolean supported() {
            if (supportedCache != null) return supportedCache.booleanValue();
            boolean ok = false;
            try {
                ClassLoader cl = Browser.class.getClassLoader();
                Class<?> f = Class.forName("androidx.webkit.WebViewFeature", true, cl);
                Object feature = f.getField("MULTI_PROFILE").get(null);
                Method m = f.getMethod("isFeatureSupported", String.class);
                ok = Boolean.TRUE.equals(m.invoke(null, feature));
            } catch (Throwable ignored) {
                ok = false;
            }
            supportedCache = Boolean.valueOf(ok);
            return ok;
        }

        /** Привязывает WebView к профилю; вызывать до первой загрузки. Для профиля 0 ничего не делает. */
        static boolean apply(WebView w, int idx) {
            if (idx <= 0) return true;
            if (!supported()) return false;
            try {
                Class<?> c = Class.forName("androidx.webkit.WebViewCompat", true, Browser.class.getClassLoader());
                c.getMethod("setProfile", WebView.class, String.class).invoke(null, w, backendName(idx));
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        static String status() {
            if (supported()) return "Отдельные сессии работают: профили 2-6 имеют свои cookies и хранилище";
            return "Отдельные сессии недоступны: в приложении-хосте нет androidx.webkit или WebView устройства не поддерживает "
                    + "профили. Профиль 1 работает, вкладки профилей 2-6 не открываются, чтобы аккаунты не смешались";
        }
    }

    // ---------------------------------------------------------------- пользовательские скрипты

    private static final class UserScript {
        File file;
        String fileName;
        long modified;
        long size;
        String source;
        String name;
        String namespace = "";
        String version = "";
        String description = "";
        String runAt = "document-idle";
        String updateUrl;
        String downloadUrl;
        boolean noFrames;
        final List<String> match = new ArrayList<String>();
        final List<String> include = new ArrayList<String>();
        final List<String> exclude = new ArrayList<String>();
        final List<String> excludeMatch = new ArrayList<String>();
        final List<String> grant = new ArrayList<String>();
        final List<String> connect = new ArrayList<String>();
        private List<Pattern> matchP, includeP, excludeP;

        String id() { return namespace + "|" + name; }

        static UserScript parse(String fileName, String text) {
            int a = text.indexOf("==UserScript==");
            int b = text.indexOf("==/UserScript==");
            if (a < 0 || b < a) return null;
            UserScript s = new UserScript();
            s.fileName = fileName;
            s.source = text;
            for (String line : text.substring(a + 14, b).split("\r?\n")) {
                String t = line.trim();
                if (!t.startsWith("//")) continue;
                t = t.substring(2).trim();
                if (!t.startsWith("@")) continue;
                int sp = 1;
                while (sp < t.length() && !Character.isWhitespace(t.charAt(sp))) sp++;
                String key = t.substring(1, sp);
                String val = t.substring(sp).trim();
                if (key.equals("name") && s.name == null) s.name = val;
                else if (key.equals("namespace")) s.namespace = val;
                else if (key.equals("version")) s.version = val;
                else if (key.equals("description") && s.description.length() == 0) s.description = val;
                else if (key.equals("match")) s.match.add(val);
                else if (key.equals("include")) s.include.add(val);
                else if (key.equals("exclude")) s.exclude.add(val);
                else if (key.equals("exclude-match")) s.excludeMatch.add(val);
                else if (key.equals("grant")) s.grant.add(val);
                else if (key.equals("connect")) s.connect.add(val.toLowerCase(Locale.ROOT));
                else if (key.equals("run-at")) s.runAt = val;
                else if (key.equals("noframes")) s.noFrames = true;
                else if (key.equals("updateURL")) s.updateUrl = val;
                else if (key.equals("downloadURL")) s.downloadUrl = val;
            }
            if (s.name == null || s.name.length() == 0) {
                s.name = fileName.toLowerCase(Locale.ROOT).endsWith(".user.js") ? fileName.substring(0, fileName.length() - 8) : fileName;
            }
            return s;
        }

        /** @match, затем @include; без обоих скрипт не запускается нигде. */
        synchronized boolean applies(String url) {
            if (matchP == null) {
                matchP = new ArrayList<Pattern>();
                includeP = new ArrayList<Pattern>();
                excludeP = new ArrayList<Pattern>();
                for (String m : match) addIfNotNull(matchP, matchToRegex(m));
                for (String g : include) addIfNotNull(includeP, includeToRegex(g));
                for (String g : exclude) addIfNotNull(excludeP, includeToRegex(g));
                for (String m : excludeMatch) addIfNotNull(excludeP, matchToRegex(m));
            }
            String u = stripFragment(url);
            boolean ok = false;
            for (Pattern p : matchP) if (p.matcher(u).matches()) { ok = true; break; }
            if (!ok) for (Pattern p : includeP) if (hit(p, u)) { ok = true; break; }
            if (!ok) return false;
            for (Pattern p : excludeP) if (hit(p, u)) return false;
            return true;
        }

        /** Регулярки /.../ ищутся в любом месте URL (как в Violentmonkey), глобы и @match сравниваются целиком. */
        private static final java.util.Set<Pattern> REGEX = java.util.Collections.synchronizedSet(
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Pattern, Boolean>()));
        private static boolean hit(Pattern p, String u) {
            return REGEX.contains(p) ? p.matcher(u).find() : p.matcher(u).matches();
        }

        private static void addIfNotNull(List<Pattern> l, Pattern p) { if (p != null) l.add(p); }

        private static String glob(String g, String star) {
            StringBuilder sb = new StringBuilder();
            int last = 0;
            for (int i = 0; i < g.length(); i++) {
                if (g.charAt(i) != '*') continue;
                if (i > last) sb.append(Pattern.quote(g.substring(last, i)));
                sb.append(star);
                last = i + 1;
            }
            if (last < g.length()) sb.append(Pattern.quote(g.substring(last)));
            return sb.toString();
        }

        static Pattern matchToRegex(String m) {
            try {
                if (m.equals("<all_urls>")) return Pattern.compile("^https?://.*$", Pattern.CASE_INSENSITIVE);
                int i = m.indexOf("://");
                if (i < 0) return null;
                String scheme = m.substring(0, i);
                String rest = m.substring(i + 3);
                int slash = rest.indexOf('/');
                String host = slash < 0 ? rest : rest.substring(0, slash);
                String path = slash < 0 ? "/*" : rest.substring(slash);
                StringBuilder sb = new StringBuilder("^");
                sb.append(scheme.equals("*") ? "https?" : Pattern.quote(scheme)).append("://");
                if (host.equals("*")) sb.append("[^/]*");
                else if (host.startsWith("*.")) sb.append("(?:[^/]*\\.)?").append(Pattern.quote(host.substring(2)));
                else sb.append(glob(host, "[^/]*"));
                sb.append(glob(path, ".*")).append("$");
                return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
            } catch (Throwable t) {
                return null;
            }
        }

        /** @include: /regex/ или /regex/i как есть, иначе glob со звёздочками. */
        static Pattern includeToRegex(String g) {
            try {
                if (g.length() >= 2 && g.startsWith("/") && (g.endsWith("/") || g.endsWith("/i"))) {
                    int end = g.lastIndexOf('/');
                    Pattern rp = Pattern.compile(g.substring(1, end), g.endsWith("/i") ? Pattern.CASE_INSENSITIVE : 0);
                    REGEX.add(rp);
                    return rp;
                }
                return Pattern.compile("^" + glob(g, ".*") + "$", Pattern.CASE_INSENSITIVE);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static final class ScriptStore {
        private static final Map<String, UserScript> cache = new HashMap<String, UserScript>();

        @SuppressWarnings("deprecation")
        static File dir() {
            return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SCRIPTS_SUBDIR);
        }

        static synchronized List<UserScript> list() {
            List<UserScript> out = new ArrayList<UserScript>();
            File[] fs = dir().listFiles(new FilenameFilter() {
                @Override public boolean accept(File d, String n) { return n.toLowerCase(Locale.ROOT).endsWith(".user.js"); }
            });
            if (fs == null) return out;
            Arrays.sort(fs, new Comparator<File>() {
                @Override public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); }
            });
            java.util.HashSet<String> seen = new java.util.HashSet<String>();
            for (File f : fs) {
                String path = f.getAbsolutePath();
                seen.add(path);
                UserScript s = cache.get(path);
                if (s == null || s.modified != f.lastModified() || s.size != f.length()) {
                    s = null;
                    try {
                        if (f.length() <= 2 * 1024 * 1024) {
                            s = UserScript.parse(f.getName(), new String(readFile(f), Charset.forName("UTF-8")));
                        }
                    } catch (IOException ignored) { }
                    if (s != null) {
                        s.file = f;
                        s.modified = f.lastModified();
                        s.size = f.length();
                    }
                    if (s == null) cache.remove(path); else cache.put(path, s);
                }
                if (s != null) out.add(s);
            }
            cache.keySet().retainAll(seen);
            return out;
        }

        private static byte[] readFile(File f) throws IOException {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                return Net.readAll(in, 2 * 1024 * 1024);
            } finally {
                in.close();
            }
        }

        private static void writeFile(File f, String text) throws IOException {
            OutputStream out = new FileOutputStream(f);
            try {
                out.write(text.getBytes(Charset.forName("UTF-8")));
            } finally {
                out.close();
            }
        }

        private static String safeName(String name) {
            String n = name.replaceAll("[^\\p{L}\\p{N}._-]+", "_");
            while (n.startsWith(".")) n = n.substring(1);
            if (n.length() > 60) n = n.substring(0, 60);
            return n.length() == 0 ? "script" : n;
        }

        /** Скачивает скрипт по ссылке в папку скриптов; при совпадении @name+@namespace заменяет существующий файл. */
        static String install(String url) {
            if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
                return "Вставьте ссылку http(s) на файл .user.js";
            }
            try {
                String text = Net.fetchText(url, 2 * 1024 * 1024, false);
                UserScript s = UserScript.parse("x.user.js", text);
                if (s == null) return "Это не скрипт: в файле нет блока ==UserScript==";
                File d = dir();
                if (!d.exists() && !d.mkdirs()) return "Нет доступа к папке " + d + " (нужен доступ ко всем файлам)";
                File target = null;
                for (UserScript e : list()) if (e.id().equals(s.id())) { target = e.file; break; }
                if (target == null) target = new File(d, safeName(s.name) + ".user.js");
                writeFile(target, text);
                return "Скрипт установлен: " + s.name + (s.version.length() > 0 ? " " + s.version : "");
            } catch (IOException e) {
                return "Не удалось установить скрипт: " + e.getMessage();
            }
        }

        static String updateAll() {
            int total = 0, updated = 0, failed = 0;
            for (UserScript s : list()) {
                String u = s.downloadUrl != null ? s.downloadUrl : s.updateUrl;
                if (u == null) continue;
                total++;
                try {
                    String text = Net.fetchText(u, 2 * 1024 * 1024, true);
                    UserScript n = UserScript.parse(s.fileName, text);
                    if (n == null) { failed++; continue; }
                    if (compareVersions(n.version, s.version) > 0) {
                        writeFile(s.file, text);
                        updated++;
                    }
                } catch (IOException e) {
                    failed++;
                }
            }
            if (total == 0) return "Нет скриптов с @downloadURL или @updateURL";
            return "Скрипты: обновлено " + updated + " из " + total + (failed > 0 ? ", ошибок: " + failed : "");
        }
    }

    /** Один запуск скрипта в одной загруженной странице: ключ (nonce) выдаётся странице и проверяется при каждом вызове моста. */
    private static final class Injected {
        final UserScript script;
        final String nonce = randHex(12);
        final String disp = "ud" + randHex(8);
        final String guard = "ug" + randHex(8);
        boolean persistent;     // зарегистрирован как document-start скрипт вкладки: живёт между загрузками страниц
        Injected(UserScript s) { script = s; }
    }

    private static final class MenuCmd {
        final Injected inj;
        final int id;
        final String name;
        MenuCmd(Injected inj, int id, String name) { this.inj = inj; this.id = id; this.name = name; }
    }

    private static String buildInfo(UserScript s) {
        try {
            JSONObject sc = new JSONObject();
            sc.put("name", s.name);
            sc.put("namespace", s.namespace);
            sc.put("version", s.version);
            sc.put("description", s.description);
            sc.put("run-at", s.runAt);
            sc.put("grant", new JSONArray(s.grant));
            sc.put("matches", new JSONArray(s.match));
            sc.put("includes", new JSONArray(s.include));
            sc.put("excludes", new JSONArray(s.exclude));
            sc.put("connects", new JSONArray(s.connect));
            JSONObject info = new JSONObject();
            info.put("script", sc);
            info.put("scriptHandler", "UniversalBrowser");
            info.put("version", "1");
            info.put("isIncognito", false);
            return forJs(info.toString());
        } catch (JSONException e) {
            return "{}";
        }
    }

    /** Исходник скрипта кладётся прямо в тело функции: "use strict" в начале скрипта продолжает работать. */
    private static String buildScript(UserScript s, Injected inj, String bridge) {
        int at = SHIM.indexOf("@@BODY@@");
        String head = fill(SHIM.substring(0, at), s, inj, bridge);
        String tail = fill(SHIM.substring(at + 8), s, inj, bridge);
        return head + s.source + "\n" + tail;
    }

    private static String fill(String t, UserScript s, Injected inj, String bridge) {
        return t.replace("@@BR@@", bridge).replace("@@NO@@", inj.nonce).replace("@@DP@@", inj.disp)
                .replace("@@GD@@", inj.guard).replace("@@INFO@@", buildInfo(s));
    }

    /** JS-прослойка GM_*: собрана из shim.js (проверена под Node). Маркеры @@..@@ подставляет buildScript(). */
    private static final String SHIM =
            "(function(){\n" +
            "var BR='@@BR@@',NO='@@NO@@',DP='@@DP@@',GD='@@GD@@';\n" +
            "var B=window[BR];\n" +
            "if(!B||window[GD])return;\n" +
            "try{Object.defineProperty(window,GD,{value:1,configurable:true});}catch(e){window[GD]=1;}\n" +
            "var info=@@INFO@@;\n" +
            "var menu={},menuN=0,xhr={},xhrN=0,vl={},vlN=0;\n" +
            "function jp(s,d){if(s===null||s===undefined)return d;try{return JSON.parse(s);}catch(e){return d;}}\n" +
            "function GM_getValue(k,d){var r=B.getValue(NO,String(k));return(r===null||r===undefined)?d:jp(r,d);}\n" +
            "function GM_setValue(k,v){B.setValue(NO,String(k),JSON.stringify(v===undefined?null:v));}\n" +
            "function GM_deleteValue(k){B.deleteValue(NO,String(k));}\n" +
            "function GM_listValues(){return jp(B.listValues(NO),[]);}\n" +
            "function GM_addValueChangeListener(k,f){var id=++vlN;vl[id]={k:String(k),f:f};return id;}\n" +
            "function GM_removeValueChangeListener(id){delete vl[id];}\n" +
            "function GM_addStyle(css){var s=document.createElement('style');s.textContent=css;(document.head||document.documentElement).appendChild(s);return s;}\n" +
            "function GM_registerMenuCommand(n,f){var id=++menuN;menu[id]=f;B.registerMenu(NO,id,String(n));return id;}\n" +
            "function GM_unregisterMenuCommand(id){delete menu[id];B.unregisterMenu(NO,id);}\n" +
            "function GM_openInTab(u,o){var bg=false;if(typeof o==='boolean')bg=o;else if(o&&(o.active===false||o.background))bg=true;B.openTab(NO,String(u),bg);return{close:function(){},closed:false};}\n" +
            "function GM_setClipboard(t){B.setClipboard(NO,String(t));}\n" +
            "function GM_log(){try{console.log.apply(console,arguments);}catch(e){}}\n" +
            "function GM_notification(d,t,i,cb){var o=(typeof d==='object'&&d)?d:{text:String(d),title:t,image:i,onclick:cb};B.notif(NO,String(o.title||''),String(o.text||''));if(o.ondone){try{o.ondone();}catch(e){}}}\n" +
            "function GM_download(d,n){var o=(typeof d==='object'&&d)?d:{url:d,name:n};B.dl(NO,String(o.url),String(o.name||''));if(o.onload){setTimeout(function(){try{o.onload();}catch(e){}},0);}return{abort:function(){}};}\n" +
            "function GM_addElement(a,b,c){var p,tag,at;if(typeof a==='string'){p=document.head||document.documentElement;tag=a;at=b;}else{p=a||document.head||document.documentElement;tag=b;at=c;}var e=document.createElement(tag);for(var k in (at||{})){if(k==='textContent')e.textContent=at[k];else e.setAttribute(k,at[k]);}p.appendChild(e);return e;}\n" +
            "function GM_getResourceText(){return null;}\n" +
            "function GM_getResourceURL(){return null;}\n" +
            "function GM_getTab(cb){try{cb({});}catch(e){}}\n" +
            "function GM_saveTab(){}\n" +
            "function GM_getTabs(cb){try{cb({});}catch(e){}}\n" +
            "function GM_xmlhttpRequest(d){\n" +
            "var id=++xhrN;xhr[id]=d;\n" +
            "var data=null,b64=null,x=d.data;\n" +
            "if(x!==undefined&&x!==null){var u8=null;if(x instanceof ArrayBuffer)u8=new Uint8Array(x);else if(ArrayBuffer.isView(x))u8=new Uint8Array(x.buffer,x.byteOffset,x.byteLength);\n" +
            "if(u8){var bs='';for(var bi=0;bi<u8.length;bi+=8192)bs+=String.fromCharCode.apply(null,u8.subarray(bi,bi+8192));b64=btoa(bs);}else data=String(x);}\n" +
            "B.xhr(NO,id,JSON.stringify({method:String(d.method||'GET').toUpperCase(),url:String(d.url),headers:d.headers||{},data:data,b64:b64,timeout:d.timeout||0,responseType:d.responseType||''}));\n" +
            "return{abort:function(){B.xhrAbort(NO,id);}};\n" +
            "}\n" +
            "function b64ab(s){var bin=atob(s),n=bin.length,u=new Uint8Array(n);for(var i=0;i<n;i++)u[i]=bin.charCodeAt(i);return u.buffer;}\n" +
            "var D={\n" +
            "m:function(id){var f=menu[id];if(f){try{f();}catch(e){console.error(e);}}},\n" +
            "v:function(k,o,n,r){for(var i in vl){var l=vl[i];if(l.k===k){try{l.f(k,jp(o,undefined),jp(n,undefined),!!r);}catch(e){console.error(e);}}}},\n" +
            "x:function(id,r){\n" +
            "var d=xhr[id];if(!d)return;delete xhr[id];\n" +
            "var resp={readyState:4,status:r.status||0,statusText:r.statusText||'',responseHeaders:r.headers||'',finalUrl:r.finalUrl||d.url,responseText:'',response:null,context:d.context};\n" +
            "if(r.kind==='load'){\n" +
            "if(r.b64!==undefined){var ab=b64ab(r.b64);resp.response=(d.responseType==='blob')?new Blob([ab],{type:r.mime||''}):ab;}\n" +
            "else{resp.responseText=r.text||'';resp.response=(d.responseType==='json')?jp(resp.responseText,null):resp.responseText;}\n" +
            "}\n" +
            "var cb=r.kind==='load'?d.onload:r.kind==='timeout'?d.ontimeout:r.kind==='abort'?d.onabort:d.onerror;\n" +
            "if(r.kind==='error')resp.error=r.error||'error';\n" +
            "try{if(d.onreadystatechange)d.onreadystatechange(resp);if(cb)cb(resp);if(d.onloadend)d.onloadend(resp);}catch(e){console.error(e);}\n" +
            "}};\n" +
            "try{Object.defineProperty(window,DP,{value:D,configurable:true});}catch(e){}\n" +
            "function pr(f){return function(){var a=arguments;return new Promise(function(res,rej){try{res(f.apply(null,a));}catch(e){rej(e);}});};}\n" +
            "var GM={info:info,getValue:pr(GM_getValue),setValue:pr(GM_setValue),deleteValue:pr(GM_deleteValue),listValues:pr(GM_listValues),addStyle:pr(GM_addStyle),registerMenuCommand:GM_registerMenuCommand,unregisterMenuCommand:GM_unregisterMenuCommand,openInTab:GM_openInTab,setClipboard:pr(GM_setClipboard),log:GM_log,\n" +
            "xmlHttpRequest:function(d){return new Promise(function(res,rej){var o={};for(var k in d)o[k]=d[k];var l=d.onload,e=d.onerror;o.onload=function(r){if(l)l(r);res(r);};o.onerror=function(r){if(e)e(r);rej(r);};GM_xmlhttpRequest(o);});}};\n" +
            "GM.xmlhttpRequest=GM.xmlHttpRequest;\n" +
            "GM.addValueChangeListener=GM_addValueChangeListener;GM.removeValueChangeListener=GM_removeValueChangeListener;GM.notification=GM_notification;GM.download=GM_download;GM.addElement=GM_addElement;GM.getResourceUrl=pr(GM_getResourceURL);GM.getResourceText=pr(GM_getResourceText);GM.getTab=pr(GM_getTab);GM.saveTab=GM_saveTab;GM.getTabs=pr(GM_getTabs);\n" +
            "var run=function(GM_info,GM_getValue,GM_setValue,GM_deleteValue,GM_listValues,GM_addValueChangeListener,GM_removeValueChangeListener,GM_addStyle,GM_registerMenuCommand,GM_unregisterMenuCommand,GM_openInTab,GM_setClipboard,GM_xmlhttpRequest,GM_log,GM,unsafeWindow,GM_notification,GM_download,GM_addElement,GM_getResourceText,GM_getResourceURL,GM_getResourceUrl,GM_getTab,GM_saveTab,GM_getTabs){\n" +
            "@@BODY@@\n" +
            "};\n" +
            "try{run.call(window,info,GM_getValue,GM_setValue,GM_deleteValue,GM_listValues,GM_addValueChangeListener,GM_removeValueChangeListener,GM_addStyle,GM_registerMenuCommand,GM_unregisterMenuCommand,GM_openInTab,GM_setClipboard,GM_xmlhttpRequest,GM_log,GM,window,GM_notification,GM_download,GM_addElement,GM_getResourceText,GM_getResourceURL,GM_getResourceURL,GM_getTab,GM_saveTab,GM_getTabs);}catch(e){console.error('[UserScript]',e);}\n" +
            "})();\n";

    /**
     * Подмена speechSynthesis поверх нативного TextToSpeech. Маркер @@TTS@@ заменяется именем моста вкладки.
     * Повторная вставка в тот же документ безвредна (защита __ubTts).
     */
    private static final String TTS_POLYFILL =
            "(function(){var B=window['@@TTS@@'];if(!B||window.__ubTts)return;\n" +
            "function def(o,k,v){try{Object.defineProperty(o,k,{value:v,configurable:true,writable:true});}catch(e){}}\n" +
            "def(window,'__ubTts',1);\n" +
            "var live={},held=[],cnt=0,voices=null,paused=false;\n" +
            "class U extends EventTarget{constructor(t){super();this.text=t===undefined?'':String(t);this.lang='';this.voice=null;this.volume=1;this.rate=1;this.pitch=1;\n" +
            "this.onstart=this.onend=this.onerror=this.onpause=this.onresume=this.onboundary=this.onmark=null;}}\n" +
            "function fire(u,type,err){var e=new Event(type);e.utterance=u;e.charIndex=0;e.elapsedTime=0;e.name='';if(err)e.error=err;\n" +
            "try{if(typeof u['on'+type]==='function')u['on'+type].call(u,e);}catch(x){console.error(x);}try{u.dispatchEvent(e);}catch(x){}}\n" +
            "function any(){for(var k in live)return true;return false;}\n" +
            "var S=new EventTarget();S.onvoiceschanged=null;\n" +
            "S.speak=function(u){var id='@@TTS@@_'+(++cnt);live[id]=u;\n" +
            "B.speak(id,u.text,(u.voice&&u.voice.lang)||u.lang||'',u.rate,u.pitch);};\n" +
            "S.cancel=function(){var l=live;live={};held=[];paused=false;B.cancel();for(var k in l)fire(l[k],'error','canceled');};\n" +
            "S.pause=function(){if(paused||!any())return;paused=true;held=[];for(var k in live)held.push(live[k]);live={};B.cancel();if(held[0])fire(held[0],'pause');};\n" +
            "S.resume=function(){if(!paused)return;paused=false;var h=held;held=[];h.forEach(function(u){S.speak(u);});if(h[0])fire(h[0],'resume');};\n" +
            "S.getVoices=function(){if(voices&&voices.length)return voices;var a=[];try{a=JSON.parse(B.voices());}catch(e){}\n" +
            "voices=a.map(function(v){return{name:v.n,lang:v.l,localService:true,default:false,voiceURI:v.n};});return voices;};\n" +
            "Object.defineProperty(S,'speaking',{get:function(){return any();}});\n" +
            "Object.defineProperty(S,'pending',{get:function(){return false;}});\n" +
            "Object.defineProperty(S,'paused',{get:function(){return paused;}});\n" +
            "def(window,'__ubTtsEv',function(id,kind){\n" +
            "if(kind==='voices'){voices=null;var e=new Event('voiceschanged');try{if(S.onvoiceschanged)S.onvoiceschanged(e);S.dispatchEvent(e);}catch(x){}return;}\n" +
            "var u=live[id];if(!u)return;if(kind!=='start')delete live[id];\n" +
            "fire(u,kind,kind==='error'?'synthesis-failed':null);});\n" +
            "def(window,'SpeechSynthesisUtterance',U);def(window,'speechSynthesis',S);\n" +
            "})();\n";

    // ---------------------------------------------------------------- вкладка и мелкие вью

    private static final class Tab {
        final int id;
        String url;
        String title = "";
        int profile;
        boolean blocked;
        boolean loading;
        boolean desktop;
        boolean popup;
        int progress = 100;
        WebView wv;
        Bundle saved;
        Bitmap thumb;
        Bitmap icon;
        String uid = randHex(5);
        String iconHost;
        int bar;                // цвет строки состояния для этой страницы, 0: не определён
        int group;              // 0: вне группы
        long lastUsed;
        Tab opener;
        JsResult pendingJs;
        final String bridgeName = "ub" + randHex(6);
        final String ttsName = "ut" + randHex(6);
        Object ttsDocumentStartScript;
        final Map<String, Object[]> docStart = new HashMap<String, Object[]>();    // файл скрипта -> {версия, ScriptHandler, Injected}
        final Map<String, Injected> injected = new ConcurrentHashMap<String, Injected>();
        final Map<String, Injected> byScript = new ConcurrentHashMap<String, Injected>();
        final List<MenuCmd> menu = new CopyOnWriteArrayList<MenuCmd>();
        final Map<String, HttpURLConnection> conns = new ConcurrentHashMap<String, HttpURLConnection>();
        final Set<String> aborted = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

        Tab(int id, String url, int profile) {
            this.id = id;
            this.url = url;
            this.profile = profile;
        }

        /** Новая страница: запуски обычных скриптов забываются, document-start скрипты вкладки остаются. */
        void resetScripts() {
            for (Injected i : new ArrayList<Injected>(injected.values())) if (!i.persistent) injected.remove(i.nonce);
            for (Map.Entry<String, Injected> e : new ArrayList<Map.Entry<String, Injected>>(byScript.entrySet())) {
                if (!e.getValue().persistent) byScript.remove(e.getKey());
            }
            menu.clear();
        }

        void clearScripts() {
            injected.clear();
            byScript.clear();
            menu.clear();
            docStart.clear();
        }
    }

    /** Группа вкладок: вкладки ссылаются на неё номером, порядок вкладок общий. */
    private static final class Group {
        final int id;
        String name;
        Group(int id, String name) { this.id = id; this.name = name; }
    }

    /** Тонкая полоса над панелью: цвет профиля, поверх неё прогресс загрузки. */
    private static final class Bar extends View {
        private final Paint p = new Paint();
        private int progress = 100;
        private int color = C_ACCENT;

        Bar(Context c) {
            super(c);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void set(int pr, int col) {
            progress = pr;
            color = col;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            p.setColor((color & 0x00FFFFFF) | 0x66000000);
            c.drawRect(0, 0, getWidth(), getHeight(), p);
            if (progress < 100) {
                p.setColor(color);
                c.drawRect(0, 0, getWidth() * progress / 100f, getHeight(), p);
            }
        }
    }

    /** ScrollView с потолком высоты: окна-панели не вылезают за экран. */
    private static final class MaxScroll extends ScrollView {
        private final int maxHeight;

        MaxScroll(Context c, int maxHeight) {
            super(c);
            this.maxHeight = maxHeight;
        }

        @Override protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
        }
    }

    private interface Pick { void on(int index); }

    // ---------------------------------------------------------------- окно браузера (вид внутри ModuleWakeActivity)

    private final class BrowserView extends FrameLayout {
        private final Activity act;
        private final SharedPreferences sp;
        private final Handler h = new Handler(Looper.getMainLooper());
        private final float density;
        private final List<Tab> tabs = new ArrayList<Tab>();
        private final ExecutorService pool = Executors.newCachedThreadPool();
        private final FrameLayout column;
        private final LinearLayout dock;
        private final FrameLayout content;
        private final Bar bar;
        private final EditText urlField;
        private final TextView btnBack, btnFwd, btnReload, btnTabs, btnMenu;
        private Tab cur;
        private int nextId = 1;
        private FrameLayout layer;
        private Runnable layerCancel;
        private View customView;
        private WebChromeClient.CustomViewCallback customCb;
        private int savedUi;
        private boolean backPending;
        private boolean closed;
        private ValueCallback<Uri[]> fileCb;
        private String defaultUa;
        private boolean fullscreen, dockHidden, started, wantPanel, tabsPanel, lightBar;
        private ScrollView tabScroll;
        private Object dragItem;
        private int dragScrollDir;
        private int pendingProfile = -1;
        private final List<Bookmark> bookmarks = new ArrayList<Bookmark>();
        private boolean bookmarksLoaded;
        private int bmProfile = -1;                 // профиль, в котором открываются закладки; -1: профиль текущей вкладки
        private final Map<Integer, Bitmap> profileImgs = new HashMap<Integer, Bitmap>();
        private int scrollAccum;
        private long dockToggleAt;
        private final List<Group> groups = new ArrayList<Group>();
        private int nextGroup = 1;
        private Group openGroup;
        private TextView hub;
        private final java.util.Map<String, Long> pixelAt = new java.util.HashMap<String, Long>();
        private boolean startOpen, startForNew;
        private long pendingBm = -1;
        private final List<View> ringViews = new ArrayList<View>();
        private final List<Runnable> ringActions = new ArrayList<Runnable>();
        private int ringHot = -1;
        private final Runnable hubReturn = new Runnable() {
            @Override public void run() {
                scrollAccum = 0;
                setDockHidden(false, true);
            }
        };

        BrowserView(Activity a) {
            super(a);
            act = a;
            sp = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            density = a.getResources().getDisplayMetrics().density;
            configureWindow();
            setBackgroundColor(C_BG);
            setFocusable(true);
            setFocusableInTouchMode(true);

            column = new FrameLayout(a);
            dock = new LinearLayout(a);
            dock.setOrientation(LinearLayout.VERTICAL);
            content = new FrameLayout(a);
            content.setBackgroundColor(C_BG);
            bar = new Bar(a);

            LinearLayout tool = new LinearLayout(a);
            tool.setOrientation(LinearLayout.HORIZONTAL);
            tool.setGravity(Gravity.CENTER_VERTICAL);
            tool.setBackgroundColor(C_SURFACE);
            tool.setPadding(dp(4), 0, dp(4), 0);

            btnBack = toolButton("‹", "Назад", new View.OnClickListener() {
                @Override public void onClick(View v) { goBackOrClose(); }
            });
            btnFwd = toolButton("›", "Вперёд", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (cur != null && cur.wv != null && cur.wv.canGoForward()) cur.wv.goForward();
                }
            });
            urlField = new EditText(a);
            urlField.setSingleLine(true);
            urlField.setTextColor(C_TEXT);
            urlField.setHintTextColor(C_TEXT2);
            urlField.setHint("Адрес или запрос");
            urlField.setTextSize(15);
            urlField.setSelectAllOnFocus(true);
            urlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            urlField.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            urlField.setBackground(round(C_SURFACE2, 20, 0, 0));
            urlField.setPadding(dp(14), 0, dp(14), 0);
            urlField.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean focus) {
                    if (cur == null) return;
                    if (focus) {
                        setDockHidden(false, true);
                        urlField.setText(cur.url == null ? "" : cur.url);
                        urlField.selectAll();
                    } else {
                        urlField.setText(displayUrl(cur));
                        hideKeyboard();
                    }
                }
            });
            urlField.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                    boolean go = actionId == EditorInfo.IME_ACTION_GO
                            || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN);
                    if (!go) return false;
                    String text = urlField.getText().toString();
                    urlField.clearFocus();
                    requestFocus();
                    if (cur != null) navigate(cur, text);
                    return true;
                }
            });
            installSwipeOnUrlField();
            btnReload = toolButton("↻", "Обновить", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (cur == null || cur.wv == null) return;
                    if (cur.loading) cur.wv.stopLoading(); else cur.wv.reload();
                }
            });
            btnTabs = toolButton("1", "Вкладки", new View.OnClickListener() {
                @Override public void onClick(View v) { openTabsRoot(); }
            });
            btnTabs.setTextSize(16);
            btnTabs.setBackground(pressableFrame());
            btnTabs.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    if (cur != null) newTab(home(), cur.profile, null, true);
                    return true;
                }
            });
            btnMenu = toolButton("⋮", "Меню", new View.OnClickListener() {
                @Override public void onClick(View v) { showMenu(); }
            });

            tool.addView(btnBack, new LinearLayout.LayoutParams(dp(42), dp(52)));
            tool.addView(btnFwd, new LinearLayout.LayoutParams(dp(42), dp(52)));
            LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            ulp.setMargins(dp(2), 0, dp(2), 0);
            tool.addView(urlField, ulp);
            tool.addView(btnReload, new LinearLayout.LayoutParams(dp(42), dp(52)));
            tool.addView(btnTabs, new LinearLayout.LayoutParams(dp(42), dp(40)));
            tool.addView(btnMenu, new LinearLayout.LayoutParams(dp(42), dp(52)));

            dock.addView(tool, new LinearLayout.LayoutParams(-1, dp(52)));
            dock.setVisibility(View.GONE);          // нижней панели больше нет; её кнопки живут скрытыми, их состояние обновляет refreshUi
            column.addView(content, new FrameLayout.LayoutParams(-1, -1));
            column.addView(dock, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            column.addView(bar, new FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP));
            addView(column, new FrameLayout.LayoutParams(-1, -1));
            buildHub();
            loadGroups();
            applyFullscreen();
            try {       // своя карточка в системных «Недавних»: название и значок браузера, а не хоста
                act.setTaskDescription(new android.app.ActivityManager.TaskDescription("Браузер", makeIcon(128), C_BG));
            } catch (Throwable ignored) { }
        }

        // ------------------------------------------------------------ запуск и остановка

        void start(Intent in) {
            restoreTabs();
            cleanGroups();
            if (!tabs.isEmpty()) {
                int ci = Math.max(0, Math.min(tabs.size() - 1, sp.getInt("tabs_cur", 0)));
                selectTab(tabs.get(ci));
            }
            handleIntent(in);
            if (tabs.isEmpty()) newTab(home(), 0, null, true);
            requestFocus();
            started = true;
            flushPanel();
            maybeAutoUpdate();
        }

        /** Запуск без адреса (ярлык, пункт «Открыть браузер», Недавние): по настройке сначала панель вкладок. */
        private void flushPanel() {
            if (!wantPanel) return;
            wantPanel = false;
            openGroup = null;
            showTabs();
        }

        void handleIntent(Intent in) {
            if (in == null) return;
            long stamp = in.getLongExtra("stamp", 0);
            if (stamp != 0 && stamp == sp.getLong("last_stamp", -1)) return;     // тот же запуск после поворота экрана
            sp.edit().putLong("last_stamp", stamp).apply();
            String url = in.getStringExtra("url");
            String action = in.getAction();
            if (url == null || url.length() == 0) {
                if (Intent.ACTION_VIEW.equals(action) && in.getData() != null) {
                    String d = in.getData().toString();
                    String low = d.toLowerCase(Locale.ROOT);
                    if (low.startsWith("http://") || low.startsWith("https://")) url = d;      // из «Открыть с помощью»: только веб-ссылки
                } else if (Intent.ACTION_SEND.equals(action)) {
                    String text = in.getStringExtra(Intent.EXTRA_TEXT);                          // из «Поделиться»: ссылка или текст для поиска
                    if (text != null) {
                        Matcher m = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE).matcher(text);
                        url = m.find() ? m.group() : text.trim();
                    }
                }
            }
            if (url != null && url.length() > 0) {
                String norm = normalize(url);
                if (norm != null) {
                    wantPanel = false;
                    closeLayer(false);                  // открытая панель вкладок или меню не должны закрывать новую страницу
                    newTab(norm, in.getIntExtra("profile", 0), null, true);
                }
            } else if (sp.getBoolean("open_tabs_first", true)) {
                wantPanel = true;
            }
            if (started) flushPanel();
        }

        /** Результат окна выбора файла: страница получает выбранные адреса или null, если выбор отменён. */
        void activityResult(int requestCode, int resultCode, Intent data) {
            if (requestCode == REQ_PROFILE_IMG) {
                final int idx = pendingProfile;
                pendingProfile = -1;
                if (idx >= 0 && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) saveProfileImage(idx, data.getData());
                return;
            }
            if (requestCode == REQ_BM_IMG) {
                final long bmId = pendingBm;
                pendingBm = -1;
                if (bmId >= 0 && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) saveBookmarkImage(bmId, data.getData());
                return;
            }
            if (requestCode != REQ_FILE) return;
            ValueCallback<Uri[]> cb = fileCb;
            fileCb = null;
            if (cb == null) return;
            Uri[] r = null;
            try {
                r = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            } catch (Throwable ignored) { }
            cb.onReceiveValue(r);
        }

        void shutdown() {
            if (closed) return;
            saveTabs();
            closed = true;
            if (fileCb != null) { fileCb.onReceiveValue(null); fileCb = null; }
            closeLayer(false);
            h.removeCallbacksAndMessages(null);
            for (Tab t : tabs) destroyWebView(t);
            if (tts != null) {
                try { tts.stop(); tts.shutdown(); } catch (Throwable ignored) { }
                tts = null;
                ttsReady = false;
            }
            pool.shutdownNow();
            try { CookieManager.getInstance().flush(); } catch (Throwable ignored) { }
        }

        void applySettingsLive() {
            boolean ttsEnabled = sp.getBoolean("tts_bridge", true);
            for (Tab t : tabs) {
                applyUserAgent(t);
                if (t.wv != null && !t.blocked) {
                    if (ttsEnabled) {
                        t.wv.addJavascriptInterface(new TtsBridge(t), t.ttsName);
                        installTtsDocumentStart(t, t.wv);
                        injectTtsPolyfill(t);
                    } else {
                        t.wv.removeJavascriptInterface(t.ttsName);
                    }
                }
            }
            if (sp.getBoolean("keep_screen_on", false)) act.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else act.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            applyFullscreen();
        }
        private Boolean docStartCache;

        /** WebViewFeature.DOCUMENT_START_SCRIPT: скрипт выполняется до скриптов страницы. Нужен androidx.webkit хоста и свежий WebView. */
        private boolean docStartSupported() {
            if (docStartCache != null) return docStartCache.booleanValue();
            boolean ok = false;
            try {
                ClassLoader cl = Browser.class.getClassLoader();
                Class<?> feature = Class.forName("androidx.webkit.WebViewFeature", true, cl);
                String featureName = String.valueOf(feature.getField("DOCUMENT_START_SCRIPT").get(null));
                Method supported = feature.getMethod("isFeatureSupported", String.class);
                ok = Boolean.TRUE.equals(supported.invoke(null, featureName));
            } catch (Throwable ignored) {
                ok = false;
            }
            docStartCache = Boolean.valueOf(ok);
            return ok;
        }

        /** Регистрирует JS, который WebView выполняет в начале каждого нового документа; возвращает ScriptHandler или null. */
        private Object addDocStart(WebView w, String code) {
            if (!docStartSupported()) return null;
            try {
                Class<?> compat = Class.forName("androidx.webkit.WebViewCompat", true, Browser.class.getClassLoader());
                Method add = compat.getMethod("addDocumentStartJavaScript", WebView.class, String.class, Set.class);
                Set<String> all = new HashSet<String>();
                all.add("*");
                try {
                    return add.invoke(null, w, code, all);
                } catch (Throwable first) {
                    Set<String> two = new HashSet<String>();
                    two.add("http://*");
                    two.add("https://*");
                    return add.invoke(null, w, code, two);
                }
            } catch (Throwable ignored) {
                return null;
            }
        }

        private void removeHandle(Object handle) {
            if (handle == null) return;
            try {
                Class<?> sh = Class.forName("androidx.webkit.ScriptHandler", true, Browser.class.getClassLoader());
                sh.getMethod("remove").invoke(handle);
            } catch (Throwable ignored) { }
        }

        /** Регистрирует полифилл до выполнения скриптов сайта. Старые WebView используют fallback из callbacks загрузки. */
        private void installTtsDocumentStart(Tab t, WebView w) {
            if (t.ttsDocumentStartScript != null) return;
            t.ttsDocumentStartScript = addDocStart(w, TTS_POLYFILL.replace("@@TTS@@", t.ttsName));
        }

        /**
         * Скрипты с @run-at document-start вставляются средствами WebView до скриптов страницы (onPageStarted слишком ранний:
         * вызов попадает ещё в старый документ). Вызывается перед загрузкой и после каждой загрузки, список файлов кэшируется.
         */
        private void syncDocStart(Tab t) {
            if (t.wv == null || t.blocked || closed || !docStartSupported()) return;
            Set<String> keep = new HashSet<String>();
            for (UserScript s : ScriptStore.list()) {
                if (!"document-start".equals(s.runAt)) continue;
                if (!sp.getBoolean("script_on:" + s.fileName, true)) continue;
                if (s.match.isEmpty() && s.include.isEmpty()) continue;
                keep.add(s.fileName);
                Long ver = Long.valueOf(s.modified + s.size);
                Object[] cur = t.docStart.get(s.fileName);
                if (cur != null && ver.equals(cur[0])) continue;
                if (cur != null) dropDocStart(t, s.fileName);
                Injected inj = new Injected(s);
                inj.persistent = true;
                String code = "(function(){var B=window['" + t.bridgeName + "'];try{if(!B||window.top!==window||!B.applies('"
                        + inj.nonce + "',location.href))return;}catch(e){return;}\n"
                        + buildScript(s, inj, t.bridgeName) + "\n})();";
                Object handle = addDocStart(t.wv, code);
                if (handle == null) continue;
                t.byScript.put(s.id(), inj);
                t.injected.put(inj.nonce, inj);
                t.docStart.put(s.fileName, new Object[]{ver, handle, inj});
            }
            for (String k : new ArrayList<String>(t.docStart.keySet())) {
                if (!keep.contains(k)) dropDocStart(t, k);
            }
        }

        private void dropDocStart(Tab t, String fileName) {
            Object[] cur = t.docStart.remove(fileName);
            if (cur == null) return;
            removeHandle(cur[1]);
            Injected inj = (Injected) cur[2];
            t.injected.remove(inj.nonce);
            if (t.byScript.get(inj.script.id()) == inj) t.byScript.remove(inj.script.id());
        }

        private void injectTtsPolyfill(Tab t) {
            if (t.wv == null || !sp.getBoolean("tts_bridge", true)) return;
            try { t.wv.evaluateJavascript(TTS_POLYFILL.replace("@@TTS@@", t.ttsName), null); }
            catch (Throwable ignored) { }
        }

        private void maybeAutoUpdate() {
            if (!sp.getBoolean("auto_update", true)) return;
            long now = System.currentTimeMillis();
            if (now - sp.getLong("scripts_checked", 0) < 24L * 3600 * 1000) return;
            sp.edit().putLong("scripts_checked", now).apply();
            pool.execute(new Runnable() {
                @Override public void run() {
                    final String r = ScriptStore.updateAll();
                    if (r.startsWith("Скрипты: обновлено 0") || r.startsWith("Нет скриптов")) return;
                    h.post(new Runnable() { @Override public void run() { toast(r); } });
                }
            });
        }

        /** Окно хоста по умолчанию показывается поверх блокировки, включает экран и занимает весь экран: браузеру это не нужно. */
        @SuppressWarnings("deprecation")
        private void configureWindow() {
            Window w = act.getWindow();
            w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN);
            if (Build.VERSION.SDK_INT >= 27) {
                try {
                    act.setShowWhenLocked(false);
                    act.setTurnScreenOn(false);
                } catch (Throwable ignored) { }
            }
            if (sp.getBoolean("keep_screen_on", false)) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            w.setStatusBarColor(C_BG);
            w.setNavigationBarColor(C_SURFACE);
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            requestFocus();
        }

        // ------------------------------------------------------------ полноэкранный режим

        /** Полноэкранный режим: системные панели скрыты, нижняя панель браузера лежит поверх страницы и прячется при прокрутке. */
        @SuppressWarnings("deprecation")
        private void applyFullscreen() {
            fullscreen = sp.getBoolean("fullscreen", false);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) content.getLayoutParams();
            lp.bottomMargin = 0;
            content.setLayoutParams(lp);
            applyCutoutMode();
            applySysUi();
            setDockHidden(false, false);
        }

        /**
         * Вырез камеры: по умолчанию система закрашивает чёрным всю полосу над окном (у выреза сверху это и есть «чёрное наложение»
         * вместо полного экрана). В полноэкранном режиме разрешаем окну заходить под вырез по короткому краю.
         */
        private void applyCutoutMode() {
            if (Build.VERSION.SDK_INT < 28) return;
            try {
                Window w = act.getWindow();
                WindowManager.LayoutParams wl = w.getAttributes();
                int mode = fullscreen
                        ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
                if (wl.layoutInDisplayCutoutMode != mode) {
                    wl.layoutInDisplayCutoutMode = mode;
                    w.setAttributes(wl);
                }
            } catch (Throwable ignored) { }
        }

        /** Флаги системных панелей: полный экран или обычный режим со светлыми/тёмными значками в зависимости от цвета строки. */
        @SuppressWarnings("deprecation")
        private void applySysUi() {
            if (customView != null) return;
            int f = 0;
            if (fullscreen) {
                f = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            } else if (lightBar && Build.VERSION.SDK_INT >= 23) {
                f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            act.getWindow().getDecorView().setSystemUiVisibility(f);
        }

        /** Строка состояния берёт цвет страницы (theme-color или фон), а не остаётся чёрной полосой над ней. */
        private void applyBar(Tab t) {
            int col = t == null || t.bar == 0 ? C_BG : t.bar;
            lightBar = (0.299f * Color.red(col) + 0.587f * Color.green(col) + 0.114f * Color.blue(col)) > 150f;
            try { act.getWindow().setStatusBarColor(col); } catch (Throwable ignored) { }
            applySysUi();
        }

        private static final String BAR_JS = "(function(){try{var m=document.querySelector('meta[name=theme-color]');var c=m&&m.content;"
                + "if(!c){var e=document.body||document.documentElement;c=getComputedStyle(e).backgroundColor;"
                + "if(!c||c==='transparent'||/rgba\\(.*,\\s*0\\)$/.test(c)){c=getComputedStyle(document.documentElement).backgroundColor;}}"
                + "return c||'';}catch(e){return '';}})()";

        private void probeBar(final Tab t) {
            if (t.wv == null || t.blocked || closed) return;
            try {
                t.wv.evaluateJavascript(BAR_JS, new ValueCallback<String>() {
                    @Override public void onReceiveValue(String v) {
                        int col = parseCssColor(v);
                        t.bar = col;
                        if (t == cur) applyBar(t);
                    }
                });
            } catch (Throwable ignored) { }
        }

        /** #rgb, #rrggbb, rgb(), rgba(); прозрачное или непонятное считается белым фоном страницы. */
        private int parseCssColor(String raw) {
            if (raw == null) return 0xFFFFFFFF;
            String s = raw.replace("\"", "").trim().toLowerCase(Locale.ROOT);
            try {
                if (s.startsWith("#")) return Color.parseColor(s.length() == 4
                        ? "#" + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2) + s.charAt(3) + s.charAt(3) : s);
                Matcher m = Pattern.compile("rgba?\\(\\s*(\\d+)[ ,]+(\\d+)[ ,]+(\\d+)(?:[ ,/]+([0-9.]+))?").matcher(s);
                if (m.find()) {
                    float a = m.group(4) == null ? 1f : Float.parseFloat(m.group(4));
                    if (a < 0.5f) return 0xFFFFFFFF;
                    return Color.rgb(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                }
            } catch (Throwable ignored) { }
            return 0xFFFFFFFF;
        }

        private void setDockHidden(boolean hide, boolean animate) {
            if (!sp.getBoolean("autohide_dock", true)) hide = false;
            if (hide && layer != null) hide = false;
            boolean changed = hide != dockHidden;
            dockHidden = hide;
            if (changed) dockToggleAt = SystemClock.uptimeMillis();
            if (hub == null) return;
            h.removeCallbacks(hubReturn);
            hub.animate().cancel();
            if (hide) {
                if (animate) {
                    hub.animate().alpha(0f).setDuration(150).withEndAction(new Runnable() {
                        @Override public void run() { if (dockHidden) hub.setVisibility(View.INVISIBLE); }
                    }).start();
                } else {
                    hub.setAlpha(0f);
                    hub.setVisibility(View.INVISIBLE);
                }
            } else {
                hub.setVisibility(View.VISIBLE);
                if (animate) hub.animate().alpha(HUB_ALPHA).setDuration(150).start();
                else hub.setAlpha(HUB_ALPHA);
            }
        }

        /** Движение пальца по странице: вверх (содержимое едет вниз) прячет нижнюю панель, вниз возвращает. Работает и там, где страница прокручивает свой внутренний блок. */
        private void onSwipe(Tab t, float dy) {
            if (t != cur) return;
            if ((dy > 0) != (scrollAccum > 0)) scrollAccum = 0;
            scrollAccum += Math.round(dy);
            if (scrollAccum > dp(24) && !dockHidden) setDockHidden(true, true);
            else if (scrollAccum < -dp(24) && dockHidden) setDockHidden(false, true);
        }

        /** Возврат к началу страницы возвращает панель. Остальное решает onSwipe; сразу после смены размера страницы событие прокрутки не в счёт. */
        private void onPageScroll(Tab t, int sy, int oy) {
            if (t != cur) return;
            if (sy <= dp(8) && SystemClock.uptimeMillis() - dockToggleAt > 400) {
                scrollAccum = 0;
                if (dockHidden) setDockHidden(false, true);
            }
        }

        // ------------------------------------------------------------ кнопка в центре экрана: нажатие, долгое нажатие, перетаскивание

        private void buildHub() {
            hub = new HubView(act);
            hub.setTextColor(0xFFFFFFFF);
            hub.setTextSize(15);
            hub.setTypeface(hub.getTypeface(), android.graphics.Typeface.BOLD);
            hub.setGravity(Gravity.CENTER);
            hub.setText("I");
            hub.setBackground(round(0xCC000000, 39, 0x99FFFFFF, 2));
            hub.setAlpha(HUB_ALPHA);
            hub.setClickable(true);
            hub.setFocusable(true);
            hub.setContentDescription("Вкладки");
            hub.setTranslationX(sp.getFloat("hub_dx", 0f));
            hub.setTranslationY(sp.getFloat("hub_dy", 0f));
            addView(hub, new FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER));
            // для экранного диктора: двойное нажатие и долгое двойное нажатие; обычные касания разбирает слушатель ниже
            hub.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openTabsRoot(); }
            });
            hub.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    showRing();
                    return true;
                }
            });
            final float slop = ViewConfiguration.get(act).getScaledTouchSlop();
            hub.setOnTouchListener(new View.OnTouchListener() {
                private float downX, downY, startTx, startTy;
                private boolean moved, ringShown, longFired;
                private final Runnable longRun = new Runnable() {
                    @Override public void run() {
                        longFired = true;
                        hub.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                        showRing();
                        ringShown = layer != null;
                    }
                };

                @Override public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = e.getRawX();
                            downY = e.getRawY();
                            startTx = v.getTranslationX();
                            startTy = v.getTranslationY();
                            moved = false;
                            longFired = false;
                            ringShown = false;
                            h.removeCallbacks(hubReturn);
                            v.animate().cancel();
                            v.setAlpha(1f);
                            v.postDelayed(longRun, ViewConfiguration.getLongPressTimeout());
                            if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                            return true;
                        case MotionEvent.ACTION_MOVE: {
                            if (ringShown) {
                                ringHover(e.getRawX(), e.getRawY());
                                return true;
                            }
                            float dx = e.getRawX() - downX;
                            float dy = e.getRawY() - downY;
                            if (!moved && !longFired && (Math.abs(dx) > slop * 2 || Math.abs(dy) > slop * 2)) {
                                moved = true;
                                v.removeCallbacks(longRun);
                            }
                            if (moved) moveHub(startTx + dx, startTy + dy);
                            return true;
                        }
                        case MotionEvent.ACTION_UP:
                            v.removeCallbacks(longRun);
                            v.setAlpha(HUB_ALPHA);
                            if (ringShown) ringRelease(e.getRawX(), e.getRawY());
                            else if (moved) sp.edit().putFloat("hub_dx", v.getTranslationX()).putFloat("hub_dy", v.getTranslationY()).apply();
                            else if (!longFired) openTabsRoot();
                            return true;
                        case MotionEvent.ACTION_CANCEL:
                            v.removeCallbacks(longRun);
                            v.setAlpha(HUB_ALPHA);
                            return true;
                        default:
                            return true;
                    }
                }
            });
        }

        private void moveHub(float tx, float ty) {
            float maxX = Math.max(0f, getWidth() / 2f - dp(39));
            float maxY = Math.max(0f, getHeight() / 2f - dp(39));
            hub.setTranslationX(Math.max(-maxX, Math.min(maxX, tx)));
            hub.setTranslationY(Math.max(-maxY, Math.min(maxY, ty)));
        }

        private void updateHub(Tab t) {
            if (hub == null) return;
            hub.setText(roman(tabs.size()));
            hub.setContentDescription("Вкладки: открыто " + tabs.size() + ", сейчас " + (tabs.indexOf(t) + 1)
                    + ". Долгое нажатие: кнопки управления");
        }

        /** Число вкладок римскими цифрами: подбирается размер, при котором углы рамки текста касаются края круга. */
        private final class HubView extends TextView {
            private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.Rect rb = new android.graphics.Rect();

            HubView(Context c) {
                super(c);
                tp.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD));
            }

            @Override protected void onDraw(Canvas c) {
                String s = String.valueOf(getText());
                if (s.length() == 0) return;
                float d = Math.min(getWidth(), getHeight());
                tp.setColor(getCurrentTextColor());
                tp.setTextSize(100f);
                tp.getTextBounds(s, 0, s.length(), rb);
                float w = rb.width();
                float hh = rb.height();
                if (w <= 0f || hh <= 0f) return;
                float k = 0.98f * d / (float) Math.hypot(w, hh);
                tp.setTextSize(100f * k);
                tp.getTextBounds(s, 0, s.length(), rb);
                c.drawText(s, getWidth() / 2f - rb.exactCenterX(), getHeight() / 2f - rb.exactCenterY(), tp);
            }
        }

        private String roman(int n) {
            if (n <= 0) return "0";
            int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
            String[] r = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < v.length; i++) {
                while (n >= v[i]) {
                    b.append(r[i]);
                    n -= v[i];
                }
            }
            return b.toString();
        }

        private final class RingItem {
            final String glyph, desc;
            final boolean on;
            final Runnable run;

            RingItem(String glyph, String desc, boolean on, Runnable run) {
                this.glyph = glyph;
                this.desc = desc;
                this.on = on;
                this.run = run;
            }
        }

        /** Полное кольцо значков без текста вокруг кнопки. Можно, не отпуская палец, довести его до значка и отпустить, или нажать значок отдельно. */
        private void showRing() {
            final Tab t = cur;
            if (t == null || hub == null) return;
            closeLayer(true);
            ringViews.clear();
            ringActions.clear();
            ringHot = -1;
            List<RingItem> items = new ArrayList<RingItem>();
            boolean back = (t.wv != null && t.wv.canGoBack()) || (t.opener != null && tabs.contains(t.opener));
            items.add(new RingItem("‹", "Назад", back, new Runnable() { @Override public void run() { goBackOrClose(); } }));
            items.add(new RingItem("›", "Вперёд", t.wv != null && t.wv.canGoForward(), new Runnable() {
                @Override public void run() { if (t.wv != null && t.wv.canGoForward()) t.wv.goForward(); }
            }));
            items.add(new RingItem(t.loading ? "✕" : "↻", t.loading ? "Остановить загрузку" : "Обновить страницу", true, new Runnable() {
                @Override public void run() {
                    if (t.wv == null) return;
                    if (t.loading) t.wv.stopLoading(); else t.wv.reload();
                }
            }));
            items.add(new RingItem("＋", "Новая вкладка", true, new Runnable() { @Override public void run() { newTabWithStart(); } }));
            items.add(new RingItem("⌕", "Поиск, адрес и закладки", true, new Runnable() { @Override public void run() { showStart(false, true); } }));
            items.add(new RingItem("⋮", "Меню", true, new Runnable() { @Override public void run() { showMenu(); } }));

            int[] hp = new int[2];
            int[] mp = new int[2];
            hub.getLocationOnScreen(hp);
            getLocationOnScreen(mp);
            float r = dp(130);
            float half = dp(73) / 2f;
            float cx = hp[0] - mp[0] + hub.getWidth() / 2f;
            float cy = hp[1] - mp[1] + hub.getHeight() / 2f;
            float pad = r + half + dp(6);
            cx = Math.max(pad, Math.min(getWidth() - pad, cx));
            cy = Math.max(pad, Math.min(getHeight() - pad, cy));

            FrameLayout l = new FrameLayout(act);
            l.setClickable(true);
            l.setClipChildren(false);
            l.setBackgroundColor(0x66000000);
            l.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { closeLayer(true); }
            });
            for (int i = 0; i < items.size(); i++) {
                final RingItem it = items.get(i);
                double ang = -Math.PI / 2 + 2 * Math.PI * i / items.size();
                TextView v = glyph(it.glyph, C_TEXT);
                v.setTextSize(30);
                v.setBackground(round(0xEE202124, 37, C_TEXT2, 1));
                v.setContentDescription(it.desc);
                v.setAlpha(it.on ? 1f : 0.4f);
                v.setClickable(true);
                v.setFocusable(true);
                v.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View x) {
                        if (!it.on) return;
                        closeLayer(false);
                        it.run.run();
                    }
                });
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(73), dp(73), Gravity.TOP | Gravity.START);
                lp.leftMargin = Math.round(cx + (float) (r * Math.cos(ang)) - half);
                lp.topMargin = Math.round(cy + (float) (r * Math.sin(ang)) - half);
                l.addView(v, lp);
                ringViews.add(v);
                ringActions.add(it.on ? it.run : null);
            }
            layer = l;
            layerCancel = null;
            addView(l, new FrameLayout.LayoutParams(-1, -1));
            column.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            l.announceForAccessibility("Кнопки управления браузером");
        }

        private int ringIndexAt(float rx, float ry) {
            int best = -1;
            float bestD = dp(53);
            for (int i = 0; i < ringViews.size(); i++) {
                View v = ringViews.get(i);
                int[] p = new int[2];
                v.getLocationOnScreen(p);
                float d = (float) Math.hypot(rx - (p[0] + v.getWidth() / 2f), ry - (p[1] + v.getHeight() / 2f));
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            return best;
        }

        private void ringHover(float rx, float ry) {
            int i = ringIndexAt(rx, ry);
            if (i == ringHot) return;
            if (ringHot >= 0 && ringHot < ringViews.size()) {
                ringViews.get(ringHot).setScaleX(1f);
                ringViews.get(ringHot).setScaleY(1f);
            }
            ringHot = i;
            if (i >= 0) {
                ringViews.get(i).setScaleX(1.25f);
                ringViews.get(i).setScaleY(1.25f);
                try { ringViews.get(i).performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); } catch (Throwable ignored) { }
            }
        }

        private void ringRelease(float rx, float ry) {
            int i = ringIndexAt(rx, ry);
            if (i < 0 || i >= ringActions.size()) return;       // отпустили не на значке: кольцо остаётся, значок можно нажать
            Runnable a = ringActions.get(i);
            if (a == null) return;
            closeLayer(false);
            a.run();
        }

        // ------------------------------------------------------------ стартовая страница: строка поиска и значки закладок

        private void newTabWithStart() {
            if (cur == null) return;
            final Tab from = cur;
            captureThumbThen(from, new Runnable() {
                @Override public void run() {
                    newTab(home(), from.profile, null, true);
                    showStart(true, true);
                }
            });
        }

        /** Обновить стартовую страницу после смены закладки. */
        private void afterBookmarkChange() {
            if (cur != null) showStart(startForNew, false);
        }

        private void showStart(final boolean forNew, boolean keyboard) {
            final Tab t = cur;
            if (t == null) return;
            loadBookmarks();
            LinearLayout root = new LinearLayout(act);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(12), dp(16), dp(12), 0);

            final EditText box = new EditText(act);
            box.setSingleLine(true);
            box.setTextColor(C_TEXT);
            box.setHintTextColor(C_TEXT2);
            box.setHint("Поиск или адрес");
            box.setTextSize(17);
            box.setSelectAllOnFocus(true);
            box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            box.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            box.setBackground(round(C_SURFACE2, 24, 0, 0));
            box.setPadding(dp(18), 0, dp(18), 0);
            if (!forNew && t.url != null) box.setText(t.url);
            box.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                    boolean go = actionId == EditorInfo.IME_ACTION_GO
                            || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN);
                    if (!go) return false;
                    String text = box.getText().toString();
                    closeLayer(false);
                    if (cur != null) navigate(cur, text);
                    return true;
                }
            });
            root.addView(box, new LinearLayout.LayoutParams(-1, dp(52)));

            final int sel = bookmarkTargetProfile();
            List<View> cells = new ArrayList<View>();

            View pb = profileBadge(sel, 56);
            pb.setClickable(true);
            pb.setFocusable(true);
            pb.setContentDescription("Профиль для закладок: " + profileName(sel) + ". Нажмите, чтобы выбрать другой");
            pb.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    chooseProfile("Открывать закладки в профиле", new Pick() {
                        @Override public void on(int idx) {
                            bmProfile = idx;
                            showStart(forNew, false);
                        }
                    });
                }
            });
            cells.add(pb);

            for (final Bookmark b : new ArrayList<Bookmark>(bookmarks)) {
                View ic = bookmarkIcon(b, 56);
                ic.setClickable(true);
                ic.setFocusable(true);
                ic.setContentDescription("Закладка: " + b.title + ", " + hostOf(b.url) + ", профиль " + profileName(sel));
                ic.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (forNew && cur != null && bookmarkTargetProfile() == cur.profile) {
                            closeLayer(false);
                            navigate(cur, b.url);         // новая вкладка сразу открывает закладку, пустой вкладки не остаётся
                        } else {
                            openBookmark(b, bookmarkTargetProfile(), true);
                        }
                    }
                });
                ic.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override public boolean onLongClick(View v) {
                        startForNew = forNew;
                        showBookmarkMenu(b);
                        return true;
                    }
                });
                cells.add(ic);
            }

            TextView plus = glyph("⭐", C_TEXT);
            plus.setBackground(round(C_SURFACE2, 28, 0, 0));
            plus.setClickable(true);
            plus.setFocusable(true);
            plus.setContentDescription("Добавить текущую страницу в закладки");
            plus.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    addBookmark(cur);
                    showStart(forNew, false);
                }
            });
            cells.add(plus);

            ScrollView sv = new ScrollView(act);
            LinearLayout grid = new LinearLayout(act);
            grid.setOrientation(LinearLayout.VERTICAL);
            grid.setPadding(0, dp(16), 0, dp(24));
            sv.addView(grid, new ViewGroup.LayoutParams(-1, -2));
            final int cols = 4;
            LinearLayout row = null;
            for (int i = 0; i < cells.size(); i++) {
                if (i % cols == 0) {
                    row = new LinearLayout(act);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
                }
                FrameLayout cell = new FrameLayout(act);
                cell.addView(cells.get(i), new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));
                row.addView(cell, new LinearLayout.LayoutParams(0, dp(76), 1f));
            }
            while (row != null && row.getChildCount() < cols) row.addView(new View(act), new LinearLayout.LayoutParams(0, dp(76), 1f));
            root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

            showLayer(root, true, null, "Поиск и закладки");
            startOpen = true;
            startForNew = forNew;
            if (keyboard) {
                box.requestFocus();
                box.postDelayed(new Runnable() {
                    @Override public void run() {
                        try {
                            ((InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(box, InputMethodManager.SHOW_IMPLICIT);
                        } catch (Throwable ignored) { }
                    }
                }, 200);
            }
        }

        /** Своя картинка для значка закладки: квадрат из середины выбранной картинки. */
        private void saveBookmarkImage(final long id, final Uri uri) {
            loadBookmarks();
            Bookmark found = null;
            for (Bookmark x : bookmarks) if (x.id == id) found = x;
            if (found == null) return;
            final Bookmark b = found;
            try {
                pool.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            InputStream in = act.getContentResolver().openInputStream(uri);
                            byte[] data;
                            try {
                                data = Net.readAll(in, 12 * 1024 * 1024);
                            } finally {
                                if (in != null) in.close();
                            }
                            BitmapFactory.Options bo = new BitmapFactory.Options();
                            bo.inJustDecodeBounds = true;
                            BitmapFactory.decodeByteArray(data, 0, data.length, bo);
                            BitmapFactory.Options o = new BitmapFactory.Options();
                            o.inSampleSize = 1;
                            while (Math.max(bo.outWidth, bo.outHeight) / (o.inSampleSize * 2) >= 256) o.inSampleSize *= 2;
                            Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                            if (src == null) throw new IOException("не картинка");
                            int side = Math.min(src.getWidth(), src.getHeight());
                            Bitmap sq = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
                            final Bitmap fin = Bitmap.createScaledBitmap(sq, 128, 128, true);
                            OutputStream os = new FileOutputStream(bookmarkIconFile(b));
                            try { fin.compress(Bitmap.CompressFormat.PNG, 90, os); } finally { os.close(); }
                            h.post(new Runnable() {
                                @Override public void run() {
                                    if (closed) return;
                                    b.icon = fin;
                                    b.iconTried = true;
                                    toast("Значок закладки заменён");
                                    afterBookmarkChange();
                                }
                            });
                        } catch (Throwable ex) {
                            h.post(new Runnable() {
                                @Override public void run() {
                                    if (closed) return;
                                    toast("Не удалось взять эту картинку");
                                    afterBookmarkChange();
                                }
                            });
                        }
                    }
                });
            } catch (Throwable ignored) { }
        }

        // ------------------------------------------------------------ группы, снимки и значки сайтов (хранятся на диске)

        private void loadGroups() {
            groups.clear();
            nextGroup = sp.getInt("groups_next", 1);
            String raw = sp.getString("groups_json", null);
            if (raw == null) return;
            try {
                JSONArray a = new JSONArray(raw);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    int id = o.optInt("i", 0);
                    if (id <= 0) continue;
                    groups.add(new Group(id, o.optString("n", "Группа")));
                    if (id >= nextGroup) nextGroup = id + 1;
                }
            } catch (JSONException ignored) { }
        }

        private void saveGroups() {
            try {
                JSONArray a = new JSONArray();
                for (Group g : groups) {
                    JSONObject o = new JSONObject();
                    o.put("i", g.id);
                    o.put("n", g.name);
                    a.put(o);
                }
                sp.edit().putString("groups_json", a.toString()).putInt("groups_next", nextGroup).apply();
            } catch (JSONException ignored) { }
        }

        private Group groupById(int id) {
            for (Group g : groups) if (g.id == id) return g;
            return null;
        }

        private List<Tab> tabsOf(Group g) {
            List<Tab> out = new ArrayList<Tab>();
            for (Tab t : tabs) if (t.group == g.id) out.add(t);
            return out;
        }

        private Group newGroup() {
            Group g = new Group(nextGroup++, "Группа " + (groups.size() + 1));
            groups.add(g);
            return g;
        }

        /** Пустые группы исчезают, вкладки с неизвестной группой выходят из групп. */
        private void cleanGroups() {
            for (Tab t : tabs) if (t.group != 0 && groupById(t.group) == null) t.group = 0;
            List<Group> keep = new ArrayList<Group>();
            for (Group g : groups) {
                boolean used = false;
                for (Tab t : tabs) if (t.group == g.id) { used = true; break; }
                if (used) keep.add(g);
            }
            groups.clear();
            groups.addAll(keep);
            if (openGroup != null && !groups.contains(openGroup)) openGroup = null;
        }

        private File thumbDir() {
            File d = new File(act.getFilesDir(), "ubthumbs");
            if (!d.exists()) d.mkdirs();
            return d;
        }

        private void saveBitmapAsync(final Bitmap b, final File f, final boolean png) {
            if (closed || b == null) return;
            try {
                pool.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            OutputStream o = new FileOutputStream(f);
                            try {
                                b.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 80, o);
                            } finally {
                                o.close();
                            }
                        } catch (Throwable ignored) { }
                    }
                });
            } catch (Throwable ignored) { }
        }

        private Bitmap loadBitmap(File f) {
            try {
                if (f.exists()) return android.graphics.BitmapFactory.decodeFile(f.getPath());
            } catch (Throwable ignored) { }
            return null;
        }

        private Bitmap thumbOf(Tab t) {
            if (t.thumb == null) {
                t.thumb = loadBitmap(new File(thumbDir(), t.uid + ".jpg"));
            }
            return t.thumb;
        }

        private Bitmap iconOf(Tab t) {
            if (t.icon == null) t.icon = loadBitmap(new File(thumbDir(), t.uid + "_i.png"));
            return t.icon;
        }

        /** Значок сайта, если страница не отдала его сама: favicon.ico с того же хоста, один раз на хост. */
        private void fetchFavicon(final Tab t) {
            final String u = t.url;
            final String host = hostOf(u);
            if (u == null || host.length() == 0 || host.equals(t.iconHost)) return;
            if (!(u.startsWith("http://") || u.startsWith("https://"))) return;
            t.iconHost = host;
            final String target = (u.startsWith("http://") ? "http://" : "https://") + host + "/favicon.ico";
            try {
                pool.execute(new Runnable() {
                    @Override public void run() {
                        HttpURLConnection c = null;
                        try {
                            c = (HttpURLConnection) new URL(target).openConnection();
                            c.setConnectTimeout(8000);
                            c.setReadTimeout(8000);
                            c.setRequestProperty("User-Agent", "UniversalBrowser/1");
                            if (c.getResponseCode() != 200) return;
                            byte[] data = Net.readAll(c.getInputStream(), 200 * 1024);
                            Bitmap b = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
                            if (b == null || b.getWidth() < 8) return;
                            final Bitmap sc = Bitmap.createScaledBitmap(b, dp(32), dp(32), true);
                            h.post(new Runnable() {
                                @Override public void run() {
                                    if (closed || t.icon != null || !host.equals(hostOf(t.url))) return;
                                    t.icon = sc;
                                    saveBitmapAsync(sc, new File(thumbDir(), t.uid + "_i.png"), true);
                                }
                            });
                        } catch (Throwable ignored) {
                        } finally {
                            if (c != null) c.disconnect();
                        }
                    }
                });
            } catch (Throwable ignored) { }
        }

        // ------------------------------------------------------------ кнопка «Назад»

        @Override public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    backPending = canHandleBack();
                    if (backPending) return true;
                } else if (e.getAction() == KeyEvent.ACTION_UP && backPending) {
                    backPending = false;
                    doBack();
                    return true;
                }
            }
            return super.dispatchKeyEvent(e);
        }

        private boolean canHandleBack() {
            if (customView != null || layer != null || urlField.hasFocus()) return true;
            Tab t = cur;
            if (t == null) return false;
            return (t.wv != null && t.wv.canGoBack()) || (t.opener != null && tabs.contains(t.opener));
        }

        private void doBack() {
            if (customView != null) hideCustomView();
            else if (layer != null) {
                if (tabsPanel && openGroup != null) openTabsRoot();      // из группы назад к общему списку, а не сразу к странице
                else closeLayer(true);
            }
            else if (urlField.hasFocus()) { urlField.clearFocus(); requestFocus(); }
            else goBackOrClose();
        }

        private void goBackOrClose() {
            Tab t = cur;
            if (t == null) return;
            if (t.wv != null && t.wv.canGoBack()) t.wv.goBack();
            else if (t.opener != null && tabs.contains(t.opener)) closeTab(t);
        }

        // ------------------------------------------------------------ вкладки

        private Tab newTab(String url, int profile, Tab opener, boolean select) {
            return newTab(url, profile, opener, select, opener != null ? opener.group : 0);
        }

        private Tab newTab(String url, int profile, Tab opener, boolean select, int group) {
            Tab t = new Tab(nextId++, url == null ? "" : url, Math.max(0, Math.min(PROFILES - 1, profile)));
            t.opener = opener;
            t.group = group;
            tabs.add(t);
            if (select) selectTab(t); else refreshUi();
            saveTabs();
            return t;
        }

        private void selectTab(Tab t) {
            if (cur != null && cur != t) {
                if (layer == null) captureThumb(cur);       // под панелью вкладок снимок уже взят при её открытии
                if (cur.wv != null) content.removeView(cur.wv);
            }
            cur = t;
            applyBar(t);
            setDockHidden(false, false);
            t.lastUsed = SystemClock.uptimeMillis();
            ensureWebView(t, true);
            if (t.wv != null && t.wv.getParent() == null) content.addView(t.wv, new FrameLayout.LayoutParams(-1, -1));
            if (t.wv != null) t.wv.requestFocus();
            requestFocus();
            if (urlField.hasFocus()) urlField.clearFocus();
            refreshUi();
            trimLive();
            saveTabs();
        }

        private void switchTab(int delta) {
            int n = tabs.size();
            if (n < 2) { toast("Открыта одна вкладка"); return; }
            int i = tabs.indexOf(cur);
            Tab t = tabs.get(((i + delta) % n + n) % n);
            selectTab(t);
            announceForAccessibility("Вкладка " + (tabs.indexOf(t) + 1) + " из " + n + ": " + tabTitle(t));
        }

        private void closeTab(Tab t) {
            int idx = tabs.indexOf(t);
            if (idx < 0) return;
            boolean wasCur = t == cur;
            destroyWebView(t);
            tabs.remove(t);
            t.thumb = null;
            t.icon = null;
            try {
                new File(thumbDir(), t.uid + ".jpg").delete();
                new File(thumbDir(), t.uid + "_i.png").delete();
            } catch (Throwable ignored) { }
            cleanGroups();
            for (Tab o : tabs) if (o.opener == t) o.opener = t.opener;
            if (tabs.isEmpty()) {
                cur = null;
                newTab(home(), 0, null, true);
                return;
            }
            if (wasCur) {
                Tab next = (t.opener != null && tabs.contains(t.opener)) ? t.opener : tabs.get(Math.min(idx, tabs.size() - 1));
                cur = null;
                selectTab(next);
            } else {
                refreshUi();
                saveTabs();
            }
        }

        /** Лимита вкладок в памяти нет: вкладка выгружается только вручную (меню вкладки, «Выгрузить из памяти»). */
        private void trimLive() { }

        /** Выгрузка вкладки из памяти: история и положение сохраняются, страница загрузится заново при выборе вкладки. */
        private void unloadTab(Tab t) {
            if (t == null || t == cur || t.wv == null) return;
            captureThumb(t);
            try {
                Bundle b = new Bundle();
                t.wv.saveState(b);
                t.saved = b;
            } catch (Throwable ignored) { }
            destroyWebView(t);
            toast("Выгружено из памяти: " + tabTitle(t));
        }

        private void destroyWebView(Tab t) {
            WebView w = t.wv;
            if (t.pendingJs != null) {
                try { t.pendingJs.cancel(); } catch (Throwable ignored) { }
                t.pendingJs = null;
            }
            for (HttpURLConnection c : t.conns.values()) {
                try { c.disconnect(); } catch (Throwable ignored) { }
            }
            if (ttsOwner.values().removeAll(Collections.singleton(t)) && tts != null) {
                try { tts.stop(); } catch (Throwable ignored) { }      // вкладка закрыта или выгружена: её речь больше никому не нужна
            }
            t.clearScripts();
            t.ttsDocumentStartScript = null;
            if (w == null) return;
            t.wv = null;
            try {
                ViewGroup p = (ViewGroup) w.getParent();
                if (p != null) p.removeView(w);
                w.stopLoading();
                w.setWebViewClient(new WebViewClient());
                w.setWebChromeClient(null);
                w.removeAllViews();
                w.destroy();
            } catch (Throwable ignored) { }
        }

        private void captureThumb(Tab t) {
            if (t != null) {
                Long px = pixelAt.get(String.valueOf(t.uid));
                if (px != null && SystemClock.uptimeMillis() - px.longValue() < 4000) return;   // свежий снимок с экрана не затираем
            }
            WebView w = t == null ? null : t.wv;
            if (w == null || t.blocked || w.getParent() == null || w.getWidth() <= 0 || w.getHeight() <= 0) return;
            try {
                int tw = dp(170);
                int th = Math.round(tw * (float) w.getHeight() / w.getWidth());
                Bitmap b = Bitmap.createBitmap(tw, th, Bitmap.Config.RGB_565);
                Canvas c = new Canvas(b);
                float sc = (float) tw / w.getWidth();
                c.scale(sc, sc);
                w.draw(c);
                t.thumb = b;
                saveBitmapAsync(b, new File(thumbDir(), t.uid + ".jpg"), false);
            } catch (Throwable ignored) { }
        }

        /** Снимок того, что сейчас видно на экране (с масштабом и прокруткой), через PixelCopy; WebView.draw иногда рисует другое место. */
        private void captureThumbThen(final Tab t, final Runnable after) {
            final WebView w = t == null ? null : t.wv;
            if (Build.VERSION.SDK_INT < 26 || w == null || t != cur || layer != null || t.blocked || w.getParent() == null
                    || w.getWidth() <= 0 || w.getHeight() <= 0 || act.getWindow() == null) {
                captureThumb(t);
                if (after != null) after.run();
                return;
            }
            final boolean hubWas = hub != null && hub.getVisibility() == View.VISIBLE;
            if (hubWas) hub.setVisibility(View.INVISIBLE);       // кнопка не должна попасть на снимок
            final boolean[] done = new boolean[1];
            final Runnable finish = new Runnable() {
                @Override public void run() {
                    if (done[0]) return;
                    done[0] = true;
                    if (hubWas && hub != null && !dockHidden) hub.setVisibility(View.VISIBLE);
                    if (after != null) after.run();
                }
            };
            h.postDelayed(finish, 1500);                         // страховка, если копирование не ответит
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        if (done[0]) return;
                        if (closed || t.wv != w || w.getParent() == null) {
                            finish.run();
                            return;
                        }
                        int[] loc = new int[2];
                        w.getLocationInWindow(loc);
                        android.graphics.Rect srcRect = new android.graphics.Rect(loc[0], loc[1], loc[0] + w.getWidth(), loc[1] + w.getHeight());
                        int tw = dp(170);
                        int th = Math.round(tw * (float) w.getHeight() / w.getWidth());
                        final Bitmap full = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
                        android.view.PixelCopy.request(act.getWindow(), srcRect, full, new android.view.PixelCopy.OnPixelCopyFinishedListener() {
                            @Override public void onPixelCopyFinished(int result) {
                                try {
                                    if (!closed && result == android.view.PixelCopy.SUCCESS) {
                                        Bitmap small = full.copy(Bitmap.Config.RGB_565, false);
                                        Bitmap use = small != null ? small : full;
                                        t.thumb = use;
                                        pixelAt.put(String.valueOf(t.uid), Long.valueOf(SystemClock.uptimeMillis()));
                                        saveBitmapAsync(use, new File(thumbDir(), t.uid + ".jpg"), false);
                                    } else if (!closed) {
                                        captureThumb(t);
                                    }
                                } catch (Throwable ignored) { }
                                finish.run();
                            }
                        }, h);
                    } catch (Throwable ex) {
                        captureThumb(t);
                        finish.run();
                    }
                }
            }, 90);
        }

        private void saveTabs() {
            if (closed) return;
            try {
                JSONArray a = new JSONArray();
                int ci = 0;
                for (int i = 0; i < tabs.size(); i++) {
                    Tab t = tabs.get(i);
                    if (t == cur) ci = i;
                    JSONObject o = new JSONObject();
                    o.put("u", t.url == null ? "" : t.url);
                    o.put("t", t.title);
                    o.put("p", t.profile);
                    o.put("k", t.uid);
                    o.put("g", t.group);
                    a.put(o);
                }
                sp.edit().putString("tabs_json", a.toString()).putInt("tabs_cur", ci).apply();
                saveGroups();
            } catch (JSONException ignored) { }
        }

        private void restoreTabs() {
            if (!sp.getBoolean("restore_tabs", true)) return;
            String raw = sp.getString("tabs_json", null);
            if (raw == null) return;
            try {
                JSONArray a = new JSONArray(raw);
                for (int i = 0; i < a.length() && i < 60; i++) {
                    JSONObject o = a.getJSONObject(i);
                    Tab t = new Tab(nextId++, o.optString("u", ""), Math.max(0, Math.min(PROFILES - 1, o.optInt("p", 0))));
                    t.title = o.optString("t", "");
                    String k = o.optString("k", "");
                    if (k.length() > 0) t.uid = k;
                    t.group = o.optInt("g", 0);
                    tabs.add(t);
                }
            } catch (JSONException ignored) { }
        }

        // ------------------------------------------------------------ WebView

        private String home() {
            String u = sp.getString("home_url", DEF_HOME).trim();
            return u.length() == 0 ? DEF_HOME : u;
        }

        /** Адресная строка: адрес как есть; текст без точки и пробелов или с пробелами идёт в поиск. */
        private String normalize(String input) {
            String s = input.trim();
            if (s.length() == 0) return home();
            String low = s.toLowerCase(Locale.ROOT);
            if (low.startsWith("javascript:")) { toast("Адреса javascript: не поддерживаются"); return null; }
            if (low.startsWith("about:") || low.matches("^[a-z][a-z0-9+.-]*://.*")) return s;
            boolean hostLike = s.indexOf(' ') < 0 && !s.endsWith(".")
                    && (s.indexOf('.') > 0 || low.startsWith("localhost") || s.matches("^[0-9.]+(:[0-9]+)?(/.*)?$"));
            if (hostLike) {
                boolean local = low.startsWith("localhost") || s.matches("^(192\\.168|10\\.|127\\.)[0-9.]*(:[0-9]+)?(/.*)?$");
                return (local ? "http://" : "https://") + s;
            }
            String tpl = sp.getString("search_url", DEF_SEARCH);
            if (!tpl.contains("%s")) tpl = DEF_SEARCH;
            try {
                return tpl.replace("%s", URLEncoder.encode(s, "UTF-8"));
            } catch (java.io.UnsupportedEncodingException e) {
                return DEF_SEARCH.replace("%s", s);
            }
        }

        private void navigate(Tab t, String input) {
            String url = normalize(input);
            if (url == null) return;
            if (t.blocked) { toast(Profiles.status()); return; }
            if (isUserScriptUrl(url)) { offerInstall(url); return; }
            ensureWebView(t, false);
            if (t.wv == null) return;
            t.url = url;
            t.wv.loadUrl(url);
            refreshUi();
        }

        private String userAgentFor(Tab t) {
            if (t.desktop) return DESKTOP_UA;
            String custom = sp.getString("user_agent", "").trim();
            if (custom.length() > 0) return custom;
            if (defaultUa == null) defaultUa = WebSettings.getDefaultUserAgent(act).replace("; wv", "");
            return defaultUa;
        }

        private void applyUserAgent(Tab t) {
            if (t.wv != null && !t.blocked) {
                try { t.wv.getSettings().setUserAgentString(userAgentFor(t)); } catch (Throwable ignored) { }
            }
        }

        @SuppressWarnings({"deprecation", "SetJavaScriptEnabled"})
        private void ensureWebView(final Tab t, boolean load) {
            if (t.wv != null) return;
            final WebView w = new WebView(act);
            t.wv = w;
            w.setTag(t);
            w.setBackgroundColor(C_BG);
            t.blocked = !Profiles.apply(w, t.profile);      // без изоляции чужой профиль не открываем
            WebSettings s = w.getSettings();
            s.setAllowFileAccess(false);
            s.setAllowContentAccess(false);
            s.setAllowFileAccessFromFileURLs(false);
            s.setAllowUniversalAccessFromFileURLs(false);
            if (t.blocked) {
                s.setJavaScriptEnabled(false);
                w.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
                });
                String name = profileName(t.profile);
                w.loadDataWithBaseURL(null, "<html><body style='background:#121212;color:#fff;font:18px sans-serif;padding:24px'>"
                        + "<h2>Профиль «" + escapeHtml(name) + "» недоступен</h2><p>" + escapeHtml(Profiles.status())
                        + "</p></body></html>", "text/html", "UTF-8", null);
                return;
            }
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            s.setSupportMultipleWindows(true);
            s.setJavaScriptCanOpenWindowsAutomatically(false);
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
            s.setSupportZoom(true);
            s.setBuiltInZoomControls(true);
            s.setDisplayZoomControls(false);
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            s.setMediaPlaybackRequiresUserGesture(false);   // скрипты запускают звук (new Audio().play()) не из касания
            applyUserAgent(t);
            try { CookieManager.getInstance().setAcceptThirdPartyCookies(w, true); } catch (Throwable ignored) { }
            w.addJavascriptInterface(new Bridge(t), t.bridgeName);
            if (sp.getBoolean("tts_bridge", true)) {
                w.addJavascriptInterface(new TtsBridge(t), t.ttsName);
                installTtsDocumentStart(t, w);
            }
            syncDocStart(t);
            if (sp.getBoolean("force_zoom", true)) addDocStart(w, FORCE_ZOOM_JS);
            w.setWebViewClient(new Client(t));
            w.setWebChromeClient(new Chrome(t));
            if (Build.VERSION.SDK_INT >= 23) {
                w.setOnScrollChangeListener(new View.OnScrollChangeListener() {
                    @Override public void onScrollChange(View v, int sx, int sy, int ox, int oy) { onPageScroll(t, sy, oy); }
                });
            }
            final float[] lastY = new float[1];
            w.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent e) {
                    if (e.getPointerCount() > 1) { lastY[0] = e.getY(); return false; }
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN: lastY[0] = e.getY(); break;
                        case MotionEvent.ACTION_MOVE:
                            onSwipe(t, lastY[0] - e.getY());
                            lastY[0] = e.getY();
                            break;
                        default: break;
                    }
                    return false;                   // касание идёт странице как обычно
                }
            });
            w.setDownloadListener(new DownloadListener() {
                @Override public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                    download(t, url, ua, cd, mime);
                }
            });
            if (!load) return;
            if (t.popup) { t.popup = false; return; }               // окно, открытое страницей: содержимое придёт от WebView
            if (t.saved != null) {
                WebBackForwardList l = w.restoreState(t.saved);
                t.saved = null;
                if (l != null) return;
            }
            String u = t.url == null || t.url.length() == 0 ? home() : t.url;
            t.url = u;
            w.loadUrl(u);
        }

        private String escapeHtml(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        private final class Client extends WebViewClient {
            private final Tab t;
            Client(Tab t) { this.t = t; }

            @Override public void onPageStarted(WebView v, String url, Bitmap icon) {
                String prevHost = hostOf(t.url);
                t.url = url;
                if (!prevHost.equals(hostOf(url))) {
                    t.icon = null;
                    t.iconHost = null;
                    try { new File(thumbDir(), t.uid + "_i.png").delete(); } catch (Throwable ignored) { }
                }
                if (t == cur) setDockHidden(false, true);
                t.loading = true;
                t.progress = 5;
                injectScripts(t, url, true);
                if (t == cur) refreshUi();
            }

            @Override public void onPageFinished(WebView v, String url) {
                t.url = url;
                t.loading = false;
                t.progress = 100;
                injectScripts(t, url, false);
                syncDocStart(t);
                if (sp.getBoolean("force_zoom", true)) v.evaluateJavascript(FORCE_ZOOM_JS, null);
                probeBar(t);
                if (t.icon == null && iconOf(t) == null) fetchFavicon(t);
                if (t == cur) {
                    refreshUi();
                    final Tab tt = t;
                    h.postDelayed(new Runnable() {
                        @Override public void run() { if (!closed) captureThumb(tt); }
                    }, 900);
                }
                saveTabs();
            }

            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return handleUrl(t, r.getUrl().toString(), r.isForMainFrame(), r.hasGesture());
            }

            @Override public void doUpdateVisitedHistory(WebView v, String url, boolean isReload) {
                t.url = url;
                if (t == cur) refreshUi();
            }

            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                renderGone(t);
                return true;
            }
        }

        private final class Chrome extends WebChromeClient {
            private final Tab t;
            Chrome(Tab t) { this.t = t; }

            @Override public void onProgressChanged(WebView v, int p) {
                t.progress = p;
                if (t == cur) refreshUi();
            }

            @Override public void onReceivedTitle(WebView v, String title) {
                t.title = title == null ? "" : title;
            }

            @Override public void onReceivedIcon(WebView v, Bitmap icon) {
                if (icon == null) return;
                try {
                    Bitmap sc = Bitmap.createScaledBitmap(icon, dp(32), dp(32), true);
                    t.icon = sc;
                    saveBitmapAsync(sc, new File(thumbDir(), t.uid + "_i.png"), true);
                } catch (Throwable ignored) { }
            }

            @Override public boolean onCreateWindow(WebView v, boolean isDialog, boolean isUserGesture, android.os.Message msg) {
                if (!isUserGesture) return false;                  // всплывающие окна без нажатия не открываем
                Tab n = new Tab(nextId++, "", t.profile);
                n.opener = t;
                n.group = t.group;
                n.popup = true;
                tabs.add(n);
                selectTab(n);
                if (n.wv == null || n.blocked) { closeTab(n); return false; }
                WebView.WebViewTransport tr = (WebView.WebViewTransport) msg.obj;
                tr.setWebView(n.wv);
                msg.sendToTarget();
                return true;
            }

            @Override public void onCloseWindow(WebView v) {
                if (tabs.size() > 1) closeTab(t);
            }

            @Override public boolean onJsAlert(WebView v, String url, String message, final JsResult r) {
                t.pendingJs = r;
                showConfirm(hostOf(url) + " сообщает", message, "OK", null, new Runnable() {
                    @Override public void run() { done(r, true); }
                }, new Runnable() {
                    @Override public void run() { done(r, false); }
                });
                return true;
            }

            @Override public boolean onJsConfirm(WebView v, String url, String message, final JsResult r) {
                t.pendingJs = r;
                showConfirm(hostOf(url) + " спрашивает", message, "OK", "Отмена", new Runnable() {
                    @Override public void run() { done(r, true); }
                }, new Runnable() {
                    @Override public void run() { done(r, false); }
                });
                return true;
            }

            @Override public boolean onJsPrompt(WebView v, String url, String message, String def, final JsPromptResult r) {
                t.pendingJs = r;
                showPrompt(hostOf(url) + " спрашивает", message, def, new PromptDone() {
                    @Override public void done(String value) {
                        t.pendingJs = null;
                        if (value == null) r.cancel(); else r.confirm(value);
                    }
                });
                return true;
            }

            @Override public boolean onJsBeforeUnload(WebView v, String url, String message, JsResult r) {
                r.confirm();
                return true;
            }

            private void done(JsResult r, boolean ok) {
                t.pendingJs = null;
                if (ok) r.confirm(); else r.cancel();
            }

            @Override public void onShowCustomView(View v, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                showCustomView(v, cb);
            }

            @Override public void onHideCustomView() { hideCustomView(); }

            @Override public Bitmap getDefaultVideoPoster() {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }

            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);          // предыдущий выбор не завершён: отвечаем «ничего»
                fileCb = cb;
                try {
                    act.startActivityForResult(p.createIntent(), REQ_FILE);
                } catch (Throwable e) {
                    fileCb = null;
                    cb.onReceiveValue(null);
                    toast("Нет приложения для выбора файла");
                }
                return true;
            }

            @Override public void onPermissionRequest(PermissionRequest r) { r.deny(); }

            @Override public void onGeolocationPermissionsShowPrompt(String origin, android.webkit.GeolocationPermissions.Callback cb) {
                cb.invoke(origin, false, false);
            }
        }

        private void renderGone(Tab t) {
            boolean wasCur = t == cur;
            if (t.wv != null && wasCur) content.removeView(t.wv);
            destroyWebView(t);
            toast("Страница аварийно закрылась, загружаю заново");
            if (wasCur) {
                cur = null;
                selectTab(t);
            }
        }

        private void showCustomView(View v, WebChromeClient.CustomViewCallback cb) {
            customView = v;
            customCb = cb;
            v.setBackgroundColor(Color.BLACK);
            addView(v, new FrameLayout.LayoutParams(-1, -1));
            column.setVisibility(View.GONE);
            savedUi = act.getWindow().getDecorView().getSystemUiVisibility();
            act.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }

        private void hideCustomView() {
            if (customView == null) return;
            removeView(customView);
            customView = null;
            column.setVisibility(View.VISIBLE);
            act.getWindow().getDecorView().setSystemUiVisibility(savedUi);
            WebChromeClient.CustomViewCallback cb = customCb;
            customCb = null;
            if (cb != null) cb.onCustomViewHidden();
            requestFocus();
        }

        private boolean isUserScriptUrl(String url) {
            String low = url.toLowerCase(Locale.ROOT);
            if (!(low.startsWith("http://") || low.startsWith("https://"))) return false;
            int end = low.length();
            int qm = low.indexOf('?');
            if (qm >= 0) end = qm;
            int hs = low.indexOf('#');
            if (hs >= 0 && hs < end) end = hs;
            return low.substring(0, end).endsWith(".user.js");
        }

        private boolean handleUrl(Tab t, String url, boolean mainFrame, boolean gesture) {
            String low = url.toLowerCase(Locale.ROOT);
            if (low.startsWith("http://") || low.startsWith("https://")) {
                if (mainFrame && isUserScriptUrl(url)) { offerInstall(url); return true; }
                return false;
            }
            if (low.startsWith("about:") || low.startsWith("data:") || low.startsWith("blob:") || low.startsWith("javascript:")) return false;
            if (low.startsWith("file:") || low.startsWith("content:")) return true;
            if (mainFrame || gesture) launchExternal(t, url);
            return true;
        }

        /** intent: (в том числе прямая отправка в NewPipe, SnapTube и т. п.), market:, tel:, mailto: и другие схемы. */
        private void launchExternal(Tab t, String url) {
            try {
                Intent i;
                if (url.startsWith("intent:")) {
                    i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                    i.addCategory(Intent.CATEGORY_BROWSABLE);
                    i.setComponent(null);
                    i.setSelector(null);
                    i.setFlags(i.getFlags() & ~(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
                } else {
                    i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                }
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    act.startActivity(i);
                } catch (ActivityNotFoundException e) {
                    String fb = url.startsWith("intent:") ? i.getStringExtra("browser_fallback_url") : null;
                    if (fb != null && (fb.startsWith("http://") || fb.startsWith("https://"))) navigate(t, fb);
                    else toast("Нет приложения для этой ссылки");
                }
            } catch (Throwable e) {
                toast("Не удалось открыть ссылку: " + e.getMessage());
            }
        }

        private void download(Tab t, String url, String ua, String cd, String mime) {
            if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                toast("Загрузка этого адреса не поддерживается");
                return;
            }
            try {
                String name = URLUtil.guessFileName(url, cd, mime);
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                if (mime != null && mime.length() > 0) r.setMimeType(mime);
                if (ua != null) r.addRequestHeader("User-Agent", ua);
                if (t.profile == 0) {
                    String ck = CookieManager.getInstance().getCookie(url);
                    if (ck != null) r.addRequestHeader("Cookie", ck);
                }
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                ((DownloadManager) act.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
                toast("Загрузка: " + name + (t.profile == 0 ? "" : " (без cookies профиля)"));
            } catch (Throwable e) {
                toast("Не удалось начать загрузку: " + e.getMessage());
            }
        }

        private void offerInstall(final String url) {
            showConfirm("Установить скрипт?", url, "Установить", "Отмена", new Runnable() {
                @Override public void run() {
                    pool.execute(new Runnable() {
                        @Override public void run() {
                            final String r = ScriptStore.install(url);
                            h.post(new Runnable() { @Override public void run() { toast(r); } });
                        }
                    });
                }
            }, null);
        }

        // ------------------------------------------------------------ скрипты

        private void injectScripts(Tab t, String url, boolean early) {
            if (t.wv == null || t.blocked || url == null) return;
            String low = url.toLowerCase(Locale.ROOT);
            if (!(low.startsWith("http://") || low.startsWith("https://"))) return;
            if (early) t.resetScripts();                               // началась новая страница
            if (sp.getBoolean("tts_bridge", true)) {                   // fallback для WebView без document-start API
                injectTtsPolyfill(t);
            }
            for (UserScript s : ScriptStore.list()) {
                if (!sp.getBoolean("script_on:" + s.fileName, true)) continue;
                if (early && !"document-start".equals(s.runAt)) continue;
                if (early && t.docStart.containsKey(s.fileName)) continue;      // этот скрипт уже внедряется до страницы средствами WebView
                if (!s.applies(url)) continue;
                Injected inj = t.byScript.get(s.id());
                if (inj == null) {
                    inj = new Injected(s);
                    t.byScript.put(s.id(), inj);
                    t.injected.put(inj.nonce, inj);
                }
                try {
                    t.wv.evaluateJavascript(buildScript(s, inj, t.bridgeName), null);
                } catch (Throwable ignored) { }
            }
        }

        private SharedPreferences values() {
            return act.getSharedPreferences(VALUES_PREFS, Context.MODE_PRIVATE);
        }

        private void broadcastValue(final Tab src, final Injected from, final String key, final String oldJ, final String newJ) {
            h.post(new Runnable() {
                @Override public void run() {
                    if (closed) return;
                    for (Tab t : tabs) {
                        Injected i = t.byScript.get(from.script.id());
                        if (i == null || t.wv == null) continue;
                        t.wv.evaluateJavascript("try{window['" + i.disp + "'].v(" + q(key) + "," + q(oldJ) + "," + q(newJ) + ","
                                + (t != src) + ")}catch(e){}", null);
                    }
                }
            });
        }

        private boolean hostAllowed(UserScript s, String pageHost, String target) {
            String host = target.toLowerCase(Locale.ROOT);
            if (host.length() == 0) return false;
            if (s.connect.isEmpty()) return host.equals(pageHost) || host.endsWith("." + pageHost);
            for (String c : s.connect) {
                if (c.equals("*")) return true;
                if (c.equals("self")) { if (host.equals(pageHost) || host.endsWith("." + pageHost)) return true; continue; }
                if (host.equals(c) || host.endsWith("." + c)) return true;
            }
            return false;
        }

        /** GM_xmlhttpRequest: только http/https, хост по @connect (без @connect только сайт самой страницы), редиректы проверяются заново. */
        private void runXhr(final Tab t, final Injected inj, final int id, String reqJson) {
            final JSONObject req;
            try {
                req = new JSONObject(reqJson);
            } catch (JSONException e) {
                return;
            }
            final String pageHost = hostOf(t.url);
            final String key = inj.nonce + ":" + id;
            try {
                pool.execute(new Runnable() {
                    @Override public void run() {
                        final JSONObject out = xhrFetch(t, inj, key, req, pageHost);
                        final String js = "try{window['" + inj.disp + "'].x(" + id + "," + forJs(out.toString()) + ")}catch(e){}";
                        h.post(new Runnable() {
                            @Override public void run() {
                                if (!closed && t.wv != null && t.injected.containsKey(inj.nonce)) t.wv.evaluateJavascript(js, null);
                            }
                        });
                    }
                });
            } catch (Throwable ignored) { }
        }

        private JSONObject xhrFetch(Tab t, Injected inj, String key, JSONObject req, String pageHost) {
            JSONObject out = new JSONObject();
            HttpURLConnection c = null;
            try {
                String method = req.optString("method", "GET").toUpperCase(Locale.ROOT);
                if (!(method.equals("GET") || method.equals("POST") || method.equals("HEAD") || method.equals("PUT")
                        || method.equals("DELETE") || method.equals("OPTIONS"))) throw new IOException("метод " + method + " не поддерживается");
                String cur = req.optString("url");
                String data = req.isNull("data") ? null : req.optString("data", null);
                String b64 = req.isNull("b64") ? null : req.optString("b64", null);
                // Бинарное тело (ArrayBuffer/Uint8Array, например protobuf у VOT) приходит в base64 — раньше превращалось в строку и портилось
                byte[] reqBody = b64 != null ? Base64.decode(b64, Base64.DEFAULT) : (data != null ? data.getBytes(Charset.forName("UTF-8")) : null);
                JSONObject headers = req.optJSONObject("headers");
                String type = req.optString("responseType", "");
                boolean binary = type.equals("arraybuffer") || type.equals("blob");
                int timeout = req.optInt("timeout", 0);
                for (int hop = 0; hop < 6; hop++) {
                    URL u = new URL(cur);
                    String proto = u.getProtocol();
                    if (!(proto.equals("http") || proto.equals("https"))) throw new SecurityException("схема " + proto);
                    if (!hostAllowed(inj.script, pageHost, u.getHost())) throw new SecurityException("хост " + u.getHost() + " не разрешён в @connect");
                    if (t.aborted.contains(key)) throw new InterruptedIOException2();
                    c = (HttpURLConnection) u.openConnection();
                    t.conns.put(key, c);
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(timeout > 0 ? timeout : 20000);
                    c.setReadTimeout(timeout > 0 ? timeout : 30000);
                    c.setRequestMethod(method);
                    c.setRequestProperty("User-Agent", t.wv != null ? t.wv.getSettings().getUserAgentString() : "UniversalBrowser/1");
                    if (headers != null) {
                        java.util.Iterator<String> it = headers.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            if (k.equalsIgnoreCase("host") || k.equalsIgnoreCase("content-length")) continue;
                            c.setRequestProperty(k, headers.optString(k));
                        }
                    }
                    if (reqBody != null && !method.equals("GET") && !method.equals("HEAD")) {
                        c.setDoOutput(true);
                        OutputStream os = c.getOutputStream();
                        os.write(reqBody);
                        os.close();
                    }
                    int code = c.getResponseCode();
                    if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                        cur = new URL(u, c.getHeaderField("Location")).toString();
                        if (code == 303 || ((code == 301 || code == 302) && method.equals("POST"))) { method = "GET"; data = null; reqBody = null; }
                        c.disconnect();
                        continue;
                    }
                    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    byte[] body = in == null ? new byte[0] : Net.readAll(in, binary ? 6 * 1024 * 1024 : 10 * 1024 * 1024);
                    if (t.aborted.contains(key)) throw new InterruptedIOException2();
                    out.put("kind", "load");
                    out.put("status", code);
                    out.put("statusText", c.getResponseMessage() == null ? "" : c.getResponseMessage());
                    out.put("finalUrl", cur);
                    StringBuilder hs = new StringBuilder();
                    for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                        if (e.getKey() == null) continue;
                        hs.append(e.getKey()).append(": ");
                        for (int i = 0; i < e.getValue().size(); i++) hs.append(i > 0 ? ", " : "").append(e.getValue().get(i));
                        hs.append("\r\n");
                    }
                    out.put("headers", hs.toString());
                    String ct = c.getContentType();
                    if (binary) {
                        out.put("b64", Base64.encodeToString(body, Base64.NO_WRAP));
                        out.put("mime", ct == null ? "" : ct);
                    } else {
                        Charset cs = Charset.forName("UTF-8");
                        if (ct != null) {
                            Matcher m = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE).matcher(ct);
                            if (m.find()) { try { cs = Charset.forName(m.group(1)); } catch (Throwable ignored) { } }
                        }
                        out.put("text", new String(body, cs));
                    }
                    return out;
                }
                throw new IOException("слишком много редиректов");
            } catch (InterruptedIOException2 e) {
                put(out, "kind", "abort");
            } catch (SocketTimeoutException e) {
                put(out, "kind", "timeout");
            } catch (Throwable e) {
                put(out, "kind", t.aborted.contains(key) ? "abort" : "error");
                put(out, "error", String.valueOf(e.getMessage()));
            } finally {
                t.conns.remove(key);
                t.aborted.remove(key);
                if (c != null) c.disconnect();
            }
            return out;
        }

        private void put(JSONObject o, String k, String v) {
            try { o.put(k, v); } catch (JSONException ignored) { }
        }

        /** Мост для страницы: каждый вызов проверяет ключ (nonce) запуска скрипта и его @grant. */
        private final class Bridge {
            private final Tab t;
            Bridge(Tab t) { this.t = t; }

            private Injected inj(String nonce) { return nonce == null ? null : t.injected.get(nonce); }

            private boolean granted(Injected i, String... names) {
                for (String g : i.script.grant) for (String n : names) if (g.equals(n)) return true;
                return false;
            }

            private String vkey(Injected i, String key) { return i.script.id() + "\t" + key; }

            @JavascriptInterface public String getValue(String nonce, String key) {
                Injected i = inj(nonce);
                return i == null || key == null ? null : values().getString(vkey(i, key), null);
            }

            @JavascriptInterface public void setValue(String nonce, String key, String json) {
                Injected i = inj(nonce);
                if (i == null || key == null || json == null || json.length() > 1024 * 1024) return;
                String old = values().getString(vkey(i, key), null);
                values().edit().putString(vkey(i, key), json).apply();
                broadcastValue(t, i, key, old, json);
            }

            @JavascriptInterface public void deleteValue(String nonce, String key) {
                Injected i = inj(nonce);
                if (i == null || key == null) return;
                String old = values().getString(vkey(i, key), null);
                values().edit().remove(vkey(i, key)).apply();
                if (old != null) broadcastValue(t, i, key, old, null);
            }

            @JavascriptInterface public String listValues(String nonce) {
                Injected i = inj(nonce);
                JSONArray a = new JSONArray();
                if (i != null) {
                    String prefix = i.script.id() + "\t";
                    for (String k : values().getAll().keySet()) if (k.startsWith(prefix)) a.put(k.substring(prefix.length()));
                }
                return forJs(a.toString());
            }

            @JavascriptInterface public void registerMenu(String nonce, int id, String name) {
                Injected i = inj(nonce);
                if (i != null && name != null && t.menu.size() < 50) t.menu.add(new MenuCmd(i, id, name));
            }

            @JavascriptInterface public void unregisterMenu(String nonce, int id) {
                Injected i = inj(nonce);
                if (i == null) return;
                for (MenuCmd m : t.menu) if (m.inj == i && m.id == id) t.menu.remove(m);
            }

            @JavascriptInterface public void openTab(String nonce, final String url, final boolean background) {
                Injected i = inj(nonce);
                if (i == null || url == null || !granted(i, "GM_openInTab", "GM.openInTab")) return;
                String low = url.toLowerCase(Locale.ROOT);
                if (!(low.startsWith("http://") || low.startsWith("https://"))) return;
                h.post(new Runnable() {
                    @Override public void run() {
                        if (closed || tabs.size() >= 60) return;
                        newTab(url, t.profile, t, !background);
                    }
                });
            }

            @JavascriptInterface public void setClipboard(String nonce, final String text) {
                Injected i = inj(nonce);
                if (i == null || text == null || !granted(i, "GM_setClipboard", "GM.setClipboard")) return;
                h.post(new Runnable() {
                    @Override public void run() {
                        try {
                            ((ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE))
                                    .setPrimaryClip(ClipData.newPlainText("script", text));
                            toast("Скопировано");
                        } catch (Throwable ignored) { }
                    }
                });
            }

            @JavascriptInterface public void xhr(String nonce, int id, String reqJson) {
                Injected i = inj(nonce);
                if (i == null || reqJson == null || !granted(i, "GM_xmlhttpRequest", "GM.xmlHttpRequest", "GM.xmlhttpRequest")) return;
                runXhr(t, i, id, reqJson);
            }

            /** document-start скрипт спрашивает, подходит ли ему адрес загруженной страницы (@match/@include/@exclude и выключатель). */
            @JavascriptInterface public boolean applies(String nonce, String url) {
                Injected i = inj(nonce);
                if (i == null || url == null) return false;
                if (!sp.getBoolean("script_on:" + i.script.fileName, true)) return false;
                String low = url.toLowerCase(Locale.ROOT);
                if (!(low.startsWith("http://") || low.startsWith("https://"))) return false;
                return i.script.applies(url);
            }

            @JavascriptInterface public void notif(String nonce, String title, String text) {
                Injected i = inj(nonce);
                if (i == null || !granted(i, "GM_notification", "GM.notification")) return;
                final String msg = (title != null && title.length() > 0 ? title + ": " : "") + (text == null ? "" : text);
                h.post(new Runnable() {
                    @Override public void run() { if (!closed) BrowserView.this.toast(msg); }
                });
            }

            @JavascriptInterface public void dl(String nonce, final String url, final String name) {
                Injected i = inj(nonce);
                if (i == null || url == null || !granted(i, "GM_download", "GM.download")) return;
                h.post(new Runnable() {
                    @Override public void run() {
                        if (closed) return;
                        String cd = name != null && name.length() > 0 ? "attachment; filename=\"" + name.replace("\"", "") + "\"" : null;
                        BrowserView.this.download(t, url, t.wv != null ? t.wv.getSettings().getUserAgentString() : null, cd, null);
                    }
                });
            }

            @JavascriptInterface public void xhrAbort(String nonce, int id) {
                Injected i = inj(nonce);
                if (i == null) return;
                String key = i.nonce + ":" + id;
                t.aborted.add(key);
                HttpURLConnection c = t.conns.get(key);
                if (c != null) c.disconnect();
            }
        }

        // ------------------------------------------------------------ озвучка для скриптов (speechSynthesis поверх TextToSpeech)

        private volatile android.speech.tts.TextToSpeech tts;
        private volatile boolean ttsReady;
        private final List<Runnable> ttsPending = new ArrayList<Runnable>();          // только главный поток
        private final Map<String, Tab> ttsOwner = new ConcurrentHashMap<String, Tab>();

        /** Движок создаётся при первой фразе (или первом запросе голосов); вызывать на главном потоке. */
        private void ensureTts() {
            if (tts != null || closed) return;
            tts = new android.speech.tts.TextToSpeech(act.getApplicationContext(),
                    new android.speech.tts.TextToSpeech.OnInitListener() {
                @Override public void onInit(int status) {
                    ttsReady = status == android.speech.tts.TextToSpeech.SUCCESS;
                    if (ttsReady) {
                        tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                            @Override public void onStart(String id) { ttsEvent(id, "start"); }
                            @Override public void onDone(String id) { ttsEvent(id, "end"); }
                            @Override public void onError(String id) { ttsEvent(id, "error"); }
                        });
                    }
                    h.post(new Runnable() {
                        @Override public void run() {
                            List<Runnable> jobs = new ArrayList<Runnable>(ttsPending);
                            ttsPending.clear();
                            if (ttsReady) for (Runnable r : jobs) r.run();
                            else if (!jobs.isEmpty()) toast("Не удалось запустить голосовой движок Android");
                            for (Tab t : tabs) {
                                if (t.wv != null) t.wv.evaluateJavascript("try{window.__ubTtsEv('','voices')}catch(e){}", null);
                            }
                        }
                    });
                }
            });
        }

        /** Событие движка (поток биндера) уходит странице на главном потоке. */
        private void ttsEvent(final String id, final String kind) {
            final Tab t = ttsOwner.get(id);
            if (t == null) return;
            if (!kind.equals("start")) ttsOwner.remove(id);
            h.post(new Runnable() {
                @Override public void run() {
                    if (!closed && t.wv != null) {
                        t.wv.evaluateJavascript("try{window.__ubTtsEv(" + q(id) + "," + q(kind) + ")}catch(e){}", null);
                    }
                }
            });
        }

        private final class TtsBridge {
            private final Tab t;
            TtsBridge(Tab t) { this.t = t; }

            @JavascriptInterface public void speak(final String id, final String text, final String lang,
                                                   final float rate, final float pitch) {
                if (id == null || text == null || text.length() == 0) return;
                h.post(new Runnable() {
                    @Override public void run() {
                        if (closed) return;
                        ensureTts();
                        Runnable job = new Runnable() {
                            @Override public void run() {
                                android.speech.tts.TextToSpeech e = tts;
                                if (e == null) return;
                                try {
                                    if (lang != null && lang.length() > 0) {
                                        int languageStatus = e.setLanguage(Locale.forLanguageTag(lang));
                                        if (languageStatus == android.speech.tts.TextToSpeech.LANG_MISSING_DATA
                                                || languageStatus == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
                                            e.setLanguage(Locale.getDefault());
                                        }
                                    }
                                    e.setSpeechRate(Math.max(0.1f, Math.min(2.0f, rate > 0 ? rate : 1f)));
                                    e.setPitch(Math.max(0.1f, Math.min(2.0f, pitch > 0 ? pitch : 1f)));
                                    ttsOwner.put(id, t);
                                    String part = text.length() > 3900 ? text.substring(0, 3900) : text;     // Android TTS limit
                                    int result = e.speak(part, android.speech.tts.TextToSpeech.QUEUE_ADD, null, id);
                                    if (result != android.speech.tts.TextToSpeech.SUCCESS) {
                                        ttsOwner.remove(id);
                                        ttsEvent(id, "error");
                                    }
                                } catch (Throwable error) {
                                    ttsOwner.remove(id);
                                    ttsEvent(id, "error");
                                }
                            }
                        };
                        if (ttsReady) job.run(); else ttsPending.add(job);
                    }
                });
            }

            @JavascriptInterface public void cancel() {
                h.post(new Runnable() {
                    @Override public void run() {
                        ttsPending.clear();
                        ttsOwner.clear();
                        android.speech.tts.TextToSpeech e = tts;
                        if (e != null) { try { e.stop(); } catch (Throwable ignored) { } }
                    }
                });
            }

            /** JSON [{n: имя, l: язык}]; пока движок не готов, пусто, а страница получит voiceschanged. */
            @JavascriptInterface public String voices() {
                JSONArray a = new JSONArray();
                android.speech.tts.TextToSpeech e = tts;
                if (e == null) {
                    h.post(new Runnable() { @Override public void run() { ensureTts(); } });
                    return "[]";
                }
                try {
                    if (ttsReady) {
                        Set<android.speech.tts.Voice> vs = e.getVoices();
                        if (vs != null) {
                            for (android.speech.tts.Voice v : vs) {
                                JSONObject o = new JSONObject();
                                o.put("n", v.getName());
                                o.put("l", v.getLocale().toLanguageTag());
                                a.put(o);
                            }
                        }
                    }
                } catch (Throwable ignored) { }
                return forJs(a.toString());
            }
        }

        // ------------------------------------------------------------ интерфейс

        private int dp(int v) { return Math.round(v * density); }

        private void toast(String s) { Toast.makeText(act, s, Toast.LENGTH_LONG).show(); }

        private String profileName(int idx) {
            int i = Math.max(0, Math.min(PROFILES - 1, idx));
            String n = sp.getString("profile_name_" + i, PROFILE_DEFAULT_NAMES[i]).trim();
            return n.length() == 0 ? PROFILE_DEFAULT_NAMES[i] : n;
        }

        private int profileColor(int idx) { return PROFILE_COLORS[Math.max(0, Math.min(PROFILES - 1, idx))]; }

        private String displayUrl(Tab t) {
            if (t.blocked) return "Профиль недоступен";
            String host = hostOf(t.url);
            if (host.startsWith("www.")) host = host.substring(4);
            if (host.length() > 0) return host;
            return t.url == null ? "" : t.url;
        }

        private String tabTitle(Tab t) {
            if (t.title != null && t.title.trim().length() > 0) return t.title.trim();
            String d = displayUrl(t);
            return d.length() == 0 ? "Новая вкладка" : d;
        }

        private void refreshUi() {
            Tab t = cur;
            if (t == null) return;
            if (!urlField.hasFocus()) urlField.setText(displayUrl(t));
            bar.set(t.progress, profileColor(t.profile));
            boolean back = (t.wv != null && t.wv.canGoBack()) || (t.opener != null && tabs.contains(t.opener));
            setEnabledLook(btnBack, back);
            setEnabledLook(btnFwd, t.wv != null && t.wv.canGoForward());
            btnReload.setText(t.loading ? "✕" : "↻");
            btnReload.setContentDescription(t.loading ? "Остановить загрузку" : "Обновить страницу");
            btnTabs.setText(String.valueOf(tabs.size()));
            btnTabs.setContentDescription("Вкладки: открыто " + tabs.size() + ", сейчас " + (tabs.indexOf(t) + 1)
                    + ", профиль " + profileName(t.profile));
            updateHub(t);
        }

        private void setEnabledLook(TextView b, boolean on) {
            b.setEnabled(on);
            b.setAlpha(on ? 1f : 0.35f);
        }

        private void hideKeyboard() {
            try {
                InputMethodManager m = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (m != null) m.hideSoftInputFromWindow(getWindowToken(), 0);
            } catch (Throwable ignored) { }
        }

        private GradientDrawable round(int color, int radiusDp, int strokeColor, int strokeDp) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(color);
            g.setCornerRadius(dp(radiusDp));
            if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor);
            return g;
        }

        /** Подсветка нажатия и фокуса: фокус виден при управлении кнопками геймпада и клавиатуры. */
        private StateListDrawable pressable() {
            StateListDrawable sl = new StateListDrawable();
            sl.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(0x44FFFFFF));
            sl.addState(new int[]{android.R.attr.state_focused}, new ColorDrawable(0x33FFFFFF));
            sl.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
            return sl;
        }

        private StateListDrawable pressableFrame() {
            StateListDrawable sl = new StateListDrawable();
            sl.addState(new int[]{android.R.attr.state_pressed}, round(0x44FFFFFF, 8, C_TEXT, 2));
            sl.addState(new int[]{android.R.attr.state_focused}, round(0x33FFFFFF, 8, C_TEXT, 2));
            sl.addState(new int[]{}, round(Color.TRANSPARENT, 8, C_TEXT2, 2));
            return sl;
        }

        private TextView toolButton(String glyph, String desc, View.OnClickListener l) {
            TextView b = new TextView(act);
            b.setText(glyph);
            b.setTextSize(24);
            b.setTextColor(C_TEXT);
            b.setGravity(Gravity.CENTER);
            b.setContentDescription(desc);
            b.setClickable(true);
            b.setFocusable(true);
            b.setBackground(pressable());
            b.setOnClickListener(l);
            return b;
        }

        private TextView label(String s, int sizeSp, int color, boolean bold) {
            TextView v = new TextView(act);
            v.setText(s);
            v.setTextSize(sizeSp);
            v.setTextColor(color);
            if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
            return v;
        }

        private TextView glyph(String g, int color) {
            TextView v = new TextView(act);
            v.setText(g);
            v.setTextSize(22);
            v.setTextColor(color);
            v.setGravity(Gravity.CENTER);
            return v;
        }

        private TextView glyph(String g) { return glyph(g, C_TEXT); }

        /** Строка меню: сначала значок, потом текст. Значок скрыт от экранного диктора, строку он читает по тексту. */
        private View row(View icon, String text, final Runnable action) {
            LinearLayout v = new LinearLayout(act);
            v.setOrientation(LinearLayout.HORIZONTAL);
            v.setGravity(Gravity.CENTER_VERTICAL);
            v.setPadding(dp(16), dp(8), dp(18), dp(8));
            v.setMinimumHeight(dp(52));
            v.setBackground(pressable());
            v.setClickable(true);
            v.setFocusable(true);
            v.setContentDescription(text);
            if (icon != null) {
                icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                v.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));
            }
            TextView tv = label(text, 17, C_TEXT, false);
            tv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
            tlp.setMargins(dp(12), 0, 0, 0);
            v.addView(tv, tlp);
            v.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) {
                    closeLayer(false);
                    if (action != null) action.run();
                }
            });
            return v;
        }

        private GradientDrawable ring(int color, int sizeDp) {
            return round(color, sizeDp / 2, 0xB3000000, 2);        // тёмный контур, чтобы значок читался на любом снимке
        }

        /** Значок сайта: favicon, а если его нет, буква хоста в цветном круге. */
        private View siteIcon(Tab t, int sizeDp) {
            Bitmap ic = iconOf(t);
            if (ic != null) {
                ImageView iv = new ImageView(act);
                iv.setImageBitmap(ic);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setBackground(ring(0xFFEEEEEE, sizeDp));
                int pad = Math.max(1, dp(sizeDp) / 7);
                iv.setPadding(pad, pad, pad, pad);
                return iv;
            }
            String host = displayUrl(t);
            String letter = host.length() > 0 && Character.isLetterOrDigit(host.charAt(0))
                    ? host.substring(0, 1).toUpperCase(Locale.ROOT) : "🌐";
            int[] pal = {0xFF90A4AE, 0xFFA1887F, 0xFF9575CD, 0xFF4DB6AC, 0xFFF06292, 0xFFAED581};
            TextView tv = new TextView(act);
            tv.setText(letter);
            tv.setTextColor(0xFF121212);
            tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
            tv.setTextSize(Math.max(10, sizeDp * 0.42f));
            tv.setGravity(Gravity.CENTER);
            tv.setBackground(ring(pal[Math.abs(host.hashCode()) % pal.length], sizeDp));
            return tv;
        }

        private final String[] PROFILE_GLYPHS = {"👤", "🏠", "💼", "🛒", "🎮", "📺", "🎵", "📚", "✈️", "💬",
                "⭐", "🔒", "❤️", "🍀", "🔧", "🎓", "📰", "💰", "📷", "🧪", "⚽", "🍕", "🐱", "🌍"};

        private File profileImageFile(int idx) { return new File(act.getFilesDir(), "ubprofile_" + idx + ".png"); }

        private Bitmap profileImage(int idx) {
            Integer k = Integer.valueOf(idx);
            Bitmap b = profileImgs.get(k);
            if (b == null) {
                b = loadBitmap(profileImageFile(idx));
                if (b != null) profileImgs.put(k, b);
            }
            return b;
        }

        /** Значок профиля: своя картинка, либо эмодзи, либо номер, на цветном круге профиля. */
        private View profileBadge(int idx, int sizeDp) {
            int i = Math.max(0, Math.min(PROFILES - 1, idx));
            Bitmap img = profileImage(i);
            if (img != null) {
                ImageView iv = new ImageView(act);
                iv.setImageBitmap(img);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackground(ring(profileColor(i), sizeDp));
                iv.setClipToOutline(true);
                iv.setContentDescription(profileName(i));
                return iv;
            }
            String glyph = sp.getString("profile_glyph_" + i, "");
            TextView tv = new TextView(act);
            tv.setText(glyph.length() > 0 ? glyph : String.valueOf(i + 1));
            tv.setTextColor(0xFF121212);
            if (glyph.length() == 0) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
            tv.setTextSize(Math.max(10, sizeDp * (glyph.length() > 0 ? 0.5f : 0.46f)));
            tv.setGravity(Gravity.CENTER);
            tv.setIncludeFontPadding(false);
            tv.setBackground(ring(profileColor(i), sizeDp));
            tv.setContentDescription(profileName(i));
            return tv;
        }

        private void saveProfileImage(final int idx, final Uri uri) {
            try {
                pool.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            InputStream in = act.getContentResolver().openInputStream(uri);
                            Bitmap src;
                            try {
                                BitmapFactory.Options o = new BitmapFactory.Options();
                                o.inSampleSize = 1;
                                byte[] data = Net.readAll(in, 12 * 1024 * 1024);
                                BitmapFactory.Options b = new BitmapFactory.Options();
                                b.inJustDecodeBounds = true;
                                BitmapFactory.decodeByteArray(data, 0, data.length, b);
                                while (Math.max(b.outWidth, b.outHeight) / (o.inSampleSize * 2) >= 256) o.inSampleSize *= 2;
                                src = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                            } finally {
                                if (in != null) in.close();
                            }
                            if (src == null) throw new IOException("не картинка");
                            int side = Math.min(src.getWidth(), src.getHeight());
                            Bitmap sq = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
                            final Bitmap fin = Bitmap.createScaledBitmap(sq, 128, 128, true);
                            OutputStream o2 = new FileOutputStream(profileImageFile(idx));
                            try { fin.compress(Bitmap.CompressFormat.PNG, 90, o2); } finally { o2.close(); }
                            h.post(new Runnable() {
                                @Override public void run() {
                                    if (closed) return;
                                    profileImgs.put(Integer.valueOf(idx), fin);
                                    sp.edit().putString("profile_glyph_" + idx, "").apply();
                                    toast("Значок профиля заменён");
                                    if (tabsPanel) showTabs();
                                }
                            });
                        } catch (Throwable e) {
                            h.post(new Runnable() { @Override public void run() { toast("Не удалось использовать картинку"); } });
                        }
                    }
                });
            } catch (Throwable ignored) { }
        }

        /** Список профилей: тап открывает замену значка. */
        private void showProfileList() {
            List<View> icons = new ArrayList<View>();
            List<String> labels = new ArrayList<String>();
            List<Runnable> actions = new ArrayList<Runnable>();
            for (int i = 0; i < PROFILES; i++) {
                final int idx = i;
                icons.add(profileBadge(i, 30));
                labels.add(profileName(i));
                actions.add(new Runnable() { @Override public void run() { showProfileIconEditor(idx); } });
            }
            showList("Значки профилей", icons, labels, actions);
        }

        /** Замена значка профиля: эмодзи, номер, своя картинка; здесь же переименование. */
        private void showProfileIconEditor(final int idx) {
            LinearLayout c = card();
            c.addView(panelTitle("Значок: " + profileName(idx)));
            int perRow = 6;
            LinearLayout rowL = null;
            for (int i = 0; i < PROFILE_GLYPHS.length; i++) {
                if (i % perRow == 0) {
                    rowL = new LinearLayout(act);
                    rowL.setOrientation(LinearLayout.HORIZONTAL);
                    rowL.setPadding(dp(8), 0, dp(8), 0);
                    c.addView(rowL, new LinearLayout.LayoutParams(-1, -2));
                }
                final String g = PROFILE_GLYPHS[i];
                TextView cell = glyph(g);
                cell.setTextSize(26);
                cell.setBackground(pressable());
                cell.setClickable(true);
                cell.setFocusable(true);
                cell.setContentDescription("Значок " + g);
                cell.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { profileImageFile(idx).delete(); } catch (Throwable ignored) { }
                        profileImgs.remove(Integer.valueOf(idx));
                        sp.edit().putString("profile_glyph_" + idx, g).apply();
                        closeLayer(false);
                        if (tabsPanel) showTabs(); else showProfileList();
                    }
                });
                rowL.addView(cell, new LinearLayout.LayoutParams(0, dp(52), 1f));
            }
            c.addView(row(glyph("№"), "Показывать номер", new Runnable() { @Override public void run() {
                try { profileImageFile(idx).delete(); } catch (Throwable ignored) { }
                profileImgs.remove(Integer.valueOf(idx));
                sp.edit().putString("profile_glyph_" + idx, "").apply();
                if (tabsPanel) showTabs(); else showProfileList();
            } }));
            c.addView(row(glyph("🖼"), "Своя картинка…", new Runnable() { @Override public void run() {
                pendingProfile = idx;
                try {
                    act.startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                            .addCategory(Intent.CATEGORY_OPENABLE), REQ_PROFILE_IMG);
                } catch (Throwable e) {
                    pendingProfile = -1;
                    toast("Нет приложения для выбора картинки");
                }
            } }));
            c.addView(row(glyph("✎"), "Переименовать профиль", new Runnable() { @Override public void run() {
                showPrompt("Название профиля", null, profileName(idx), new PromptDone() {
                    @Override public void done(String value) {
                        if (value != null && value.trim().length() > 0) sp.edit().putString("profile_name_" + idx, value.trim()).apply();
                        showProfileList();
                    }
                });
            } }));
            showLayer(c, false, null, "Значок профиля " + profileName(idx));
        }

        private void installSwipeOnUrlField() {
            final boolean[] flung = new boolean[1];
            final GestureDetector gd = new GestureDetector(act, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                    if (e1 == null || e2 == null) return false;
                    float dx = e2.getX() - e1.getX();
                    if (Math.abs(dx) > dp(60) && Math.abs(dx) > Math.abs(e2.getY() - e1.getY()) * 1.5f && Math.abs(vx) > 400) {
                        flung[0] = true;
                        switchTab(dx < 0 ? 1 : -1);          // смахивание по адресной строке переключает вкладки
                        return true;
                    }
                    return false;
                }
            });
            urlField.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent e) {
                    if (urlField.hasFocus()) return false;
                    gd.onTouchEvent(e);
                    int a = e.getActionMasked();
                    if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                        boolean f = flung[0];
                        flung[0] = false;
                        return f;
                    }
                    return false;
                }
            });
        }

        // ------------------------------------------------------------ панели поверх страницы (не всплывающие окна)

        private void showLayer(View body, boolean full, Runnable cancel, String announce) {
            closeLayer(true);
            setDockHidden(false, false);
            FrameLayout l = new FrameLayout(act);
            l.setClickable(true);
            if (full) {
                l.setBackgroundColor(C_BG);
                l.addView(body, new FrameLayout.LayoutParams(-1, -1));
            } else {
                l.setBackgroundColor(0xB3000000);
                l.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { closeLayer(true); }
                });
                MaxScroll sv = new MaxScroll(act, Math.round(act.getResources().getDisplayMetrics().heightPixels * 0.78f));
                sv.setBackground(round(C_SURFACE, 14, C_SURFACE2, 1));
                sv.setClickable(true);
                sv.addView(body, new ViewGroup.LayoutParams(-1, -2));
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
                lp.setMargins(dp(14), dp(14), dp(14), dp(14));
                l.addView(sv, lp);
            }
            layer = l;
            layerCancel = cancel;
            addView(l, new FrameLayout.LayoutParams(-1, -1));
            column.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            if (announce != null) l.announceForAccessibility(announce);
        }

        private void closeLayer(boolean runCancel) {
            if (layer == null) return;
            removeView(layer);
            layer = null;
            tabsPanel = false;
            startOpen = false;
            Runnable c = layerCancel;
            layerCancel = null;
            column.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            hideKeyboard();
            requestFocus();
            if (runCancel && c != null) c.run();
        }

        private LinearLayout card() {
            LinearLayout c = new LinearLayout(act);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setPadding(0, dp(8), 0, dp(8));
            return c;
        }

        private TextView panelTitle(String s) {
            TextView v = label(s, 18, C_TEXT, true);
            v.setPadding(dp(18), dp(12), dp(18), dp(8));
            v.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            return v;
        }

        private void showList(String title, List<View> icons, List<String> labels, List<Runnable> actions) {
            LinearLayout c = card();
            c.addView(panelTitle(title));
            for (int i = 0; i < labels.size(); i++) c.addView(row(icons.get(i), labels.get(i), actions.get(i)));
            showLayer(c, false, null, title);
        }

        private void showConfirm(String title, String msg, String okLabel, String cancelLabel, final Runnable ok, final Runnable cancel) {
            LinearLayout c = card();
            c.addView(panelTitle(title));
            TextView m = label(msg == null ? "" : msg, 15, C_TEXT2, false);
            m.setPadding(dp(18), dp(4), dp(18), dp(12));
            m.setTextIsSelectable(false);
            c.addView(m);
            c.addView(row(glyph("✔", C_ACCENT), okLabel, ok));
            if (cancelLabel != null) c.addView(row(glyph("✕"), cancelLabel, cancel));
            showLayer(c, false, cancel, title + ". " + (msg == null ? "" : msg));
        }

        private abstract class PromptDone { abstract void done(String value); }

        private void showPrompt(String title, String msg, String def, final PromptDone cb) {
            LinearLayout c = card();
            c.addView(panelTitle(title));
            TextView m = label(msg == null ? "" : msg, 15, C_TEXT2, false);
            m.setPadding(dp(18), dp(4), dp(18), dp(8));
            c.addView(m);
            final EditText in = new EditText(act);
            in.setTextColor(C_TEXT);
            in.setText(def == null ? "" : def);
            in.setSingleLine(true);
            in.setBackground(round(C_SURFACE2, 8, 0, 0));
            in.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(dp(18), 0, dp(18), dp(8));
            c.addView(in, lp);
            c.addView(row(glyph("✔", C_ACCENT), "OK", new Runnable() {
                @Override public void run() { cb.done(in.getText().toString()); }
            }));
            c.addView(row(glyph("✕"), "Отмена", new Runnable() {
                @Override public void run() { cb.done(null); }
            }));
            showLayer(c, false, new Runnable() {
                @Override public void run() { cb.done(null); }
            }, title);
        }

        /** Список профилей для новой вкладки: значок профиля, потом название. */
        private void chooseProfile(String title, final Pick pick) {
            List<View> icons = new ArrayList<View>();
            List<String> labels = new ArrayList<String>();
            List<Runnable> actions = new ArrayList<Runnable>();
            for (int i = 0; i < PROFILES; i++) {
                final int idx = i;
                boolean ok = i == 0 || Profiles.supported();
                icons.add(profileBadge(i, 28));
                labels.add(profileName(i) + (ok ? "" : " (недоступно: нет изоляции сессий)"));
                actions.add(ok ? new Runnable() {
                    @Override public void run() { pick.on(idx); }
                } : new Runnable() {
                    @Override public void run() { toast(Profiles.status()); }
                });
            }
            showList(title, icons, labels, actions);
        }

        private void showMenu() {
            final Tab t = cur;
            if (t == null) return;
            final List<GridItem> items = new ArrayList<GridItem>();
            addG(items, "new", "＋", "Новая вкладка…", new Runnable() { @Override public void run() {
                chooseProfile("Новая вкладка: выберите профиль", new Pick() {
                    @Override public void on(int idx) { newTab(home(), idx, null, true); }
                });
            } });
            addG(items, "group", "📁", "Группа этой вкладки…", new Runnable() { @Override public void run() { showTabMenu(t); } });
            if (tabs.size() > 1) {
                addG(items, "next", "›", "Следующая вкладка", new Runnable() { @Override public void run() { switchTab(1); } });
                addG(items, "prev", "‹", "Предыдущая вкладка", new Runnable() { @Override public void run() { switchTab(-1); } });
            }
            addG(items, "closetab", "✕", "Закрыть эту вкладку", new Runnable() { @Override public void run() { closeTab(t); } });
            if (t.wv != null && t.wv.canGoForward()) {
                addG(items, "fwd", "→", "Вперёд", new Runnable() { @Override public void run() { t.wv.goForward(); } });
            }
            addG(items, "reload", t.loading ? "✕" : "↻", t.loading ? "Остановить загрузку" : "Обновить страницу", new Runnable() { @Override public void run() {
                if (t.wv == null) return;
                if (t.loading) t.wv.stopLoading(); else t.wv.reload();
            } });
            addG(items, "desktop", t.desktop ? "📱" : "🖥", t.desktop ? "Версия для телефона" : "Версия для компьютера", new Runnable() { @Override public void run() {
                t.desktop = !t.desktop;
                applyUserAgent(t);
                if (t.wv != null) t.wv.reload();
            } });
            addG(items, "scripts", "📜", "Скрипты", new Runnable() { @Override public void run() { showScripts(t); } });
            addG(items, "profiles", "🎭", "Значки профилей…", new Runnable() { @Override public void run() { showProfileList(); } });
            addG(items, "fullscreen", "⛶", fullscreen ? "Выйти из полноэкранного режима" : "Полноэкранный режим", new Runnable() { @Override public void run() {
                sp.edit().putBoolean("fullscreen", !sp.getBoolean("fullscreen", false)).apply();
                applyFullscreen();
            } });
            addG(items, "bookmark", "⭐", "Добавить в закладки", new Runnable() { @Override public void run() { addBookmark(t); } });
            addG(items, "copy", "🔗", "Скопировать ссылку", new Runnable() { @Override public void run() {
                try {
                    ((ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE))
                            .setPrimaryClip(ClipData.newPlainText("url", t.url == null ? "" : t.url));
                    toast("Ссылка скопирована");
                } catch (Throwable ignored) { }
            } });
            addG(items, "share", "↗", "Поделиться ссылкой", new Runnable() { @Override public void run() {
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t.url == null ? "" : t.url);
                try { act.startActivity(Intent.createChooser(send, "Поделиться ссылкой").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Throwable ignored) { }
            } });
            addG(items, "external", "⧉", "Открыть в другом приложении", new Runnable() { @Override public void run() {
                if (t.url != null) launchExternal(t, t.url);
            } });
            addG(items, "pin", "📌", "Ярлык на рабочий стол", new Runnable() { @Override public void run() { pinShortcut(); } });
            addG(items, "exit", "⏻", "Закрыть браузер", new Runnable() { @Override public void run() { act.finish(); } });
            showIconGrid("menu", "Меню · " + profileName(t.profile), items);
        }

        // ------------------------------------------------------------ меню сеткой значков: прозрачный фон, подсказка, перетаскивание, скрытие

        private final class GridItem {
            final String key;
            final String glyph;
            final String label;
            final Runnable action;

            GridItem(String key, String glyph, String label, Runnable action) {
                this.key = key;
                this.glyph = glyph;
                this.label = label;
                this.action = action;
            }
        }

        private void addG(List<GridItem> l, String key, String glyph, String label, Runnable action) {
            l.add(new GridItem(key, glyph, label, action));
        }

        private List<String> splitKeys(String s) {
            List<String> out = new ArrayList<String>();
            if (s == null) return out;
            for (String k : s.split(",")) if (k.length() > 0) out.add(k);
            return out;
        }

        private String joinKeys(java.util.Collection<String> c) {
            StringBuilder sb = new StringBuilder();
            for (String k : c) {
                if (sb.length() > 0) sb.append(',');
                sb.append(k);
            }
            return sb.toString();
        }

        /**
         * Меню сеткой круглых значков на прозрачном фоне. Нажатие выполняет пункт, долгое нажатие показывает пояснение,
         * удержание и движение перетаскивает значок на новое место. Значок «настроить» включает режим, где нажатие
         * скрывает пункт или возвращает его. Порядок и скрытые пункты хранятся отдельно для каждого меню (scope).
         */
        private void showIconGrid(String scope, String title, List<GridItem> items) {
            closeLayer(true);
            setDockHidden(false, false);
            final GridPanel gp = new GridPanel(scope, title, items);
            FrameLayout l = new FrameLayout(act);
            l.setClickable(true);
            l.setBackgroundColor(0x1A000000);
            l.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { closeLayer(true); }
            });
            l.setClipChildren(false);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.setMargins(dp(8), dp(8), dp(8), dp(28));
            l.addView(gp.box, lp);
            layer = l;
            layerCancel = null;
            addView(l, new FrameLayout.LayoutParams(-1, -1));
            column.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            l.announceForAccessibility(title);
        }

        private final class GridPanel {
            static final int COLS = 4;
            final String scope;
            final String title;
            final Map<String, GridItem> byKey = new HashMap<String, GridItem>();
            final List<String> order = new ArrayList<String>();
            final Set<String> hidden = new HashSet<String>();
            final LinearLayout box;
            final TextView tip;
            final LinearLayout cells;
            final List<String> shownKeys = new ArrayList<String>();
            final List<View> shownViews = new ArrayList<View>();
            boolean edit;

            GridPanel(String scope, String title, List<GridItem> items) {
                this.scope = scope;
                this.title = title;
                for (GridItem g : items) byKey.put(g.key, g);
                for (String k : splitKeys(sp.getString("grid_order_" + scope, ""))) if (byKey.containsKey(k) && !order.contains(k)) order.add(k);
                for (GridItem g : items) if (!order.contains(g.key)) order.add(g.key);
                hidden.addAll(splitKeys(sp.getString("grid_hidden_" + scope, "")));

                box = new LinearLayout(act);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setGravity(Gravity.CENTER_HORIZONTAL);
                box.setClipChildren(false);
                tip = label("", 15, C_TEXT, false);
                tip.setGravity(Gravity.CENTER);
                tip.setBackground(round(0xE6202124, 14, C_SURFACE2, 1));
                tip.setPadding(dp(14), dp(8), dp(14), dp(8));
                tip.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
                tlp.setMargins(0, 0, 0, dp(10));
                box.addView(tip, tlp);
                MaxScroll sv = new MaxScroll(act, Math.round(act.getResources().getDisplayMetrics().heightPixels * 0.7f));
                sv.setClipChildren(false);
                cells = new LinearLayout(act);
                cells.setOrientation(LinearLayout.VERTICAL);
                cells.setClipChildren(false);
                sv.addView(cells, new ViewGroup.LayoutParams(-2, -2));
                box.addView(sv, new LinearLayout.LayoutParams(-2, -2));
                redraw();
            }

            void save() {
                sp.edit().putString("grid_order_" + scope, joinKeys(order)).putString("grid_hidden_" + scope, joinKeys(hidden)).apply();
            }

            void redraw() {
                cells.removeAllViews();
                shownKeys.clear();
                shownViews.clear();
                tip.setText(edit ? "Нажмите на значок: скрыть или вернуть. Перетащите, чтобы переставить" : title);
                List<View> all = new ArrayList<View>();
                for (String k : order) {
                    if (!edit && hidden.contains(k)) continue;
                    View v = cellFor(k);
                    shownKeys.add(k);
                    shownViews.add(v);
                    all.add(v);
                }
                all.add(editCell());
                LinearLayout row = null;
                for (int i = 0; i < all.size(); i++) {
                    if (i % COLS == 0) {
                        row = new LinearLayout(act);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setClipChildren(false);
                        cells.addView(row, new LinearLayout.LayoutParams(-2, -2));
                    }
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(64), dp(64));
                    lp.setMargins(dp(8), dp(8), dp(8), dp(8));
                    row.addView(all.get(i), lp);
                }
            }

            private FrameLayout disc(String glyph, String desc) {
                FrameLayout cell = new FrameLayout(act);
                cell.setClipChildren(false);
                cell.setBackground(round(0xE6202124, 32, C_SURFACE2, 1));
                TextView g = glyph(glyph);
                g.setTextSize(26);
                g.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                cell.addView(g, new FrameLayout.LayoutParams(-1, -1));
                cell.setClickable(true);
                cell.setFocusable(true);
                cell.setContentDescription(desc);
                return cell;
            }

            private View editCell() {
                FrameLayout c = disc(edit ? "✔" : "⚙", edit ? "Готово" : "Настроить меню: скрыть пункты, переставить");
                final String lab = edit ? "Готово" : "Настроить меню";
                c.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        edit = !edit;
                        redraw();
                    }
                });
                c.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override public boolean onLongClick(View v) {
                        tip.setText(lab);
                        return true;
                    }
                });
                return c;
            }

            private View cellFor(final String key) {
                final GridItem g = byKey.get(key);
                final boolean isHidden = hidden.contains(key);
                FrameLayout cell = disc(g.glyph, g.label + (edit ? (isHidden ? ", скрыт. Нажмите, чтобы вернуть" : ". Нажмите, чтобы скрыть") : ""));
                if (isHidden) cell.setAlpha(0.4f);
                if (edit) {
                    TextView badge = label(isHidden ? "🚫" : "👁", 11, C_TEXT, false);
                    badge.setGravity(Gravity.CENTER);
                    badge.setBackground(round(0xFF202124, 9, C_TEXT2, 1));
                    badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    cell.addView(badge, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP | Gravity.END));
                }
                final boolean[] held = new boolean[1];
                final boolean[] dragging = new boolean[1];
                final float[] down = new float[2];
                cell.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (edit) {
                            if (!hidden.remove(key)) hidden.add(key);
                            save();
                            redraw();
                        } else {
                            closeLayer(false);
                            if (g.action != null) g.action.run();
                        }
                    }
                });
                cell.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override public boolean onLongClick(View v) {
                        held[0] = true;
                        tip.setText(g.label);
                        return true;
                    }
                });
                cell.setOnTouchListener(new View.OnTouchListener() {
                    @Override public boolean onTouch(View v, MotionEvent e) {
                        switch (e.getActionMasked()) {
                            case MotionEvent.ACTION_DOWN:
                                down[0] = e.getRawX();
                                down[1] = e.getRawY();
                                held[0] = false;
                                dragging[0] = false;
                                return false;
                            case MotionEvent.ACTION_MOVE:
                                if (!dragging[0] && held[0]
                                        && Math.hypot(e.getRawX() - down[0], e.getRawY() - down[1]) > dp(10)) {
                                    dragging[0] = true;
                                    v.getParent().requestDisallowInterceptTouchEvent(true);
                                    ((View) v.getParent()).setElevation(dp(12));
                                    v.setElevation(dp(12));
                                    v.setScaleX(1.15f);
                                    v.setScaleY(1.15f);
                                }
                                if (dragging[0]) {
                                    v.setTranslationX(e.getRawX() - down[0]);
                                    v.setTranslationY(e.getRawY() - down[1]);
                                    return true;
                                }
                                return false;
                            case MotionEvent.ACTION_UP:
                            case MotionEvent.ACTION_CANCEL:
                                if (dragging[0]) {
                                    dragging[0] = false;
                                    v.setPressed(false);
                                    if (e.getActionMasked() == MotionEvent.ACTION_UP) drop(key, e.getRawX(), e.getRawY());
                                    else redraw();
                                    return true;
                                }
                                return false;
                            default:
                                return false;
                        }
                    }
                });
                return cell;
            }

            /** Отпустили значок над другим: он встаёт на его место, остальные сдвигаются. */
            void drop(String key, float rawX, float rawY) {
                int[] loc = new int[2];
                for (int i = 0; i < shownViews.size(); i++) {
                    String tk = shownKeys.get(i);
                    if (tk.equals(key)) continue;
                    View v = shownViews.get(i);
                    v.getLocationOnScreen(loc);
                    if (rawX >= loc[0] && rawX <= loc[0] + v.getWidth() && rawY >= loc[1] && rawY <= loc[1] + v.getHeight()) {
                        int ti = order.indexOf(tk);
                        order.remove(key);
                        order.add(ti, key);
                        save();
                        break;
                    }
                }
                redraw();
            }
        }

        private void add(List<View> icons, List<String> labels, List<Runnable> actions, String glyph, String label, Runnable action) {
            icons.add(glyph(glyph));
            labels.add(label);
            actions.add(action);
        }

        private boolean scriptsDirty;
        private int scriptsScrollY;
        private View scriptsLayer;
        private ScrollView scriptsScroll;

        private View scriptSwitchRow(String text, boolean on, android.widget.CompoundButton.OnCheckedChangeListener l) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(10), dp(4), dp(10));
            final android.widget.Switch sw = new android.widget.Switch(act);
            sw.setChecked(on);
            sw.setContentDescription(text);
            sw.setOnCheckedChangeListener(l);
            row.addView(sw, new LinearLayout.LayoutParams(-2, -2));
            TextView tv = label(text, 18, C_TEXT, true);
            tv.setPadding(dp(14), 0, 0, 0);
            row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { sw.toggle(); }
            });
            return row;
        }

        /** Экран как окно Tampermonkey: скрипты с переключателями, под каждым его команды меню. Изменения включения применяются обновлением страницы при закрытии. */
        private void showScripts(Tab t) {
            scriptsDirty = false;
            scriptsScrollY = 0;
            buildScripts(t);
        }

        private void buildScripts(final Tab t) {
            closeLayer(false);                      // обновление списка не должно запускать обновление страницы
            final ScrollView sv = new ScrollView(act);
            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(14), dp(14), dp(14), dp(28));
            sv.addView(col, new ViewGroup.LayoutParams(-1, -2));
            col.addView(panelTitle("Скрипты"));
            List<UserScript> scripts = ScriptStore.list();
            if (scripts.isEmpty()) {
                col.addView(label("Скриптов нет. Папка: " + ScriptStore.dir().getPath(), 15, C_TEXT2, false));
            }
            for (final UserScript s : scripts) {
                col.addView(scriptSwitchRow("📄  " + s.name + (s.version.length() > 0 ? " " + s.version : ""),
                        sp.getBoolean("script_on:" + s.fileName, true),
                        new android.widget.CompoundButton.OnCheckedChangeListener() {
                            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                                sp.edit().putBoolean("script_on:" + s.fileName, on).apply();
                                scriptsDirty = true;
                            }
                        }));
                for (final MenuCmd m : new ArrayList<MenuCmd>(t.menu)) {
                    if (!m.inj.script.fileName.equals(s.fileName)) continue;
                    LinearLayout row = new LinearLayout(act);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(20), dp(10), dp(4), dp(10));
                    TextView gear = label("⚙", 22, C_TEXT2, false);
                    row.addView(gear, new LinearLayout.LayoutParams(dp(34), -2));
                    row.addView(label(m.name, 16, C_TEXT, false), new LinearLayout.LayoutParams(0, -2, 1f));
                    row.setClickable(true);
                    row.setContentDescription(m.name);
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (t.wv != null) t.wv.evaluateJavascript("try{window['" + m.inj.disp + "'].m(" + m.id + ")}catch(e){}", null);
                            final View mine = scriptsLayer;
                            h.postDelayed(new Runnable() {
                                @Override public void run() {
                                    if (closed || layer == null || layer != mine) return;
                                    if (scriptsScroll != null) scriptsScrollY = scriptsScroll.getScrollY();
                                    buildScripts(t);       // скрипт мог переименовать команду, например «Жесты наклона: Вкл» -> «Выкл»
                                }
                            }, 450);
                        }
                    });
                    col.addView(row);
                }
            }
            showLayer(sv, true, new Runnable() {
                @Override public void run() {
                    if (scriptsDirty && t.wv != null) {
                        scriptsDirty = false;
                        t.wv.reload();
                    }
                }
            }, "Скрипты");
            scriptsLayer = layer;
            scriptsScroll = sv;
            sv.post(new Runnable() {
                @Override public void run() { sv.scrollTo(0, scriptsScrollY); }
            });
        }

        private void showScriptCommands(final Tab t) {
            List<View> icons = new ArrayList<View>();
            List<String> labels = new ArrayList<String>();
            List<Runnable> actions = new ArrayList<Runnable>();
            for (final MenuCmd m : t.menu) {
                icons.add(glyph("▶", C_ACCENT));
                labels.add(m.name + "  (" + m.inj.script.name + ")");
                actions.add(new Runnable() { @Override public void run() {
                    if (t.wv != null) t.wv.evaluateJavascript("try{window['" + m.inj.disp + "'].m(" + m.id + ")}catch(e){}", null);
                } });
            }
            showList("Команды скриптов", icons, labels, actions);
        }

        // ------------------------------------------------------------ закладки: ряд значков в панели вкладок

        private final class Bookmark {
            final long id;
            String url;
            String title;
            int profile;
            Bitmap icon;
            boolean iconTried;

            Bookmark(long id, String url, String title, int profile) {
                this.id = id;
                this.url = url;
                this.title = title;
                this.profile = profile;
            }
        }

        private File bookmarkIconFile(Bookmark b) { return new File(thumbDir(), "bm_" + b.id + ".png"); }

        private void loadBookmarks() {
            if (bookmarksLoaded) return;
            bookmarksLoaded = true;
            bookmarks.clear();
            try {
                JSONArray a = new JSONArray(sp.getString("bookmarks", "[]"));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    String u = o.optString("u", "");
                    if (u.length() == 0) continue;
                    bookmarks.add(new Bookmark(o.optLong("i", System.currentTimeMillis() + i), u, o.optString("t", ""),
                            Math.max(0, Math.min(PROFILES - 1, o.optInt("p", 0)))));
                }
            } catch (Throwable ignored) { }
        }

        private void saveBookmarks() {
            try {
                JSONArray a = new JSONArray();
                for (Bookmark b : bookmarks) {
                    JSONObject o = new JSONObject();
                    o.put("i", b.id);
                    o.put("u", b.url);
                    o.put("t", b.title);
                    o.put("p", b.profile);
                    a.put(o);
                }
                sp.edit().putString("bookmarks", a.toString()).apply();
            } catch (Throwable ignored) { }
        }

        private void addBookmark(Tab t) {
            loadBookmarks();
            String u = t == null ? null : t.url;
            if (u == null || !(u.startsWith("http://") || u.startsWith("https://"))) { toast("Эту страницу в закладки не добавить"); return; }
            for (Bookmark x : bookmarks) if (x.url.equals(u)) { toast("Уже в закладках"); return; }
            Bookmark b = new Bookmark(System.currentTimeMillis(), u, tabTitle(t), t.profile);
            Bitmap ic = iconOf(t);
            if (ic != null) {
                b.icon = ic;
                b.iconTried = true;
                saveBitmapAsync(ic, bookmarkIconFile(b), true);
            }
            bookmarks.add(b);
            saveBookmarks();
            toast("Добавлено в закладки");
            if (ic == null) fetchBookmarkIcon(b);
        }

        private Bitmap bookmarkIconOf(Bookmark b) {
            if (b.icon == null && !b.iconTried) {
                b.iconTried = true;
                b.icon = loadBitmap(bookmarkIconFile(b));
                if (b.icon == null) fetchBookmarkIcon(b);
            }
            return b.icon;
        }

        /** Значок закладки: favicon.ico с хоста страницы; пока его нет, в ряду буква хоста. */
        private void fetchBookmarkIcon(final Bookmark b) {
            final String host = hostOf(b.url);
            if (host.length() == 0) return;
            final String target = (b.url.startsWith("http://") ? "http://" : "https://") + host + "/favicon.ico";
            try {
                pool.execute(new Runnable() {
                    @Override public void run() {
                        HttpURLConnection c = null;
                        try {
                            c = (HttpURLConnection) new URL(target).openConnection();
                            c.setConnectTimeout(8000);
                            c.setReadTimeout(8000);
                            c.setRequestProperty("User-Agent", "UniversalBrowser/1");
                            if (c.getResponseCode() != 200) return;
                            byte[] data = Net.readAll(c.getInputStream(), 200 * 1024);
                            Bitmap raw = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
                            if (raw == null || raw.getWidth() < 8) return;
                            final Bitmap sc = Bitmap.createScaledBitmap(raw, dp(48), dp(48), true);
                            h.post(new Runnable() {
                                @Override public void run() {
                                    if (closed || !bookmarks.contains(b)) return;
                                    b.icon = sc;
                                    saveBitmapAsync(sc, bookmarkIconFile(b), true);
                                    if (tabsPanel && openGroup == null) showTabs();
                                }
                            });
                        } catch (Throwable ignored) {
                        } finally {
                            if (c != null) c.disconnect();
                        }
                    }
                });
            } catch (Throwable ignored) { }
        }

        private View bookmarkIcon(Bookmark b, int sizeDp) {
            Bitmap ic = bookmarkIconOf(b);
            if (ic != null) {
                ImageView iv = new ImageView(act);
                iv.setImageBitmap(ic);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setBackground(round(0xFFEEEEEE, sizeDp / 2, C_SURFACE2, 1));
                int pad = Math.max(1, dp(sizeDp) / 6);
                iv.setPadding(pad, pad, pad, pad);
                return iv;
            }
            String host = hostOf(b.url);
            if (host.startsWith("www.")) host = host.substring(4);
            String letter = host.length() > 0 && Character.isLetterOrDigit(host.charAt(0))
                    ? host.substring(0, 1).toUpperCase(Locale.ROOT) : "🌐";
            int[] pal = {0xFF90A4AE, 0xFFA1887F, 0xFF9575CD, 0xFF4DB6AC, 0xFFF06292, 0xFFAED581};
            TextView tv = new TextView(act);
            tv.setText(letter);
            tv.setTextColor(0xFF121212);
            tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
            tv.setTextSize(Math.max(10, sizeDp * 0.42f));
            tv.setGravity(Gravity.CENTER);
            tv.setBackground(round(pal[Math.abs(host.hashCode()) % pal.length], sizeDp / 2, C_SURFACE2, 1));
            return tv;
        }

        /** Профиль, в котором откроется закладка: выбранный в ряду, а если не выбран, профиль текущей вкладки. */
        private int bookmarkTargetProfile() {
            return bmProfile >= 0 ? bmProfile : (cur != null ? cur.profile : 0);
        }

        private void openBookmark(Bookmark b, int profile, boolean select) {
            if (profile != 0 && !Profiles.supported()) { toast(Profiles.status()); return; }
            if (select) closeLayer(false);
            newTab(b.url, profile, null, select);
            if (!select) { toast("Открыто в фоне: " + b.title); showTabs(); }
        }

        /** Ряд значков закладок: слева значок профиля (выбор профиля для открытия), справа «+» (добавить текущую страницу). */
        private View bookmarkStrip() {
            loadBookmarks();
            final int sel = bookmarkTargetProfile();
            HorizontalScrollView hs = new HorizontalScrollView(act);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout r = new LinearLayout(act);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(10), dp(4), dp(10), dp(6));
            hs.addView(r, new ViewGroup.LayoutParams(-2, -2));

            View pb = profileBadge(sel, 44);
            pb.setClickable(true);
            pb.setFocusable(true);
            pb.setContentDescription("Профиль для закладок: " + profileName(sel) + ". Нажмите, чтобы выбрать другой");
            pb.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    chooseProfile("Открывать закладки в профиле", new Pick() {
                        @Override public void on(int idx) {
                            bmProfile = idx;
                            showTabs();
                        }
                    });
                }
            });
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(44), dp(44));
            plp.setMargins(0, 0, dp(12), 0);
            r.addView(pb, plp);

            for (final Bookmark b : new ArrayList<Bookmark>(bookmarks)) {
                View ic = bookmarkIcon(b, 44);
                ic.setClickable(true);
                ic.setFocusable(true);
                ic.setContentDescription("Закладка: " + b.title + ", " + hostOf(b.url) + ", профиль " + profileName(sel));
                ic.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { openBookmark(b, bookmarkTargetProfile(), true); }
                });
                ic.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override public boolean onLongClick(View v) {
                        showBookmarkMenu(b);
                        return true;
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
                lp.setMargins(0, 0, dp(10), 0);
                r.addView(ic, lp);
            }

            TextView plus = glyph("⭐", C_TEXT);
            plus.setBackground(round(C_SURFACE2, 22, 0, 0));
            plus.setClickable(true);
            plus.setFocusable(true);
            plus.setContentDescription("Добавить текущую страницу в закладки");
            plus.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    addBookmark(cur);
                    showTabs();
                }
            });
            r.addView(plus, new LinearLayout.LayoutParams(dp(44), dp(44)));
            return hs;
        }

        /** Долгое нажатие на закладку. */
        private void showBookmarkMenu(final Bookmark b) {
            List<View> icons = new ArrayList<View>();
            List<String> labels = new ArrayList<String>();
            List<Runnable> actions = new ArrayList<Runnable>();
            add(icons, labels, actions, "↗", "Открыть в профиле…", new Runnable() { @Override public void run() {
                chooseProfile("Открыть в профиле", new Pick() {
                    @Override public void on(int idx) { openBookmark(b, idx, true); }
                });
            } });
            add(icons, labels, actions, "⧉", "Открыть в фоне", new Runnable() { @Override public void run() {
                openBookmark(b, bookmarkTargetProfile(), false);
            } });
            add(icons, labels, actions, "✎", "Переименовать", new Runnable() { @Override public void run() {
                showPrompt("Название закладки", null, b.title, new PromptDone() {
                    @Override public void done(String value) {
                        if (value != null && value.trim().length() > 0) {
                            b.title = value.trim();
                            saveBookmarks();
                        }
                        afterBookmarkChange();
                    }
                });
            } });
            add(icons, labels, actions, "⇤", "Переместить в начало", new Runnable() { @Override public void run() {
                bookmarks.remove(b);
                bookmarks.add(0, b);
                saveBookmarks();
                afterBookmarkChange();
            } });
            add(icons, labels, actions, "🖼", "Своя картинка…", new Runnable() { @Override public void run() {
                pendingBm = b.id;
                try {
                    act.startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                            .addCategory(Intent.CATEGORY_OPENABLE), REQ_BM_IMG);
                } catch (Throwable e) {
                    pendingBm = -1;
                    toast("Нет приложения для выбора картинки");
                }
            } });
            add(icons, labels, actions, "↺", "Вернуть значок сайта", new Runnable() { @Override public void run() {
                try { bookmarkIconFile(b).delete(); } catch (Throwable ignored) { }
                b.icon = null;
                b.iconTried = false;
                afterBookmarkChange();
            } });
            add(icons, labels, actions, "✕", "Удалить закладку", new Runnable() { @Override public void run() {
                bookmarks.remove(b);
                try { bookmarkIconFile(b).delete(); } catch (Throwable ignored) { }
                saveBookmarks();
                afterBookmarkChange();
            } });
            showList(b.title.length() > 0 ? b.title : hostOf(b.url), icons, labels, actions);
        }

        // ------------------------------------------------------------ панель вкладок: снимок, значок сайта, значок профиля; группы

        private void openTabsRoot() {
            openGroup = null;
            if (layer != null) {
                showTabs();
                return;
            }
            captureThumbThen(cur, new Runnable() {
                @Override public void run() { showTabs(); }
            });
        }

        private void showTabs() {
            cleanGroups();
            final Group og = openGroup;
            LinearLayout root = new LinearLayout(act);
            root.setOrientation(LinearLayout.VERTICAL);

            int shown = og != null ? tabsOf(og).size() : tabs.size();

            if (og != null) root.addView(outOfGroupZone(og), new LinearLayout.LayoutParams(-1, -2));

            ScrollView sv = new ScrollView(act);
            tabScroll = sv;
            LinearLayout grid = new LinearLayout(act);
            grid.setOrientation(LinearLayout.VERTICAL);
            grid.setPadding(dp(6), dp(14), dp(6), dp(16));
            sv.addView(grid, new ViewGroup.LayoutParams(-1, -2));
            root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

            List<Object> items = new ArrayList<Object>();
            if (og != null) {
                items.addAll(tabsOf(og));
            } else {
                Set<Integer> seen = new HashSet<Integer>();
                for (Tab t : tabs) {
                    if (t.group == 0) items.add(t);
                    else if (seen.add(Integer.valueOf(t.group))) items.add(groupById(t.group));     // группа стоит на месте своей первой вкладки
                }
            }
            LinearLayout rowL = null;
            for (int i = 0; i < items.size(); i++) {
                if (i % 2 == 0) {
                    rowL = new LinearLayout(act);
                    rowL.setOrientation(LinearLayout.HORIZONTAL);
                    grid.addView(rowL, new LinearLayout.LayoutParams(-1, -2));
                }
                Object it = items.get(i);
                View card = it instanceof Group ? groupCard((Group) it) : tabCard((Tab) it, i, items.size());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                lp.setMargins(dp(5), dp(5), dp(5), dp(5));
                rowL.addView(card, lp);
            }
            if (items.size() % 2 == 1 && rowL != null) {
                rowL.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
            }
            showLayer(root, true, null, og != null ? "Группа " + og.name + ": вкладок " + shown : "Вкладки: " + shown);
            tabsPanel = true;
        }

        /** Карточка: снимок страницы, поверх него крупные значки сайта (слева внизу) и профиля (справа внизу). */
        private FrameLayout cardBody(Tab t, boolean current, boolean isGroup, int count) {
            FrameLayout box = new FrameLayout(act);
            box.setBackground(round(C_SURFACE, 12, current ? C_ACCENT : (isGroup ? C_TEXT2 : C_SURFACE2), current ? 3 : (isGroup ? 2 : 1)));
            box.setClickable(true);
            box.setFocusable(true);
            box.setClipToOutline(true);

            FrameLayout thumbBox = new FrameLayout(act);
            thumbBox.setBackgroundColor(C_SURFACE2);
            thumbBox.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            Bitmap tb = thumbOf(t);
            if (tb != null) {
                ImageView iv = new ImageView(act);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageBitmap(tb);
                thumbBox.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            } else {
                TextView ph = label(displayUrl(t), 14, C_TEXT2, false);
                ph.setGravity(Gravity.CENTER);
                thumbBox.addView(ph, new FrameLayout.LayoutParams(-1, -1));
            }
            box.addView(thumbBox, new FrameLayout.LayoutParams(-1, dp(190)));

            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM | Gravity.START);
            slp.setMargins(dp(8), 0, 0, dp(8));
            box.addView(siteIcon(t, 48), slp);
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM | Gravity.END);
            plp.setMargins(0, 0, dp(8), dp(8));
            box.addView(profileBadge(t.profile, 48), plp);

            if (isGroup) {
                TextView badge = label(String.valueOf(count), 16, C_TEXT, true);
                badge.setGravity(Gravity.CENTER);
                badge.setBackground(round(0xCC000000, 16, 0, 0));
                badge.setPadding(dp(8), dp(2), dp(8), dp(2));
                badge.setMinWidth(dp(32));
                badge.setMinHeight(dp(32));
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
                blp.setMargins(dp(8), dp(8), 0, 0);
                box.addView(badge, blp);
            }
            return box;
        }

        private View tabCard(final Tab t, int index, int total) {
            boolean current = t == cur;
            final FrameLayout box = cardBody(t, current, false, 0);
            box.setContentDescription("Вкладка " + (index + 1) + " из " + total + ": " + tabTitle(t) + ", " + displayUrl(t)
                    + ", профиль " + profileName(t.profile) + (current ? ", текущая" : ""));

            // крестика на карточке нет: вкладка закрывается смахиванием или пунктом «Закрыть вкладку» в меню долгого нажатия
            final Runnable menu = new Runnable() { @Override public void run() { showTabMenu(t); } };
            attachDrop(box, t, menu);
            cardGestures(box, new Runnable() {
                @Override public void run() {
                    closeLayer(false);
                    selectTab(t);
                }
            }, new Runnable() {
                @Override public void run() { startItemDrag(box, t, menu); }
            }, new Runnable() {
                @Override public void run() {
                    closeTab(t);                     // смахнули карточку: закрыть вкладку
                    showTabs();
                }
            });
            return box;
        }

        private View groupCard(final Group g) {
            List<Tab> gt = tabsOf(g);
            Tab first = gt.get(0);
            boolean current = cur != null && cur.group == g.id;
            FrameLayout outer = new FrameLayout(act);
            View back = new View(act);
            back.setBackground(round(C_SURFACE2, 12, C_TEXT2, 1));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, -1);
            blp.setMargins(dp(7), dp(7), 0, 0);
            outer.addView(back, blp);
            final FrameLayout box = cardBody(first, current, true, gt.size());
            box.setContentDescription("Группа " + g.name + ", вкладок " + gt.size() + (current ? ", в ней текущая вкладка" : ""));
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2);
            clp.setMargins(0, 0, dp(7), dp(7));
            outer.addView(box, clp);
            final Runnable menu = new Runnable() { @Override public void run() { showGroupMenu(g); } };
            attachDrop(box, g, menu);
            cardGestures(box, new Runnable() {
                @Override public void run() {
                    openGroup = g;
                    showTabs();
                }
            }, new Runnable() {
                @Override public void run() { startItemDrag(box, g, menu); }
            }, null);
            return outer;
        }

        /** Нажатие, долгое нажатие и (если задано) смахивание карточки в сторону. Вертикальную прокрутку списка не перехватывает. */
        private void cardGestures(final View box, final Runnable onClick, final Runnable onLong, final Runnable onSwipe) {
            final float slop = ViewConfiguration.get(act).getScaledTouchSlop();
            box.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { onClick.run(); }
            });
            box.setOnTouchListener(new View.OnTouchListener() {
                private float downX, downY;
                private boolean dragging, longFired;
                private final Runnable longRun = new Runnable() {
                    @Override public void run() {
                        longFired = true;
                        box.setPressed(false);
                        if (onLong != null) onLong.run();
                    }
                };
                @Override public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = e.getRawX();
                            downY = e.getRawY();
                            dragging = false;
                            longFired = false;
                            if (onLong != null) v.postDelayed(longRun, ViewConfiguration.getLongPressTimeout());
                            return true;
                        case MotionEvent.ACTION_MOVE: {
                            float dx = e.getRawX() - downX;
                            float dy = e.getRawY() - downY;
                            if (Math.abs(dx) > slop || Math.abs(dy) > slop) v.removeCallbacks(longRun);
                            if (onSwipe != null && !dragging && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                                dragging = true;
                                v.getParent().requestDisallowInterceptTouchEvent(true);
                            }
                            if (dragging) {
                                v.setTranslationX(dx);
                                v.setAlpha(Math.max(0.3f, 1f - Math.abs(dx) / (v.getWidth() * 1.2f)));
                            }
                            return true;
                        }
                        case MotionEvent.ACTION_UP:
                            v.removeCallbacks(longRun);
                            if (longFired) return true;
                            if (dragging) {
                                if (Math.abs(v.getTranslationX()) > v.getWidth() * 0.4f) {
                                    if (onSwipe != null) onSwipe.run();
                                } else {
                                    v.animate().translationX(0).alpha(1f).setDuration(120).start();
                                }
                            } else {
                                v.performClick();
                            }
                            return true;
                        case MotionEvent.ACTION_CANCEL:
                            v.removeCallbacks(longRun);
                            v.animate().translationX(0).alpha(1f).setDuration(120).start();
                            return true;
                        default:
                            return true;
                    }
                }
            });
        }

        // ------------------------------------------------------------ перетаскивание: группы и обложка группы

        /** Долгое нажатие поднимает карточку. Если отпустить её на ней же, откроется меню. */
        private void startItemDrag(View v, Object item, Runnable menu) {
            dragItem = item;
            boolean ok = false;
            try {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                ok = v.startDragAndDrop(ClipData.newPlainText("item", "item"), new View.DragShadowBuilder(v), item, 0);
            } catch (Throwable ignored) { }
            if (!ok) {
                dragItem = null;
                menu.run();
            }
        }

        private GradientDrawable dropHint() { return round(0x334FC3F7, 12, C_ACCENT, 4); }

        private void attachDrop(final FrameLayout box, final Object item, final Runnable menu) {
            box.setOnDragListener(new View.OnDragListener() {
                @Override public boolean onDrag(View v, DragEvent e) {
                    Object d = e.getLocalState();
                    switch (e.getAction()) {
                        case DragEvent.ACTION_DRAG_STARTED:
                            if (d == item) v.setAlpha(0.35f);
                            return d != null;
                        case DragEvent.ACTION_DRAG_ENTERED:
                            if (d != item) box.setForeground(dropHint());
                            return true;
                        case DragEvent.ACTION_DRAG_EXITED:
                            box.setForeground(null);
                            return true;
                        case DragEvent.ACTION_DRAG_LOCATION:
                            dragAutoScroll(v, e);
                            return true;
                        case DragEvent.ACTION_DROP: {
                            box.setForeground(null);
                            final Object dropped = d;
                            h.post(new Runnable() {
                                @Override public void run() { handleDrop(dropped, item, menu); }
                            });
                            return true;
                        }
                        case DragEvent.ACTION_DRAG_ENDED:
                            v.setAlpha(1f);
                            box.setForeground(null);
                            stopDragScroll();
                            dragItem = null;
                            return true;
                        default:
                            return true;
                    }
                }
            });
        }

        /** Зона «вынуть из группы»: появляется в открытой группе только пока тянут вкладку. */
        private View outOfGroupZone(final Group g) {
            final TextView z = label("↩  Перетащите сюда, чтобы вынуть из группы", 15, C_TEXT, true);
            z.setGravity(Gravity.CENTER);
            z.setPadding(dp(12), dp(14), dp(12), dp(14));
            z.setBackground(round(C_SURFACE2, 10, C_ACCENT, 2));
            z.setVisibility(View.GONE);
            z.setOnDragListener(new View.OnDragListener() {
                @Override public boolean onDrag(View v, DragEvent e) {
                    Object d = e.getLocalState();
                    switch (e.getAction()) {
                        case DragEvent.ACTION_DRAG_STARTED:
                            if (!(d instanceof Tab)) return false;
                            z.setVisibility(View.VISIBLE);
                            return true;
                        case DragEvent.ACTION_DRAG_ENTERED:
                            z.setBackground(round(0x554FC3F7, 10, C_ACCENT, 3));
                            return true;
                        case DragEvent.ACTION_DRAG_EXITED:
                            z.setBackground(round(C_SURFACE2, 10, C_ACCENT, 2));
                            return true;
                        case DragEvent.ACTION_DROP: {
                            final Object dropped = d;
                            h.post(new Runnable() {
                                @Override public void run() {
                                    if (!(dropped instanceof Tab)) return;
                                    Tab dt = (Tab) dropped;
                                    Tab anchor = lastOfGroup(g.id, dt);
                                    dt.group = 0;
                                    if (anchor != null) moveAfter(dt, anchor);
                                    finishDrop();
                                }
                            });
                            return true;
                        }
                        case DragEvent.ACTION_DRAG_ENDED:
                            z.setVisibility(View.GONE);
                            return true;
                        default:
                            return true;
                    }
                }
            });
            return z;
        }

        private final Runnable dragScroller = new Runnable() {
            @Override public void run() {
                if (dragScrollDir == 0 || tabScroll == null || closed) return;
                tabScroll.scrollBy(0, dragScrollDir * dp(14));
                h.postDelayed(this, 16);
            }
        };

        private void dragAutoScroll(View card, DragEvent e) {
            if (tabScroll == null) return;
            int[] a = new int[2];
            int[] s = new int[2];
            card.getLocationOnScreen(a);
            tabScroll.getLocationOnScreen(s);
            float y = a[1] + e.getY();
            int dir = 0;
            if (y < s[1] + dp(90)) dir = -1;
            else if (y > s[1] + tabScroll.getHeight() - dp(90)) dir = 1;
            if (dir != dragScrollDir) {
                dragScrollDir = dir;
                h.removeCallbacks(dragScroller);
                if (dir != 0) h.post(dragScroller);
            }
        }

        private void stopDragScroll() {
            dragScrollDir = 0;
            h.removeCallbacks(dragScroller);
        }

        private Tab lastOfGroup(int gid, Tab exclude) {
            Tab last = null;
            for (Tab t : tabs) if (t.group == gid && t != exclude) last = t;
            return last;
        }

        private void moveAfter(Tab t, Tab anchor) {
            tabs.remove(t);
            int i = tabs.indexOf(anchor);
            tabs.add(i < 0 ? tabs.size() : i + 1, t);
        }

        private void finishDrop() {
            cleanGroups();
            saveTabs();
            showTabs();
        }

        /**
         * Бросили [d] на [target]. В общем списке: вкладка на вкладку создаёт группу, вкладка на группу добавляется в неё,
         * группа на группу сливается. Внутри группы: вкладка встаёт на место той, на которую её бросили; бросить на первую
         * значит сделать её обложкой группы (по первой вкладке рисуется карточка группы).
         */
        private void handleDrop(Object d, Object target, Runnable menu) {
            if (closed || d == null) return;
            if (d == target) { menu.run(); return; }
            if (openGroup != null) {
                if (d instanceof Tab && target instanceof Tab) {
                    Tab dt = (Tab) d, tt = (Tab) target;
                    boolean after = tabs.indexOf(dt) < tabs.indexOf(tt);
                    tabs.remove(dt);
                    int ti = tabs.indexOf(tt);
                    tabs.add(after ? ti + 1 : ti, dt);
                    finishDrop();
                }
                return;
            }
            if (d instanceof Tab) {
                Tab dt = (Tab) d;
                if (target instanceof Tab) {
                    Tab tt = (Tab) target;
                    int gid;
                    if (tt.group != 0) {
                        gid = tt.group;
                    } else {
                        Group g = newGroup();
                        tt.group = g.id;
                        gid = g.id;
                    }
                    Tab anchor = lastOfGroup(gid, dt);
                    dt.group = gid;
                    if (anchor != null) moveAfter(dt, anchor);
                } else if (target instanceof Group) {
                    Group g = (Group) target;
                    Tab anchor = lastOfGroup(g.id, dt);
                    dt.group = g.id;
                    if (anchor != null) moveAfter(dt, anchor);
                }
            } else if (d instanceof Group) {
                Group dg = (Group) d;
                if (target instanceof Tab) {
                    Tab tt = (Tab) target;
                    Tab anchor = lastOfGroup(dg.id, tt);
                    tt.group = dg.id;
                    if (anchor != null) moveAfter(tt, anchor);
                } else if (target instanceof Group) {
                    Group tg = (Group) target;
                    for (Tab m : tabsOf(dg)) {
                        Tab anchor = lastOfGroup(tg.id, m);
                        m.group = tg.id;
                        if (anchor != null) moveAfter(m, anchor);
                    }
                }
            }
            finishDrop();
        }

        /** Долгое нажатие на вкладку: группы и закрытие. */
        private void showTabMenu(final Tab t) {
            List<GridItem> items = new ArrayList<GridItem>();
            addG(items, "grp_new", "📁", "В новую группу", new Runnable() { @Override public void run() {
                Group g = newGroup();
                t.group = g.id;
                saveTabs();
                showTabs();
            } });
            for (final Group g : new ArrayList<Group>(groups)) {
                if (g.id == t.group) continue;
                addG(items, "grp_" + g.id, "📂", "В группу «" + g.name + "»", new Runnable() { @Override public void run() {
                    t.group = g.id;
                    cleanGroups();
                    saveTabs();
                    showTabs();
                } });
            }
            if (t.group != 0) {
                addG(items, "ungroup", "↩", "Убрать из группы", new Runnable() { @Override public void run() {
                    t.group = 0;
                    cleanGroups();
                    saveTabs();
                    showTabs();
                } });
            }
            if (t.wv != null && t != cur) {
                addG(items, "unload", "⏏", "Выгрузить из памяти", new Runnable() { @Override public void run() {
                    unloadTab(t);
                    showTabs();
                } });
            }
            addG(items, "close", "✕", "Закрыть вкладку", new Runnable() { @Override public void run() {
                closeTab(t);
                showTabs();
            } });
            showIconGrid("tab", displayUrl(t), items);
        }

        /** Долгое нажатие на группу. */
        private void showGroupMenu(final Group g) {
            List<View> icons = new ArrayList<View>();
            List<String> labels = new ArrayList<String>();
            List<Runnable> actions = new ArrayList<Runnable>();
            add(icons, labels, actions, "✎", "Переименовать группу", new Runnable() { @Override public void run() {
                showPrompt("Название группы", null, g.name, new PromptDone() {
                    @Override public void done(String value) {
                        if (value != null && value.trim().length() > 0) g.name = value.trim();
                        saveTabs();
                        showTabs();
                    }
                });
            } });
            add(icons, labels, actions, "⊟", "Разгруппировать", new Runnable() { @Override public void run() {
                for (Tab x : tabsOf(g)) x.group = 0;
                cleanGroups();
                saveTabs();
                showTabs();
            } });
            add(icons, labels, actions, "✕", "Закрыть всю группу", new Runnable() { @Override public void run() {
                for (Tab x : tabsOf(g)) closeTab(x);
                showTabs();
            } });
            showList(g.name, icons, labels, actions);
        }
    }

    /** Бросается из потока запроса GM_xmlhttpRequest, когда скрипт вызвал abort(). */
    private static final class InterruptedIOException2 extends IOException {
        InterruptedIOException2() { super("aborted"); }
    }
}
