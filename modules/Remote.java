package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import im.manus.universalhost.IKeyHandler;
import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.ISettingsViewProvider;
import im.manus.universalhost.SettingItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Пульт — кнопки геймпада управляют любым плеером.
 *
 * Просто, без лишних настроек: назначил кнопку — она шлёт обычную медиаклавишу
 * системе (пауза, треки, перемотка). Shizuku не нужен: кнопки идут через службу
 * специальных возможностей, профилей нет — одна раскладка на всё.
 */
public class Remote implements IPlugin, ISettingsProvider, ISettingsViewProvider, IKeyHandler {

    private static final String PREFS = "module_Пульт";

    private static final String[] ACT_KEYS = {
            "play_pause", "play", "pause", "prev", "next",
            "seek_back", "seek_fwd", "stop", "vol_down", "vol_up", "mute"
    };
    private static final String[] ACT_NAMES = {
            "Пауза / воспроизведение", "Воспроизведение", "Пауза", "Предыдущий трек", "Следующий трек",
            "Перемотка назад", "Перемотка вперёд", "Стоп", "Тише", "Громче", "Без звука"
    };
    /** media-код клавиши; 0 = громкость (обрабатывается отдельно) */
    private static final int[] ACT_MEDIA = {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_STOP, 0, 0, 0
    };
    /** {код кнопки, индекс действия} по умолчанию */
    private static final int[][] DEFAULTS = {
            {KeyEvent.KEYCODE_BUTTON_A, 0},
            {KeyEvent.KEYCODE_BUTTON_X, 3},
            {KeyEvent.KEYCODE_BUTTON_Y, 4},
            {KeyEvent.KEYCODE_BUTTON_L1, 5},
            {KeyEvent.KEYCODE_BUTTON_R1, 6},
    };

    /** Пока открыт экран настроек, кнопки уходят в обучение, а не в действия. */
    private static boolean uiOpen = false;

    private Context appCtx;
    private AccessibilityService svc;

    @Override public String getName() { return "Пульт"; }
    @Override public int getVersion() { return 1; }
    @Override public String getDescription() {
        return "Кнопки геймпада управляют любым плеером: пауза, треки, перемотка. Назначил — работает.";
    }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override public void init(Context context) {
        appCtx = context.getApplicationContext();
        svc = (context instanceof AccessibilityService) ? (AccessibilityService) context : null;
    }
    @Override public Object execute(Map<String, Object> data) { return null; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event, AccessibilityService service) { }
    @Override public Bitmap createIcon(int sizePx) { return null; }

    private SharedPreferences prefs() {
        Context c = appCtx;
        return c == null ? null : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------- назначения

    private static int defaultFor(String action) {
        for (int[] d : DEFAULTS) if (ACT_KEYS[d[1]].equals(action)) return d[0];
        return -1;
    }

    /** Какая кнопка назначена на действие; -1 = не назначена. */
    int buttonFor(String action) {
        SharedPreferences p = prefs();
        if (p == null || !p.contains("btn_" + action)) return defaultFor(action);
        return p.getInt("btn_" + action, -1);
    }

    /** Назначить кнопку; -1 = снять. Одна кнопка — одно действие. */
    void setButton(String action, int code) {
        SharedPreferences p = prefs();
        if (p == null) return;
        SharedPreferences.Editor e = p.edit();
        for (String a : ACT_KEYS) {
            String k = "btn_" + a;
            int cur = p.contains(k) ? p.getInt(k, -1) : defaultFor(a);
            if (cur == code && code >= 0) e.putInt(k, -1);
        }
        e.putInt("btn_" + action, code);
        e.apply();
    }

    private String actionFor(int code) {
        for (String a : ACT_KEYS) if (buttonFor(a) == code) return a;
        return null;
    }

    static boolean isPadKey(KeyEvent e) {
        int s = e.getSource();
        if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) return true;
        int c = e.getKeyCode();
        if (KeyEvent.isGamepadButton(c)) return true;
        return c >= KeyEvent.KEYCODE_DPAD_UP && c <= KeyEvent.KEYCODE_DPAD_CENTER;
    }

    // ---------------------------------------------------------- вход: кнопки

