package im.manus.universalhost;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Список ссылок YouTube для модуля «YouTube аудио» и цепочка кнопки 🎧. Список хранится в настройках модуля и переживает
 * перезапуск. Что закончилось и попало в плеер, из списка уходит; остаётся то, что ещё в работе или не вышло.
 *
 * Цепочка для каждой ссылки:
 *  1. ⬇ оригинальная русская дорожка (с YouTube, до max_dl роликов одновременно) → 🧹 очистка от «эээ»;
 *  2. 🗣 дорожки нет → перевод Яндекса (VOT): слота загрузки не занимает, роликов может быть сколько угодно;
 *  3. 🤖 VOT не справился → русская дорожка, переведённая самим YouTube (снова слот загрузки);
 *  4. 🚫 больше вариантов нет → ссылка остаётся в списке.
 * Очистка от «эээ» идёт по одной, потом и без интернета (слабый телефон): файл уже скачан и воспроизводится,
 * в списке он стоит с значком 🧹 и уходит, когда очистка закончилась.
 */
public final class YtAudioQueue {

    // Хост хранит настройки модуля под именем module_ + ModuleStorage.safe(имя): всё, кроме латиницы, цифр и _.-, становится «_».
    public static final String PREFS = "module_" + "YouTube аудио".replaceAll("[^A-Za-z0-9_.-]", "_");
    public static final String QUEUED = "q";      // ждёт загрузки
    public static final String NO_TRACK = "n";    // нет нужной дорожки (остаётся в списке)
    public static final String ERROR = "e";       // ошибка (остаётся в списке)
    public static final String VOT = "v";         // ждёт перевода Яндекса: слота загрузки не занимает
    public static final String CLEAN = "c";       // файл скачан, ждёт очистки от «эээ»

    public static final class Item {
        public String url = "";
        public String title = "";
        public String status = QUEUED;
        public String err = "";
        /** Шаг цепочки: o — оригинал, d — дубляж YouTube, v — перевод Яндекса, c — очистка. */
        public String step = "o";
        /** Скачанный файл (для очистки). */
        public String file = "";
    }

    private static YtAudioQueue inst;

    public static synchronized YtAudioQueue get(Context ctx) {
        if (inst == null) inst = new YtAudioQueue(ctx.getApplicationContext());
        return inst;
    }

    private final Context app;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final List<Item> items = new ArrayList<Item>();
    private final Set<String> active = new HashSet<String>();         // качаются сейчас (занимают слот)
    private final Set<String> votRestart = new HashSet<String>();     // ждали VOT до перезапуска: отдать заново
    private final List<Runnable> listeners = new ArrayList<Runnable>();
    private ConnectivityManager.NetworkCallback netCb;
    private boolean forceAll;
    private boolean votOn, cleanerOn;
    private String cleaning = "";                                     // ссылка, у которой идёт очистка

    private YtAudioQueue(Context app) {
        this.app = app;
        load();
        if (hasStatus(VOT)) startVotWatcher();
        if (hasStatus(CLEAN)) startCleaner();
    }

    // ---------------------------------------------------------- настройки и хранение

