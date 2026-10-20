package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import im.manus.universalhost.IKeyHandler;
import im.manus.universalhost.IMotionHandler;
import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.ISettingsViewProvider;
import im.manus.universalhost.PadMapper;
import im.manus.universalhost.SettingItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Пульт — кнопки геймпада управляют любым плеером.
 *
 * Назначил кнопку — она шлёт обычную медиаклавишу системе (пауза, треки, перемотка) или меняет
 * громкость. Shizuku не нужен: кнопки идут через службу специальных возможностей, стики, курки и
 * крестовина — через неё же на Android 14+. Раскладка настраивается на картинке геймпада, как в
 * модуле Gamepad: у каждой кнопки нажатие, двойное нажатие и долгое. Профилей нет, одна раскладка на всё.
 */
public class Remote implements IPlugin, ISettingsProvider, ISettingsViewProvider, IKeyHandler, IMotionHandler {

    private static final String PREFS = "module_Пульт";

    /** id, подпись. */
    private static final String[][] ACTIONS = {
            {"play_pause", "Пауза / воспроизведение"},
            {"play", "Воспроизведение"},
            {"pause", "Пауза"},
            {"prev", "Предыдущий трек"},
            {"next", "Следующий трек"},
            {"seek_back", "Перемотка назад"},
            {"seek_fwd", "Перемотка вперёд"},
            {"stop", "Стоп"},
            {"vol_down", "Тише"},
            {"vol_up", "Громче"},
            {"mute", "Без звука"},
    };

    private Context appCtx;
    private AccessibilityService svc;
    private PadMapper mapper;

    @Override public String getName() { return "Пульт"; }
    @Override public int getVersion() { return 2; }
    @Override public String getDescription() {
        return "Кнопки геймпада управляют любым плеером: пауза, треки, перемотка, громкость. Схема на картинке геймпада, без Shizuku.";
    }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override public void init(Context context) {
        appCtx = context.getApplicationContext();
        svc = (context instanceof AccessibilityService) ? (AccessibilityService) context : null;
        PadMapper m = mapper(context);
        if (svc != null) m.applyAxesCapture(svc);
    }

    @Override public void stop() {
        PadMapper m = mapper;
        if (m != null) m.stop(svc);
    }

    @Override public Object execute(Map<String, ?> data) { return null; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event, AccessibilityService service) { }
    @Override public Bitmap createIcon(int sizePx) { return null; }

    /** Один PadMapper на модуль; создаётся и до init(), чтобы экран настроек работал при выключенной службе. */
    private PadMapper mapper(Context c) {
        PadMapper m = mapper;
        if (m != null) return m;
        if (appCtx == null) appCtx = c.getApplicationContext();
        SharedPreferences p = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        m = new PadMapper(p, ACTIONS, new PadMapper.Performer() {
            @Override public void perform(String action) { doPerform(action); }
        });
        m.addDefault(KeyEvent.KEYCODE_BUTTON_A, "t", "play_pause");
        m.addDefault(KeyEvent.KEYCODE_BUTTON_X, "t", "prev");
        m.addDefault(KeyEvent.KEYCODE_BUTTON_Y, "t", "next");
        m.addDefault(KeyEvent.KEYCODE_BUTTON_L1, "t", "seek_back");
        m.addDefault(KeyEvent.KEYCODE_BUTTON_R1, "t", "seek_fwd");
        m.migrateLegacy("btn_");
        mapper = m;
        return m;
    }

    // ---------------------------------------------------------- вход

    @Override public boolean onKeyEvent(KeyEvent e) {
        PadMapper m = mapper;
        return m != null && m.onKeyEvent(e);
    }

    @Override public void onMotionEvent(MotionEvent e) {
        PadMapper m = mapper;
        if (m != null) m.onMotionEvent(e);
    }

    // ---------------------------------------------------------- действия

    private void doPerform(String a) {
        Context c = appCtx;
        if (c == null || a == null) return;
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        if (a.equals("vol_up")) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
            return;
        }
        if (a.equals("vol_down")) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
            return;
        }
        if (a.equals("mute")) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
            return;
        }
        int kc = 0;
        if (a.equals("play_pause")) kc = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        else if (a.equals("play")) kc = KeyEvent.KEYCODE_MEDIA_PLAY;
        else if (a.equals("pause")) kc = KeyEvent.KEYCODE_MEDIA_PAUSE;
        else if (a.equals("prev")) kc = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
        else if (a.equals("next")) kc = KeyEvent.KEYCODE_MEDIA_NEXT;
        else if (a.equals("seek_back")) kc = KeyEvent.KEYCODE_MEDIA_REWIND;
        else if (a.equals("seek_fwd")) kc = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
        else if (a.equals("stop")) kc = KeyEvent.KEYCODE_MEDIA_STOP;
        if (kc == 0) return;
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, kc, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, kc, 0));
    }

    // ---------------------------------------------------------- настройки

    @Override public List<SettingItem> getSettingsSchema() {
        List<SettingItem> r = new ArrayList<SettingItem>();
        r.add(SettingItem.section("Кнопки геймпада"));
        r.add(SettingItem.custom("pad", "Геймпад",
                "Коснись кнопки на картинке или нажми её на геймпаде, стик подвигай. У каждой кнопки нажатие, двойное и долгое"));
        r.add(SettingItem.section("Ввод"));
        r.add(SettingItem.toggle("intercept", "Перехват кнопок",
                "Включено: назначенная кнопка работает только на пульт. Выключено: действие срабатывает, а приложение тоже получает нажатие",
                true));
        r.add(SettingItem.toggle("axes_service", "Стики и курки через службу (Android 14+)",
                "Пока включено, другие приложения не получают движения стиков", true));
        r.add(SettingItem.section("Тайминги"));
        r.add(SettingItem.slider("long_ms", "Долгое нажатие, мс", "", 300, 1000, 50, 500));
        r.add(SettingItem.slider("double_ms", "Окно двойного нажатия, мс", "", 150, 600, 50, 300));
        r.add(SettingItem.section("Прочее"));
        r.add(SettingItem.action("pin", "Ярлык на рабочий стол", "Значок модуля на рабочий стол — откроется отдельным окном"));
        r.add(SettingItem.action("reset", "Сбросить назначения", "Вернуть кнопки по умолчанию"));
        return r;
    }

    @Override public void onSettingChanged(String key) {
        if ("reset".equals(key)) {
            PadMapper m = mapper;
            if (m != null) m.reset();
        } else if ("axes_service".equals(key)) {
            PadMapper m = mapper;
            if (m != null && svc != null) m.applyAxesCapture(svc);
        } else if ("pin".equals(key)) {
            try { im.manus.universalhost.ModuleShortcuts.pin(appCtx, this); }
            catch (Throwable t) { }
        }
    }

    @Override public View createSettingsView(Context context, String key) {
        String hint = (svc != null) ? ""
                : "Включи службу специальных возможностей — без неё кнопки не дойдут.";
        return mapper(context).createView(context, hint);
    }
}
