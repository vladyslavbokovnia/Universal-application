package im.manus.universalhost;

import android.accessibilityservice.AccessibilityService;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaScannerConnection;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import im.manus.universalhost.IMainViewProvider;
import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.IShareHandler;
import im.manus.universalhost.SettingItem;
import im.manus.universalhost.YtAudioBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Закадровый перевод YouTube через сервис Яндекса (тот же, что использует скрипт VOT), без WebView и без скрипта.
 *
 * Как запустить: «Поделиться» с типом text/x-votaudio (ссылка в тексте, название в теме, длительность секундами в
 * дополнительном поле vot_duration). Из браузера это пункт меню «Перевод VOT: скачать русское аудио».
 *
 * Что делает: создаёт сессию Яндекса, просит перевод ролика, ждёт его готовности, скачивает готовую дорожку в папку
 * Music/VOT. Для YouTube при запросе аудио от Яндекса отправляет такую же заглушку, как VOT (fail-audio-js и пустое аудио).
 *
 * Живой голос Яндекс отдаёт только вошедшим в аккаунт: настройки модуля, «Войти в Яндекс» → код подтверждения →
 * «Подтвердить код». Без входа (или если живого голоса для ролика нет) используется обычный голос, если это не отключено.
 *
 * Протокол (protobuf-сообщения, подпись HMAC, заголовки) повторяет клиент vot.js; ключ подписи и версия компонента
 * Яндекса взяты оттуда же и со временем могут устареть: тогда пункт «Проверить подключение» покажет ошибку.
 */
public class VoiceOverTranslation implements IPlugin, ISettingsProvider, IMainViewProvider, IShareHandler {

    static final String NAME = "VOT перевод";
    static final String MIME_AUDIO = "text/x-votaudio";

    private static final String HOST = "https://api.browser.yandex.ru";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/152.0.0.0 YaBrowser/26.10.0.0 Safari/537.36";
    private static final String HMAC_KEY = "bt8xH3VOlb4mqf0nqAibnDOoiPlXsisf";
    private static final String COMPONENT_VERSION = "26.10.0.1854";
    private static final double DEFAULT_DURATION = 310;

    private static final String DEF_CLIENT_ID = "1666fbe22f3749c581002a4f97b2592d";
    private static final String OAUTH = "https://oauth.yandex.ru";
    private static final String REDIRECT = OAUTH + "/verification_code";

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final SecureRandom RNG = new SecureRandom();
    private static final ExecutorService EXEC = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final List<String> LOG = new ArrayList<String>();
    private static final Object GATE = new Object();
    private static int running;
    private static int jobSeq;