    private SharedPreferences prefs() {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean autoMode() { return prefs().getBoolean("auto_mode", true); }

    public boolean wifiOnly() { return prefs().getBoolean("wifi_only", false); }

    /** Сколько роликов качается с YouTube одновременно (перевод Яндекса и так без ограничения, очистка по одной). */
    private int maxDl() {
        try {
            return Math.max(1, Math.min(10, Integer.parseInt(prefs().getString("max_dl", "3").trim())));
        } catch (Throwable t) {
            return 3;
        }
    }

    private boolean cleanWanted() {
        return prefs().getBoolean("clean_fillers", true) && FillerCleaner.available();
    }

    /** Корень SD-карты (съёмного тома) или null, если её нет или она не подключена. */
    public static File sdRoot(Context app) {
        try {
            File internal = Environment.getExternalStorageDirectory();
            File[] dirs = app.getExternalFilesDirs(null);
            if (dirs == null) return null;
            for (File d : dirs) {
                if (d == null) continue;
                String path = d.getAbsolutePath();
                int i = path.indexOf("/Android/data/");
                if (i < 0) continue;
                File root = new File(path.substring(0, i));
                if (root.equals(internal)) continue;
                if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(d))) continue;
                if (!Environment.isExternalStorageRemovable(d)) continue;
                return root;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /**
     * Единая папка для всех аудио (оригинал, дубляж YouTube, перевод Яндекса): внутри памяти телефона или SD-карты
     * (настройка «Куда сохранять»). null: выбрана SD-карта, а её нет.
     */
    public static File mediaDir(Context app) {
        SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String rel = p.getString("out_dir", "Music/YouTube");
        if (rel == null || rel.trim().length() == 0) rel = "Music/YouTube";
        rel = rel.trim();
        while (rel.startsWith("/")) rel = rel.substring(1);
        File root = p.getInt("storage", 0) == 1 ? sdRoot(app) : Environment.getExternalStorageDirectory();
        return root == null ? null : new File(root, rel);
    }

    private String outDir() {
        File d = mediaDir(app);
        return d == null ? null : d.getAbsolutePath();
    }

    private void load() {
        try {
            SharedPreferences p = prefs();
            String raw = p.getString("items", null);
            if (raw == null) raw = p.getString("pending", "[]");      // очередь старых версий
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.url = o.getString("u");
                it.title = o.optString("t", "");
                it.status = o.optString("s", QUEUED);
                it.err = o.optString("e", "");
                it.step = o.optString("p", "o");
                it.file = o.optString("f", "");
                if (VOT.equals(it.status)) votRestart.add(it.url);
                items.add(it);
            }
        } catch (Throwable t) { }
    }

    private synchronized void save() {
        try {
            JSONArray a = new JSONArray();
            for (Item it : items) {
                a.put(new JSONObject().put("u", it.url).put("t", it.title).put("s", it.status).put("e", it.err)
                        .put("p", it.step).put("f", it.file));
            }
            prefs().edit().putString("items", a.toString()).apply();
        } catch (Throwable t) { }
    }

    // ---------------------------------------------------------- список

    public synchronized List<Item> snapshot() { return new ArrayList<Item>(items); }

    private synchronized boolean hasStatus(String s) {
        for (Item it : items) if (s.equals(it.status)) return true;
        return false;
    }

    /** В работе: качается, ждёт перевод Яндекса или стоит в очереди на очистку. */
    public synchronized boolean isActive(String url) {
        if (active.contains(url) || cleaning.equals(url)) return true;
        Item it = find(url);
        return it != null && (VOT.equals(it.status) || CLEAN.equals(it.status));
    }

    /** Значок шага для ролика в работе: ⬇ качается, 🤖⬇ дубляж YouTube, 🗣 перевод Яндекса, 🧹⏳ ждёт очистки, 🧹 чистится. */
    public synchronized String stage(String url) {
        Item it = find(url);
        if (it == null) return "⬇";
        if (active.contains(url)) return "d".equals(it.step) ? "🤖⬇" : "⬇";
        if (VOT.equals(it.status)) return "🗣";
        if (CLEAN.equals(it.status)) return cleaning.equals(url) ? "🧹" : "🧹⏳";
        return "⬇";
    }

    public void addListener(Runnable r) { synchronized (listeners) { listeners.add(r); } }

    public void removeListener(Runnable r) { synchronized (listeners) { listeners.remove(r); } }

    private void changed() {
        h.post(new Runnable() {
            @Override public void run() {
                List<Runnable> copy;
                synchronized (listeners) { copy = new ArrayList<Runnable>(listeners); }
                for (Runnable r : copy) r.run();
            }
        });
    }

    private Item find(String url) {
        for (Item it : items) if (it.url.equals(url)) return it;
        return null;
    }

    /** Заново: очистка, если файл уже скачан, иначе вся цепочка с начала. */
    private void retry(Item it) {
        it.err = "";
        if ("c".equals(it.step) && it.file.length() > 0 && new File(it.file).isFile()) {
            it.status = CLEAN;
        } else {
            it.status = QUEUED;
            it.step = "o";
            it.file = "";
        }
    }

    /** Новая ссылка: в начало списка; в автоматическом режиме сразу идёт в загрузку, когда есть сеть. */
    public synchronized void submit(String url, String title) {
        Item it = find(url);
        if (it == null) {
            it = new Item();
            it.url = url;
            items.add(0, it);
        }
        if (title != null && title.length() > 0) it.title = title;
        boolean busy = active.contains(url) || cleaning.equals(url) || VOT.equals(it.status) || CLEAN.equals(it.status);
        if (!busy) retry(it);
        save();
        changed();
        if (CLEAN.equals(it.status)) startCleaner();
        if (autoMode()) {
            toast("📋 В списке. Сеть сейчас: " + netLabel(netKind()));
            runNext();
        } else {
            toast("📋 Добавлено в список: загрузка вручную");
        }
    }

    public synchronized void remove(String url) {
        Item it = find(url);
        if (it != null && !active.contains(url) && !cleaning.equals(url)) items.remove(it);
        save();
        changed();
    }

    /** Убирает всё, что сейчас не качается и не чистится. */
    public synchronized void clear() {
        List<Item> keep = new ArrayList<Item>();
        for (Item it : items) if (active.contains(it.url) || cleaning.equals(it.url)) keep.add(it);
        items.clear();
        items.addAll(keep);
        save();
        changed();
    }

    public static String statusText(Item it) {
        if (QUEUED.equals(it.status)) return "Ждёт загрузки";
        if (VOT.equals(it.status)) return "Яндекс переводит";
        if (CLEAN.equals(it.status)) return "Ждёт очистки от «эээ»";
        return it.err == null ? "" : it.err;
    }

    // ---------------------------------------------------------- сеть

    /** -1: не удалось определить, 0: нет сети, 1: мобильная, 2: Wi-Fi или Ethernet. */
    public int netKind() {
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, mobile = false;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c == null || c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                NetworkInfo ni = cm.getNetworkInfo(n);
                if (ni != null && !ni.isConnected()) continue;
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) wifi = true;
                else if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) mobile = true;
            }
            if (!wifi && !mobile) {
                NetworkInfo ni = cm.getActiveNetworkInfo();
                if (ni != null && ni.isConnected()) {
                    int t = ni.getType();
                    if (t == ConnectivityManager.TYPE_WIFI || t == ConnectivityManager.TYPE_ETHERNET) wifi = true;
                    else mobile = true;
                }
            }
            return wifi ? 2 : (mobile ? 1 : 0);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static String netLabel(int kind) {
        switch (kind) {
            case 2: return "Wi-Fi";
            case 1: return "мобильная сеть";
            case 0: return "нет сети";
            default: return "не определена";
        }
    }

    private Network wifiNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return n;
            }
        } catch (Throwable t) { }
        return null;
    }

    private boolean netFits(int kind) {
        if (kind < 0) return true;                      // не определили: пробуем, при сбое вернётся в список
        return wifiOnly() ? kind == 2 : kind >= 1;
    }

    private synchronized void watchNet() {
        if (netCb != null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkRequest rq = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            netCb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { later(); }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) { later(); }
                private void later() {
                    h.postDelayed(new Runnable() {
                        @Override public void run() { tryDrain(); }
                    }, 1500);                           // даём сети стать основной
                }
            };
            cm.registerNetworkCallback(rq, netCb);
        } catch (Throwable t) {
            netCb = null;
        }
    }

    public synchronized void unwatchNet() {
        if (netCb == null) return;
        try {
            ((ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCb);
        } catch (Throwable t) { }
        netCb = null;
    }

    // ---------------------------------------------------------- загрузка

    /** Вызывать при старте модуля, смене режима и появлении сети. */
    public synchronized void tryDrain() {
        if (autoMode() || forceAll) runNext();
    }

    /** До max_dl роликов одновременно, пока есть ждущие ссылки и подходящая сеть. */
    private synchronized void runNext() {
        int k = netKind();
        int limit = maxDl();
        while (active.size() < limit) {
            Item next = null;
            for (Item it : items) if (QUEUED.equals(it.status) && !active.contains(it.url)) { next = it; break; }
            if (next == null) {
                forceAll = false;
                if (active.isEmpty()) unwatchNet();
                return;
            }
            if (!netFits(k)) {
                forceAll = false;
                if (autoMode()) watchNet();
                return;
            }
            startItem(next, wifiOnly() && k == 2 ? wifiNetwork() : null);
        }
    }

    /** Кнопка «качать всё»: весь список, даже в ручном режиме. */
    public synchronized void downloadAll() {
        boolean any = false;
        for (Item it : items) {
            if (!active.contains(it.url) && ERROR.equals(it.status)) retry(it);
            if (QUEUED.equals(it.status) || CLEAN.equals(it.status)) any = true;
        }
        if (!any) { toast("📭"); return; }
        forceAll = true;
        save();
        changed();
        startCleaner();
        runNext();
    }

    /** Нажатие на плитку: заново именно эту ссылку. */
    public synchronized void downloadOne(Item it) {
        if (active.contains(it.url) || cleaning.equals(it.url)) return;
        if (VOT.equals(it.status)) { toast("🗣"); return; }
        if (CLEAN.equals(it.status)) { toast("🧹"); startCleaner(); return; }
        retry(it);
        save();
        if (CLEAN.equals(it.status)) {
            changed();
            startCleaner();
            return;
        }
        int k = netKind();
        if (k == 0) {
            toast("📵 Нет сети: ссылка ждёт в списке");
            changed();
            return;
        }
        if (!netFits(k)) {
            toast("📶 Нужен Wi-Fi, сейчас: " + netLabel(k) + ". Выключи «Только по Wi-Fi» или дождись Wi-Fi");
            changed();
            return;
        }
        startItem(it, wifiOnly() && k == 2 ? wifiNetwork() : null);
    }

    private void startItem(final Item it, Network net) {
        final String dir = outDir();
        if (dir == null) {                                   // выбрана SD-карта, а её нет: не качаем в другое место молча
            it.status = ERROR;
            it.err = "SD-карта не найдена";
            save();
            changed();
            toast("💾⚠️");
            return;
        }
        active.add(it.url);
        changed();
        String lang = prefs().getString("lang", "ru");
        final boolean dub = "d".equals(it.step);
        toast(dub ? "🤖" : "🎧");
        int mode = dub ? YtAudioBridge.MODE_DUBBED : YtAudioBridge.MODE_ORIGINAL;
        YtAudioBridge.start(app, it.url, it.title, lang, dir, false, mode, net, new YtAudioBridge.Listener() {
            @Override public void onStatus(String text) { }              // проценты наружу не идут: на плитке значок шага
            @Override public void onDone(String audioPath, String error) { finish(it, audioPath, error); }
        });
    }

    /** Закончилась загрузка оригинала или дубляжа. */
    private void finish(final Item it, String path, String error) {
        boolean toVot = false;
        synchronized (this) {
            boolean netLost = false;
            if (error == null) {
                active.remove(it.url);
                if ("o".equals(it.step) && path != null && cleanWanted()) {
                    it.status = CLEAN;                  // файл уже в плеере; чистка потом, по одной
                    it.step = "c";
                    it.file = path;
                    it.err = "";
                    toast("🧹⏳");
                } else {
                    items.remove(it);
                    toast("✅");
                }
            } else if (error.startsWith("Нет сети")) {
                active.remove(it.url);
                it.status = QUEUED;
                it.err = "";
                netLost = true;
                toast("📵 Сеть пропала: ссылка осталась в списке");
            } else if (error.startsWith("Нет дорожки")) {
                if ("o".equals(it.step)) {
                    toVot = true;                       // слот держим, пока не решим, куда дальше
                } else {
                    active.remove(it.url);
                    it.status = NO_TRACK;
                    it.err = "Нет дорожки: русской нет, перевод Яндекса не вышел, дубляжа YouTube тоже нет";
                    toast("🚫");
                }
            } else {
                active.remove(it.url);
                it.status = ERROR;
                it.err = error;
                toast("⚠️");
            }
            save();
            changed();
            if (!toVot) {
                if (CLEAN.equals(it.status)) startCleaner();
                if (netLost) {
                    forceAll = false;
                    if (autoMode()) watchNet();
                } else if (forceAll || autoMode()) {
                    runNext();
                }
            }
        }
        if (toVot) goVot(it);
    }

    // ---------------------------------------------------------- перевод Яндекса (VOT)

    /** Нет русской дорожки: ролик уходит в VOT и слот загрузки освобождается. Нет модуля VOT: сразу дубляж YouTube. */
    private void goVot(final Item it) {
        io.execute(new Runnable() {
            @Override public void run() {
                String e = YtPipeline.votSubmit(app, it.url, it.title);
                synchronized (YtAudioQueue.this) {
                    active.remove(it.url);
                    it.err = "";
                    if (e == null) {
                        it.status = VOT;
                        it.step = "v";
                        toast("🗣");
                    } else {
                        it.status = QUEUED;
                        it.step = "d";
                        toast("🤖");
                    }
                    save();
                    changed();
                }
                if (e == null) startVotWatcher();
                synchronized (YtAudioQueue.this) {
                    if (forceAll || autoMode()) runNext();
                }
            }
        });
    }

    private void startVotWatcher() {
        synchronized (this) {
            if (votOn) return;
            votOn = true;
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() { votLoop(); }
        }, "YtVotWatch");
        t.start();
    }

    /** Раз в несколько секунд читает, как дела у VOT; сколько роликов ждёт перевода, не важно. */
    private void votLoop() {
        List<Item> redo = new ArrayList<Item>();
        synchronized (this) {
            for (Item it : items) if (VOT.equals(it.status) && votRestart.remove(it.url)) redo.add(it);
        }
        for (Item it : redo) YtPipeline.votSubmit(app, it.url, it.title);    // после перезапуска перевод мог оборваться
        while (true) {
            List<Item> waiting = new ArrayList<Item>();
            synchronized (this) {
                for (Item it : items) if (VOT.equals(it.status)) waiting.add(it);
                if (waiting.isEmpty()) {
                    votOn = false;
                    return;
                }
            }
            for (Item it : waiting) {
                String[] st = YtPipeline.votState(app, it.url);
                if ("done".equals(st[0])) {
                    synchronized (this) {
                        items.remove(it);
                        save();
                        changed();
                        toast("✅");
                    }
                    YtPipeline.votRemove(app, it.url);                        // запись в списке VOT больше не нужна, файл остаётся
                } else if ("err".equals(st[0]) || "gone".equals(st[0])) {
                    synchronized (this) {
                        it.status = QUEUED;                                   // VOT не справился: дубляж YouTube
                        it.step = "d";
                        it.err = "";
                        save();
                        changed();
                        toast("🤖");
                        if (forceAll || autoMode()) runNext();
                    }
                }
            }
            try { Thread.sleep(3000); } catch (InterruptedException e) { synchronized (this) { votOn = false; } return; }
        }
    }

    // ---------------------------------------------------------- очистка от «эээ»

    private void startCleaner() {
        synchronized (this) {
            if (cleanerOn) return;
            cleanerOn = true;
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); } catch (Throwable ignored) { }
                cleanLoop();
            }
        }, "YtCleaner");
        t.start();
    }

    /** По одному файлу: телефон слабый. Интернет не нужен (кроме первого раза: тогда скачивается модель). */
    private void cleanLoop() {
        while (true) {
            final Item it;
            synchronized (this) {
                Item next = null;
                for (Item x : items) if (CLEAN.equals(x.status)) { next = x; break; }
                if (next == null) {
                    cleaning = "";
                    cleanerOn = false;
                    return;
                }
                it = next;
                cleaning = it.url;
            }
            changed();
            FillerCleaner.Report rep = null;
            String err = null;
            try {
                File f = new File(it.file);
                if (!f.isFile()) throw new IOException("файл не найден");
                rep = FillerCleaner.clean(app, f);
            } catch (Throwable t) {
                err = t.getMessage() == null ? t.toString() : t.getMessage();
            }
            synchronized (this) {
                cleaning = "";
                if (err == null) {
                    items.remove(it);
                } else {
                    it.status = ERROR;
                    it.step = "c";
                    it.err = "Очистка не вышла: " + err;
                }
                save();
            }
            changed();
            toast(err != null ? "⚠️🧹" : (rep != null && rep.count > 0 ? "✂️✅" : "👌✅"));
        }
    }

    public void toast(final String s) {
        h.post(new Runnable() {
            @Override public void run() {
                // уведомления без текста: от строки остаются только эмодзи и значки
                String g = YtNotify.glyphs(s);
                try { Toast.makeText(app, g.length() == 0 ? "🔔" : g, Toast.LENGTH_SHORT).show(); } catch (Throwable t) { }
            }
        });
    }
}
