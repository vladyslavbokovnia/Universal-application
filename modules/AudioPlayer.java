package im.manus.plugins;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.KeyguardManager;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.hardware.input.InputManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.MediaScannerConnection;
import android.media.PlaybackParams;
import android.media.ToneGenerator;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import im.manus.universalhost.IKeyHandler;
import im.manus.universalhost.IMotionHandler;
import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.ISettingsViewProvider;
import im.manus.universalhost.IShareHandler;
import im.manus.universalhost.PadMapper;
import im.manus.universalhost.SettingItem;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Аудиоплеер и пульт в одном модуле.
 *
 * Свой плеер: песни с устройства (список, удаление), своя медиа-сессия — гарнитура и системные клавиши работают
 * с ним. Пульт: если свой плеер не играет, кнопки геймпада управляют другим играющим плеером (через его
 * медиасессию; нужен доступ к уведомлениям для хоста). Кнопки назначаются на картинке геймпада: нажатие,
 * двойное и долгое. Стик-циферблат: стик отклонён — играет место трека, на которое он указывает, вернул в
 * центр — остаёшься там; какой стик работает циферблатом, включается на картинке геймпада (нажать стик на
 * картинке), нажатие самого правого стика по умолчанию озвучивает процент трека. Shizuku не нужен: кнопки идут
 * через службу специальных возможностей (работает при выключенном экране), стики — через неё на Android 14+,
 * а на более старых (Android 11) — через открытое окно плеера (на e-ink экран можно не гасить).
 *
 * Списки: «Все песни» (без тех, что перенесены в плейлисты), «Послушать позже» и «Избранное». Трек, добавленный в
 * плейлист, из общего списка пропадает; вернуть его можно кнопкой «↩» в плейлисте. Следующий и предыдущий идут по
 * тому списку, с которого начали играть; кнопкой геймпада «Сменить список» можно перейти на следующий список.
 */
