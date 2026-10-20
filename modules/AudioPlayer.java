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
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
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
import java.util.Map;

/**
 * Аудиоплеер — свой плеер прямо в хосте.
 *
 * Песни с устройства (список, удаление), своя медиа-сессия — поэтому пауза/треки
 * работают с гарнитуры и из модуля «Пульт». Кнопки геймпада назначаются тут же, на картинке геймпада
 * (как в модуле Gamepad): нажатие, двойное и долгое. Shizuku не нужен: кнопки идут через службу
 * специальных возможностей, стики и курки — через неё же на Android 14+.
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
    };

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
    private MediaSession session;
    private BroadcastReceiver cmdReceiver;
    private PlayerView boundView;
    private PadMapper mapper;

    @Override public String getName() { return "Аудиоплеер"; }
    @Override public int getVersion() { return 2; }
    @Override public String getDescription() {
        return "Свой аудиоплеер: песни с устройства, удаление, управление с геймпада и гарнитуры.";
    }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override public void init(Context context) {
        appCtx = context.getApplicationContext();
        h = new Handler(Looper.getMainLooper());
        svc = (context instanceof AccessibilityService) ? (AccessibilityService) context : null;
        ensureSession();
        loadTracks();
        PadMapper m = mapper(context);
        if (svc != null) m.applyAxesCapture(svc);
    }

    @Override public void stop() {
        boundView = null;
        PadMapper pm = mapper;
        if (pm != null) pm.stop(svc);
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
        if (!tracks.isEmpty()) playAt((cur + 1) % tracks.size());
    }

    public void prev() {
        if (!tracks.isEmpty()) playAt((cur - 1 + tracks.size()) % tracks.size());
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
        try {
            int n = appCtx.getContentResolver().delete(t.uri, null, null);
            if (n <= 0) throw new Exception("система отказала");
        } catch (Throwable th) {
            toast("Не удалось удалить");
            return;
        }
        tracks.remove(i);
        if (wasCur) {
            try {
                if (mp != null) { mp.reset(); prepared = false; }
            } catch (Throwable th) { }
            cur = -1;
            if (!tracks.isEmpty()) playAt(Math.min(i, tracks.size() - 1));
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
            ensureSession();
            ensureReceiver();
            playAt(0);
            toast("Играет: " + title);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------- геймпад

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
        m.migrateLegacy("gbtn_");
        mapper = m;
        return m;
    }

    @Override public boolean onKeyEvent(KeyEvent e) {
        PadMapper m = mapper;
        return m != null && m.onKeyEvent(e);
    }

    @Override public void onMotionEvent(MotionEvent e) {
        PadMapper m = mapper;
        if (m != null) m.onMotionEvent(e);
    }

    private void doPerform(String a) {
        if (a == null) return;
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
        r.add(SettingItem.section("Геймпад"));
        r.add(SettingItem.toggle("intercept", "Перехват кнопок",
                "Включено: назначенная кнопка работает только на плеер. Выключено: действие срабатывает, а приложение тоже получает нажатие",
                true));
        r.add(SettingItem.toggle("axes_service", "Стики и курки через службу (Android 14+)",
                "Пока включено, другие приложения не получают движения стиков", true));
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
        } else if ("axes_service".equals(key)) {
            PadMapper m = mapper;
            if (m != null && svc != null) m.applyAxesCapture(svc);
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
            requestFocus();
            refresh();
        }

        @Override protected void onDetachedFromWindow() {
            if (boundView == this) boundView = null;
            super.onDetachedFromWindow();
        }

        @Override public void onWindowFocusChanged(boolean hasFocus) {
            super.onWindowFocusChanged(hasFocus);
            if (hasFocus) refresh();
        }

        private void togglePad() {
            padOpen = !padOpen;
            padBox.removeAllViews();
            if (padOpen) {
                Context c = getContext();
                String hint = (svc != null) ? ""
                        : "Включи службу специальных возможностей — иначе кнопки геймпада не дойдут.";
                padBox.addView(mapper(c).createView(c, hint));
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
            status.setText(svc != null ? ""
                    : "Включи службу специальных возможностей — иначе кнопки геймпада не дойдут.");
        }

        /** Полное обновление: шапка + список песен. */
        void refresh() {
            refreshHead();
            buildTrackRows();
        }

        private void buildTrackRows() {
            trackRows.removeAllViews();
            final Context ctx = getContext();
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
                    @Override public void onClick(View v) { playAt(idx); }
                });
                trackRows.addView(row);
            }
        }
    }
}