    @Override public boolean onKeyEvent(KeyEvent e) {
        try {
            if (uiOpen) return false;
            int code = e.getKeyCode();
            if (!isPadKey(e)) return false;
            String a = actionFor(code);
            if (a == null) return false;
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) perform(a);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void perform(String a) {
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
        for (int i = 0; i < ACT_KEYS.length; i++) if (ACT_KEYS[i].equals(a)) kc = ACT_MEDIA[i];
        if (kc == 0) return;
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, kc, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, kc, 0));
    }

    // ---------------------------------------------------------- настройки

    @Override public List<SettingItem> getSettingsSchema() {
        List<SettingItem> r = new ArrayList<SettingItem>();
        r.add(SettingItem.section("Кнопки геймпада"));
        r.add(SettingItem.custom("pad", "Назначение кнопок", "Нажми «Назначить» и нажми кнопку геймпада"));
        r.add(SettingItem.action("reset", "Сбросить назначения", "Вернуть кнопки по умолчанию"));
        return r;
    }

    @Override public void onSettingChanged(String key) {
        if ("reset".equals(key)) {
            SharedPreferences p = prefs();
            if (p != null) p.edit().clear().apply();
        }
    }

    @Override public View createSettingsView(Context context, String key) {
        return new PadView(context);
    }

    static String keyName(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A: return "A";
            case KeyEvent.KEYCODE_BUTTON_B: return "B";
            case KeyEvent.KEYCODE_BUTTON_X: return "X";
            case KeyEvent.KEYCODE_BUTTON_Y: return "Y";
            case KeyEvent.KEYCODE_BUTTON_L1: return "LB";
            case KeyEvent.KEYCODE_BUTTON_R1: return "RB";
            case KeyEvent.KEYCODE_BUTTON_L2: return "LT";
            case KeyEvent.KEYCODE_BUTTON_R2: return "RT";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "Левый стик";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "Правый стик";
            case KeyEvent.KEYCODE_BUTTON_START: return "Start";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "Select";
            case KeyEvent.KEYCODE_BUTTON_MODE: return "Mode";
            case KeyEvent.KEYCODE_DPAD_UP: return "Крестовина вверх";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "Крестовина вниз";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "Крестовина влево";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "Крестовина вправо";
            case KeyEvent.KEYCODE_DPAD_CENTER: return "Крестовина (центр)";
            default: return "код " + code;
        }
    }

    static String actName(String key) {
        for (int i = 0; i < ACT_KEYS.length; i++) if (ACT_KEYS[i].equals(key)) return ACT_NAMES[i];
        return key;
    }

    /** Экран назначения: строка на действие, обучение нажатием кнопки геймпада. */
    class PadView extends LinearLayout {
        private final TextView status;
        private final LinearLayout rows;
        private String learning = null;

        PadView(Context ctx) {
            super(ctx);
            setOrientation(VERTICAL);
            int p = dp(12);
            setPadding(p, p, p, p);
            setFocusable(true);
            setFocusableInTouchMode(true);
            status = new TextView(ctx);
            status.setTextSize(14);
            addView(status);
            rows = new LinearLayout(ctx);
            rows.setOrientation(VERTICAL);
            addView(rows, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            refresh();
        }

        private int dp(int v) {
            return Math.round(v * getResources().getDisplayMetrics().density);
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            uiOpen = true;
            requestFocus();
        }

        @Override protected void onDetachedFromWindow() {
            uiOpen = false;
            super.onDetachedFromWindow();
        }

        @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (learning != null && isPadKey(event) && event.getRepeatCount() == 0) {
                setButton(learning, keyCode);
                learning = null;
                refresh();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        void refresh() {
            rows.removeAllViews();
            Context ctx = getContext();
            for (int i = 0; i < ACT_KEYS.length; i++) {
                final String ak = ACT_KEYS[i];
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int rp = dp(6);
                row.setPadding(0, rp, 0, rp);

                TextView name = new TextView(ctx);
                name.setText(ACT_NAMES[i]);
                name.setTextSize(15);
                row.addView(name, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

                int btn = buttonFor(ak);
                TextView cur = new TextView(ctx);
                cur.setText(btn < 0 ? "—" : keyName(btn));
                cur.setTextSize(15);
                int cp = dp(8);
                cur.setPadding(cp, 0, cp, 0);
                row.addView(cur);

                Button b = new Button(ctx);
                final boolean isL = ak.equals(learning);
                b.setText(isL ? "Жду…" : "Назначить");
                b.setOnClickListener(new OnClickListener() {
                    @Override public void onClick(View v) {
                        learning = isL ? null : ak;
                        requestFocus();
                        refresh();
                    }
                });
                row.addView(b);

                Button x = new Button(ctx);
                x.setText("✕");
                x.setOnClickListener(new OnClickListener() {
                    @Override public void onClick(View v) {
                        setButton(ak, -1);
                        if (ak.equals(learning)) learning = null;
                        refresh();
                    }
                });
                row.addView(x);
                rows.addView(row);
            }
            String s = (svc != null)
                    ? "Служба специальных возможностей включена."
                    : "Включи службу специальных возможностей — без неё кнопки не дойдут.";
            if (learning != null) s += "\nНажми кнопку геймпада для «" + actName(learning) + "»…";
            status.setText(s);
        }
    }
}