public class AudioPlayer implements IPlugin, ISettingsProvider, ISettingsViewProvider, IKeyHandler,
        IMotionHandler, IShareHandler {

    private static final String PREFS = "module_Аудиоплеер";
    private static final String CHANNEL = "audioplayer";
    private static final int NOTIF_ID = 4101;

    private static final String A_TOGGLE = "im.manus.plugins.audioplayer.TOGGLE";
    private static final String A_NEXT = "im.manus.plugins.audioplayer.NEXT";
    private static final String A_PREV = "im.manus.plugins.audioplayer.PREV";

    /** Геймпад: действия плеера — id, подпись (эмодзи в начале подписи рисуется значком на кнопке картинки). */
    private static final String[][] ACTIONS = {
            {"play_pause", "⏯ Пауза / пуск"},
            {"play", "▶ Играть"},
            {"pause", "⏸ Пауза"},
            {"prev", "⏮ Назад"},
            {"next", "⏭ Вперёд"},
            {"seek_back", "⏪ −10 с"},
            {"seek_fwd", "⏩ +10 с"},
            {"stop", "⏹ Стоп"},
            {"vol_down", "🔉 Тише"},
            {"vol_up", "🔊 Громче"},
            {"mute", "🔇 Без звука"},
            {"later", "🕓 Послушать позже"},
            {"fav", "⭐ Избранное"},
            {"pl_switch", "🔀 Сменить список (все / позже / избранное)"},
            {"delete", "🗑 Удалить (нажать дважды)"},
            {"say", "🗣 Название и сколько осталось"},
            {"say_pct", "📊 Сколько процентов трека"},
            {"lock", "🔒 Блок касаний до выключения экрана"},
            {"stop_after", "⏹ Стоп после этого трека"},
            {"speed_up", "🐇 Быстрее"},
            {"speed_down", "🐢 Медленнее"},
            {"speed_reset", "1× Обычная скорость"},
            {"focus", "🎯 Фокус для геймпада вкл/выкл"},
    };

    private static final String PL_LATER = "pl_later";
    private static final String PL_FAV = "pl_fav";

    static class Track {
        long id;
        String title;
        String artist;
        long dur;
        Uri uri;
        /** Путь к файлу из медиатеки (по нему ищется превью .jpg рядом); у песен из «Поделиться» его нет. */
        String path;
    }

    private Context appCtx;
    private Handler h;
    private AccessibilityService svc;
    private MediaPlayer mp;
    private boolean prepared = false;
    private final List<Track> tracks = new ArrayList<Track>();
    private int cur = -1;
    /** Очередь из плейлиста (индексы в tracks); null: играем общий список (без треков из плейлистов) по порядку. */
    private int[] queue;
    /** С какого списка начали играть: 0 все песни, 1 «Послушать позже», 2 «Избранное». */
    private int listSrc = 0;
    private boolean stopAfter;
    private int resumeTo;
    private MediaSession session;
    private BroadcastReceiver cmdReceiver;
    private PlayerView boundView;
    private PadMapper mapper;
    private ContentObserver mediaObs;
    private volatile boolean scanning;
    private final Runnable reloadR = new Runnable() {
        @Override public void run() {
            if (reloadIfChanged()) refreshUiAll();
        }
    };

    @Override public String getName() { return "Аудиоплеер"; }
    @Override public int getVersion() { return 11; }
    @Override public String getDescription() {
        return "Аудиоплеер и пульт: песни с устройства, управление другим плеером, геймпад, гарнитура, стик-циферблат по треку.";
    }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override public void init(Context context) {
        appCtx = context.getApplicationContext();
        h = new Handler(Looper.getMainLooper());
        svc = (context instanceof AccessibilityService) ? (AccessibilityService) context : null;
        if (!remoteAny()) ensureSession();   // свой плеер всегда главный: гарнитура и системные клавиши идут сюда сразу
        loadTracks();
        ensureObserver();
        mapper(context);
        registerFocusWatch();
        updateFocusCatcher();
    }

    @Override public void stop() {
        try { if (coverPool != null) coverPool.shutdownNow(); } catch (Throwable t) { }
        coverPool = null;
        coverCache.evictAll();
        unregisterFocusWatch();
        removeFocusCatcher();
        try {
            if (mediaObs != null && appCtx != null) appCtx.getContentResolver().unregisterContentObserver(mediaObs);
        } catch (Throwable t) { }
        mediaObs = null;
        if (h != null) h.removeCallbacks(reloadR);
        rememberPos();
        releaseHeadset();
        unlockTouch();
        try { if (tts != null) { tts.shutdown(); } } catch (Throwable t) { }
        tts = null;
        ttsReady = false;
        boundView = null;
        padReleaseAll();
        try {
            if (cmdReceiver != null && appCtx != null) appCtx.unregisterReceiver(cmdReceiver);
        } catch (Throwable t) { }
        cmdReceiver = null;
        try {
            if (session != null) session.release();
        } catch (Throwable t) { }
        session = null;
        try {
            if (mp != null) {
                try { mp.stop(); } catch (Throwable t) { }
                mp.release();
            }
        } catch (Throwable t) { }
        mp = null;
        prepared = false;
        cancelNotif();
    }

    @Override public Object execute(Map<String, ?> data) { return null; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event, AccessibilityService service) { }
    @Override public Bitmap createIcon(int sizePx) { return null; }

    private SharedPreferences prefs() {
        Context c = appCtx;
        return c == null ? null : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void toast(final String s) {
        if (h == null || appCtx == null) return;
        h.post(new Runnable() {
            @Override public void run() {
                try { Toast.makeText(appCtx, s, Toast.LENGTH_SHORT).show(); } catch (Throwable t) { }
            }
        });
    }

    // ---------------------------------------------------------- разрешения

    boolean hasPermission() {
        if (appCtx == null) return false;
        String perm = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return appCtx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    void requestPermission(Activity act) {
        String audio = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        String[] req = Build.VERSION.SDK_INT >= 33
                ? new String[]{audio, Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{audio};
        try { act.requestPermissions(req, NOTIF_ID); } catch (Throwable t) { }
    }

    private boolean notifAllowed() {
        if (appCtx == null) return false;
        if (Build.VERSION.SDK_INT < 33) return true;
        return appCtx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ---------------------------------------------------------- список песен

    /** Порядок списка: по времени добавления на устройство (DATE_ADDED); «новые сверху» включается в настройках. */
    private boolean newestFirst() {
        SharedPreferences p = prefs();
        return p != null && p.getBoolean("newest_first", false);
    }

    /**
     * Перечитывает песни из медиатеки. Текущий трек и очередь плейлиста находятся заново по id (порядок мог
     * поменяться, появились новые песни); песни из «Поделиться» остаются в начале списка.
     */
    void loadTracks() {
        long curId = (cur >= 0 && cur < tracks.size()) ? tracks.get(cur).id : Long.MIN_VALUE;
        int[] q0 = queue;
        long[] qIds = null;
        if (q0 != null) {
            qIds = new long[q0.length];
            for (int k = 0; k < q0.length; k++) {
                qIds[k] = (q0[k] >= 0 && q0[k] < tracks.size()) ? tracks.get(q0[k]).id : Long.MIN_VALUE;
            }
        }
        List<Track> shared = new ArrayList<Track>();
        for (Track t : tracks) if (t.id < 0) shared.add(t);
        tracks.clear();
        noCover.clear();
        queue = null;
        tracks.addAll(shared);
        if (appCtx != null && hasPermission()) {
            Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String[] proj = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA
            };
            String dir = newestFirst() ? " DESC" : " ASC";
            String order = MediaStore.Audio.Media.DATE_ADDED + dir + ", " + MediaStore.Audio.Media._ID + dir;
            Cursor c = null;
            try {
                c = appCtx.getContentResolver().query(uri, proj,
                        MediaStore.Audio.Media.IS_MUSIC + " != 0", null, order);
                while (c != null && c.moveToNext()) {
                    Track t = new Track();
                    t.id = c.getLong(0);
                    t.title = c.getString(1);
                    if (t.title == null || t.title.length() == 0) t.title = "Без названия";
                    t.artist = c.getString(2);
                    if (t.artist == null || t.artist.length() == 0) t.artist = "Неизвестный исполнитель";
                    t.dur = c.getLong(3);
                    t.path = c.getString(4);
                    t.uri = ContentUris.withAppendedId(uri, t.id);
                    tracks.add(t);
                }
            } catch (Throwable t) {
                // нет доступа — список останется пустым
            } finally {
                if (c != null) try { c.close(); } catch (Throwable t) { }
            }
        }
        if (curId != Long.MIN_VALUE) {
            cur = -1;
            for (int i = 0; i < tracks.size(); i++) {
                if (tracks.get(i).id == curId) { cur = i; break; }
            }
        }
        if (cur >= tracks.size()) cur = -1;
        if (qIds != null) {
            List<Integer> nq = new ArrayList<Integer>();
            for (int k = 0; k < qIds.length; k++) {
                if (qIds[k] == Long.MIN_VALUE) continue;
                for (int i = 0; i < tracks.size(); i++) {
                    if (tracks.get(i).id == qIds[k]) { nq.add(Integer.valueOf(i)); break; }
                }
            }
            if (!nq.isEmpty()) {
                int[] arr = new int[nq.size()];
                for (int k = 0; k < arr.length; k++) arr[k] = nq.get(k).intValue();
                queue = arr;
            }
        }
    }

    private long sigOf() {
        long s = tracks.size();
        for (Track t : tracks) s = s * 1000003L + t.id;
        return s;
    }

    /** Перечитать песни; true, если список изменился (появились или пропали песни, поменялся порядок). */
    boolean reloadIfChanged() {
        long before = sigOf();
        loadTracks();
        return sigOf() != before;
    }

    /** Следим за медиатекой: новая песня появляется в списке сама, без перезапуска окна. */
    private void ensureObserver() {
        if (mediaObs != null || appCtx == null || h == null) return;
        try {
            mediaObs = new ContentObserver(h) {
                @Override public void onChange(boolean selfChange) {
                    h.removeCallbacks(reloadR);
                    h.postDelayed(reloadR, 1000);
                }
            };
            appCtx.getContentResolver().registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaObs);
        } catch (Throwable t) {
            mediaObs = null;
        }
    }

    private static boolean isAudioName(String n) {
        String s = n.toLowerCase(Locale.ROOT);
        return s.endsWith(".mp3") || s.endsWith(".m4a") || s.endsWith(".flac") || s.endsWith(".ogg")
                || s.endsWith(".opus") || s.endsWith(".wav") || s.endsWith(".aac") || s.endsWith(".wma");
    }

    private void collectAudio(File dir, int depth, HashSet<String> known, List<String> out) {
        if (dir == null || depth > 4 || out.size() >= 500) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) collectAudio(f, depth + 1, known, out);
            else if (isAudioName(n) && !known.contains(f.getAbsolutePath())) out.add(f.getAbsolutePath());
        }
    }

    /**
     * Кнопка «🔄»: песни, которых система ещё не внесла в медиатеку (скачали или скопировали файлом), ищем в
     * папках Music, Download и Podcasts и просим систему добавить; потом список перечитывается.
     */
    void scanNewFiles() {
        final Context c = appCtx;
        if (c == null || scanning) return;
        scanning = true;
        toast("Ищу новые песни…");
        new Thread(new Runnable() {
            @Override public void run() {
                final List<String> fresh = new ArrayList<String>();
                try {
                    HashSet<String> known = new HashSet<String>();
                    Cursor cu = null;
                    try {
                        cu = c.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                new String[]{MediaStore.Audio.Media.DATA}, null, null, null);
                        while (cu != null && cu.moveToNext()) {
                            String d = cu.getString(0);
                            if (d != null) known.add(d);
                        }
                    } catch (Throwable t) {
                    } finally {
                        if (cu != null) try { cu.close(); } catch (Throwable t) { }
                    }
                    String[] dirs = {Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_PODCASTS};
                    for (String d : dirs) collectAudio(Environment.getExternalStoragePublicDirectory(d), 0, known, fresh);
                } catch (Throwable t) { }
                final Handler hh = h;
                if (fresh.isEmpty()) {
                    scanning = false;
                    toast("Новых песен нет");
                    if (hh != null) hh.post(reloadR);
                    return;
                }
                toast("Добавляю песен: " + fresh.size());
                final AtomicInteger left = new AtomicInteger(fresh.size());
                try {
                    MediaScannerConnection.scanFile(c, fresh.toArray(new String[0]), null,
                            new MediaScannerConnection.OnScanCompletedListener() {
                                @Override public void onScanCompleted(String path, Uri uri) {
                                    if (left.decrementAndGet() <= 0) {
                                        scanning = false;
                                        if (hh != null) hh.post(reloadR);
                                    }
                                }
                            });
                } catch (Throwable t) {
                    scanning = false;
                }
            }
        }).start();
    }

    static String fmtDur(long ms) {
        if (ms <= 0) return "";
        long s = ms / 1000;
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    // ---------------------------------------------------------- общий список и очередь

    /** Для каждой песни: лежит ли она в «Послушать позже» или «Избранном» (такие в общем списке не показываются). */
    private boolean[] hiddenFlags() {
        boolean[] hid = new boolean[tracks.size()];
        HashSet<String> uris = new HashSet<String>();
        HashSet<String> names = new HashSet<String>();
        String[] keys = {PL_LATER, PL_FAV};
        for (int k = 0; k < keys.length; k++) {
            for (String[] e : plLoad(keys[k])) {
                if (!e[0].equals("-")) uris.add(e[0]);
                else names.add((e[1] + "\t" + e[2]).toLowerCase(Locale.ROOT));
            }
        }
        if (uris.isEmpty() && names.isEmpty()) return hid;
        for (int i = 0; i < hid.length; i++) {
            Track t = tracks.get(i);
            if (t.uri != null && uris.contains(t.uri.toString())) hid[i] = true;
            else if (names.contains((t.title + "\t" + t.artist).toLowerCase(Locale.ROOT))) hid[i] = true;
        }
        return hid;
    }

    /** Номера песен общего списка: все, кроме перенесённых в плейлисты, в порядке списка. */
    private int[] generalArray() {
        boolean[] hid = hiddenFlags();
        int n = 0;
        for (int i = 0; i < hid.length; i++) if (!hid[i]) n++;
        int[] r = new int[n];
        int k = 0;
        for (int i = 0; i < hid.length; i++) if (!hid[i]) r[k++] = i;
        return r;
    }

    /** Очередь, по которой идут «следующий» и «предыдущий»: плейлист, иначе общий список, иначе вся медиатека. */
    private int[] effectiveQueue() {
        int[] q = queue;
        if (q != null && q.length > 0) return q;
        int[] g = generalArray();
        if (g.length > 0) return g;
        int[] all = new int[tracks.size()];
        for (int i = 0; i < all.length; i++) all[i] = i;
        return all;
    }

    // ---------------------------------------------------------- воспроизведение

    private void ensurePlayer() {
        if (mp != null) return;
        mp = new MediaPlayer();
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(MediaPlayer p) {
                prepared = true;
                if (resumeTo > 0) {
                    try { p.seekTo(resumeTo); } catch (Throwable t) { }
                    resumeTo = 0;
                }
                try { p.start(); } catch (Throwable t) { }
                applySpeed();
                afterChange();
            }
        });
        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override public void onCompletion(MediaPlayer p) {
                rememberPos();                                   // дослушали: место забываем
                SharedPreferences sp = prefs();
                if (stopAfter || (sp != null && sp.getBoolean("no_auto_next", false))) {
                    stopAfter = false;
                    afterChange();                               // остановка без перехода к следующему
                    return;
                }
                next();
            }
        });
        mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override public boolean onError(MediaPlayer p, int what, int extra) {
                prepared = false;
                afterChange();
                return true;
            }
        });
    }

    public void playAt(int i) {
        if (i < 0 || i >= tracks.size() || appCtx == null) return;
        extPkg = null;
        try {
            ensurePlayer();
            ensureSession();
            ensureReceiver();
            ensureHeadset();
            rememberPos();
            mp.reset();
            prepared = false;
            cur = i;
            stopAfter = false;
            resumeTo = savedPos(tracks.get(i));
            mp.setDataSource(appCtx, tracks.get(i).uri);
            mp.prepareAsync();
            afterChange();
        } catch (Throwable t) {
            toast("Не играется: " + t.getMessage());
        }
    }

    public void toggle() {
        extPkg = null;
        if (mp == null || !prepared) {
            if (!tracks.isEmpty()) playAt(cur >= 0 ? cur : firstIndex());
            return;
        }
        try {
            if (mp.isPlaying()) { mp.pause(); rememberPos(); } else mp.start();
            afterChange();
        } catch (Throwable t) { }
    }

    /** С чего начинать, если ничего не выбрано: первая песня общего списка. */
    private int firstIndex() {
        int[] q = effectiveQueue();
        return q.length > 0 ? q[0] : 0;
    }

    public void pause() {
        try {
            if (mp != null && prepared && mp.isPlaying()) {
                mp.pause();
                rememberPos();
                afterChange();
            }
        } catch (Throwable t) { }
    }

    /**
     * Номер следующей (dir = 1) или предыдущей (dir = -1) песни в текущей очереди. Если текущей песни в очереди нет
     * (её только что перенесли в плейлист), берём ближайшую по порядку списка.
     */
    private int stepIdx(int dir) {
        boolean byIdx = queue == null || queue.length == 0;
        int[] q = effectiveQueue();
        if (q.length == 0) return -1;
        int p = queuePos(q);
        if (p >= 0) return q[(p + dir + q.length) % q.length];
        if (byIdx) {
            if (dir > 0) {
                for (int k = 0; k < q.length; k++) if (q[k] > cur) return q[k];
                return q[0];
            }
            for (int k = q.length - 1; k >= 0; k--) if (q[k] < cur) return q[k];
            return q[q.length - 1];
        }
        return dir > 0 ? q[0] : q[q.length - 1];
    }

    public void next() {
        int i = stepIdx(1);
        if (i >= 0) playAt(i);
    }

    public void prev() {
        int i = stepIdx(-1);
        if (i >= 0) playAt(i);
    }

    private int queuePos(int[] q) {
        for (int i = 0; i < q.length; i++) if (q[i] == cur) return i;
        return -1;
    }

    public void seekBy(int ms) {
        try {
            if (mp != null && prepared) {
                int pos = mp.getCurrentPosition() + ms;
                int dur = mp.getDuration();
                if (pos < 0) pos = 0;
                if (dur > 0 && pos > dur) pos = dur;
                mp.seekTo(pos);
                afterChange();
            }
        } catch (Throwable t) { }
    }

    public boolean isPlaying() {
        try { return mp != null && prepared && mp.isPlaying(); }
        catch (Throwable t) { return false; }
    }

    /** Всё обновить после смены состояния: сессия, уведомление, экран. */
    private void afterChange() {
        updateSession();
        updateNotif();
        refreshUiHead();
    }

    private void refreshUiHead() {
        final PlayerView v = boundView;
        if (v == null || h == null) return;
        h.post(new Runnable() {
            @Override public void run() { v.refreshHead(); }
        });
    }

    private void refreshUiAll() {
        final PlayerView v = boundView;
        if (v == null || h == null) return;
        h.post(new Runnable() {
            @Override public void run() { v.refresh(); }
        });
    }

    private void setViewMode(final int mode) {
        final PlayerView v = boundView;
        if (v == null || h == null) return;
        h.post(new Runnable() {
            @Override public void run() { v.setMode(mode); }
        });
    }

    // ---------------------------------------------------------- медиа-сессия
    // Своя сессия — поэтому гарнитура, системные медиаклавиши и «Пульт»
    // управляют этим плеером, а не чем-то ещё.

    private void ensureSession() {
        if (session != null || appCtx == null) return;
        try {
            session = new MediaSession(appCtx, "AudioPlayer");
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { toggle(); }
                @Override public void onPause() { pause(); }
                @Override public void onSkipToNext() { next(); }
                @Override public void onSkipToPrevious() { prev(); }
                @Override public void onSeekTo(long pos) {
                    try {
                        if (mp != null && prepared) {
                            mp.seekTo((int) pos);
                            afterChange();
                        }
                    } catch (Throwable t) { }
                }
                @Override public boolean onMediaButtonEvent(Intent intent) {
                    // Перемотка медиаклавишами (например, из «Пульта»): FF/RW -> +-10 с.
                    KeyEvent e = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (e != null && e.getAction() == KeyEvent.ACTION_DOWN) {
                        int kc = e.getKeyCode();
                        if (kc == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) { seekBy(10000); return true; }
                        if (kc == KeyEvent.KEYCODE_MEDIA_REWIND) { seekBy(-10000); return true; }
                    }
                    return super.onMediaButtonEvent(intent);
                }
            });
            session.setActive(true);
        } catch (Throwable t) {
            session = null;
        }
    }

    private void updateSession() {
        if (session == null) return;
        try {
            int state = isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
            long pos = 0;
            try { if (mp != null && prepared) pos = mp.getCurrentPosition(); } catch (Throwable t) { }
            PlaybackState ps = new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                            | PlaybackState.ACTION_SEEK_TO)
                    .setState(state, pos, 1f, SystemClock.elapsedRealtime())
                    .build();
            session.setPlaybackState(ps);
            Track t = (cur >= 0 && cur < tracks.size()) ? tracks.get(cur) : null;
            MediaMetadata md = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, t == null ? "Аудиоплеер" : t.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, t == null ? "" : t.artist)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, t == null ? 0 : t.dur)
                    .build();
            session.setMetadata(md);
        } catch (Throwable t) { }
    }

    // ---------------------------------------------------------- уведомление

    private void ensureReceiver() {
        if (cmdReceiver != null || appCtx == null) return;
        cmdReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                String a = intent.getAction();
                if (A_TOGGLE.equals(a)) toggle();
                else if (A_NEXT.equals(a)) next();
                else if (A_PREV.equals(a)) prev();
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(A_TOGGLE);
        f.addAction(A_NEXT);
        f.addAction(A_PREV);
        try { appCtx.registerReceiver(cmdReceiver, f); }
        catch (Throwable t) { cmdReceiver = null; }
    }

    private PendingIntent pi(String action) {
        Intent i = new Intent(action);
        i.setPackage(appCtx.getPackageName());
        return PendingIntent.getBroadcast(appCtx, action.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void updateNotif() {
        if (appCtx == null) return;
        NotificationManager nm =
                (NotificationManager) appCtx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            if (!notifAllowed() || (cur < 0 && !isPlaying())) {
                nm.cancel(NOTIF_ID);
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL, "Аудиоплеер", NotificationManager.IMPORTANCE_LOW));
            }
            Track t = (cur >= 0 && cur < tracks.size()) ? tracks.get(cur) : null;
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(appCtx, CHANNEL)
                    : new Notification.Builder(appCtx);
            b.setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(t == null ? "Аудиоплеер" : t.title)
                    .setContentText(t == null ? "" : t.artist)
                    .setOngoing(isPlaying())
                    .addAction(android.R.drawable.ic_media_previous, "Назад", pi(A_PREV))
                    .addAction(isPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                            "Пауза/Играть", pi(A_TOGGLE))
                    .addAction(android.R.drawable.ic_media_next, "Вперёд", pi(A_NEXT));
            if (session != null) {
                b.setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
            }
            nm.notify(NOTIF_ID, b.build());
        } catch (Throwable t) { }
    }

    private void cancelNotif() {
        if (appCtx == null) return;
        try {
            NotificationManager nm =
                    (NotificationManager) appCtx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_ID);
        } catch (Throwable t) { }
    }

    // ---------------------------------------------------------- превью песен
    //
    // Превью лежит рядом с песней: то же имя, расширение .jpg (его кладёт модуль «YouTube аудио»). Картинки читаются
    // в фоне, уменьшенными, и кэшируются; песни без превью показываются плиткой с названием.

    private final LruCache<String, Bitmap> coverCache = new LruCache<String, Bitmap>(48);
    private final HashSet<String> noCover = new HashSet<String>();
    private java.util.concurrent.ExecutorService coverPool;

    private File coverFile(String path) {
        if (path == null) return null;
        int dot = path.lastIndexOf('.');
        if (dot < 0) return null;
        File f = new File(path.substring(0, dot) + ".jpg");
        return f.isFile() ? f : null;
    }

    private static Bitmap decodeCover(File f, int targetW) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int s = 1;
        while (o.outWidth / (s * 2) >= targetW) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        o2.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
    }

    /** Выставляет превью в картинку; пока оно грузится (или его нет), поверх лежит placeholder с названием. */
    void loadCover(final String path, final ImageView img, final View placeholder, final int targetW) {
        if (path == null || h == null) return;
        img.setTag(path);
        Bitmap cached = coverCache.get(path);
        if (cached != null) {
            img.setImageBitmap(cached);
            placeholder.setVisibility(View.GONE);
            return;
        }
        if (noCover.contains(path)) return;
        if (coverPool == null) coverPool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            coverPool.execute(new Runnable() {
                @Override public void run() {
                    Bitmap bm = null;
                    try {
                        File f = coverFile(path);
                        if (f != null) bm = decodeCover(f, targetW);
                    } catch (Throwable t) { }
                    final Bitmap fb = bm;
                    h.post(new Runnable() {
                        @Override public void run() {
                            if (fb == null) { noCover.add(path); return; }
                            coverCache.put(path, fb);
                            if (path.equals(img.getTag())) {
                                img.setImageBitmap(fb);
                                placeholder.setVisibility(View.GONE);
                            }
                        }
                    });
                }
            });
        } catch (Throwable t) { }
    }

    // ---------------------------------------------------------- удаление песен

    public void askDelete(final Context actCtx, final int i) {
        if (i < 0 || i >= tracks.size()) return;
        final Track t = tracks.get(i);
        new AlertDialog.Builder(actCtx)
                .setTitle("Удалить песню?")
                .setMessage(t.title + "\nФайл будет удалён с устройства.")
                .setPositiveButton("Удалить", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { deleteTrack(i); }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void deleteTrack(int i) {
        if (i < 0 || i >= tracks.size() || appCtx == null) return;
        Track t = tracks.get(i);
        boolean wasCur = (i == cur);
        int[] q0 = queue;
        int qp = (q0 != null && wasCur) ? queuePos(q0) : -1;
        try {
            int n = appCtx.getContentResolver().delete(t.uri, null, null);
            if (n <= 0) throw new Exception("система отказала");
        } catch (Throwable th) {
            toast("Не удалось удалить");
            tone(ToneGenerator.TONE_PROP_NACK);
            return;
        }
        tracks.remove(i);
        plPurge(t);
        if (q0 != null) {                            // очередь плейлиста: убираем трек и сдвигаем номера
            int[] nq = new int[q0.length];
            int c = 0;
            for (int k = 0; k < q0.length; k++) {
                if (q0[k] == i) continue;
                nq[c++] = q0[k] > i ? q0[k] - 1 : q0[k];
            }
            if (c > 0) {
                int[] cut = new int[c];
                for (int k = 0; k < c; k++) cut[k] = nq[k];
                queue = cut;
            } else {
                queue = null;
            }
        }
        if (wasCur) {
            try {
                if (mp != null) { mp.reset(); prepared = false; }
            } catch (Throwable th) { }
            cur = -1;
            int[] q = queue;
            if (q != null) {
                playAt(q[Math.max(0, qp) % q.length]);
            } else {
                int[] g = generalArray();             // после удаления номера сдвинулись: следующая песня теперь на месте i
                if (g.length > 0) {
                    int pick = g[g.length - 1];
                    for (int k = 0; k < g.length; k++) {
                        if (g[k] >= i) { pick = g[k]; break; }
                    }
                    playAt(pick);
                } else if (!tracks.isEmpty()) {
                    playAt(Math.min(i, tracks.size() - 1));
                } else {
                    afterChange();
                }
            }
        } else {
            if (i < cur) cur--;
        }
        toast("Удалено");
        refreshUiAll();
    }

    // ---------------------------------------------------------- «Поделиться» (PWA-стиль)

    @Override public List<String> shareMimeTypes() {
        List<String> r = new ArrayList<String>();
        r.add("audio/*");
        return r;
    }

    /**
     * Входящий файл из меню «Поделиться»: добавляем первым в список и сразу играем.
     * Работает, даже если модуль ещё не был проинициализирован службой.
     */
    @Override public boolean onShare(Context ctx, Intent intent) {
        try {
            if (appCtx == null) {
                appCtx = ctx.getApplicationContext();
                h = new Handler(Looper.getMainLooper());
            }
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream == null || appCtx == null) return false;
            String title = null;
            Cursor c = null;
            try {
                c = ctx.getContentResolver().query(stream,
                        new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null);
                if (c != null && c.moveToFirst()) title = c.getString(0);
            } catch (Throwable t) { }
            finally { if (c != null) try { c.close(); } catch (Throwable t) { } }
            if (title == null || title.length() == 0) title = "Из «Поделиться»";
            Track tr = new Track();
            tr.id = -System.currentTimeMillis();
            tr.title = title;
            tr.artist = "Поделиться";
            tr.dur = 0;
            tr.uri = stream;
            tracks.add(0, tr);
            queue = null;
            listSrc = 0;
            ensureSession();
            ensureReceiver();
            playAt(0);
            toast("Играет: " + title);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------- место в треке, скорость, наушники

    private boolean resumeOn() {
        SharedPreferences p = prefs();
        return p == null || p.getBoolean("resume", true);
    }

    private LinkedHashMap<String, Integer> posLoad() {
        LinkedHashMap<String, Integer> m = new LinkedHashMap<String, Integer>();
        SharedPreferences p = prefs();
        if (p == null) return m;
        for (String line : p.getString("pos_map", "").split("\n")) {
            int k = line.lastIndexOf('\t');
            if (k <= 0) continue;
            try { m.put(line.substring(0, k), Integer.valueOf(line.substring(k + 1))); } catch (Throwable t) { }
        }
        return m;
    }

    private void posStore(LinkedHashMap<String, Integer> m) {
        SharedPreferences p = prefs();
        if (p == null) return;
        while (m.size() > 300) m.remove(m.keySet().iterator().next());
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        p.edit().putString("pos_map", sb.toString()).apply();
    }

    /** Запомнить, где остановились в текущем треке; если почти дослушали или только начали, место забывается. */
    private void rememberPos() {
        if (!resumeOn() || mp == null || !prepared || cur < 0 || cur >= tracks.size()) return;
        Uri u = tracks.get(cur).uri;
        if (u == null) return;
        try {
            int pos = mp.getCurrentPosition();
            int dur = mp.getDuration();
            LinkedHashMap<String, Integer> m = posLoad();
            String key = u.toString();
            m.remove(key);
            if (dur > 0 && pos > 5000 && pos < dur - 8000) m.put(key, Integer.valueOf(pos));
            posStore(m);
        } catch (Throwable t) { }
    }

    private int savedPos(Track t) {
        if (!resumeOn() || t == null || t.uri == null) return 0;
        Integer v = posLoad().get(t.uri.toString());
        return v == null ? 0 : v.intValue();
    }

    private float speed() {
        SharedPreferences p = prefs();
        return p == null ? 1f : p.getFloat("speed", 1f);
    }

    private void applySpeed() {
        try {
            if (mp != null && prepared) {
                boolean was = mp.isPlaying();
                PlaybackParams pp = mp.getPlaybackParams();
                pp.setSpeed(speed());
                mp.setPlaybackParams(pp);
                if (!was) mp.pause();               // смена скорости на паузе не должна запускать воспроизведение
            }
        } catch (Throwable t) { }
    }

    /** Скорость меняется шагом 0,1 от 0,5 до 2,5, запоминается и озвучивается числом. */
    private void setSpeed(float v) {
        v = Math.max(0.5f, Math.min(2.5f, Math.round(v * 10f) / 10f));
        SharedPreferences p = prefs();
        if (p != null) p.edit().putFloat("speed", v).apply();
        applySpeed();
        speak(String.format(Locale.getDefault(), "%.1f", v));
    }

    private void toggleStopAfter() {
        stopAfter = !stopAfter;
        tone(stopAfter ? ToneGenerator.TONE_PROP_ACK : ToneGenerator.TONE_PROP_BEEP);
    }

    private BroadcastReceiver noisyRx;
    private AudioDeviceCallback devCb;
    private long devCbSince;

    /** Пауза при отключении наушников и пуск при подключении (оба пункта включаются в настройках). */
    private void ensureHeadset() {
        Context c = appCtx;
        if (c == null) return;
        if (noisyRx == null) {
            noisyRx = new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) {
                    SharedPreferences p = prefs();
                    if (p == null || p.getBoolean("unplug_pause", true)) pause();
                }
            };
            try { c.registerReceiver(noisyRx, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)); }
            catch (Throwable t) { noisyRx = null; }
        }
        if (devCb == null) {
            try {
                AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                devCb = new AudioDeviceCallback() {
                    @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                        if (SystemClock.uptimeMillis() - devCbSince < 1500) return;   // первый вызов сразу после подписки: уже подключённые
                        SharedPreferences p = prefs();
                        if (p == null || !p.getBoolean("plug_play", false)) return;
                        boolean headset = false;
                        for (AudioDeviceInfo d : added) {
                            int t = d.getType();
                            if (d.isSink() && (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                                    || t == AudioDeviceInfo.TYPE_USB_HEADSET || t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)) {
                                headset = true;
                            }
                        }
                        try {
                            if (headset && mp != null && prepared && !mp.isPlaying()) toggle();
                        } catch (Throwable t) { }
                    }
                };
                devCbSince = SystemClock.uptimeMillis();
                if (am != null) am.registerAudioDeviceCallback(devCb, h);
            } catch (Throwable t) { devCb = null; }
        }
    }

    private void releaseHeadset() {
        Context c = appCtx;
        if (c == null) return;
        try { if (noisyRx != null) c.unregisterReceiver(noisyRx); } catch (Throwable t) { }
        noisyRx = null;
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && devCb != null) am.unregisterAudioDeviceCallback(devCb);
        } catch (Throwable t) { }
        devCb = null;
    }

    // ---------------------------------------------------------- плейлисты: «Послушать позже» и «Избранное»

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /** Записи плейлиста: {uri или "-", название, исполнитель}. */
    private List<String[]> plLoad(String key) {
        List<String[]> r = new ArrayList<String[]>();
        SharedPreferences p = prefs();
        if (p == null) return r;
        for (String line : p.getString(key, "").split("\n")) {
            if (line.length() == 0) continue;
            String[] f = line.split("\t", -1);
            if (f.length >= 3) r.add(new String[]{f[0], f[1], f[2]});
        }
        return r;
    }

    private void plSave(String key, List<String[]> l) {
        SharedPreferences p = prefs();
        if (p == null) return;
        StringBuilder sb = new StringBuilder();
        for (String[] e : l) {
            sb.append(clean(e[0])).append('\t').append(clean(e[1])).append('\t').append(clean(e[2])).append('\n');
        }
        p.edit().putString(key, sb.toString()).apply();
    }

    private static boolean sameEntry(String[] a, String[] b) {
        if (!a[0].equals("-") && a[0].equals(b[0])) return true;
        return a[1].equalsIgnoreCase(b[1]) && a[2].equalsIgnoreCase(b[2]);
    }

    /** Убрать запись из плейлиста: трек, если он больше ни в одном плейлисте, снова виден в общем списке. */
    void plRemove(String key, int pos) {
        List<String[]> l = plLoad(key);
        if (pos >= 0 && pos < l.size()) {
            l.remove(pos);
            plSave(key, l);
        }
    }

    int plCount(String key) {
        return plLoad(key).size();
    }

    /** Громкость звуковых сигналов 10-100 (настройка «Громкость сигналов»); по умолчанию максимум. */
    private int toneVolume() {
        SharedPreferences p = prefs();
        int v = p == null ? 100 : p.getInt("tone_vol", 100);
        return Math.max(10, Math.min(100, v));
    }

    /** Звуковой отклик, чтобы не смотреть на экран. */
    private void tone(int type) {
        try {
            final ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, toneVolume());
            tg.startTone(type, 250);
            Handler hh = h;
            if (hh != null) {
                hh.postDelayed(new Runnable() {
                    @Override public void run() { try { tg.release(); } catch (Throwable t) { } }
                }, 700);
            }
        } catch (Throwable t) { }
    }

    /** Что сейчас слушаем: свой трек, а если свой не играет, то трек чужого плеера (по названию и исполнителю). */
    private String[] currentEntry() {
        if (isPlaying()) return ownEntry();
        if (remoteAny()) {
            MediaController ext = externalTarget();
            if (ext != null) {
                PlaybackState ps = ext.getPlaybackState();
                MediaMetadata md = ext.getMetadata();
                if (md != null && ps != null && ps.getState() == PlaybackState.STATE_PLAYING) {
                    String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                    String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                    if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                    if (title != null && title.length() > 0) {
                        return new String[]{"-", title, artist == null ? "" : artist};
                    }
                }
            }
        }
        if (mp != null && prepared) return ownEntry();
        return null;
    }

    private String[] ownEntry() {
        if (cur < 0 || cur >= tracks.size()) return null;
        Track t = tracks.get(cur);
        return new String[]{t.uri == null ? "-" : t.uri.toString(), t.title, t.artist};
    }

    /**
     * Кнопка геймпада: текущий трек переносится в плейлист (из общего списка он пропадает). Отклик: «позже» и
     * «избранное» звучат по-разному, повтор и «нечего добавлять» тоже.
     */
    private void addCurrentTo(String key) {
        String[] e = currentEntry();
        if (e == null) { tone(ToneGenerator.TONE_PROP_NACK); return; }
        List<String[]> l = plLoad(key);
        for (String[] x : l) {
            if (sameEntry(x, e)) { tone(ToneGenerator.TONE_PROP_BEEP); return; }
        }
        l.add(e);
        plSave(key, l);
        tone(key.equals(PL_FAV) ? ToneGenerator.TONE_PROP_ACK : ToneGenerator.TONE_PROP_PROMPT);
        refreshUiAll();
    }

    /** Номер трека в библиотеке для записи плейлиста (по адресу, затем по названию и исполнителю) или -1. */
    private int findTrack(String[] e) {
        if (!e[0].equals("-")) {
            for (int i = 0; i < tracks.size(); i++) {
                Uri u = tracks.get(i).uri;
                if (u != null && u.toString().equals(e[0])) return i;
            }
        }
        for (int i = 0; i < tracks.size(); i++) {
            Track t = tracks.get(i);
            if (t.title.equalsIgnoreCase(e[1]) && (e[2].length() == 0 || t.artist.equalsIgnoreCase(e[2]))) return i;
        }
        return -1;
    }

    /** Играть плейлист с выбранной записи; дальше «следующий» и «предыдущий» идут по плейлисту. */
    void playFromList(String key, int pos) {
        List<String[]> pl = plLoad(key);
        List<Integer> q = new ArrayList<Integer>();
        int start = -1;
        for (int i = 0; i < pl.size(); i++) {
            int idx = findTrack(pl.get(i));
            if (idx < 0) continue;
            if (i == pos) start = q.size();
            q.add(Integer.valueOf(idx));
        }
        if (start < 0) {
            toast("Этого трека нет на устройстве");
            return;
        }
        int[] arr = new int[q.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = q.get(i).intValue();
        queue = arr;
        listSrc = key.equals(PL_FAV) ? 2 : 1;
        playAt(arr[start]);
    }

    /** Играть общий список с выбранной песни. */
    void playFromGeneral(int idx) {
        queue = null;
        listSrc = 0;
        playAt(idx);
    }

    private int firstPlayable(String key) {
        List<String[]> pl = plLoad(key);
        for (int i = 0; i < pl.size(); i++) {
            if (findTrack(pl.get(i)) >= 0) return i;
        }
        return -1;
    }

    private static String listName(int s) {
        return s == 1 ? "Послушать позже" : (s == 2 ? "Избранное" : "Все песни");
    }

    /** Кнопка геймпада: перейти на следующий непустой список (все, позже, избранное), начать с его первой песни и назвать его. */
    private void switchList() {
        for (int step = 1; step <= 2; step++) {
            int s = (listSrc + step) % 3;
            if (s == 0) {
                int[] g = generalArray();
                if (g.length == 0) continue;
                playFromGeneral(g[0]);
            } else {
                String key = s == 1 ? PL_LATER : PL_FAV;
                int pos = firstPlayable(key);
                if (pos < 0) continue;
                playFromList(key, pos);
            }
            setViewMode(listSrc);
            speak(listName(listSrc));
            return;
        }
        tone(ToneGenerator.TONE_PROP_NACK);
    }

    /** Удалённый с устройства трек убираем из обоих плейлистов (по адресу). */
    private void plPurge(Track t) {
        if (t == null || t.uri == null) return;
        String u = t.uri.toString();
        String[] keys = {PL_LATER, PL_FAV};
        for (int k = 0; k < keys.length; k++) {
            List<String[]> l = plLoad(keys[k]);
            boolean changed = false;
            for (int i = l.size() - 1; i >= 0; i--) {
                if (l.get(i)[0].equals(u)) {
                    l.remove(i);
                    changed = true;
                }
            }
            if (changed) plSave(keys[k], l);
        }
    }

    // ---------------------------------------------------------- удалить текущий, озвучить, заблокировать касания

    private long delArmedAt;

    /** Удаление с геймпада: первое нажатие даёт сигнал «подтверди», второе в течение 4 секунд удаляет файл. */
    private void deleteCurrentConfirm() {
        if (cur < 0 || cur >= tracks.size() || mp == null || !prepared) {
            tone(ToneGenerator.TONE_PROP_NACK);
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (delArmedAt != 0 && now - delArmedAt <= 4000) {
            delArmedAt = 0;
            deleteTrack(cur);
            return;
        }
        delArmedAt = now;
        tone(ToneGenerator.TONE_CDMA_ABBR_ALERT);
    }

    private TextToSpeech tts;
    private boolean ttsReady;
    private String ttsPending;
    private final AudioManager.OnAudioFocusChangeListener focusListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override public void onAudioFocusChange(int change) { }
    };

    /** Название трека и после него только число: сколько треков осталось до конца текущего списка. */
    private void sayCurrent() {
        String[] e = currentEntry();
        if (e == null || e[1] == null || e[1].length() == 0) {
            tone(ToneGenerator.TONE_PROP_NACK);
            return;
        }
        String text = e[1];
        boolean own = !e[0].equals("-") && cur >= 0 && cur < tracks.size();
        if (own) {
            boolean byIdx = queue == null || queue.length == 0;
            int[] q = effectiveQueue();
            int p = queuePos(q);
            int left;
            if (p >= 0) {
                left = q.length - 1 - p;
            } else if (byIdx) {
                left = 0;
                for (int k = 0; k < q.length; k++) if (q[k] > cur) left++;
            } else {
                left = q.length;
            }
            text = text + ". " + Math.max(0, left);
        }
        speak(text);
    }

    /** Доля прослушанного текущего трека 0..1: своего, а если свой не играет, то чужого плеера; -1, если узнать нельзя. */
    private float currentFraction() {
        try {
            if (!isPlaying() && remoteAny()) {
                MediaController ext = externalTarget();
                if (ext != null) {
                    MediaMetadata md = ext.getMetadata();
                    PlaybackState ps = ext.getPlaybackState();
                    long dur = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
                    if (dur > 0 && ps != null) {
                        long pos = ps.getPosition();
                        if (ps.getState() == PlaybackState.STATE_PLAYING) {
                            pos += (long) ((SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime()) * ps.getPlaybackSpeed());
                        }
                        return Math.max(0f, Math.min(1f, pos / (float) dur));
                    }
                    return -1f;
                }
            }
            if (mp != null && prepared) {
                int dur = mp.getDuration();
                if (dur > 0) return Math.max(0f, Math.min(1f, mp.getCurrentPosition() / (float) dur));
            }
        } catch (Throwable t) { }
        return -1f;
    }

    private static String ruPercent(int n) {
        int m100 = n % 100;
        int m10 = n % 10;
        if (m100 >= 11 && m100 <= 14) return "процентов";
        if (m10 == 1) return "процент";
        if (m10 >= 2 && m10 <= 4) return "процента";
        return "процентов";
    }

    /** Нажатие на стик-циферблат: сказать, сколько процентов трека прослушано. */
    private void sayPercent() {
        float f = currentFraction();
        if (f < 0f) {
            tone(ToneGenerator.TONE_PROP_NACK);
            return;
        }
        int pct = Math.max(0, Math.min(100, Math.round(f * 100f)));
        speak(pct + " " + ruPercent(pct));
    }

    private void speak(String text) {
        Context c = appCtx;
        if (c == null) return;
        ttsPending = text;
        if (tts == null) {
            tts = new TextToSpeech(c, new TextToSpeech.OnInitListener() {
                @Override public void onInit(int status) {
                    ttsReady = status == TextToSpeech.SUCCESS;
                    if (ttsReady && tts != null) {
                        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                            @Override public void onStart(String id) { }
                            @Override public void onDone(String id) { duckEnd(); }
                            @Override public void onError(String id) { duckEnd(); }
                        });
                    }
                    flushSpeech();
                }
            });
            return;
        }
        if (ttsReady) flushSpeech();
    }

    private void flushSpeech() {
        String text = ttsPending;
        ttsPending = null;
        if (text == null || tts == null || !ttsReady) {
            if (text != null) tone(ToneGenerator.TONE_PROP_NACK);
            return;
        }
        boolean cyr = false;
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeBlock.of(text.charAt(i)) == Character.UnicodeBlock.CYRILLIC) {
                cyr = true;
                break;
            }
        }
        try { tts.setLanguage(cyr ? new Locale("ru", "RU") : Locale.getDefault()); } catch (Throwable t) { }
        duckStart();
        try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "say");
        } catch (Throwable t) {
            duckEnd();
        }
    }

    /** На время речи приглушаем музыку: свою и чужую. */
    private void duckStart() {
        try {
            AudioManager am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }
            if (mp != null) mp.setVolume(0.25f, 0.25f);
        } catch (Throwable t) { }
    }

    private void duckEnd() {
        Handler hh = h;
        Runnable r = new Runnable() {
            @Override public void run() {
                try {
                    AudioManager am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) am.abandonAudioFocus(focusListener);
                    if (mp != null) mp.setVolume(1f, 1f);
                } catch (Throwable t) { }
            }
        };
        if (hh != null) hh.post(r); else r.run();
    }

    private View touchShield;
    private ViewGroup shieldParent;
    private WindowManager shieldWm;
    private BroadcastReceiver screenOffRx;

    /**
     * Блокировка касаний до выключения экрана. Поверх всего ставится прозрачный слой, который съедает касания
     * (кнопки геймпада и стики идут как раньше). Снимается сама, когда экран гаснет, поэтому застрять нельзя;
     * повторное нажатие той же кнопки тоже снимает.
     */
    private void toggleTouchLock() {
        if (touchShield != null) {
            unlockTouch();
            tone(ToneGenerator.TONE_PROP_ACK);
            return;
        }
        Context c = appCtx;
        if (c == null) return;
        try {
            View shield = new View(c);
            shield.setClickable(true);
            shield.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent ev) { return true; }
            });
            if (svc != null) {
                WindowManager wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                wm.addView(shield, lp);
                shieldWm = wm;
            } else {
                PlayerView v = boundView;
                if (v == null || !(v.getRootView() instanceof ViewGroup)) {
                    tone(ToneGenerator.TONE_PROP_NACK);
                    return;
                }
                ViewGroup root = (ViewGroup) v.getRootView();
                root.addView(shield, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
                shieldParent = root;
            }
            touchShield = shield;
            screenOffRx = new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) { unlockTouch(); }
            };
            c.registerReceiver(screenOffRx, new IntentFilter(Intent.ACTION_SCREEN_OFF));
            tone(ToneGenerator.TONE_PROP_PROMPT);
        } catch (Throwable t) {
            unlockTouch();
            tone(ToneGenerator.TONE_PROP_NACK);
        }
    }

    private void unlockTouch() {
        View shield = touchShield;
        touchShield = null;
        try {
            if (shield != null) {
                if (shieldWm != null) shieldWm.removeView(shield);
                else if (shieldParent != null) shieldParent.removeView(shield);
            }
        } catch (Throwable t) { }
        shieldWm = null;
        shieldParent = null;
        try {
            if (screenOffRx != null && appCtx != null) appCtx.unregisterReceiver(screenOffRx);
        } catch (Throwable t) { }
        screenOffRx = null;
    }

    // ---------------------------------------------------------- фокус для геймпада (оверлей)
    //
    // На Android 11 оси джойстика приходят только окну в фокусе. Прозрачное окно 1x1 из службы специальных
    // возможностей без FLAG_NOT_FOCUSABLE получает фокус, а касания пропускает (FLAG_NOT_TOUCHABLE): стики, курки
    // и кнопки идут в PadMapper и циферблат, пока окно плеера закрыто. Цена: приложение под окном теряет фокус
    // (клавиатура, «Назад»), поэтому окно есть только при включённой настройке pad_focus, подключённом геймпаде,
    // включённом и разблокированном экране и закрытом окне плеера. Кнопкой геймпада «Фокус» настройка выключается.

    private View focusView;
    private WindowManager focusWm;
    private BroadcastReceiver focusScreenRx;
    private InputManager.InputDeviceListener focusDevCb;
    private final Runnable focusUpdate = new Runnable() {
        @Override public void run() { applyFocusCatcher(); }
    };

    private boolean padFocusOn() {
        SharedPreferences p = prefs();
        return p != null && p.getBoolean("pad_focus", false);
    }

    private boolean padPresent() {
        try {
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice d = InputDevice.getDevice(id);
                if (d == null || d.isVirtual()) continue;
                int s = d.getSources();
                if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                        || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return true;
            }
        } catch (Throwable t) { }
        return false;
    }

    private boolean screenReady() {
        try {
            PowerManager pm = (PowerManager) appCtx.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) return false;
            KeyguardManager kg = (KeyguardManager) appCtx.getSystemService(Context.KEYGUARD_SERVICE);
            return kg == null || !kg.isKeyguardLocked();
        } catch (Throwable t) {
            return true;
        }
    }

    /** Пересчитать, нужно ли окно фокуса; можно вызывать откуда угодно, работа идёт в главном потоке. */
    void updateFocusCatcher() {
        Handler hh = h;
        if (hh == null) return;
        hh.removeCallbacks(focusUpdate);
        hh.post(focusUpdate);
    }

    /** Окно плеера на экране (видно, не в фоне): оно само принимает геймпад, окно фокуса не нужно. */
    private boolean playerOnScreen() {
        PlayerView v = boundView;
        return v != null && v.isAttachedToWindow() && v.getWindowVisibility() == View.VISIBLE;
    }

    private String focusWhy = "";
    private final List<String> focusLog = new ArrayList<String>();
    private int fcMotion;
    private int fcKeys;
    private long focusReaddAt;
    private int focusNoFocusTicks;
    private final Runnable focusTick = new Runnable() {
        @Override public void run() { focusWatchdog(); }
    };

    /** Короткий журнал окна фокуса: его видно на экране плеера, чтобы понять, почему окно не работает. */
    void focusLog(String msg) {
        synchronized (focusLog) {
            String t = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date());
            focusLog.add(t + " " + msg);
            while (focusLog.size() > 10) focusLog.remove(0);
        }
    }

    String focusReport() {
        if (!padFocusOn()) return "🎯 Окно фокуса: настройка выключена";
        StringBuilder sb = new StringBuilder("🎯 Окно фокуса: ");
        View v = focusView;
        if (v == null) sb.append("нет (").append(focusWhy.length() == 0 ? "ещё не считалось" : focusWhy).append(")");
        else sb.append("есть, в фокусе: ").append(v.hasWindowFocus() ? "да" : "НЕТ");
        sb.append("\nГеймпад: ").append(padPresent() ? "найден" : "не найден")
                .append(", событий в окно: оси ").append(fcMotion).append(", кнопки ").append(fcKeys);
        synchronized (focusLog) {
            for (String l : focusLog) sb.append("\n").append(l);
        }
        return sb.toString();
    }

    private void applyFocusCatcher() {
        String why = null;
        if (svc == null || appCtx == null) why = "нет службы специальных возможностей";
        else if (!padFocusOn()) why = "настройка выключена";
        else if (playerOnScreen()) why = "открыто окно плеера, оно принимает геймпад само";
        else if (!padPresent()) why = "геймпад не найден";
        else if (!screenReady()) why = "экран выключен или заблокирован";
        boolean want = why == null;
        if (!(why == null ? "" : why).equals(focusWhy)) {
            focusWhy = why == null ? "" : why;
            if (!want) focusLog("ждёт: " + why);
        }
        if (want && focusView == null) addFocusCatcher();
        else if (!want && focusView != null) {
            focusLog("окно снято: " + why);
            removeFocusCatcher();
        }
        Handler hh = h;
        if (hh != null) {
            hh.removeCallbacks(focusTick);
            if (padFocusOn() && svc != null) hh.postDelayed(focusTick, 4000);   // страховка от пропущенных событий
        }
    }

    /** Раз в 4 с: пересчёт условий; если окно есть, но фокуса нет 8 с, окно пересоздаётся (не чаще раза в 10 с). */
    private void focusWatchdog() {
        applyFocusCatcher();
        View v = focusView;
        if (v == null || v.hasWindowFocus()) {
            focusNoFocusTicks = 0;
            return;
        }
        focusNoFocusTicks++;
        long now = SystemClock.uptimeMillis();
        if (focusNoFocusTicks >= 2 && now - focusReaddAt > 10000) {
            focusReaddAt = now;
            focusNoFocusTicks = 0;
            focusLog("фокуса нет 8 с: окно пересоздаётся");
            removeFocusCatcher();
            addFocusCatcher();
        }
    }

    private void addFocusCatcher() {
        try {
            WindowManager wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
            FocusCatcher v = new FocusCatcher(svc);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,   // клавиатура остаётся у приложения под окном
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            wm.addView(v, lp);
            focusWm = wm;
            focusView = v;
            v.requestFocus();
            focusLog("окно добавлено");
        } catch (Throwable t) {
            focusView = null;
            focusWm = null;
            focusLog("не удалось добавить окно: " + t);
        }
    }

    private void removeFocusCatcher() {
        View v = focusView;
        focusView = null;
        try {
            if (v != null && focusWm != null) focusWm.removeView(v);
        } catch (Throwable t) { }
        focusWm = null;
        if (v != null) padReleaseAll();
    }

    private void toggleFocusPref() {
        SharedPreferences p = prefs();
        if (p == null) return;
        boolean now = !p.getBoolean("pad_focus", false);
        p.edit().putBoolean("pad_focus", now).apply();
        tone(now ? ToneGenerator.TONE_PROP_ACK : ToneGenerator.TONE_PROP_BEEP);
        updateFocusCatcher();
    }

    /** Экран включился или погас, геймпад подключили или отключили: окно фокуса пересчитывается. */
    private void registerFocusWatch() {
        Context c = appCtx;
        if (c == null) return;
        if (focusScreenRx == null) {
            focusScreenRx = new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) { updateFocusCatcher(); }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_USER_PRESENT);
            try { c.registerReceiver(focusScreenRx, f); }
            catch (Throwable t) { focusScreenRx = null; }
        }
        if (focusDevCb == null) {
            try {
                InputManager im = (InputManager) c.getSystemService(Context.INPUT_SERVICE);
                if (im != null) {
                    focusDevCb = new InputManager.InputDeviceListener() {
                        @Override public void onInputDeviceAdded(int id) { updateFocusCatcher(); }
                        @Override public void onInputDeviceRemoved(int id) { updateFocusCatcher(); }
                        @Override public void onInputDeviceChanged(int id) { }
                    };
                    im.registerInputDeviceListener(focusDevCb, h);
                }
            } catch (Throwable t) { focusDevCb = null; }
        }
    }

    private void unregisterFocusWatch() {
        Context c = appCtx;
        if (c == null) return;
        try { if (focusScreenRx != null) c.unregisterReceiver(focusScreenRx); } catch (Throwable t) { }
        focusScreenRx = null;
        try {
            InputManager im = (InputManager) c.getSystemService(Context.INPUT_SERVICE);
            if (im != null && focusDevCb != null) im.unregisterInputDeviceListener(focusDevCb);
        } catch (Throwable t) { }
        focusDevCb = null;
        if (h != null) {
            h.removeCallbacks(focusUpdate);
            h.removeCallbacks(focusTick);
        }
    }

    /** Невидимое окно, которое держит фокус ради стиков и кнопок геймпада. */
    class FocusCatcher extends View {
        FocusCatcher(Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        @Override public boolean onGenericMotionEvent(MotionEvent e) {
            if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                fcMotion++;
                PadMapper m = mapper;
                if (m != null) {
                    try { m.onMotionEvent(e); } catch (Throwable t) { }
                }
                try { dialMotion(e); } catch (Throwable t) { }
                return true;
            }
            return super.onGenericMotionEvent(e);
        }

        @Override public boolean dispatchKeyEvent(KeyEvent e) {
            try {
                PadMapper m = mapper;
                if (m != null && PadMapper.isPadEvent(e)) {
                    if (e.getAction() == KeyEvent.ACTION_DOWN) fcKeys++;
                    m.onKeyEvent(e);
                    return true;
                }
            } catch (Throwable t) { }
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                // «Назад» пришёл нам, а не приложению под окном: передаём его через службу
                if (e.getAction() == KeyEvent.ACTION_UP && svc != null) {
                    try { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); } catch (Throwable t) { }
                }
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        @Override public void onWindowFocusChanged(boolean hasFocus) {
            super.onWindowFocusChanged(hasFocus);
            focusLog(hasFocus ? "фокус получен" : "фокус потерян");
            if (hasFocus) applyBars(); else padReleaseAll();
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            requestFocus();
            post(new Runnable() {
                @Override public void run() { applyBars(); }
            });
        }

        /**
         * Строка состояния скрыта, пока окно держит фокус (настройка pad_focus_fullscreen): систему рулит окно в
         * фокусе. Режим «липкий»: потянуть с верхнего края, чтобы показать на время. Навигацию не трогаем.
         */
        @SuppressWarnings("deprecation")
        void applyBars() {
            SharedPreferences p = prefs();
            boolean hide = p == null || p.getBoolean("pad_focus_fullscreen", true);
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    WindowInsetsController c = getWindowInsetsController();
                    if (c != null) {
                        if (hide) {
                            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                            c.hide(WindowInsets.Type.statusBars());
                        } else {
                            c.show(WindowInsets.Type.statusBars());
                        }
                    }
                }
                setSystemUiVisibility(hide
                        ? (View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY) : 0);
            } catch (Throwable t) { }
        }
    }

    // ---------------------------------------------------------- пульт: другой плеер

    private String extPkg;
    private long extSeekTarget;
    private long extSeekAt;

    private boolean remoteAny() {
        SharedPreferences p = prefs();
        return p == null || p.getBoolean("remote_any", true);
    }

    private List<MediaController> activeSessions() {
        Context c = appCtx;
        if (c == null) return null;
        try {
            MediaSessionManager msm = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return null;
            return msm.getActiveSessions(new ComponentName(c, "im.manus.universalhost.CoreNotificationListener"));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Играющий чужой плеер; если никто не играет, тот, которым мы управляли последним (чтобы его можно было продолжить). */
    private MediaController externalTarget() {
        Context c = appCtx;
        List<MediaController> list = activeSessions();
        if (c == null || list == null) return null;
        MediaController remembered = null;
        for (MediaController mc : list) {
            if (c.getPackageName().equals(mc.getPackageName())) continue;
            PlaybackState ps = mc.getPlaybackState();
            if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) return mc;
            if (extPkg != null && extPkg.equals(mc.getPackageName())) remembered = mc;
        }
        return remembered;
    }

    private boolean routeExternal(String a) {
        if (!remoteAny() || isPlaying()) return false;
        MediaController ext = externalTarget();
        return ext != null && controlExternal(ext, a);
    }

    private boolean controlExternal(MediaController mc, String a) {
        MediaController.TransportControls tc = mc.getTransportControls();
        PlaybackState ps = mc.getPlaybackState();
        boolean playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
        if (a.equals("play_pause")) {
            if (playing) tc.pause(); else tc.play();
        } else if (a.equals("play")) tc.play();
        else if (a.equals("pause")) tc.pause();
        else if (a.equals("next")) tc.skipToNext();
        else if (a.equals("prev")) tc.skipToPrevious();
        else if (a.equals("stop")) tc.stop();
        else if (a.equals("seek_fwd") || a.equals("seek_back")) {
            if (ps != null && (ps.getActions() & PlaybackState.ACTION_SEEK_TO) != 0) {
                long now = SystemClock.elapsedRealtime();
                long pos;
                if (now - extSeekAt < 600) {
                    pos = extSeekTarget;   // при удержании плеер не успевает обновить позицию
                } else {
                    pos = ps.getPosition();
                    if (playing) pos += (long) ((now - ps.getLastPositionUpdateTime()) * ps.getPlaybackSpeed());
                }
                extSeekTarget = Math.max(0, pos + (a.equals("seek_fwd") ? 10000 : -10000));
                extSeekAt = now;
                tc.seekTo(extSeekTarget);
            }
        } else return false;
        extPkg = mc.getPackageName();
        return true;
    }

    // ---------------------------------------------------------- стик-циферблат: место в треке по кругу

    private boolean dialActive;
    private float dialPos = -1f;
    private float dialSent = -1f;
    private long dialSentAt;

    private boolean dialOn() {
        SharedPreferences p = prefs();
        return p == null || p.getBoolean("dial", true);
    }

    private boolean dialLeft() {
        SharedPreferences p = prefs();
        return p != null && p.getBoolean("dial_left", false);
    }

    /** Переключатель на картинке геймпада: циферблат на левом (left=true) или правом стике; второе нажатие выключает. */
    private void toggleDial(boolean left) {
        SharedPreferences p = prefs();
        if (p == null) return;
        boolean here = dialOn() && dialLeft() == left;
        SharedPreferences.Editor e = p.edit();
        if (here) {
            e.putBoolean("dial", false);
        } else {
            e.putBoolean("dial", true);
            e.putBoolean("dial_left", left);
        }
        e.apply();
        dialActive = false;
        dialPos = -1f;
        dialSent = -1f;
    }

    /** Оси стика из события (служба или окно) -> циферблат. */
    private void dialMotion(MotionEvent e) {
        SharedPreferences p = prefs();
        if (p == null || !p.getBoolean("dial", true)) return;
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return;
        if (e.getActionMasked() != MotionEvent.ACTION_MOVE) return;
        PlayerView v = boundView;
        if (v != null && v.padOpen) return;          // идёт настройка кнопок на картинке
        InputDevice d = e.getDevice();
        float x;
        float y;
        if (p.getBoolean("dial_left", false)) {
            x = e.getAxisValue(MotionEvent.AXIS_X);
            y = e.getAxisValue(MotionEvent.AXIS_Y);
        } else {
            boolean trig = d != null && (d.getMotionRange(MotionEvent.AXIS_LTRIGGER) != null
                    || d.getMotionRange(MotionEvent.AXIS_BRAKE) != null);
            boolean rxy = d != null && d.getMotionRange(MotionEvent.AXIS_RX) != null
                    && d.getMotionRange(MotionEvent.AXIS_RY) != null;
            boolean hasZ = d != null && d.getMotionRange(MotionEvent.AXIS_Z) != null;
            boolean useRxy = rxy && (!trig || !hasZ);
            x = e.getAxisValue(useRxy ? MotionEvent.AXIS_RX : MotionEvent.AXIS_Z);
            y = e.getAxisValue(useRxy ? MotionEvent.AXIS_RY : MotionEvent.AXIS_RZ);
        }
        dialStep(x, y);
    }

    /**
     * Вверх = 0%, вправо = 25%, вниз = 50%, влево = 75%. Пока стик отклонён, играет место, на которое он указывает
     * (перемотка по ходу движения, не чаще раза в 250 мс и не меньше чем на 1%). Вернул стик в центр: остаёшься там.
     * Не то место: веди стик дальше по кругу. На обратном пути в центр угол замораживается.
     */
    private void dialStep(float x, float y) {
        float r = (float) Math.hypot(x, y);
        if (!dialActive) {
            if (r < 0.75f) return;
            dialActive = true;
            dialSent = -1f;
        } else if (r < 0.45f) {
            dialActive = false;
            float f = dialPos;
            dialPos = -1f;
            if (f >= 0f && Math.abs(f - dialSent) > 0.002f) seekFractionAny(f);
            dialSent = -1f;
            updateSession();                         // экран не трогаем: на e-ink не дёргается
            return;
        }
        if (r < 0.6f) return;
        double deg = Math.toDegrees(Math.atan2(x, -y));
        if (deg < 0) deg += 360;
        dialPos = (float) (deg / 360.0);
        long now = SystemClock.uptimeMillis();
        if (now - dialSentAt >= 250 && (dialSent < 0f || Math.abs(dialPos - dialSent) >= 0.01f)) {
            dialSentAt = now;
            dialSent = dialPos;
            seekFractionAny(dialPos);
        }
    }

    /** Перемотка на долю трека: своего, а если свой не играет — чужого играющего плеера. */
    private void seekFractionAny(float f) {
        f = Math.max(0f, Math.min(1f, f));
        if (!isPlaying() && remoteAny()) {
            MediaController ext = externalTarget();
            if (ext != null) {
                try {
                    MediaMetadata md = ext.getMetadata();
                    long dur = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
                    PlaybackState ps = ext.getPlaybackState();
                    if (dur > 0 && ps != null && (ps.getActions() & PlaybackState.ACTION_SEEK_TO) != 0) {
                        ext.getTransportControls().seekTo((long) (dur * f));
                    }
                } catch (Throwable t) { }
                return;
            }
        }
        try {
            if (mp != null && prepared) {
                int dur = mp.getDuration();
                if (dur > 0) {
                    mp.seekTo((int) (dur * f));
                    updateSession();
                }
            }
        } catch (Throwable t) { }
    }

    // ---------------------------------------------------------- кнопки геймпада: схема с картинкой
    //
    // Назначения хранит и разбирает PadMapper (нажатие, двойное, долгое), а события получает не от службы, а из
    // открытого окна плеера: так же, как стик-циферблат. Крестовину и курки он разбирает из движений джойстика сам.

    /** Один PadMapper на модуль; создаётся и до init(), чтобы экран настроек работал при выключенной службе. */
    private PadMapper mapper(Context c) {
        PadMapper m = mapper;
        if (m != null) return m;
        if (appCtx == null) {
            appCtx = c.getApplicationContext();
            h = new Handler(Looper.getMainLooper());
        }
        SharedPreferences p = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        m = new PadMapper(p, ACTIONS, new PadMapper.Performer() {
            @Override public void perform(String action) { doPerform(action); }
        });
        m.addDefault(KeyEvent.KEYCODE_BUTTON_A, "t", "play_pause");
        m.addDefault(KeyEvent.KEYCODE_BUTTON_X, "t", "prev");
        m.addDefault(KeyEvent.KEYCODE_BUTTON_Y, "t", "next");
        m.addDefault(KeyEvent.KEYCODE_BUTTON_L1, "t", "seek_back");
        m.addDefault(KeyEvent.KEYCODE_BUTTON_R1, "t", "seek_fwd");
        m.addDefault(KeyEvent.KEYCODE_DPAD_UP, "t", "vol_up");
        m.addDefault(KeyEvent.KEYCODE_DPAD_DOWN, "t", "vol_down");
        m.addDefault(KeyEvent.KEYCODE_DPAD_LEFT, "t", "prev");
        m.addDefault(KeyEvent.KEYCODE_DPAD_RIGHT, "t", "next");
        m.addDefault(KeyEvent.KEYCODE_BUTTON_THUMBL, "t", "mute");
        m.addDefault(KeyEvent.KEYCODE_BUTTON_THUMBR, "t", "say_pct");   // нажатие правого стика (циферблат по умолчанию на нём): процент трека
        m.addToggle(KeyEvent.KEYCODE_BUTTON_THUMBL, "🕹 Циферблат на этом стике", new PadMapper.Toggle() {
            @Override public boolean isOn() { return dialOn() && dialLeft(); }
            @Override public void toggle() { toggleDial(true); }
        });
        m.addToggle(KeyEvent.KEYCODE_BUTTON_THUMBR, "🕹 Циферблат на этом стике", new PadMapper.Toggle() {
            @Override public boolean isOn() { return dialOn() && !dialLeft(); }
            @Override public void toggle() { toggleDial(false); }
        });
        m.migrateLegacy("gbtn_");
        mapper = m;
        return m;
    }

    /** Окно потеряло фокус или закрылось: отпускаем всё, чтобы удержание не зависло. */
    private void padReleaseAll() {
        PadMapper m = mapper;
        if (m != null) m.stop(null);
    }

    /** Через службу кнопки не берём: работают только при открытом окне плеера. */
    @Override public boolean onKeyEvent(KeyEvent e) {
        return false;
    }

    /** Android 14+: службе приходят движения джойстика, пока окно плеера не в фокусе. */
    @Override public void onMotionEvent(MotionEvent e) {
        PadMapper m = mapper;
        if (m != null) {
            try { m.onMotionEvent(e); } catch (Throwable t) { }
        }
        try { dialMotion(e); } catch (Throwable t) { }
    }

    private void doPerform(String a) {
        if (a == null) return;
        if (!a.startsWith("vol_") && !a.equals("mute") && routeExternal(a)) return;
        if (a.equals("focus")) { toggleFocusPref(); return; }
        if (a.equals("later")) { addCurrentTo(PL_LATER); return; }
        if (a.equals("fav")) { addCurrentTo(PL_FAV); return; }
        if (a.equals("pl_switch")) { switchList(); return; }
        if (a.equals("delete")) { deleteCurrentConfirm(); return; }
        if (a.equals("say")) { sayCurrent(); return; }
        if (a.equals("say_pct")) { sayPercent(); return; }
        if (a.equals("lock")) { toggleTouchLock(); return; }
        if (a.equals("stop_after")) { toggleStopAfter(); return; }
        if (a.equals("speed_up")) { setSpeed(speed() + 0.1f); return; }
        if (a.equals("speed_down")) { setSpeed(speed() - 0.1f); return; }
        if (a.equals("speed_reset")) { setSpeed(1f); return; }
        if (a.equals("play_pause")) toggle();
        else if (a.equals("play")) { if (!isPlaying()) toggle(); }
        else if (a.equals("pause")) pause();
        else if (a.equals("prev")) prev();
        else if (a.equals("next")) next();
        else if (a.equals("seek_back")) seekBy(-10000);
        else if (a.equals("seek_fwd")) seekBy(10000);
        else if (a.equals("stop")) stopPlayback();
        else if (a.equals("vol_up")) volume(AudioManager.ADJUST_RAISE);
        else if (a.equals("vol_down")) volume(AudioManager.ADJUST_LOWER);
        else if (a.equals("mute")) volume(AudioManager.ADJUST_TOGGLE_MUTE);
    }

    /** Стоп: пауза и возврат в начало трека. */
    private void stopPlayback() {
        try {
            if (mp != null && prepared) {
                if (mp.isPlaying()) mp.pause();
                mp.seekTo(0);
                afterChange();
            }
        } catch (Throwable t) { }
    }

    private void volume(int direction) {
        Context c = appCtx;
        if (c == null) return;
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
    }

    // ---------------------------------------------------------- настройки

    @Override public List<SettingItem> getSettingsSchema() {
        List<SettingItem> r = new ArrayList<SettingItem>();
        r.add(SettingItem.section("🎵 Плеер"));
        r.add(SettingItem.custom("player", "🎵 Плеер", "Песни, плейлисты, кнопки геймпада и стик-циферблат на картинке 🎮"));
        r.add(SettingItem.toggle("newest_first", "🆕 Новые песни сверху",
                "Список по порядку загрузки на устройство; выключено: старые сверху, новые внизу", false));
        r.add(SettingItem.toggle("resume", "📍 Помнить место в треке",
                "Ушёл на другой трек или поставил на паузу: вернёшься туда же", true));
        r.add(SettingItem.toggle("no_auto_next", "⏹ Не переходить к следующему",
                "Трек доиграл: тишина, без перехода дальше", false));
        r.add(SettingItem.toggle("unplug_pause", "🎧✕ Пауза, если наушники отключили", "", true));
        r.add(SettingItem.toggle("plug_play", "🎧▶ Играть, когда наушники подключили", "", false));
        r.add(SettingItem.toggle("remote_any", "🔀 Управлять и чужим плеером",
                "Свой плеер не играет: кнопки идут в другой играющий (нужен доступ к уведомлениям)", true));
        r.add(SettingItem.toggle("keep_screen", "💡 Не гасить экран (e-ink)",
                "Пока открыто окно плеера: стики и кнопки приходят в него", true));
        r.add(SettingItem.toggle("pad_focus", "🎯 Держать фокус для геймпада",
                "Прозрачное окно поверх экрана принимает стики и кнопки, пока геймпад подключён и окно плеера закрыто (Android 11). Минус: приложение под ним теряет фокус (клавиатура, «Назад»)", false));
        r.add(SettingItem.toggle("pad_focus_fullscreen", "📺 Скрывать строку состояния",
                "Пока работает окно фокуса: строка состояния скрыта (потянуть с верхнего края, чтобы показать на время)", true));
        r.add(SettingItem.slider("tone_vol", "🔔 Громкость сигналов",
                "Писки при удалении, плейлистах, блокировке касаний", 10, 100, 5, 100));
        r.add(SettingItem.section("🎮 Геймпад (окно открыто)"));
        r.add(SettingItem.toggle("intercept", "🚫 Перехват кнопок",
                "Включено: назначенная кнопка только для плеера", true));
        r.add(SettingItem.slider("long_ms", "⏱ Долгое нажатие, мс", "", 300, 1000, 50, 500));
        r.add(SettingItem.slider("double_ms", "⏱⏱ Двойное нажатие, мс", "", 150, 600, 50, 300));
        r.add(SettingItem.section("⚙"));
        r.add(SettingItem.action("pin", "📌 Ярлык на рабочий стол", ""));
        r.add(SettingItem.action("reset", "↺ Сбросить кнопки", ""));
        return r;
    }

    @Override public void onSettingChanged(String key) {
        if ("pin".equals(key)) {
            try { im.manus.universalhost.ModuleShortcuts.pin(appCtx, this); }
            catch (Throwable t) { }
        } else if ("reset".equals(key)) {
            PadMapper m = mapper;
            if (m != null) m.reset();
        } else if ("remote_any".equals(key)) {
            if (!remoteAny()) ensureSession();
        } else if ("newest_first".equals(key)) {
            loadTracks();                            // новый порядок списка; текущий трек находится по id
            refreshUiAll();
        } else if ("keep_screen".equals(key)) {
            final PlayerView v = boundView;          // применяем к открытому окну сразу, а не при следующем открытии
            if (v != null && h != null) {
                h.post(new Runnable() {
                    @Override public void run() { v.applyKeepScreen(); }
                });
            }
        } else if ("pad_focus".equals(key)) {
            updateFocusCatcher();
        } else if ("pad_focus_fullscreen".equals(key)) {
            View fv = focusView;
            if (fv instanceof FocusCatcher) ((FocusCatcher) fv).applyBars();
        } else if ("dial".equals(key) || "dial_left".equals(key)) {
            dialActive = false;
            dialPos = -1f;
        }
    }

    @Override public View createSettingsView(Context context, String key) {
        mapper(context);
        return new PlayerView(context);
    }

    // ---------------------------------------------------------- экран плеера

    class PlayerView extends ScrollView {
        private final LinearLayout body;
        private final LinearLayout permBox;
        private final TextView nowTitle;
        private final Button bPlay;
        private final Button bPad;
        private final LinearLayout padBox;
        private final TextView trackHead;
        private final LinearLayout trackRows;
        private final TextView status;
        private boolean padOpen = false;
        private int listMode = 0;                  // 0 все песни, 1 «Послушать позже», 2 «Избранное»
        private Button bAll;
        private Button bLater;
        private Button bFav;
        // строки общего списка: чтобы менять пометку «▶» только у двух строк и не перерисовывать весь список
        private final List<TextView> rowMarks = new ArrayList<TextView>();   // значок «▶» на плитке
        private final List<View> rowViews = new ArrayList<View>();
        private final List<Integer> rowIdx = new ArrayList<Integer>();   // номер песни в tracks для каждой строки
        private int markedCur = -2;

        PlayerView(Context ctx) {
            super(ctx);
            body = new LinearLayout(ctx);
            body.setOrientation(LinearLayout.VERTICAL);
            int p = dp(12);
            body.setPadding(p, p, p, p);
            body.setClipToPadding(false);                    // схема геймпада выходит в поля до краёв экрана
            addView(body, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            setFocusable(true);
            setFocusableInTouchMode(true);

            // доступ к музыке
            permBox = new LinearLayout(ctx);
            permBox.setOrientation(LinearLayout.VERTICAL);
            TextView pt = new TextView(ctx);
            pt.setText("Нужен доступ к музыке на устройстве.");
            pt.setTextSize(14);
            permBox.addView(pt);
            Button pb = new Button(ctx);
            pb.setText("Разрешить доступ к музыке");
            pb.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    Context c = getContext();
                    if (c instanceof Activity) requestPermission((Activity) c);
                }
            });
            permBox.addView(pb);
            body.addView(permBox);

            // сейчас играет
            nowTitle = new TextView(ctx);
            nowTitle.setTextSize(16);
            body.addView(nowTitle);

            LinearLayout ctrls = new LinearLayout(ctx);
            ctrls.setOrientation(LinearLayout.HORIZONTAL);
            ctrls.setGravity(Gravity.CENTER);
            Button bPrev = new Button(ctx);
            bPrev.setText("⏮");
            bPrev.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { prev(); }
            });
            bPlay = new Button(ctx);
            bPlay.setText("▶");
            bPlay.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { toggle(); }
            });
            Button bNext = new Button(ctx);
            bNext.setText("⏭");
            bNext.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { next(); }
            });
            ctrls.addView(bPrev);
            ctrls.addView(bPlay);
            ctrls.addView(bNext);
            body.addView(ctrls);

            // списки: все песни / «Послушать позже» / «Избранное», справа «🔄» (найти новые песни)
            LinearLayout lists = new LinearLayout(ctx);
            lists.setOrientation(LinearLayout.HORIZONTAL);
            bAll = listButton(ctx, 0);
            bLater = listButton(ctx, 1);
            bFav = listButton(ctx, 2);
            Button bScan = new Button(ctx);
            bScan.setText("🔄");
            bScan.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { scanNewFiles(); }
            });
            lists.addView(bAll, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            lists.addView(bLater, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            lists.addView(bFav, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            lists.addView(bScan, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            body.addView(lists);

            // геймпад: картинка открывается по кнопке; пока она открыта, кнопки геймпада выбирают
            // назначение, а не управляют плеером
            bPad = new Button(ctx);
            bPad.setText("🎮");
            bPad.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { togglePad(); }
            });
            body.addView(bPad);
            padBox = new LinearLayout(ctx);
            padBox.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams padLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            padLp.leftMargin = -dp(12);
            padLp.rightMargin = -dp(12);
            body.addView(padBox, padLp);

            // песни
            trackHead = new TextView(ctx);
            trackHead.setTextSize(16);
            trackHead.setPadding(0, dp(8), 0, 0);
            body.addView(trackHead);
            trackRows = new LinearLayout(ctx);
            trackRows.setOrientation(LinearLayout.VERTICAL);
            body.addView(trackRows);

            status = new TextView(ctx);
            status.setTextSize(13);
            status.setPadding(0, dp(8), 0, 0);
            body.addView(status);

            refresh();
        }

        private int dp(int v) {
            return Math.round(v * getResources().getDisplayMetrics().density);
        }

        /** «Не гасить экран» из настройки; вызывается при открытии окна и сразу при смене настройки. */
        void applyKeepScreen() {
            SharedPreferences kp = prefs();
            setKeepScreenOn(kp == null || kp.getBoolean("keep_screen", true));
        }

        /** Переключить показанный список (например, после «Сменить список» с геймпада). */
        void setMode(int mode) {
            listMode = mode;
            buildTrackRows();
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            boundView = this;
            updateFocusCatcher();
            applyKeepScreen();
            requestFocus();
            ensureObserver();
            reloadIfChanged();                       // песни могли появиться, пока окно было закрыто
            refresh();
            // при открытии активная песня выводится в окно (один раз, без перерисовок потом)
            postDelayed(new Runnable() {
                @Override public void run() {
                    int pos = rowIdx.indexOf(Integer.valueOf(cur));
                    if (listMode == 0 && pos >= 0 && pos < rowViews.size()) focusRow(rowViews.get(pos));
                }
            }, 250);
        }

        /** Окно плеера ушло в фон или вернулось: окну фокуса пора появиться или исчезнуть. */
        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            updateFocusCatcher();
        }

        @Override protected void onDetachedFromWindow() {
            if (boundView == this) boundView = null;
            updateFocusCatcher();
            if (shieldParent != null) unlockTouch();
            padReleaseAll();
            setKeepScreenOn(false);
            super.onDetachedFromWindow();
        }

        /** Стики, крестовина и курки в открытом окне плеера (Android 11 и ниже без Shizuku). */
        @Override public boolean onGenericMotionEvent(MotionEvent e) {
            if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                PadMapper m = mapper;
                if (m != null) {
                    try { m.onMotionEvent(e); } catch (Throwable t) { }
                }
                try { dialMotion(e); } catch (Throwable t) { }
                return true;
            }
            return super.onGenericMotionEvent(e);
        }

        /** Кнопки геймпада в открытом окне плеера. */
        @Override public boolean dispatchKeyEvent(KeyEvent e) {
            try {
                PadMapper m = mapper;
                if (m != null && PadMapper.isPadEvent(e) && m.onKeyEvent(e)) return true;
            } catch (Throwable t) { }
            return super.dispatchKeyEvent(e);
        }

        @Override public void onWindowFocusChanged(boolean hasFocus) {
            super.onWindowFocusChanged(hasFocus);
            if (hasFocus) {
                reloadIfChanged();
                refresh();
            } else {
                padReleaseAll();
            }
        }

        private Button listButton(Context ctx, final int mode) {
            Button b = new Button(ctx);
            b.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    listMode = mode;
                    buildTrackRows();
                }
            });
            return b;
        }

        private void updateListButtons() {
            bAll.setText((listMode == 0 ? "● " : "") + "♫ " + generalArray().length);
            bLater.setText((listMode == 1 ? "● " : "") + "🕓 " + plCount(PL_LATER));
            bFav.setText((listMode == 2 ? "● " : "") + "⭐ " + plCount(PL_FAV));
        }

        private void togglePad() {
            padOpen = !padOpen;
            padBox.removeAllViews();
            if (padOpen) {
                Context c = getContext();
                padBox.addView(mapper(c).createView(c, ""));
            }
            bPad.setText(padOpen ? "🎮 ✕" : "🎮");
        }

        /**
         * Сменился текущий трек: значок «▶» переносится только между двумя плитками (остальной список не
         * перерисовывается, e-ink не мигает), а новая плитка выводится в окно.
         */
        private void markCurrent(boolean focus) {
            if (listMode != 0) return;
            int po = markedCur >= 0 ? rowIdx.indexOf(Integer.valueOf(markedCur)) : -1;
            if (po >= 0 && po < rowMarks.size()) rowMarks.get(po).setVisibility(View.GONE);
            int pn = cur >= 0 ? rowIdx.indexOf(Integer.valueOf(cur)) : -1;
            if (pn >= 0 && pn < rowMarks.size()) rowMarks.get(pn).setVisibility(View.VISIBLE);
            markedCur = cur;
            if (focus && pn >= 0 && pn < rowViews.size()) focusRow(rowViews.get(pn));
        }

        /**
         * Прокрутить так, чтобы строка оказалась в верхней трети окна. Список лежит внутри прокрутки экрана
         * настроек хоста, поэтому ищем ближайшую внешнюю прокрутку. Если строка и так почти там, ничего не двигаем.
         */
        private void focusRow(final View row) {
            post(new Runnable() {
                @Override public void run() {
                    try {
                        ScrollView outer = PlayerView.this;
                        ViewParent pp = getParent();
                        while (pp instanceof View) {
                            if (pp instanceof ScrollView) { outer = (ScrollView) pp; break; }
                            pp = pp.getParent();
                        }
                        int y = 0;
                        View x = row;
                        while (x != outer) {
                            y += x.getTop();
                            ViewParent par = x.getParent();
                            if (!(par instanceof View)) return;
                            x = (View) par;
                        }
                        int target = Math.max(0, y - outer.getHeight() / 3);
                        if (Math.abs(target - outer.getScrollY()) > dp(24)) outer.scrollTo(0, target);
                    } catch (Throwable t) { }
                }
            });
        }

        /** Дешёвое обновление: шапка, кнопка, статус. */
        void refreshHead() {
            boolean perm = hasPermission();
            permBox.setVisibility(perm ? GONE : VISIBLE);
            if (perm && tracks.isEmpty()) {
                loadTracks();
                buildTrackRows();
            }
            Track t = (cur >= 0 && cur < tracks.size()) ? tracks.get(cur) : null;
            nowTitle.setText(t == null ? "Не играет" : "▶ " + t.title + "\n" + t.artist);
            bPlay.setText(isPlaying() ? "⏸" : "▶");
            status.setText("Геймпад работает, пока это окно открыто и в фокусе.\n"
                    + "Циферблат по треку: нажми стик на картинке 🎮 и включи его там.\n\n" + focusReport());
            if (bAll != null) updateListButtons();
            if (cur != markedCur) markCurrent(true);
        }

        /** Полное обновление: шапка + список песен. */
        void refresh() {
            refreshHead();
            buildTrackRows();
        }

        /** Плитка 16:9: высота считается от ширины. */
        private class Tile extends FrameLayout {
            Tile(Context c) { super(c); }

            @Override protected void onMeasure(int wSpec, int hSpec) {
                int w = MeasureSpec.getSize(wSpec);
                super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(w * 9 / 16, MeasureSpec.EXACTLY));
            }
        }

        private TextView badge(Context ctx, String text) {
            TextView b = new TextView(ctx);
            b.setText(text);
            b.setTextSize(16);
            b.setTextColor(0xFFFFFFFF);
            b.setBackgroundColor(0xAA000000);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(8), dp(4), dp(8), dp(4));
            return b;
        }

        /**
         * Плитка песни: превью на всю плитку, названия на ней нет. Один тап играет, двойной показывает название
         * поверх картинки и озвучивает его. Песни без превью показывают название на плитке. Угловые значки
         * (🗑, ↩) вызывают свои действия; markOut[0] получает значок «▶» (виден у играющей песни).
         */
        private View buildTile(Context ctx, Track tr, final String title, final Runnable onPlay,
                               String[] badgeText, final Runnable[] badgeAct, TextView[] markOut) {
            final Tile tile = new Tile(ctx);
            tile.setContentDescription(title);
            final ImageView img = new ImageView(ctx);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setBackgroundColor(0xFFE0E0E0);
            tile.addView(img, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            final TextView ph = new TextView(ctx);
            ph.setText(title);
            ph.setTextSize(14);
            ph.setTextColor(0xFF000000);
            ph.setGravity(Gravity.CENTER);
            ph.setPadding(dp(10), dp(10), dp(10), dp(10));
            ph.setMaxLines(4);
            ph.setEllipsize(TextUtils.TruncateAt.END);
            tile.addView(ph, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            int targetW = Math.max(160, getResources().getDisplayMetrics().widthPixels / 2);
            if (tr != null) loadCover(tr.path, img, ph, targetW);

            final TextView over = badge(ctx, title);
            over.setTextSize(15);
            over.setBackgroundColor(0xCC000000);
            over.setPadding(dp(10), dp(10), dp(10), dp(10));
            over.setVisibility(View.GONE);
            tile.addView(over, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            TextView mark = badge(ctx, "▶");
            mark.setVisibility(View.GONE);
            tile.addView(mark, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START));
            if (markOut != null) markOut[0] = mark;

            LinearLayout bar = new LinearLayout(ctx);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < badgeText.length; i++) {
                final Runnable act = badgeAct[i];
                TextView b = badge(ctx, badgeText[i]);
                b.setOnClickListener(new OnClickListener() {
                    @Override public void onClick(View v) { act.run(); }
                });
                bar.addView(b);
            }
            tile.addView(bar, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END));

            final Runnable hide = new Runnable() {
                @Override public void run() { over.setVisibility(View.GONE); }
            };
            final GestureDetector gd = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }

                @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                    onPlay.run();
                    return true;
                }

                @Override public boolean onDoubleTap(MotionEvent e) {
                    over.setText(title);
                    over.setVisibility(View.VISIBLE);
                    over.removeCallbacks(hide);
                    over.postDelayed(hide, 6000);
                    speak(title);
                    return true;
                }
            });
            tile.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent e) { return gd.onTouchEvent(e); }
            });
            return tile;
        }

        /** Плитки по две в ряд. */
        private void fillTiles(Context ctx, List<View> tiles) {
            for (int i = 0; i < tiles.size(); i += 2) {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int k = 0; k < 2; k++) {
                    View v = i + k < tiles.size() ? tiles.get(i + k) : new View(ctx);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
                    lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                    row.addView(v, lp);
                }
                trackRows.addView(row);
            }
        }

        /**
         * Плитки плейлиста: тап играет его с этой записи, «↩» убирает запись из плейлиста (песня снова
         * появляется в общем списке), «🗑» удаляет песню с устройства.
         */
        private void buildPlaylistRows(final Context ctx) {
            final String key = listMode == 1 ? PL_LATER : PL_FAV;
            final List<String[]> pl = plLoad(key);
            trackHead.setText((listMode == 1 ? "🕓 " : "⭐ ") + pl.size());
            List<View> tiles = new ArrayList<View>();
            for (int i = 0; i < pl.size(); i++) {
                final int pos = i;
                final String[] en = pl.get(i);
                int ti = findTrack(en);
                Track tr = ti >= 0 ? tracks.get(ti) : null;
                tiles.add(buildTile(ctx, tr, en[1], new Runnable() {
                    @Override public void run() { playFromList(key, pos); }
                }, new String[]{"↩", "🗑"}, new Runnable[]{
                        new Runnable() {
                            @Override public void run() {
                                plRemove(key, pos);
                                buildTrackRows();
                            }
                        },
                        new Runnable() {
                            @Override public void run() {
                                int idx = findTrack(en);
                                if (idx >= 0) {
                                    askDelete(getContext(), idx);      // файл удаляется, запись уходит из плейлистов
                                } else {
                                    plRemove(key, pos);                // трека нет на устройстве: убираем запись
                                    buildTrackRows();
                                }
                            }
                        }}, null));
            }
            fillTiles(ctx, tiles);
        }

        private void buildTrackRows() {
            trackRows.removeAllViews();
            rowMarks.clear();
            rowViews.clear();
            rowIdx.clear();
            final Context ctx = getContext();
            updateListButtons();
            if (listMode != 0) {
                markedCur = -2;
                buildPlaylistRows(ctx);
                return;
            }
            int[] gen = generalArray();               // песни из плейлистов в общем списке не показываем
            trackHead.setText("♫ " + gen.length);
            List<View> tiles = new ArrayList<View>();
            for (int g = 0; g < gen.length; g++) {
                final int idx = gen[g];
                Track tr = tracks.get(idx);
                TextView[] mk = new TextView[1];
                View tile = buildTile(ctx, tr, tr.title, new Runnable() {
                    @Override public void run() { playFromGeneral(idx); }
                }, new String[]{"🗑"}, new Runnable[]{new Runnable() {
                    @Override public void run() { askDelete(getContext(), idx); }
                }}, mk);
                if (idx == cur) mk[0].setVisibility(View.VISIBLE);
                tiles.add(tile);
                rowMarks.add(mk[0]);
                rowViews.add(tile);
                rowIdx.add(Integer.valueOf(idx));
            }
            fillTiles(ctx, tiles);
            markedCur = cur;
        }
    }
}
