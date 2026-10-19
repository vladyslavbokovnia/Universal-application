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
 * Если изоляции нет, вкладки профилей 2-4 НЕ открываются (чтобы не смешать аккаунты в одном хранилище).
 *
 * Скрипты. Файлы *.user.js из Загрузки/UniversalPlugins/BrowserScripts, их можно править любым редактором,
 * изменения подхватываются при следующей загрузке страницы. Поддерживаются @match/@include/@exclude/@run-at/@grant/@connect,
 * GM_getValue/setValue/deleteValue/listValues/addValueChangeListener, GM_addStyle, GM_registerMenuCommand,
 * GM_openInTab, GM_setClipboard, GM_xmlhttpRequest (с проверкой @connect), GM_info, объект GM.* (промисы), unsafeWindow.
 * Не поддерживаются: @require, @resource, GM_download, GM_cookie, выполнение внутри iframe; document-start
 * внедряется как можно раньше средствами платформы (onPageStarted), а если не успело — при завершении загрузки.
 *
 * Озвучка. WebView сам не озвучивает speechSynthesis, поэтому при включённой настройке «Озвучка для скриптов»
 * в страницы подставляются window.speechSynthesis и SpeechSynthesisUtterance поверх нативного Android TextToSpeech
 * (события start/end/error приходят из движка). Воспроизведение звука страницей разрешено без касания.
 */
public class BrowserModule implements IPlugin, ISettingsProvider {

    private static final String PREFS = "module_Browser";
    private static final String VALUES_PREFS = "module_Browser_values";
    private static final String BROWSER_ACTIVITY = "im.manus.universalhost.ModuleBrowserActivity";
    private static final int REQ_FILE = 7311;
    private static final String SCRIPTS_SUBDIR = "UniversalPlugins/BrowserScripts";
    private static final String DEF_HOME = "https://www.google.com/";
    private static final String DEF_SEARCH = "https://www.google.com/search?q=%s";
    private static final String DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    private static final int PROFILES = 4;
    private static final String[] PROFILE_DEFAULT_NAMES = {"Основной", "Профиль 2", "Профиль 3", "Профиль 4"};
    private static final int[] PROFILE_COLORS = {0xFF4FC3F7, 0xFFFFB74D, 0xFF81C784, 0xFFBA68C8};

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

    @Override public String getName() { return "Browser"; }
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
        l.add(SettingItem.slider("max_live", "Вкладок в памяти",
                "Остальные выгружаются и при выборе загружаются заново", 1, 8, 1, 3));
        l.add(SettingItem.toggle("keep_screen_on", "Не гасить экран", "Пока открыт браузер", false));
        l.add(SettingItem.toggle("tts_bridge", "Озвучка для скриптов",
                "Подставляет в страницы speechSynthesis поверх голоса Android, иначе скрипты в WebView молчат. Применяется при следующей загрузке страницы", true));

