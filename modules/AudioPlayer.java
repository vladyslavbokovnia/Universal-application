package im.manus.plugins;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
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
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
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
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.FrameLayout;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Аудиоплеер и пульт в одном модуле.
 *
 * Свой плеер: песни с устройства (список, удаление), своя медиа-сессия — гарнитура и системные клавиши работают
 * с ним. Пульт: если свой плеер не играет, кнопки геймпада управляют другим играющим плеером (через его
 * медиасессию; нужен доступ к уведомлениям для хоста). Кнопки назначаются на картинке геймпада: нажатие,
 * двойное и долгое. Стик-циферблат: стик отклонён — играет место трека, на которое он указывает, вернул в
 * центр — остаёшься там. Shizuku не нужен: кнопки идут через службу специальных возможностей (работает при
 * выключенном экране), стики — через неё на Android 14+, а на более старых (Android 11) — через открытое окно
 * плеера (на e-ink экран можно не гасить).
 */
public class AudioPlayer implements IPlugin, ISettingsProvider, ISettingsViewProvider, IKeyHandler,
        IMotionHandler, IShareHandler {

    private static final String PREFS = "module_Аудиоплеер";
    private static final String CHANNEL = "audioplayer";
    private static final int NOTIF_ID = 4101;

    private static final String A_TOGGLE = "im.manus.plugins.audioplayer.TOGGLE";
    private static final String A_NEXT = "im.manus.plugins.audioplayer.NEXT";
    private static final String A_PREV = "im.manus.plugins.audioplayer.PREV";

    /** Геймпад: действия плеера — id, подпись. */
    private static final String[][] ACTIONS = {
            {"play_pause", "Пауза / воспроизведение"},
            {"play", "Воспроизведение"},
            {"pause", "Пауза"},
            {"prev", "Предыдущий трек"},
            {"next", "Следующий трек"},
            {"seek_back", "Назад 10 с"},
            {"seek_fwd", "Вперёд 10 с"},
            {"stop", "Стоп"},
            {"vol_down", "Тише"},
            {"vol_up", "Громче"},
            {"mute", "Без звука"},
            {"later", "В плейлист «Послушать позже»"},
            {"fav", "В «Избранное»"},
            {"delete", "Удалить текущий трек (нажать дважды)"},
            {"say", "Озвучить название и сколько осталось"},
            {"lock", "Заблокировать касания до выключения экрана"},
    };

    private static final String PL_LATER = "pl_later";
    private static final String PL_FAV = "pl_fav";

    static class Track {
        long id;
        String title;
        String artist;
        long dur;
        Uri uri;
    }

    private Context appCtx;
    private Handler h;
    private AccessibilityService svc;
    private MediaPlayer mp;
    private boolean prepared = false;
    private final List<Track> tracks = new ArrayList<Track>();
    private int cur = -1;
    /** Очередь из плейлиста (индексы в tracks); null: играем всю библиотеку по порядку. */
    private int[] queue;
    private MediaSession session;
    private BroadcastReceiver cmdReceiver;
    private PlayerView boundView;
    private PadMapper mapper;

    @Override public String getName() { return "Аудиоплеер"; }
    @Override public int getVersion() { return 7; }
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
        mapper(context);
    }

    @Override public void stop() {
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

    void loadTracks() {
        tracks.clear();
        queue = null;
        if (appCtx == null || !hasPermission()) return;
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION
        };
        Cursor c = null;
        try {
            c = appCtx.getContentResolver().query(uri, proj,
                    MediaStore.Audio.Media.IS_MUSIC + " != 0", null,
                    MediaStore.Audio.Media.TITLE + " ASC");
            while (c != null && c.moveToNext()) {
                Track t = new Track();
                t.id = c.getLong(0);
                t.title = c.getString(1);
                if (t.title == null || t.title.length() == 0) t.title = "Без названия";
                t.artist = c.getString(2);
                if (t.artist == null || t.artist.length() == 0) t.artist = "Неизвестный исполнитель";
                t.dur = c.getLong(3);
                t.uri = ContentUris.withAppendedId(uri, t.id);
                tracks.add(t);
            }
        } catch (Throwable t) {
            // нет доступа — список останется пустым
        } finally {
            if (c != null) try { c.close(); } catch (Throwable t) { }
        }
        if (cur >= tracks.size()) cur = -1;
    }

    static String fmtDur(long ms) {
        if (ms <= 0) return "";
        long s = ms / 1000;
        return (s / 60) + ":" + String.format("%02d", s % 60);
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
                try { p.start(); } catch (Throwable t) { }
                afterChange();
            }
        });
        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override public void onCompletion(MediaPlayer p) { next(); }
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
            mp.reset();
            prepared = false;
            cur = i;
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
            if (!tracks.isEmpty()) playAt(cur >= 0 ? cur : 0);
            return;
        }
        try {
            if (mp.isPlaying()) mp.pause(); else mp.start();
            afterChange();
        } catch (Throwable t) { }
    }

    public void pause() {
        try {
            if (mp != null && prepared && mp.isPlaying()) {
                mp.pause();
                afterChange();
            }
        } catch (Throwable t) { }
    }

    public void next() {
        int[] q = queue;
        if (q != null && q.length > 0) {
            playAt(q[(queuePos(q) + 1) % q.length]);
            return;
        }
        if (!tracks.isEmpty()) playAt((cur + 1) % tracks.size());
    }

    public void prev() {
        int[] q = queue;
        if (q != null && q.length > 0) {
            int p = queuePos(q);
            playAt(q[p < 0 ? q.length - 1 : (p - 1 + q.length) % q.length]);
            return;
        }
        if (!tracks.isEmpty()) playAt((cur - 1 + tracks.size()) % tracks.size());
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
            if (q != null) playAt(q[Math.max(0, qp) % q.length]);
            else if (!tracks.isEmpty()) playAt(Math.min(i, tracks.size() - 1));
            else afterChange();
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
            ensureSession();
            ensureReceiver();
            playAt(0);
            toast("Играет: " + title);
            return true;
        } catch (Throwable t) {
            return false;
        }
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

    /** Звуковой отклик, чтобы не смотреть на экран. */
    private void tone(int type) {
        try {
            final ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 45);
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

    /** Кнопка геймпада: текущий трек в плейлист. Отклик: «позже» и «избранное» звучат по-разному, повтор и «нечего добавлять» тоже. */
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
        refreshUiHead();
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
        playAt(arr[start]);
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

    /** Название трека и после него только число: сколько треков осталось до конца списка. */
    private void sayCurrent() {
        String[] e = currentEntry();
        if (e == null || e[1] == null || e[1].length() == 0) {
            tone(ToneGenerator.TONE_PROP_NACK);
            return;
        }
        String text = e[1];
        boolean own = !e[0].equals("-") && cur >= 0 && cur < tracks.size();
        if (own) {
            int[] q = queue;
            int left;
            if (q != null && q.length > 0) {
                int p = queuePos(q);
                left = p < 0 ? q.length : q.length - 1 - p;
            } else {
                left = tracks.size() - 1 - cur;
            }
            text = text + ". " + Math.max(0, left);
        }
        speak(text);
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
        if (a.equals("later")) { addCurrentTo(PL_LATER); return; }
        if (a.equals("fav")) { addCurrentTo(PL_FAV); return; }
        if (a.equals("delete")) { deleteCurrentConfirm(); return; }
        if (a.equals("say")) { sayCurrent(); return; }
        if (a.equals("lock")) { toggleTouchLock(); return; }
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
        r.add(SettingItem.section("Плеер"));
        r.add(SettingItem.custom("player", "Плеер", "Песни с устройства, удаление, кнопки геймпада на картинке"));
        r.add(SettingItem.toggle("remote_any", "Управлять и другим плеером",
                "Если свой плеер не играет, кнопки управляют другим играющим плеером (нужен доступ к уведомлениям для хоста). Выключено: только свой плеер",
                true));
        r.add(SettingItem.toggle("dial", "Стик-циферблат",
                "Стик отклонён: играет место, на которое он указывает (вверх 0%, вправо 25%, вниз 50%, влево 75%). Вернул в центр: остаёшься там. Не то место: веди стик дальше по кругу",
                true));
        r.add(SettingItem.toggle("dial_left", "Циферблат на левом стике", "Выключено: правый стик", false));
        r.add(SettingItem.toggle("keep_screen", "Не гасить экран при открытом плеере",
                "Для e-ink: неподвижная картинка не тратит заряд, а стики приходят в окно плеера без Shizuku", true));
        r.add(SettingItem.section("Геймпад (пока окно плеера открыто)"));
        r.add(SettingItem.toggle("intercept", "Перехват кнопок",
                "Включено: назначенная кнопка работает только на плеер. Выключено: действие срабатывает, а окно тоже получает нажатие",
                true));
        r.add(SettingItem.slider("long_ms", "Долгое нажатие, мс", "", 300, 1000, 50, 500));
        r.add(SettingItem.slider("double_ms", "Окно двойного нажатия, мс", "", 150, 600, 50, 300));
        r.add(SettingItem.section("Прочее"));
        r.add(SettingItem.action("pin", "Ярлык на рабочий стол", "Значок модуля на рабочий стол — откроется отдельным окном"));
        r.add(SettingItem.action("reset", "Сбросить кнопки геймпада", "Вернуть кнопки по умолчанию"));
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

        PlayerView(Context ctx) {
            super(ctx);
            body = new LinearLayout(ctx);
            body.setOrientation(LinearLayout.VERTICAL);
            int p = dp(12);
            body.setPadding(p, p, p, p);
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

            // списки: все песни / «Послушать позже» / «Избранное»
            LinearLayout lists = new LinearLayout(ctx);
            lists.setOrientation(LinearLayout.HORIZONTAL);
            bAll = listButton(ctx, 0);
            bLater = listButton(ctx, 1);
            bFav = listButton(ctx, 2);
            lists.addView(bAll, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            lists.addView(bLater, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            lists.addView(bFav, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            body.addView(lists);

            // геймпад: картинка открывается по кнопке; пока она открыта, кнопки геймпада выбирают
            // назначение, а не управляют плеером
            bPad = new Button(ctx);
            bPad.setText("Кнопки геймпада");
            bPad.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { togglePad(); }
            });
            body.addView(bPad);
            padBox = new LinearLayout(ctx);
            padBox.setOrientation(LinearLayout.VERTICAL);
            body.addView(padBox);

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

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            boundView = this;
            SharedPreferences kp = prefs();
            setKeepScreenOn(kp == null || kp.getBoolean("keep_screen", true));
            requestFocus();
            refresh();
        }

        @Override protected void onDetachedFromWindow() {
            if (boundView == this) boundView = null;
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
            if (hasFocus) refresh(); else padReleaseAll();
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
            bAll.setText((listMode == 0 ? "● " : "") + "Все");
            bLater.setText((listMode == 1 ? "● " : "") + "Позже (" + plCount(PL_LATER) + ")");
            bFav.setText((listMode == 2 ? "● " : "") + "Избранное (" + plCount(PL_FAV) + ")");
        }

        private void togglePad() {
            padOpen = !padOpen;
            padBox.removeAllViews();
            if (padOpen) {
                Context c = getContext();
                padBox.addView(mapper(c).createView(c, ""));
            }
            bPad.setText(padOpen ? "Скрыть кнопки геймпада" : "Кнопки геймпада");
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
            status.setText("Геймпад работает, пока это окно открыто и в фокусе.");
            if (bAll != null) updateListButtons();
        }

        /** Полное обновление: шапка + список песен. */
        void refresh() {
            refreshHead();
            buildTrackRows();
        }

        /** Строки плейлиста: нажатие играет его с этой записи, «Удалить» удаляет трек с устройства. */
        private void buildPlaylistRows(final Context ctx) {
            final String key = listMode == 1 ? PL_LATER : PL_FAV;
            final List<String[]> pl = plLoad(key);
            trackHead.setText((listMode == 1 ? "Послушать позже (" : "Избранное (") + pl.size() + ")");
            for (int i = 0; i < pl.size(); i++) {
                final int pos = i;
                final String[] en = pl.get(i);
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(6), 0, dp(6));
                LinearLayout txt = new LinearLayout(ctx);
                txt.setOrientation(LinearLayout.VERTICAL);
                TextView tt = new TextView(ctx);
                tt.setText(en[1]);
                tt.setTextSize(15);
                TextView ta = new TextView(ctx);
                ta.setText(en[2].length() == 0 ? "—" : en[2]);
                ta.setTextSize(12);
                txt.addView(tt);
                txt.addView(ta);
                row.addView(txt, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
                Button rm = new Button(ctx);
                rm.setText("Удалить");
                rm.setOnClickListener(new OnClickListener() {
                    @Override public void onClick(View v) {
                        int idx = findTrack(en);
                        if (idx >= 0) {
                            askDelete(getContext(), idx);      // файл удаляется, запись уходит из плейлистов
                        } else {
                            plRemove(key, pos);                // трека нет на устройстве: убираем запись
                            buildTrackRows();
                        }
                    }
                });
                row.addView(rm);
                row.setOnClickListener(new OnClickListener() {
                    @Override public void onClick(View v) { playFromList(key, pos); }
                });
                trackRows.addView(row);
            }
        }

        private void buildTrackRows() {
            trackRows.removeAllViews();
            final Context ctx = getContext();
            updateListButtons();
            if (listMode != 0) {
                buildPlaylistRows(ctx);
                return;
            }
            trackHead.setText("Песни (" + tracks.size() + ")");
            for (int i = 0; i < tracks.size(); i++) {
                final int idx = i;
                Track tr = tracks.get(i);
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(6), 0, dp(6));

                LinearLayout txt = new LinearLayout(ctx);
                txt.setOrientation(LinearLayout.VERTICAL);
                TextView tt = new TextView(ctx);
                tt.setText((i == cur ? "▶ " : "") + tr.title);
                tt.setTextSize(15);
                TextView ta = new TextView(ctx);
                String sub = tr.artist;
                String d = fmtDur(tr.dur);
                if (d.length() > 0) sub += " · " + d;
                ta.setText(sub);
                ta.setTextSize(12);
                txt.addView(tt);
                txt.addView(ta);
                row.addView(txt, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

                Button del = new Button(ctx);
                del.setText("Удалить");
                del.setOnClickListener(new OnClickListener() {
                    @Override public void onClick(View v) { askDelete(getContext(), idx); }
                });
                row.addView(del);

                row.setOnClickListener(new OnClickListener() {
                    @Override public void onClick(View v) {
                        queue = null;
                        playAt(idx);
                    }
                });
                trackRows.addView(row);
            }
        }
    }
}