    /** Строка в журнал модуля (последние 400 строк; «Копировать журнал» в настройках). */
    static void log(String s) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + " " + s;
        synchronized (LOG) {
            LOG.add(line.length() > 700 ? line.substring(0, 700) + "…" : line);
            while (LOG.size() > 400) LOG.remove(0);
        }
    }

    static String stackTop(Throwable t) {
        StringBuilder sb = new StringBuilder(t.toString());
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < st.length && i < 4; i++) sb.append(" @ ").append(st[i]);
        return sb.toString();
    }

    /** -1: не определена, 0: нет сети, 1: мобильная, 2: Wi-Fi или Ethernet. */
    static int netKind(Context app) {
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, mobile = false;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c == null || c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                NetworkInfo ni = cm.getNetworkInfo(n);
                if (ni != null && !ni.isConnected()) continue;
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) wifi = true;
                else if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) mobile = true;
            }
            return wifi ? 2 : (mobile ? 1 : 0);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Session session;      // сессия Яндекса переживает перезагрузку модуля, пока не истекла

    private Context appCtx;

    @Override public String getName() { return NAME; }
    @Override public int getVersion() { return 7; }
    @Override public String getDescription() {
        return "Закадровый перевод YouTube на русский (сервис Яндекса, как в VOT): скачивает озвучку в Music/VOT.";
    }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override public void init(Context context) { appCtx = context.getApplicationContext(); }
    @Override public void stop() { }
    @Override public Object execute(Map<String, ?> data) { return null; }
    @Override public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event, AccessibilityService service) { }
    @Override public Bitmap createIcon(int sizePx) { return null; }

    // ---------------------------------------------------------------- «Поделиться»

    @Override public List<String> shareMimeTypes() {
        List<String> r = new ArrayList<String>();
        r.add(MIME_AUDIO);
        return r;
    }

    @Override public boolean onShare(Context ctx, Intent intent) {
        try {
            final Context app = ctx.getApplicationContext();
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            Matcher m = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE).matcher(text == null ? "" : text);
            String url = m.find() ? m.group() : (text == null ? "" : text.trim());
            String id = videoId(url);
            if (id == null) {
                toast(app, "Это не ссылка на видео YouTube");
                return false;
            }
            ensureLoaded(app);
            if (intent.getBooleanExtra("vot_remove", false)) {
                // хост забрал готовый перевод: запись из списка убираем, файл остаётся
                Item d = findItem(id);
                if (d != null && !d.busy) {
                    d.removed = true;
                    synchronized (ITEMS) { ITEMS.remove(d); }
                    changed();
                }
                return true;
            }
            String title = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            Item it = findItem(id);
            if (it == null) {
                it = new Item();
                it.videoId = id;
                synchronized (ITEMS) { ITEMS.add(0, it); }
            } else if (it.busy) {
                toast(app, "Этот ролик уже переводится");
                return true;
            }
            it.url = "https://youtu.be/" + id;
            if (title != null && title.trim().length() > 0) it.title = title.trim();
            else if (it.title.length() == 0) it.title = "YouTube " + id;
            it.duration = intent.getDoubleExtra("vot_duration", 0);
            toast(app, "🗣 В очереди на перевод: " + it.title);
            startItem(app, it);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String videoId(String u) {
        try {
            Uri uri = Uri.parse(u);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String id = null;
            List<String> p = uri.getPathSegments();
            if (host.equals("youtu.be")) {
                if (!p.isEmpty()) id = p.get(0);
            } else if (host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com")) {
                id = uri.getQueryParameter("v");
                if ((id == null || id.length() == 0) && p.size() >= 2) {
                    String first = p.get(0);
                    if (first.equals("shorts") || first.equals("embed") || first.equals("live") || first.equals("v")) id = p.get(1);
                }
            }
            return id != null && id.matches("[A-Za-z0-9_-]{6,20}") ? id : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- настройки

    private static String prefsName() { return "module_" + NAME.replaceAll("[^A-Za-z0-9_.-]", "_"); }

    // Движок больше не отдельный модуль: его настройки лежат внутри настроек «YouTube аудио» (ключи с префиксом vot_)
    private SharedPreferences prefs() {
        return appCtx == null ? null : VotPrefs.of(appCtx);
    }

    private static SharedPreferences prefs(Context c) {
        return VotPrefs.of(c);
    }

    private static String pref(SharedPreferences p, String key, String def) {
        String v = p == null ? null : p.getString(key, def);
        return v == null || v.trim().length() == 0 ? def : v.trim();
    }

    @Override public List<SettingItem> getSettingsSchema() {
        SharedPreferences p = prefs();
        String who = loginSummary(p);
        List<SettingItem> r = new ArrayList<SettingItem>();
        r.add(SettingItem.section("🗣 Перевод"));
        r.add(SettingItem.text("src_lang", "🌐 Язык оригинала", "Код языка: en, de, fr… Яндекс сам уточняет язык, если он другой.", "en"));
        r.add(SettingItem.text("dst_lang", "🎯 Язык перевода", "Код языка. Живой голос бывает только у ru.", "ru"));
        r.add(SettingItem.toggle("live_voice", "🎙 Живой голос",
                "Нужен вход в Яндекс. Без входа или если живого голоса нет, берётся обычный (если включено ниже).", true));
        r.add(SettingItem.toggle("fallback_standard", "🤖 Обычный голос, если живой недоступен",
                "Выключено: без входа в Яндекс перевод с живым голосом не запускается.", true));
        r.add(SettingItem.toggle("wifi_only", "📶 Только по Wi-Fi",
                "Перевод и скачивание ждут Wi-Fi (или Ethernet), по мобильной сети не идут.", true));
        r.add(SettingItem.text("max_parallel", "⚡ Переводов одновременно",
                "0 или пусто — без ограничения (по умолчанию). Яндекс переводит ролик не сразу, поэтому ролики можно отправлять подряд.", "0"));
        r.add(SettingItem.text("out_dir", "📁 Папка", "Внутри памяти телефона.", "Music/VOT"));
        r.add(SettingItem.section("👤 Аккаунт Яндекса (для живого голоса)"));
        r.add(SettingItem.action("login", "🔑 Войти в Яндекс",
                who + ". Откроется страница Яндекса: подтвердите вход, скопируйте код (или весь адрес страницы) в поле ниже."));
        r.add(SettingItem.text("auth_code", "📋 Код подтверждения", "Вставьте код или адрес страницы после входа.", ""));
        r.add(SettingItem.action("auth_exchange", "✅ Подтвердить код", "Обменивает код на токен и сохраняет его."));
        r.add(SettingItem.action("logout", "🚪 Выйти", "Удаляет сохранённый токен."));
        r.add(SettingItem.text("client_id", "🆔 Идентификатор приложения OAuth",
                "По умолчанию тот же, что у скрипта VOT. Менять не нужно.", DEF_CLIENT_ID));
        r.add(SettingItem.section("🐞 Журнал и проверка"));
        r.add(SettingItem.action("copy_log", "📋 Копировать журнал ошибок", "Строк в журнале: " + logSize() + ". Копирует всё в буфер обмена."));
        r.add(SettingItem.action("clear_log", "🧹 Очистить журнал", ""));
        r.add(SettingItem.action("test_api", "🧪 Проверить подключение к Яндексу", "Создаёт сессию и показывает результат тостом."));
        return r;
    }

    private static int logSize() {
        synchronized (LOG) { return LOG.size(); }
    }

    private static void copyLog(final Context app) {
        final String text;
        synchronized (LOG) {
            StringBuilder sb = new StringBuilder();
            sb.append("VOT перевод, версия модуля 6, Android ").append(android.os.Build.VERSION.RELEASE)
              .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append("), сеть: ").append(netKind(app)).append("\n\n");
            for (String l : LOG) sb.append(l).append('\n');
            text = sb.toString();
        }
        if (logSize() == 0) { toast(app, "Журнал пуст"); return; }
        MAIN.post(new Runnable() {
            @Override public void run() {
                try {
                    ((ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("vot", text));
                    Toast.makeText(app, "Журнал скопирован (" + logSize() + " строк)", Toast.LENGTH_LONG).show();
                } catch (Throwable e) {
                    Toast.makeText(app, "Не удалось скопировать: " + e, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private String loginSummary(SharedPreferences p) {
        if (p == null) return "Статус неизвестен";
        String tok = p.getString("token", "");
        if (tok == null || tok.length() == 0) return "Вход не выполнен";
        long exp = p.getLong("token_expires", 0);
        String login = p.getString("login", "");
        if (exp > 0 && exp <= System.currentTimeMillis()) return "Токен просрочен, войдите снова";
        return "Вход выполнен" + (login == null || login.length() == 0 ? "" : ": " + login);
    }

    @Override public void onSettingChanged(final String key) {
        final Context app = appCtx;
        if (app == null) return;
        if ("login".equals(key)) {
            startLogin(app);
        } else if ("auth_exchange".equals(key)) {
            EXEC.execute(new Runnable() {
                @Override public void run() { exchangeCode(app); }
            });
        } else if ("logout".equals(key)) {
            prefs(app).edit().remove("token").remove("token_expires").remove("login").apply();
            toast(app, "Токен Яндекса удалён");
        } else if ("copy_log".equals(key)) {
            copyLog(app);
        } else if ("clear_log".equals(key)) {
            synchronized (LOG) { LOG.clear(); }
            toast(app, "Журнал очищен");
        } else if ("test_api".equals(key)) {
            EXEC.execute(new Runnable() {
                @Override public void run() {
                    try {
                        synchronized (VoiceOverTranslation.class) { session = null; }
                        Session s = session();
                        toast(app, "✅ Сессия Яндекса создана (действует ещё " + Math.max(0, (s.expiresAt - System.currentTimeMillis()) / 1000) + " с)");
                    } catch (Throwable t) {
                        log("test_api: " + stackTop(t));
                        toast(app, "⚠️ " + t.getMessage());
                    }
                }
            });
        }
    }

    // ---------------------------------------------------------------- вход в Яндекс (OAuth, код + PKCE)

    private void startLogin(Context app) {
        try {
            SharedPreferences p = prefs(app);
            byte[] raw = new byte[32];
            RNG.nextBytes(raw);
            String verifier = b64url(raw);
            String challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(UTF8)));
            String state = uuid32();
            String clientId = pref(p, "client_id", DEF_CLIENT_ID);
            p.edit().putString("pkce_verifier", verifier).putString("pkce_state", state).apply();
            String url = OAUTH + "/authorize?response_type=code"
                    + "&client_id=" + Uri.encode(clientId)
                    + "&redirect_uri=" + Uri.encode(REDIRECT)
                    + "&et=" + System.currentTimeMillis()
                    + "&force_confirm=1"
                    + "&state=" + Uri.encode(state)
                    + "&code_challenge=" + Uri.encode(challenge)
                    + "&code_challenge_method=S256";
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
            toast(app, "Войдите в Яндекс, скопируйте код в поле «Код подтверждения» и нажмите «Подтвердить код»");
        } catch (Throwable t) {
            toast(app, "Не удалось начать вход: " + t);
        }
    }

    private void exchangeCode(Context app) {
        try {
            SharedPreferences p = prefs(app);
            String code = p.getString("auth_code", "");
            code = code == null ? "" : code.trim();
            Matcher m = Pattern.compile("[?&]code=([^&#\\s]+)").matcher(code);
            if (m.find()) code = Uri.decode(m.group(1));
            String verifier = p.getString("pkce_verifier", "");
            if (code.length() == 0) { toast(app, "Вставьте код подтверждения в поле выше"); return; }
            if (verifier == null || verifier.length() == 0) { toast(app, "Сначала нажмите «Войти в Яндекс»"); return; }
            String form = "grant_type=authorization_code"
                    + "&code=" + Uri.encode(code)
                    + "&client_id=" + Uri.encode(pref(p, "client_id", DEF_CLIENT_ID))
                    + "&redirect_uri=" + Uri.encode(REDIRECT)
                    + "&code_verifier=" + Uri.encode(verifier);
            Map<String, String> h = new LinkedHashMap<String, String>();
            h.put("Content-Type", "application/x-www-form-urlencoded");
            h.put("User-Agent", UA);
            Http r = http("POST", OAUTH + "/token", h, form.getBytes(UTF8), 20000);
            JSONObject o = new JSONObject(new String(r.body, UTF8));
            if (o.has("error")) {
                toast(app, "Яндекс отклонил код: " + o.optString("error_description", o.optString("error")));
                return;
            }
            String token = o.getString("access_token");
            long exp = System.currentTimeMillis() + o.optLong("expires_in", 3600L * 24 * 365) * 1000L;
            String login = "";
            try {
                Map<String, String> ih = new LinkedHashMap<String, String>();
                ih.put("Authorization", "OAuth " + token);
                ih.put("User-Agent", UA);
                Http ir = http("GET", "https://login.yandex.ru/info?format=json", ih, null, 15000);
                login = new JSONObject(new String(ir.body, UTF8)).optString("login", "");
            } catch (Throwable ignored) { }
            p.edit().putString("token", token).putLong("token_expires", exp).putString("login", login)
                    .remove("pkce_verifier").remove("pkce_state").putString("auth_code", "").apply();
            toast(app, "✅ Вход выполнен" + (login.length() > 0 ? ": " + login : ""));
        } catch (Throwable t) {
            toast(app, "Не удалось подтвердить код: " + t.getMessage());
        }
    }

    private static String validToken(SharedPreferences p) {
        String tok = p.getString("token", "");
        if (tok == null || tok.length() == 0) return null;
        long exp = p.getLong("token_expires", 0);
        return exp > 0 && exp <= System.currentTimeMillis() ? null : tok;
    }

    // ---------------------------------------------------------------- задание

    private static final class Job {
        String videoId, videoUrl, title;
        double duration;
        int n = nextSeq();
        Item it;
    }

    private static synchronized int nextSeq() { return ++jobSeq; }

    private static final class Tr {
        int status;
        int remaining;
        String url, id, message;
        boolean lively;
    }

    private static final class NeedLogin extends IOException {
        NeedLogin() { super("Яндекс требует вход в аккаунт"); }
    }

    private void say(Context app, Job job, String msg) {
        log("#" + job.n + " " + msg);
        if (job.it != null) { job.it.msg = msg; changed(); }
        String t = job.title == null ? "" : job.title;
        if (t.length() > 24) t = t.substring(0, 24) + "…";
        toast(app, "«" + t + "»: " + msg);
    }

    /** Ждёт Wi-Fi, если включён режим «только по Wi-Fi». */
    private void waitWifi(Context app, Job job, SharedPreferences p) throws IOException {
        if (!p.getBoolean("wifi_only", true)) return;
        boolean told = false;
        while (netKind(app) != 2) {                 // без ограничения по времени: ждём, пока ролик не уберут из списка
            if (job.it != null && job.it.removed) throw new IOException("Убрано из списка");
            if (!told) { told = true; say(app, job, "жду Wi-Fi"); }
            try { Thread.sleep(10000); } catch (InterruptedException e) { throw new IOException("Ожидание Wi-Fi прервано"); }
        }
    }

    private void runJob(Context app, Job job) {
        boolean entered = false;
        Item it = job.it;
        try {
            SharedPreferences p = prefs(app);
            int limit = Integer.MAX_VALUE;      // без ограничения: Яндекс переводит медленно на своей стороне, телефон при этом свободен
            try {
                int n = Integer.parseInt(pref(p, "max_parallel", "0").trim());
                if (n > 0) limit = n;
            } catch (NumberFormatException ignored) { }
            synchronized (GATE) {
                boolean told = false;
                while (running >= limit) {
                    if (!told) { told = true; say(app, job, "в очереди (идёт " + running + " из " + limit + ")"); }
                    GATE.wait();
                }
                running++;
                entered = true;
            }
            if (it != null) { it.status = "run"; it.msg = "перевод"; changed(); }
            log("#" + job.n + " старт " + job.videoUrl + " (" + job.title + ")");
            waitWifi(app, job, p);
            String src = pref(p, "src_lang", "en").toLowerCase(Locale.ROOT);
            String dst = pref(p, "dst_lang", "ru").toLowerCase(Locale.ROOT);
            boolean fallback = p.getBoolean("fallback_standard", true);
            String token = validToken(p);
            boolean live = p.getBoolean("live_voice", true) && "ru".equals(dst);
            if (live && token == null) {
                if (!fallback) throw new IOException("Для живого голоса нужен вход в Яндекс (настройки модуля → «Войти в Яндекс»)");
                say(app, job, "Нет входа в Яндекс: беру обычный голос");
                live = false;
            }
            say(app, job, "🗣 Перевожу: " + job.title);
            Tr tr = translate(app, job, src, dst, live, token, fallback);
            if (tr.url == null || tr.url.length() == 0) throw new IOException("Яндекс не прислал ссылку на озвучку");
            waitWifi(app, job, p);
            say(app, job, "⬇ Скачиваю озвучку" + (tr.lively ? " (живой голос)" : ""));
            File dir = YtAudioQueue.mediaDir(app);          // одна папка для всех аудио: память телефона или SD-карта
            if (dir == null) throw new IOException("SD-карта не найдена");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Не создать папку " + dir);
            File out = download(tr.url, dir, safeName(job.title) + " [VOT " + dst + (tr.lively ? ", живой" : "") + "]");
            saveExtras(job, out);
            MediaScannerConnection.scanFile(app, new String[]{out.getAbsolutePath()}, null, null);
            say(app, job, "✅ Готово: " + out.getName());
            if (it != null) { it.status = "done"; it.file = out.getName(); it.msg = "готово: " + out.getName(); changed(); }
        } catch (NeedLogin e) {
            if (it != null) { it.status = "err"; it.msg = e.getMessage() + ": настройки модуля → «Войти в Яндекс»"; changed(); }
            say(app, job, "⚠️ " + e.getMessage() + ". Настройки модуля → «Войти в Яндекс»");
        } catch (Throwable t) {
            log("#" + job.n + " ОШИБКА " + stackTop(t));
            if (it != null) { it.status = "err"; it.msg = t.getMessage() == null ? t.toString() : t.getMessage(); changed(); }
            say(app, job, "⚠️ " + (t.getMessage() == null ? t.toString() : t.getMessage()) + " (журнал: настройки модуля → «Копировать журнал»)");
        } finally {
            if (it != null) { it.busy = false; changed(); }
            if (entered) synchronized (GATE) { running--; GATE.notifyAll(); }
        }
    }

    /** Запрашивает перевод и ждёт готовности. Возвращает ответ со ссылкой на озвучку. */
    private Tr translate(Context app, Job job, String src, String dst, boolean live, String token, boolean fallback) throws IOException {
        boolean useToken = live;
        boolean emptyAudioSent = false;
        boolean told = false;
        long lastNote = 0;
        Tr lastPart = null;
        for (int attempt = 0; attempt < 150; attempt++) {
            Tr t = translateOnce(job, src, dst, live, useToken ? token : null);
            log("#" + job.n + " ответ Яндекса: статус " + t.status + ", осталось " + t.remaining + ", ссылка " + (t.url != null && t.url.length() > 0)
                    + ", живой " + t.lively + (t.message != null && t.message.length() > 0 ? ", сообщение: " + t.message : ""));
            switch (t.status) {
                case 1:
                    if (t.url == null || t.url.length() == 0) throw new IOException("Яндекс не прислал ссылку на озвучку");
                    return t;
                case 5:
                    if (t.url != null && t.url.length() > 0) lastPart = t;
                    if (attempt >= 40 && lastPart != null) return lastPart;
                    break;
                case 2:
                case 3:
                    break;
                case 6:
                    if (!emptyAudioSent) {
                        emptyAudioSent = true;
                        // Яндекс просит звук: как VOT с «скачиванием аудио», отдаём ему оригинальную дорожку.
                        // Запасной путь (пустая заглушка, Яндекс качает сам) часто даёт «ошибку при переводе».
                        waitWifi(app, job, prefs(app));
                        if (uploadRealAudio(app, job, t.id)) continue;
                        say(app, job, "аудио с YouTube взять не вышло, пробую запасной путь");
                        failAudio(job);
                        sendEmptyAudio(job, t.id);
                        continue;                    // сразу повторяем запрос, как в VOT
                    }
                    break;
                case 7:
                    if (!useToken && token != null) { useToken = true; continue; }
                    if (live) {
                        if (!fallback) throw new NeedLogin();
                        say(app, job, "Живой голос недоступен (нужен вход): беру обычный");
                        live = false;
                        useToken = false;
                        continue;
                    }
                    throw new NeedLogin();
                case 0: {
                    String msg = t.message == null ? "" : t.message;
                    if (live && fallback && msg.toLowerCase(Locale.ROOT).contains("обычная озвучка")) {
                        say(app, job, "Для этого видео живого голоса нет: беру обычный");
                        live = false;
                        useToken = false;
                        continue;
                    }
                    throw new IOException("Яндекс не смог перевести видео" + (msg.length() > 0 ? ": " + msg : ""));
                }
                default:
                    throw new IOException("Неожиданный ответ Яндекса, статус " + t.status);
            }
            long now = System.currentTimeMillis();
            if (!told || now - lastNote > 60000) {
                told = true;
                lastNote = now;
                say(app, job, "⏳ Яндекс переводит" + (t.remaining > 0 ? ", осталось около " + t.remaining + " с" : "") + "…");
            }
            long delay = t.remaining > 0 ? Math.min(30, Math.max(5, t.remaining / 2)) : 10;
            if (job.it != null && job.it.removed) throw new IOException("Убрано из списка");
            try {
                Thread.sleep(delay * 1000L);
            } catch (InterruptedException e) {
                throw new IOException("Перевод прерван");
            }
        }
        throw new IOException("Яндекс слишком долго переводит видео, попробуйте позже");
    }

    /**
     * Берёт оригинальное аудио ролика (NewPipe, через хост) и загружает его в Яндекс. Формат загрузки Яндекс принимает
     * не любой, а в журнале видно только «HTTP 400», поэтому пробуем по очереди: M4A кусками по 4 МБ, M4A одним куском,
     * Opus (WebM) кусками по 4 МБ.
     */
    private boolean uploadRealAudio(Context app, Job job, String translationId) {
        String[][] plan = {{"m4a", "chunks"}, {"m4a", "whole"}, {"webm", "chunks"}};
        File f = null;
        String fmt = null;
        try {
            for (String[] step : plan) {
                try {
                    if (f == null || !step[0].equals(fmt)) {
                        if (f != null) f.delete();
                        f = null;
                        say(app, job, "⬇ Беру оригинальное аудио с YouTube (" + step[0] + ")");
                        File dir = new File(app.getCacheDir(), "vot");
                        f = "m4a".equals(step[0]) ? YtAudioBridge.fetchOriginal(app, job.videoUrl, dir)
                                : YtAudioBridge.fetchOriginal(app, job.videoUrl, dir, step[0]);
                        fmt = step[0];
                        log("#" + job.n + " аудио " + f.getName() + ", " + f.length() + " байт, начало " + head(f));
                    }
                    say(app, job, "⬆ Загружаю аудио в Яндекс (" + step[1] + ")");
                    if (uploadFile(job, translationId, f, step[1])) {
                        log("#" + job.n + " аудио загружено полностью (" + step[0] + ", " + step[1] + ")");
                        return true;
                    }
                } catch (Throwable t) {
                    log("#" + job.n + " вариант " + step[0] + "/" + step[1] + ": " + stackTop(t));
                }
            }
            return false;
        } finally {
            if (f != null) f.delete();
        }
    }

    private static String head(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] b = new byte[16];
                int n = in.read(b);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < n; i++) sb.append(String.format(Locale.ROOT, "%02x", b[i] & 0xff));
                return sb.toString();
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return "?";
        }
    }

    private boolean putAudio(Job job, byte[] body, String what) throws IOException {
        String path = "/video-translation/audio";
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Session s = session();
                Map<String, String> h = baseHeaders();
                h.putAll(secHeaders(s, body, path));
                Http r = http("PUT", HOST + path, h, body, 90000);
                if (r.code != 200) throw new IOException("HTTP " + r.code + snippet(r.body));
                return true;
            } catch (IOException e) {
                last = e;
                log("#" + job.n + " " + what + ", попытка " + (attempt + 1) + " (" + body.length + " байт): " + e);
            }
        }
        throw last;
    }

    private boolean uploadFile(Job job, String translationId, File f, String mode) throws IOException {
        String tid = translationId == null ? "" : translationId;
        String fileId = "random-youtube-" + uuid32().toLowerCase(Locale.ROOT);
        FileInputStream in = new FileInputStream(f);
        try {
            if ("whole".equals(mode)) {
                byte[] data = new byte[(int) f.length()];
                int got = 0, n;
                while (got < data.length && (n = in.read(data, got, data.length - got)) > 0) got += n;
                Pb info = new Pb();
                info.str(1, fileId);
                info.msg(2, data);
                Pb req = new Pb();
                req.str(1, tid);
                req.str(2, job.videoUrl);
                req.msg(6, info.bytes());
                return putAudio(job, req.bytes(), "целиком");
            }
            final int CH = 4 * 1024 * 1024;
            int parts = (int) ((f.length() + CH - 1) / CH);
            if (parts == 0) throw new IOException("пустое аудио");
            byte[] buf = new byte[CH];
            for (int i = 0; i < parts; i++) {
                int got = 0, n;
                while (got < CH && (n = in.read(buf, got, CH - got)) > 0) got += n;
                byte[] data = new byte[got];
                System.arraycopy(buf, 0, data, 0, got);
                Pb part = new Pb();
                part.int32(1, i);
                part.msg(2, data);
                Pb chunk = new Pb();
                chunk.msg(1, part.bytes());
                chunk.int32(2, i == parts - 1 ? parts : 0);
                chunk.str(3, fileId);
                chunk.int32(4, 1);
                Pb req = new Pb();
                req.str(1, tid);
                req.str(2, job.videoUrl);
                req.msg(4, chunk.bytes());
                putAudio(job, req.bytes(), "часть " + (i + 1) + "/" + parts);
            }
            return true;
        } finally {
            in.close();
        }
    }

    private Tr translateOnce(Job job, String src, String dst, boolean live, String token) throws IOException {
        Session s = session();
        String path = "/video-translation/translate";
        Pb pb = new Pb();
        pb.str(3, job.videoUrl);
        pb.bool(5, true);
        pb.dbl(6, job.duration > 0 ? job.duration : DEFAULT_DURATION);
        pb.bool(7, true);
        pb.str(8, src);
        pb.str(14, dst);
        pb.bool(15, true);
        pb.int32(16, 2);
        pb.bool(18, live);
        if (job.title != null && job.title.length() > 0) pb.str(19, job.title);
        byte[] body = pb.bytes();
        Map<String, String> h = baseHeaders();
        h.putAll(secHeaders(s, body, path));
        if (token != null) h.put("Authorization", "OAuth " + token);
        Http r = http("POST", HOST + path, h, body, 30000);
        if (r.code != 200) { log("translate HTTP " + r.code + snippet(r.body)); throw new IOException("Яндекс ответил HTTP " + r.code + snippet(r.body)); }
        Map<Integer, List<Object>> m = Pb.parse(r.body);
        Tr t = new Tr();
        t.url = Pb.str(m, 1);
        t.status = (int) Pb.num(m, 4);
        t.remaining = (int) Pb.num(m, 5);
        t.id = Pb.str(m, 7);
        t.message = Pb.str(m, 9);
        t.lively = Pb.num(m, 10) != 0;
        return t;
    }

    /** Для YouTube Яндекс просит аудио: как VOT, сообщаем «не получилось» и отправляем пустое аудио-заглушку. */
    private void failAudio(Job job) throws IOException {
        Map<String, String> h = baseHeaders();
        h.put("Accept", "application/json");
        h.put("Content-Type", "application/json");
        byte[] body;
        try {
            body = new JSONObject().put("video_url", job.videoUrl).toString().getBytes(UTF8);
        } catch (Exception e) {
            throw new IOException(e.toString());
        }
        Http r = http("PUT", HOST + "/video-translation/fail-audio-js", h, body, 20000);
        if (r.code != 200) throw new IOException("fail-audio: HTTP " + r.code + snippet(r.body));
    }

    private void sendEmptyAudio(Job job, String translationId) throws IOException {
        Session s = session();
        String path = "/video-translation/audio";
        Pb info = new Pb();
        info.str(1, "fallback-empty-audio:video-translation:" + job.videoId);
        Pb pb = new Pb();
        pb.str(1, translationId == null ? "" : translationId);
        pb.str(2, job.videoUrl);
        pb.msg(6, info.bytes());
        byte[] body = pb.bytes();
        Map<String, String> h = baseHeaders();
        h.putAll(secHeaders(s, body, path));
        Http r = http("PUT", HOST + path, h, body, 30000);
        if (r.code != 200) throw new IOException("Отправка аудио: HTTP " + r.code + snippet(r.body));
    }

    // ---------------------------------------------------------------- сессия и подпись

    private static final class Session {
        String secret, uuid;
        long expiresAt;
    }

    private static synchronized Session session() throws IOException {
        long now = System.currentTimeMillis();
        if (session != null && session.expiresAt > now + 15000) return session;
        String uuid = uuid32();
        byte[] body = new Pb().str(1, uuid).str(2, "video-translation").bytes();
        Map<String, String> h = baseHeaders();
        h.put("Vtrans-Signature", hmacHex(body));
        Http r = http("POST", HOST + "/session/create", h, body, 20000);
        if (r.code != 200) { log("session/create HTTP " + r.code + snippet(r.body)); throw new IOException("Сессия Яндекса: HTTP " + r.code + snippet(r.body)); }
        Map<Integer, List<Object>> m = Pb.parse(r.body);
        String secret = Pb.str(m, 1);
        long expires = Pb.num(m, 2);
        if (secret == null || secret.length() == 0) throw new IOException("Яндекс не выдал ключ сессии");
        Session s = new Session();
        s.secret = secret;
        s.uuid = uuid;
        s.expiresAt = now + (expires > 0 ? expires : 3600) * 1000L;
        session = s;
        return s;
    }

    private static Map<String, String> baseHeaders() {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("User-Agent", UA);
        h.put("Accept", "application/x-protobuf");
        h.put("Accept-Language", "en");
        h.put("Content-Type", "application/x-protobuf");
        h.put("Pragma", "no-cache");
        h.put("Cache-Control", "no-cache");
        return h;
    }

    private static Map<String, String> secHeaders(Session s, byte[] body, String path) {
        String token = s.uuid + ":" + path + ":" + COMPONENT_VERSION;
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Vtrans-Signature", hmacHex(body));
        h.put("Sec-Vtrans-Sk", s.secret);
        h.put("Sec-Vtrans-Token", hmacHex(token.getBytes(UTF8)) + ":" + token);
        return h;
    }

    private static String hmacHex(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HMAC_KEY.getBytes(UTF8), "HmacSHA256"));
            byte[] d = mac.doFinal(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String uuid32() {
        String hex = "0123456789ABCDEF";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32; i++) sb.append(hex.charAt(RNG.nextInt(16)));
        return sb.toString();
    }

    private static String b64url(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    // ---------------------------------------------------------------- сеть и файлы

    private static final class Http {
        int code;
        byte[] body;
    }

    private static Http http(String method, String url, Map<String, String> headers, byte[] body, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod(method);
            for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            if (body != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                OutputStream o = c.getOutputStream();
                o.write(body);
                o.close();
            }
            Http r = new Http();
            r.code = c.getResponseCode();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
            }
            r.body = bos.toByteArray();
            return r;
        } finally {
            c.disconnect();
        }
    }

    private static String snippet(byte[] b) {
        if (b == null || b.length == 0) return "";
        String s = new String(b, 0, Math.min(b.length, 160), UTF8).replaceAll("[\\p{Cntrl}]", " ").trim();
        return s.length() == 0 ? "" : ": " + s;
    }

    private static File download(String url, File dir, String base) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        File part = null;
        try {
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", UA);
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Скачивание: HTTP " + code);
            String ext = "mp3";
            String path = new URL(url).getPath().toLowerCase(Locale.ROOT);
            String ct = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.ROOT);
            if (path.endsWith(".m4a") || ct.contains("mp4")) ext = "m4a";
            else if (path.endsWith(".ogg") || ct.contains("ogg")) ext = "ogg";
            else if (path.endsWith(".opus")) ext = "opus";
            else if (path.endsWith(".aac")) ext = "aac";
            File out = new File(dir, base + "." + ext);
            for (int n = 2; out.exists(); n++) out = new File(dir, base + " (" + n + ")." + ext);
            part = new File(dir, out.getName() + ".part");
            InputStream in = c.getInputStream();
            OutputStream o = new FileOutputStream(part);
            long total = 0;
            try {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); total += n; }
            } finally {
                o.close();
                in.close();
            }
            if (total == 0) throw new IOException("Пустой файл озвучки");
            if (!part.renameTo(out)) throw new IOException("Не удалось сохранить файл");
            return out;
        } catch (IOException e) {
            if (part != null) part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
    }

    /** Рядом с озвучкой, как у «YouTube аудио»: превью (.jpg) и ссылка на ролик (.url). */
    private static void saveExtras(Job job, File audio) {
        String name = audio.getName();
        int dot = name.lastIndexOf('.');
        File base = new File(audio.getParentFile(), dot > 0 ? name.substring(0, dot) : name);
        try {
            OutputStream o = new FileOutputStream(new File(base.getPath() + ".url"));
            try {
                o.write(("[InternetShortcut]\r\nURL=" + job.videoUrl + "\r\n").getBytes(UTF8));
            } finally {
                o.close();
            }
        } catch (Throwable t) {
            log("#" + job.n + " ссылка: " + stackTop(t));
        }
        for (String q : new String[]{"maxresdefault", "hqdefault"}) {
            File jpg = new File(base.getPath() + ".jpg");
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://i.ytimg.com/vi/" + job.videoId + "/" + q + ".jpg").openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                if (c.getResponseCode() != 200) { c.disconnect(); continue; }
                InputStream in = c.getInputStream();
                OutputStream o = new FileOutputStream(jpg);
                try {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                } finally {
                    o.close();
                    in.close();
                }
                c.disconnect();
                return;
            } catch (Throwable t) {
                jpg.delete();
                log("#" + job.n + " превью " + q + ": " + t);
            }
        }
    }

    private static String safeName(String s) {
        if (s == null) s = "YouTube";
        s = s.replaceAll("\\s*-\\s*YouTube$", "").replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (s.startsWith(".")) s = s.substring(1).trim();
        if (s.length() > 80) s = s.substring(0, 80).trim();
        return s.length() == 0 ? "YouTube" : s;
    }

    private static void toast(final Context c, final String s) {
        MAIN.post(new Runnable() {
            @Override public void run() {
                try {
                    // уведомления без текста: только эмодзи (хост с YtNotify); со старым хостом показывается как раньше
                    String g;
                    try { g = im.manus.universalhost.YtNotify.glyphs(s); } catch (Throwable e) { g = null; }
                    if (g == null) Toast.makeText(c, s, Toast.LENGTH_LONG).show();
                    else Toast.makeText(c, g.length() == 0 ? "🗣" : g, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) { }
            }
        });
    }

    // ---------------------------------------------------------------- список (главное окно модуля)

    static final class Item {
        String url = "", videoId = "", title = "", status = "wait", msg = "", file = "";
        double duration;
        volatile boolean busy, removed;
    }

    private static final List<Item> ITEMS = new ArrayList<Item>();
    private static final List<Runnable> LISTENERS = new ArrayList<Runnable>();
    private static boolean loaded;
    private static Context APP;

    static synchronized void ensureLoaded(Context app) {
        if (APP == null) APP = app.getApplicationContext();
        if (loaded) return;
        loaded = true;
        try {
            JSONArray a = new JSONArray(prefs(APP).getString("queue", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.url = o.optString("url");
                it.videoId = o.optString("id");
                it.title = o.optString("title");
                it.status = o.optString("status", "wait");
                it.msg = o.optString("msg");
                it.file = o.optString("file");
                it.duration = o.optDouble("duration", 0);
                if ("run".equals(it.status)) { it.status = "wait"; it.msg = "прервано, нажмите, чтобы продолжить"; }
                if (it.videoId.length() > 0) ITEMS.add(it);
            }
        } catch (Throwable t) {
            log("загрузка списка: " + stackTop(t));
        }
    }

    private static void saveQueue() {
        Context app = APP;
        if (app == null) return;
        try {
            JSONArray a = new JSONArray();
            synchronized (ITEMS) {
                for (Item it : ITEMS) {
                    a.put(new JSONObject().put("url", it.url).put("id", it.videoId).put("title", it.title).put("status", it.status)
                            .put("msg", it.msg).put("file", it.file).put("duration", it.duration));
                }
            }
            prefs(app).edit().putString("queue", a.toString()).apply();
        } catch (Throwable t) {
            log("сохранение списка: " + stackTop(t));
        }
    }

    static void changed() {
        saveQueue();
        MAIN.post(new Runnable() {
            @Override public void run() {
                List<Runnable> copy;
                synchronized (LISTENERS) { copy = new ArrayList<Runnable>(LISTENERS); }
                for (Runnable r : copy) r.run();
            }
        });
    }

    /** Хост забрал готовый перевод: запись из списка убираем, файл остаётся. */
    static void drop(String videoId) {
        Item d = findItem(videoId);
        if (d != null && !d.busy) {
            d.removed = true;
            synchronized (ITEMS) { ITEMS.remove(d); }
            changed();
        }
    }

    static Item findItem(String videoId) {
        synchronized (ITEMS) {
            for (Item it : ITEMS) if (it.videoId.equals(videoId)) return it;
        }
        return null;
    }

    /** Ставит ролик на перевод (повторно, если он уже в списке с ошибкой или ждёт). */
    void startItem(final Context app, final Item it) {
        if (it.busy) return;
        it.busy = true;
        it.removed = false;
        it.status = "wait";
        it.msg = "в очереди";
        final Job job = new Job();
        job.videoId = it.videoId;
        job.videoUrl = "https://youtu.be/" + it.videoId;
        job.title = it.title;
        job.duration = it.duration;
        job.it = it;
        changed();
        EXEC.execute(new Runnable() {
            @Override public void run() { runJob(app, job); }
        });
    }

    /** Главное окно (отдельная карточка в недавних): плитки роликов, как в «YouTube аудио». */
    @Override public View createMainView(Context context) {
        ensureLoaded(context);
        return new ListView(context, this);
    }

    private static final class ListView extends LinearLayout {
        private final VoiceOverTranslation mod;
        private final Runnable onChange = new Runnable() {
            @Override public void run() { rebuild(); }
        };

        ListView(Context ctx, VoiceOverTranslation mod) {
            super(ctx);
            this.mod = mod;
            setOrientation(VERTICAL);
            setPadding(dp(10), dp(6), dp(10), dp(6));
            rebuild();
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            synchronized (LISTENERS) { LISTENERS.add(onChange); }
            rebuild();
        }

        @Override protected void onDetachedFromWindow() {
            synchronized (LISTENERS) { LISTENERS.remove(onChange); }
            super.onDetachedFromWindow();
        }

        private int dp(int v) {
            return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
        }

        private void rebuild() {
            removeAllViews();
            addView(toolbar());
            List<Item> items;
            synchronized (ITEMS) { items = new ArrayList<Item>(ITEMS); }
            if (items.isEmpty()) {
                TextView empty = new TextView(getContext());
                empty.setText("📭");
                empty.setTextSize(40);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(0, dp(24), 0, dp(24));
                addView(empty);
                return;
            }
            for (int i = 0; i < items.size(); i += 2) {
                LinearLayout row = new LinearLayout(getContext());
                row.setOrientation(HORIZONTAL);
                for (int k = 0; k < 2; k++) {
                    View v = i + k < items.size() ? tile(items.get(i + k)) : new View(getContext());
                    LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
                    lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                    row.addView(v, lp);
                }
                addView(row);
            }
        }

        private View button(String glyph, final Runnable act) {
            TextView tv = new TextView(getContext());
            tv.setText(glyph);
            tv.setTextSize(24);
            tv.setGravity(Gravity.CENTER);
            tv.setTextColor(Color.WHITE);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.rgb(34, 34, 34));
            bg.setStroke(dp(1), Color.GRAY);
            bg.setCornerRadius(dp(12));
            tv.setBackground(bg);
            tv.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { act.run(); }
            });
            LayoutParams lp = new LayoutParams(dp(52), dp(52));
            lp.setMargins(0, 0, dp(8), dp(8));
            tv.setLayoutParams(lp);
            return tv;
        }

        private View toolbar() {
            LinearLayout bar = new LinearLayout(getContext());
            bar.setOrientation(HORIZONTAL);
            final Context c = getContext().getApplicationContext();
            int k = netKind(c);
            String glyph = k == 2 ? "📶" : (k == 1 ? "📱" : (k == 0 ? "📵" : "❓"));
            bar.addView(button(glyph, new Runnable() {
                @Override public void run() {
                    boolean w = prefs(c).getBoolean("wifi_only", true);
                    toast(c, "Сейчас: " + (netKind(c) == 2 ? "Wi-Fi" : netKind(c) == 1 ? "мобильная сеть" : netKind(c) == 0 ? "нет сети" : "не определена")
                            + (w ? ". Только по Wi-Fi" : ". Мобильная сеть разрешена"));
                    rebuild();
                }
            }));
            bar.addView(button("📋", new Runnable() {
                @Override public void run() { fromClipboard(); }
            }));
            bar.addView(button("⬇", new Runnable() {
                @Override public void run() {
                    List<Item> items;
                    synchronized (ITEMS) { items = new ArrayList<Item>(ITEMS); }
                    for (Item it : items) if (!"done".equals(it.status)) mod.startItem(c, it);
                }
            }));
            bar.addView(button("🐞", new Runnable() {
                @Override public void run() { copyLog(c); }
            }));
            bar.addView(button("🗑", new Runnable() {
                @Override public void run() { confirmClear(); }
            }));
            return bar;
        }

        private void fromClipboard() {
            try {
                ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData d = cm == null ? null : cm.getPrimaryClip();
                CharSequence t = (d == null || d.getItemCount() == 0) ? null : d.getItemAt(0).getText();
                Matcher m = Pattern.compile("https?://\\S+").matcher(t == null ? "" : t);
                String id = m.find() ? videoId(m.group()) : null;
                if (id == null) { toast(getContext(), "В буфере нет ссылки на YouTube"); return; }
                Context c = getContext().getApplicationContext();
                Item it = findItem(id);
                if (it == null) {
                    it = new Item();
                    it.videoId = id;
                    it.title = "YouTube " + id;
                    synchronized (ITEMS) { ITEMS.add(0, it); }
                }
                it.url = "https://youtu.be/" + id;
                mod.startItem(c, it);
            } catch (Throwable e) {
                toast(getContext(), "Не вышло: " + e);
            }
        }

        private void confirmClear() {
            try {
                new AlertDialog.Builder(getContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Очистить список?")
                        .setPositiveButton("Очистить", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                synchronized (ITEMS) {
                                    for (Item it : ITEMS) it.removed = true;
                                    ITEMS.clear();
                                }
                                changed();
                            }
                        })
                        .setNegativeButton("Отмена", null)
                        .show();
            } catch (Throwable t) {
                synchronized (ITEMS) { ITEMS.clear(); }
                changed();
            }
        }

        /** Плитка 16:9: высота считается от ширины. */
        private static final class Tile extends FrameLayout {
            Tile(Context c) { super(c); }

            @Override protected void onMeasure(int wSpec, int hSpec) {
                int w = MeasureSpec.getSize(wSpec);
                super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(w * 9 / 16, MeasureSpec.EXACTLY));
            }
        }

        private TextView badge(String text) {
            TextView b = new TextView(getContext());
            b.setText(text);
            b.setTextSize(16);
            b.setTextColor(Color.WHITE);
            b.setBackgroundColor(0xAA000000);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(8), dp(4), dp(8), dp(4));
            return b;
        }

        private View tile(final Item it) {
            Context ctx = getContext();
            final Context app = ctx.getApplicationContext();
            final String name = it.title.length() == 0 ? it.url : it.title;
            final Tile tile = new Tile(ctx);
            tile.setContentDescription(name);

            ImageView img = new ImageView(ctx);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setBackgroundColor(0xFFE0E0E0);
            tile.addView(img, new FrameLayout.LayoutParams(-1, -1));

            TextView ph = new TextView(ctx);
            ph.setText(name);
            ph.setTextSize(14);
            ph.setTextColor(Color.BLACK);
            ph.setGravity(Gravity.CENTER);
            ph.setPadding(dp(10), dp(10), dp(10), dp(10));
            ph.setMaxLines(4);
            ph.setEllipsize(TextUtils.TruncateAt.END);
            tile.addView(ph, new FrameLayout.LayoutParams(-1, -1));
            loadThumb(it.videoId, img, ph);

            final TextView over = badge(name);
            over.setTextSize(15);
            over.setBackgroundColor(0xCC000000);
            over.setPadding(dp(10), dp(10), dp(10), dp(10));
            over.setVisibility(GONE);
            tile.addView(over, new FrameLayout.LayoutParams(-1, -1));

            String mark = it.busy ? ("run".equals(it.status) ? "🗣" : "⏳") : ("done".equals(it.status) ? "✅" : ("err".equals(it.status) ? "⚠️" : "⏳"));
            tile.addView(badge(mark), new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));

            LinearLayout bar = new LinearLayout(ctx);
            bar.setOrientation(HORIZONTAL);
            TextView redo = badge("↻");
            redo.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { mod.startItem(app, it); }
            });
            bar.addView(redo);
            TextView link = badge("🔗");
            link.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { openInBrowser(it.url); }
            });
            bar.addView(link);
            TextView del = badge("🗑");
            del.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    it.removed = true;
                    synchronized (ITEMS) { ITEMS.remove(it); }
                    changed();
                }
            });
            bar.addView(del);
            tile.addView(bar, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));

            final Runnable hide = new Runnable() {
                @Override public void run() { over.setVisibility(GONE); }
            };
            final GestureDetector gd = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }

                @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                    if (it.busy) toast(app, it.msg);
                    else if ("done".equals(it.status)) toast(app, "Готово: " + it.file + " (↻ — перевести заново)");
                    else mod.startItem(app, it);
                    return true;
                }

                @Override public boolean onDoubleTap(MotionEvent e) {
                    over.setText(it.msg.length() == 0 ? name : name + "\n" + it.msg);
                    over.setVisibility(VISIBLE);
                    over.removeCallbacks(hide);
                    over.postDelayed(hide, 6000);
                    return true;
                }
            });
            tile.setOnTouchListener(new OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent e) { return gd.onTouchEvent(e); }
            });
            return tile;
        }

        private void openInBrowser(String url) {
            try {
                Context c = getContext();
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.setClassName(c.getPackageName(), c.getPackageName() + ".BrowserApp");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } catch (Throwable t) {
                toast(getContext(), "Не открыть ссылку: " + t.getMessage());
            }
        }

        /** Превью с i.ytimg.com (кэш в cache/votq); без сети остаётся плитка с названием. */
        private void loadThumb(final String id, final ImageView iv, final View ph) {
            if (id == null || id.length() == 0) return;
            iv.setTag(id);
            final File f = new File(new File(getContext().getCacheDir(), "votq"), id + ".jpg");
            EXEC.execute(new Runnable() {
                @Override public void run() {
                    Bitmap bm = null;
                    try {
                        if (!f.isFile()) {
                            f.getParentFile().mkdirs();
                            HttpURLConnection c = (HttpURLConnection) new URL("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg").openConnection();
                            c.setConnectTimeout(8000);
                            c.setReadTimeout(8000);
                            if (c.getResponseCode() == 200) {
                                File tmp = new File(f.getPath() + ".tmp");
                                InputStream in = c.getInputStream();
                                OutputStream o = new FileOutputStream(tmp);
                                try {
                                    byte[] buf = new byte[8192];
                                    int n;
                                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                                } finally {
                                    o.close();
                                    in.close();
                                }
                                tmp.renameTo(f);
                            }
                        }
                        if (f.isFile()) {
                            BitmapFactory.Options o2 = new BitmapFactory.Options();
                            o2.inPreferredConfig = Bitmap.Config.RGB_565;
                            bm = BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
                        }
                    } catch (Throwable t) { }
                    final Bitmap fb = bm;
                    if (fb != null) {
                        iv.post(new Runnable() {
                            @Override public void run() {
                                if (id.equals(iv.getTag())) {
                                    iv.setImageBitmap(fb);
                                    ph.setVisibility(GONE);
                                }
                            }
                        });
                    }
                }
            });
        }
    }

    // ---------------------------------------------------------------- protobuf (только нужные сообщения)

    private static final class Pb {
        private final ByteArrayOutputStream o = new ByteArrayOutputStream();

        private Pb varint(long v) {
            while ((v & ~0x7FL) != 0) {
                o.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            o.write((int) v);
            return this;
        }

        private Pb tag(int field, int wire) { return varint(((long) field << 3) | wire); }

        Pb str(int field, String s) {
            byte[] b = s.getBytes(UTF8);
            tag(field, 2);
            varint(b.length);
            o.write(b, 0, b.length);
            return this;
        }

        /** false не пишется: так делает protobuf со значениями по умолчанию. */
        Pb bool(int field, boolean v) {
            if (v) { tag(field, 0); varint(1); }
            return this;
        }

        Pb int32(int field, int v) {
            if (v != 0) { tag(field, 0); varint(v); }
            return this;
        }

        Pb dbl(int field, double d) {
            if (d == 0) return this;
            tag(field, 1);
            long bits = Double.doubleToLongBits(d);
            for (int i = 0; i < 8; i++) o.write((int) ((bits >>> (8 * i)) & 0xFF));
            return this;
        }

        Pb msg(int field, byte[] sub) {
            tag(field, 2);
            varint(sub.length);
            o.write(sub, 0, sub.length);
            return this;
        }

        byte[] bytes() { return o.toByteArray(); }

        /** Разбор сообщения в «номер поля -> значения»: varint и fixed64 как Long, fixed32 как Integer, строки и вложенные сообщения как byte[]. */
        static Map<Integer, List<Object>> parse(byte[] b) {
            Map<Integer, List<Object>> m = new HashMap<Integer, List<Object>>();
            int[] pos = {0};
            try {
                while (pos[0] < b.length) {
                    long tag = readVarint(b, pos);
                    int field = (int) (tag >>> 3);
                    Object val;
                    switch ((int) (tag & 7)) {
                        case 0:
                            val = Long.valueOf(readVarint(b, pos));
                            break;
                        case 1: {
                            long x = 0;
                            for (int i = 0; i < 8; i++) x |= (long) (b[pos[0] + i] & 0xFF) << (8 * i);
                            pos[0] += 8;
                            val = Long.valueOf(x);
                            break;
                        }
                        case 2: {
                            int len = (int) readVarint(b, pos);
                            byte[] sub = new byte[len];
                            System.arraycopy(b, pos[0], sub, 0, len);
                            pos[0] += len;
                            val = sub;
                            break;
                        }
                        case 5: {
                            int x = 0;
                            for (int i = 0; i < 4; i++) x |= (b[pos[0] + i] & 0xFF) << (8 * i);
                            pos[0] += 4;
                            val = Integer.valueOf(x);
                            break;
                        }
                        default:
                            return m;
                    }
                    List<Object> l = m.get(Integer.valueOf(field));
                    if (l == null) { l = new ArrayList<Object>(); m.put(Integer.valueOf(field), l); }
                    l.add(val);
                }
            } catch (RuntimeException e) {
                // обрезанный ответ: берём то, что успели разобрать
            }
            return m;
        }

        private static long readVarint(byte[] b, int[] pos) {
            long r = 0;
            int shift = 0;
            while (true) {
                int x = b[pos[0]++] & 0xFF;
                r |= (long) (x & 0x7F) << shift;
                if ((x & 0x80) == 0) return r;
                shift += 7;
                if (shift > 63) throw new IllegalStateException("varint");
            }
        }

        static String str(Map<Integer, List<Object>> m, int field) {
            List<Object> l = m.get(Integer.valueOf(field));
            if (l == null || l.isEmpty() || !(l.get(0) instanceof byte[])) return null;
            return new String((byte[]) l.get(0), UTF8);
        }

        static long num(Map<Integer, List<Object>> m, int field) {
            List<Object> l = m.get(Integer.valueOf(field));
            if (l == null || l.isEmpty()) return 0;
            Object v = l.get(0);
            return v instanceof Long ? ((Long) v).longValue() : (v instanceof Integer ? ((Integer) v).longValue() : 0);
        }
    }
}