        l.add(SettingItem.section("Профили: отдельные сессии для входа в разные аккаунты"));
        for (int i = 0; i < PROFILES; i++) {
            l.add(SettingItem.text("profile_name_" + i, "Профиль " + (i + 1),
                    i == 0 ? "Обычное хранилище WebView, работает всегда" : "Свои cookies и хранилище, нужна поддержка изоляции",
                    PROFILE_DEFAULT_NAMES[i]));
        }
        l.add(SettingItem.action("isolation_status", "Проверить изоляцию сессий", "Покажет, работают ли профили 2-4 на этом устройстве"));

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
        } else if ("user_agent".equals(key) || "keep_screen_on".equals(key)) {
            final BrowserView v = view;
            if (v != null) main.post(new Runnable() {
                @Override public void run() { v.applySettingsLive(); }
            });
        }
    }

    @Override
    public Bitmap createIcon(int sizePx) {
        return null;        // хост нарисует монограмму
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
                ClassLoader cl = BrowserModule.class.getClassLoader();
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
                Class<?> c = Class.forName("androidx.webkit.WebViewCompat", true, BrowserModule.class.getClassLoader());
                c.getMethod("setProfile", WebView.class, String.class).invoke(null, w, backendName(idx));
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        static String status() {
            if (supported()) return "Отдельные сессии работают: профили 2-4 имеют свои cookies и хранилище";
            return "Отдельные сессии недоступны: в приложении-хосте нет androidx.webkit или WebView устройства не поддерживает "
                    + "профили. Профиль 1 работает, вкладки профилей 2-4 не открываются, чтобы аккаунты не смешались";
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
            "function GM_xmlhttpRequest(d){\n" +
            "var id=++xhrN;xhr[id]=d;\n" +
            "var data=null;\n" +
            "if(d.data!==undefined&&d.data!==null)data=String(d.data);\n" +
            "B.xhr(NO,id,JSON.stringify({method:String(d.method||'GET').toUpperCase(),url:String(d.url),headers:d.headers||{},data:data,timeout:d.timeout||0,responseType:d.responseType||''}));\n" +
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
            "var run=function(GM_info,GM_getValue,GM_setValue,GM_deleteValue,GM_listValues,GM_addValueChangeListener,GM_removeValueChangeListener,GM_addStyle,GM_registerMenuCommand,GM_unregisterMenuCommand,GM_openInTab,GM_setClipboard,GM_xmlhttpRequest,GM_log,GM,unsafeWindow){\n" +
            "@@BODY@@\n" +
            "};\n" +
            "try{run.call(window,info,GM_getValue,GM_setValue,GM_deleteValue,GM_listValues,GM_addValueChangeListener,GM_removeValueChangeListener,GM_addStyle,GM_registerMenuCommand,GM_unregisterMenuCommand,GM_openInTab,GM_setClipboard,GM_xmlhttpRequest,GM_log,GM,window);}catch(e){console.error('[UserScript]',e);}\n" +
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
            "S.speak=function(u){var id='u'+(++cnt);live[id]=u;\n" +
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
        long lastUsed;
        Tab opener;
        JsResult pendingJs;
        final String bridgeName = "ub" + randHex(6);
        final String ttsName = "ut" + randHex(6);
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

        void resetScripts() {
            injected.clear();
            byScript.clear();
            menu.clear();
        }
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
        private final LinearLayout column;
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

        BrowserView(Activity a) {
            super(a);
            act = a;
            sp = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            density = a.getResources().getDisplayMetrics().density;
            configureWindow();
            setBackgroundColor(C_BG);
            setFocusable(true);
            setFocusableInTouchMode(true);

            column = new LinearLayout(a);
            column.setOrientation(LinearLayout.VERTICAL);
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
                @Override public void onClick(View v) { showTabs(); }
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

            column.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
            column.addView(bar, new LinearLayout.LayoutParams(-1, dp(3)));
            column.addView(tool, new LinearLayout.LayoutParams(-1, dp(52)));
            addView(column, new FrameLayout.LayoutParams(-1, -1));
        }

        // ------------------------------------------------------------ запуск и остановка

        void start(Intent in) {
            restoreTabs();
            if (!tabs.isEmpty()) {
                int ci = Math.max(0, Math.min(tabs.size() - 1, sp.getInt("tabs_cur", 0)));
                selectTab(tabs.get(ci));
            }
            handleIntent(in);
            if (tabs.isEmpty()) newTab(home(), 0, null, true);
            requestFocus();
            maybeAutoUpdate();
        }

        void handleIntent(Intent in) {
            if (in == null) return;
            String url = in.getStringExtra("url");
            if (url == null || url.length() == 0) return;
            long stamp = in.getLongExtra("stamp", 0);
            if (stamp != 0 && stamp == sp.getLong("last_stamp", -1)) return;     // тот же запуск после поворота экрана
            sp.edit().putLong("last_stamp", stamp).apply();
            String norm = normalize(url);
            if (norm != null) newTab(norm, in.getIntExtra("profile", 0), null, true);
        }

        /** Результат окна выбора файла: страница получает выбранные адреса или null, если выбор отменён. */
        void activityResult(int requestCode, int resultCode, Intent data) {
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
            for (Tab t : tabs) applyUserAgent(t);
            if (sp.getBoolean("keep_screen_on", false)) act.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else act.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
            else if (layer != null) closeLayer(true);
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
            Tab t = new Tab(nextId++, url == null ? "" : url, Math.max(0, Math.min(PROFILES - 1, profile)));
            t.opener = opener;
            tabs.add(t);
            if (select) selectTab(t); else refreshUi();
            saveTabs();
            return t;
        }

        private void selectTab(Tab t) {
            if (cur != null && cur != t) {
                captureThumb(cur);
                if (cur.wv != null) content.removeView(cur.wv);
            }
            cur = t;
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

        /** Держит в памяти не больше «Вкладок в памяти» страниц: самые давние выгружаются вместе с историей и положением. */
        private void trimLive() {
            int max = Math.max(1, sp.getInt("max_live", 3));
            while (true) {
                int live = 0;
                Tab victim = null;
                for (Tab t : tabs) {
                    if (t.wv == null) continue;
                    live++;
                    if (t != cur && (victim == null || t.lastUsed < victim.lastUsed)) victim = t;
                }
                if (live <= max || victim == null) break;
                try {
                    Bundle b = new Bundle();
                    victim.wv.saveState(b);
                    victim.saved = b;
                } catch (Throwable ignored) { }
                destroyWebView(victim);
            }
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
            t.resetScripts();
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
            } catch (Throwable ignored) { }
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
                    a.put(o);
                }
                sp.edit().putString("tabs_json", a.toString()).putInt("tabs_cur", ci).apply();
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
            s.setBuiltInZoomControls(true);
            s.setDisplayZoomControls(false);
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            s.setMediaPlaybackRequiresUserGesture(false);   // скрипты запускают звук (new Audio().play()) не из касания
            applyUserAgent(t);
            try { CookieManager.getInstance().setAcceptThirdPartyCookies(w, true); } catch (Throwable ignored) { }
            w.addJavascriptInterface(new Bridge(t), t.bridgeName);
            if (sp.getBoolean("tts_bridge", true)) w.addJavascriptInterface(new TtsBridge(t), t.ttsName);
            w.setWebViewClient(new Client(t));
            w.setWebChromeClient(new Chrome(t));
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
                t.url = url;
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

            @Override public boolean onCreateWindow(WebView v, boolean isDialog, boolean isUserGesture, android.os.Message msg) {
                if (!isUserGesture) return false;                  // всплывающие окна без нажатия не открываем
                Tab n = new Tab(nextId++, "", t.profile);
                n.opener = t;
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
            if (sp.getBoolean("tts_bridge", true)) {                   // speechSynthesis вставляется раньше скриптов пользователя
                try {
                    t.wv.evaluateJavascript(TTS_POLYFILL.replace("@@TTS@@", t.ttsName), null);
                } catch (Throwable ignored) { }
            }
            for (UserScript s : ScriptStore.list()) {
                if (!sp.getBoolean("script_on:" + s.fileName, true)) continue;
                if (early && !"document-start".equals(s.runAt)) continue;
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
                    if (data != null && !method.equals("GET") && !method.equals("HEAD")) {
                        c.setDoOutput(true);
                        OutputStream os = c.getOutputStream();
                        os.write(data.getBytes(Charset.forName("UTF-8")));
                        os.close();
                    }
                    int code = c.getResponseCode();
                    if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                        cur = new URL(u, c.getHeaderField("Location")).toString();
                        if (code == 303 || ((code == 301 || code == 302) && method.equals("POST"))) { method = "GET"; data = null; }
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
                                    if (lang != null && lang.length() > 0) e.setLanguage(Locale.forLanguageTag(lang));
                                    e.setSpeechRate(rate > 0 ? rate : 1f);
                                    e.setPitch(pitch > 0 ? pitch : 1f);
                                    ttsOwner.put(id, t);
                                    String part = text.length() > 3900 ? text.substring(0, 3900) : text;     // предел движка около 4000 знаков
                                    e.speak(part, android.speech.tts.TextToSpeech.QUEUE_ADD, null, id);
                                } catch (Throwable ignored) { }
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

        private TextView row(String text, final Runnable action) {
            TextView v = label(text, 17, C_TEXT, false);
            v.setPadding(dp(18), dp(12), dp(18), dp(12));
            v.setMinHeight(dp(52));
            v.setGravity(Gravity.CENTER_VERTICAL);
            v.setBackground(pressable());
            v.setClickable(true);
            v.setFocusable(true);
            v.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) {
                    closeLayer(false);
                    if (action != null) action.run();
                }
            });
            return v;
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

        private void showList(String title, List<String> labels, List<Runnable> actions) {
            LinearLayout c = card();
            c.addView(panelTitle(title));
            for (int i = 0; i < labels.size(); i++) c.addView(row(labels.get(i), actions.get(i)));
            showLayer(c, false, null, title);
        }

        private void showConfirm(String title, String msg, String okLabel, String cancelLabel, final Runnable ok, final Runnable cancel) {
            LinearLayout c = card();
            c.addView(panelTitle(title));
            TextView m = label(msg == null ? "" : msg, 15, C_TEXT2, false);
            m.setPadding(dp(18), dp(4), dp(18), dp(12));
            m.setTextIsSelectable(false);
            c.addView(m);
            c.addView(row(okLabel, ok));
            if (cancelLabel != null) c.addView(row(cancelLabel, cancel));
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
            c.addView(row("OK", new Runnable() {
                @Override public void run() { cb.done(in.getText().toString()); }
            }));
            c.addView(row("Отмена", new Runnable() {
                @Override public void run() { cb.done(null); }
            }));
            showLayer(c, false, new Runnable() {
                @Override public void run() { cb.done(null); }
            }, title);
        }

        /** Список профилей для новой вкладки. */
        private void chooseProfile(String title, final Pick pick) {
            List<String> labels = new ArrayList<String>();
            List<Runnable> actions = new ArrayList<Runnable>();
            for (int i = 0; i < PROFILES; i++) {
                final int idx = i;
                boolean ok = i == 0 || Profiles.supported();
                labels.add(profileName(i) + (ok ? "" : " (недоступно: нет изоляции сессий)"));
                actions.add(ok ? new Runnable() {
                    @Override public void run() { pick.on(idx); }
                } : new Runnable() {
                    @Override public void run() { toast(Profiles.status()); }
                });
            }
            showList(title, labels, actions);
        }

        private void showMenu() {
            final Tab t = cur;
            if (t == null) return;
            final List<String> labels = new ArrayList<String>();
            final List<Runnable> actions = new ArrayList<Runnable>();
            labels.add("Новая вкладка…");
            actions.add(new Runnable() { @Override public void run() {
                chooseProfile("Новая вкладка: выберите профиль", new Pick() {
                    @Override public void on(int idx) { newTab(home(), idx, null, true); }
                });
            } });
            if (tabs.size() > 1) {
                labels.add("Следующая вкладка");
                actions.add(new Runnable() { @Override public void run() { switchTab(1); } });
                labels.add("Предыдущая вкладка");
                actions.add(new Runnable() { @Override public void run() { switchTab(-1); } });
            }
            labels.add("Закрыть эту вкладку");
            actions.add(new Runnable() { @Override public void run() { closeTab(t); } });
            if (t.wv != null && t.wv.canGoForward()) {
                labels.add("Вперёд");
                actions.add(new Runnable() { @Override public void run() { t.wv.goForward(); } });
            }
            labels.add(t.loading ? "Остановить загрузку" : "Обновить страницу");
            actions.add(new Runnable() { @Override public void run() {
                if (t.wv == null) return;
                if (t.loading) t.wv.stopLoading(); else t.wv.reload();
            } });
            labels.add(t.desktop ? "Версия для телефона" : "Версия для компьютера");
            actions.add(new Runnable() { @Override public void run() {
                t.desktop = !t.desktop;
                applyUserAgent(t);
                if (t.wv != null) t.wv.reload();
            } });
            if (!t.menu.isEmpty()) {
                labels.add("Команды скриптов: " + t.menu.size());
                actions.add(new Runnable() { @Override public void run() { showScriptCommands(t); } });
            }
            labels.add("Скопировать ссылку");
            actions.add(new Runnable() { @Override public void run() {
                try {
                    ((ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE))
                            .setPrimaryClip(ClipData.newPlainText("url", t.url == null ? "" : t.url));
                    toast("Ссылка скопирована");
                } catch (Throwable ignored) { }
            } });
            labels.add("Поделиться ссылкой");
            actions.add(new Runnable() { @Override public void run() {
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t.url == null ? "" : t.url);
                try { act.startActivity(Intent.createChooser(send, "Поделиться ссылкой").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Throwable ignored) { }
            } });
            labels.add("Открыть в другом приложении");
            actions.add(new Runnable() { @Override public void run() {
                if (t.url != null) launchExternal(t, t.url);
            } });
            labels.add("Закрыть браузер");
            actions.add(new Runnable() { @Override public void run() { act.finish(); } });
            showList("Меню. Вкладка: " + tabTitle(t) + ", профиль " + profileName(t.profile), labels, actions);
        }

        private void showScriptCommands(final Tab t) {
            List<String> labels = new ArrayList<String>();
            List<Runnable> actions = new ArrayList<Runnable>();
            for (final MenuCmd m : t.menu) {
                labels.add(m.name + "  (" + m.inj.script.name + ")");
                actions.add(new Runnable() { @Override public void run() {
                    if (t.wv != null) t.wv.evaluateJavascript("try{window['" + m.inj.disp + "'].m(" + m.id + ")}catch(e){}", null);
                } });
            }
            showList("Команды скриптов", labels, actions);
        }

        // ------------------------------------------------------------ панель вкладок (миниатюры, как в Sleipnir)

        private void showTabs() {
            captureThumb(cur);
            LinearLayout root = new LinearLayout(act);
            root.setOrientation(LinearLayout.VERTICAL);

            LinearLayout head = new LinearLayout(act);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(dp(8), dp(4), dp(8), dp(4));
            TextView title = label("Вкладки: " + tabs.size(), 20, C_TEXT, true);
            title.setPadding(dp(10), 0, dp(10), 0);
            title.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView add = toolButton("+", "Новая вкладка", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    chooseProfile("Новая вкладка: выберите профиль", new Pick() {
                        @Override public void on(int idx) { newTab(home(), idx, null, true); }
                    });
                }
            });
            TextView close = toolButton("✕", "Закрыть панель вкладок", new View.OnClickListener() {
                @Override public void onClick(View v) { closeLayer(true); }
            });
            head.addView(add, new LinearLayout.LayoutParams(dp(52), dp(52)));
            head.addView(close, new LinearLayout.LayoutParams(dp(52), dp(52)));
            root.addView(head, new LinearLayout.LayoutParams(-1, -2));

            ScrollView sv = new ScrollView(act);
            LinearLayout grid = new LinearLayout(act);
            grid.setOrientation(LinearLayout.VERTICAL);
            grid.setPadding(dp(6), dp(4), dp(6), dp(16));
            sv.addView(grid, new ViewGroup.LayoutParams(-1, -2));
            root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

            LinearLayout rowL = null;
            for (int i = 0; i < tabs.size(); i++) {
                if (i % 2 == 0) {
                    rowL = new LinearLayout(act);
                    rowL.setOrientation(LinearLayout.HORIZONTAL);
                    grid.addView(rowL, new LinearLayout.LayoutParams(-1, -2));
                }
                View card = tabCard(tabs.get(i), i);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                lp.setMargins(dp(5), dp(5), dp(5), dp(5));
                rowL.addView(card, lp);
            }
            if (tabs.size() % 2 == 1 && rowL != null) {
                rowL.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
            }
            showLayer(root, true, null, "Вкладки: " + tabs.size());
        }

        private View tabCard(final Tab t, int index) {
            final FrameLayout box = new FrameLayout(act);
            boolean current = t == cur;
            box.setBackground(round(C_SURFACE, 12, current ? C_ACCENT : C_SURFACE2, current ? 3 : 1));
            box.setClickable(true);
            box.setFocusable(true);
            box.setContentDescription("Вкладка " + (index + 1) + " из " + tabs.size() + ": " + tabTitle(t) + ", " + displayUrl(t)
                    + ", профиль " + profileName(t.profile) + (current ? ", текущая" : ""));

            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            FrameLayout thumbBox = new FrameLayout(act);
            thumbBox.setBackgroundColor(C_SURFACE2);
            ImageView iv = new ImageView(act);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (t.thumb != null) iv.setImageBitmap(t.thumb);
            thumbBox.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            if (t.thumb == null) {
                TextView ph = label(displayUrl(t), 14, C_TEXT2, false);
                ph.setGravity(Gravity.CENTER);
                thumbBox.addView(ph, new FrameLayout.LayoutParams(-1, -1));
            }
            col.addView(thumbBox, new LinearLayout.LayoutParams(-1, dp(150)));
            View colorBar = new View(act);
            colorBar.setBackgroundColor(profileColor(t.profile));
            col.addView(colorBar, new LinearLayout.LayoutParams(-1, dp(4)));
            TextView ti = label(tabTitle(t), 13, C_TEXT, true);
            ti.setMaxLines(2);
            ti.setEllipsize(android.text.TextUtils.TruncateAt.END);
            ti.setPadding(dp(8), dp(6), dp(30), dp(2));
            col.addView(ti);
            TextView sub = label(displayUrl(t) + " · " + profileName(t.profile), 11, C_TEXT2, false);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sub.setPadding(dp(8), 0, dp(8), dp(8));
            col.addView(sub);
            box.addView(col, new FrameLayout.LayoutParams(-1, -2));

            TextView x = label("✕", 16, C_TEXT, true);
            x.setGravity(Gravity.CENTER);
            x.setBackground(round(0xCC000000, 18, 0, 0));
            x.setContentDescription("Закрыть вкладку " + (index + 1) + ": " + tabTitle(t));
            x.setClickable(true);
            x.setFocusable(true);
            x.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    closeTab(t);
                    showTabs();
                }
            });
            FrameLayout.LayoutParams xlp = new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP | Gravity.END);
            xlp.setMargins(0, dp(6), dp(6), 0);
            box.addView(x, xlp);

            final float slop = ViewConfiguration.get(act).getScaledTouchSlop();
            box.setOnTouchListener(new View.OnTouchListener() {
                private float downX, downY;
                private boolean dragging;
                @Override public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = e.getRawX();
                            downY = e.getRawY();
                            dragging = false;
                            return true;
                        case MotionEvent.ACTION_MOVE: {
                            float dx = e.getRawX() - downX;
                            float dy = e.getRawY() - downY;
                            if (!dragging && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
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
                            if (dragging) {
                                if (Math.abs(v.getTranslationX()) > v.getWidth() * 0.4f) {
                                    closeTab(t);                     // смахнули карточку: закрыть вкладку
                                    showTabs();
                                } else {
                                    v.animate().translationX(0).alpha(1f).setDuration(120).start();
                                }
                            } else {
                                v.performClick();
                            }
                            return true;
                        case MotionEvent.ACTION_CANCEL:
                            v.animate().translationX(0).alpha(1f).setDuration(120).start();
                            return true;
                        default:
                            return true;
                    }
                }
            });
            box.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    closeLayer(false);
                    selectTab(t);
                }
            });
            return box;
        }
    }

    /** Бросается из потока запроса GM_xmlhttpRequest, когда скрипт вызвал abort(). */
    private static final class InterruptedIOException2 extends IOException {
        InterruptedIOException2() { super("aborted"); }
    }
}
