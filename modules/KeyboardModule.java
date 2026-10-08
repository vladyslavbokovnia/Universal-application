package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.SettingItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keyboard — собственная прозрачная клавиатура. Работает внутри CoreInputMethodService хоста: хост передаёт
 * модулю команды createInputView / startInput / finishInput / touchRegion / destroy и саму службу.
 *
 *  - клавиши полупрозрачные, окно клавиатуры прозрачное; в режиме «поверх содержимого» (overlay_mode,
 *    по умолчанию включён) приложение под клавиатурой не сдвигается;
 *  - раскладки русская, украинская и английская (включаются в настройках), цифровой ряд, символы в двух
 *    страницах, автозаглавная в начале предложения, двойное нажатие Shift — Caps Lock;
 *  - долгое нажатие «е» даёт «ё», «г» — «ґ»; Backspace повторяется при удержании;
 *  - клавиша выбора клавиатуры (системное окно выбора) и клавиша сворачивания в одну маленькую
 *    прозрачную кнопку; тап по кнопке возвращает клавиатуру;
 *  - подсказок слов и автокоррекции нет.
 *
 * Чтобы клавиатура появилась: включить «Universal host» в настройках системы (кнопка «Включить
 * клавиатуру в системе» ниже), выбрать её кнопкой «Выбрать клавиатуру» и включить этот модуль.
 */
public class KeyboardModule implements IPlugin, ISettingsProvider {

    private static final String PREFS = "module_Keyboard";          // настройки, которые пишет хост

    private static final int T_CHAR = 0;
    private static final int T_SHIFT = 1;
    private static final int T_DEL = 2;
    private static final int T_ENTER = 3;
    private static final int T_SPACE = 4;
    private static final int T_SYMS = 5;
    private static final int T_LANG = 6;
    private static final int T_PICKER = 7;
    private static final int T_COLLAPSE = 8;

    private static final long LONG_PRESS_MS = 450L;
    private static final long REPEAT_DELAY_MS = 400L;
    private static final long REPEAT_STEP_MS = 50L;

    private Context ctx;                    // служба специальных возможностей, если модуль включён
    private InputMethodService service;
    private EditorInfo info;
    private Root root;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> langs = new ArrayList<String>();

    private int keyHeightDp = 46;
    private int keyAlpha = 45;
    private int theme;
    private boolean numberRow = true;
    private int collapsedDp = 44;
    private boolean haptic = true;

    // ---------------------------------------------------------------- IPlugin

    @Override public String getName() { return "Keyboard"; }
    @Override public int getVersion() { return 1; }
    @Override public String getDescription() { return "Прозрачная клавиатура: RU/UA/EN, сворачивание в кнопку"; }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override public void init(Context context) { ctx = context; }
    @Override public void stop() { }
    @Override public void onAccessibilityEvent(AccessibilityEvent event, AccessibilityService svc) { }

    @Override
    public Object execute(Map<String, ?> data) {
        if (data == null) return null;
        Object cmd = data.get("cmd");
        Object s = data.get("service");
        if (s instanceof InputMethodService) service = (InputMethodService) s;
        if (!(cmd instanceof String) || service == null) return null;
        String c = (String) cmd;
        if ("createInputView".equals(c)) {
            return build();
        } else if ("startInput".equals(c)) {
            Object in = data.get("info");
            info = in instanceof EditorInfo ? (EditorInfo) in : null;
            Object r = data.get("restarting");
            onStart(r instanceof Boolean && (Boolean) r);
        } else if ("finishInput".equals(c)) {
            if (root != null) root.keys.cancelAll();
        } else if ("touchRegion".equals(c)) {
            return root == null ? null : root.touchRegion();
        } else if ("destroy".equals(c)) {
            if (root != null) root.keys.cancelAll();
            root = null;
            info = null;
            service = null;
        }
        return null;
    }

    // ---------------------------------------------------------------- настройки

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private void loadConfig(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        keyHeightDp = clamp(p.getInt("key_height", 46), 36, 64);
        keyAlpha = clamp(p.getInt("key_alpha", 45), 10, 100);
        theme = p.getInt("theme", 0);
        numberRow = p.getBoolean("number_row", true);
        collapsedDp = clamp(p.getInt("collapsed_size", 44), 32, 72);
        haptic = p.getBoolean("haptic", true);
        langs.clear();
        if (p.getBoolean("lang_ru", true)) langs.add("ru");
        if (p.getBoolean("lang_uk", false)) langs.add("uk");
        if (p.getBoolean("lang_en", true)) langs.add("en");
        if (langs.isEmpty()) langs.add("en");
    }

    @Override
    public List<SettingItem> getSettingsSchema() {
        List<SettingItem> l = new ArrayList<SettingItem>();
        l.add(SettingItem.section("Вид"));
        l.add(SettingItem.slider("key_height", "Высота клавиш", "dp", 36, 64, 2, 46));
        l.add(SettingItem.slider("key_alpha", "Непрозрачность клавиш", "%", 10, 100, 5, 45));
        l.add(SettingItem.choice("theme", "Клавиши", "", Arrays.asList("Тёмные", "Светлые"), 0));
        l.add(SettingItem.toggle("number_row", "Цифровой ряд", "", true));
        l.add(SettingItem.slider("collapsed_size", "Размер кнопки в свёрнутом виде", "dp", 32, 72, 4, 44));
        l.add(SettingItem.toggle("haptic", "Виброотклик", "", true));
        l.add(SettingItem.section("Режим"));
        l.add(SettingItem.toggle("overlay_mode", "Поверх содержимого", "Приложение под клавиатурой не сдвигается и не сжимается", true));
        l.add(SettingItem.section("Языки"));
        l.add(SettingItem.toggle("lang_ru", "Русский", "", true));
        l.add(SettingItem.toggle("lang_uk", "Украинский", "", false));
        l.add(SettingItem.toggle("lang_en", "Английский", "", true));
        l.add(SettingItem.section("Клавиатуры"));
        l.add(SettingItem.action("enable", "Включить клавиатуру в системе", "Откроет системные настройки способов ввода"));
        l.add(SettingItem.action("pick", "Выбрать клавиатуру", "Системное окно выбора (Gboard, эта и другие)"));
        return l;
    }

    @Override
    public void onSettingChanged(String key) {
        if ("pick".equals(key)) { showPicker(ctx != null ? ctx : service); return; }
        if ("enable".equals(key)) { openImeSettings(ctx != null ? ctx : service); return; }
        if (service != null && root != null) {
            try { service.setInputView(build()); } catch (Throwable ignored) { }
        }
    }

    @Override
    public Bitmap createIcon(int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(size * 0.05f);
        c.drawRoundRect(new RectF(size * 0.1f, size * 0.28f, size * 0.9f, size * 0.72f), size * 0.07f, size * 0.07f, p);
        p.setStyle(Paint.Style.FILL);
        for (int row = 0; row < 2; row++) {
            for (int i = 0; i < 5; i++) c.drawCircle(size * (0.24f + i * 0.13f), size * (0.42f + row * 0.13f), size * 0.025f, p);
        }
        return b;
    }

    private void showPicker(Context c) {
        if (c == null) return;
        try {
            ((InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE)).showInputMethodPicker();
        } catch (Throwable ignored) { }
    }

    private void openImeSettings(Context c) {
        if (c == null) return;
        try {
            c.startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- ввод

    private View build() {
        loadConfig(service);
        root = new Root(service);
        onStart(false);
        return root;
    }

    private void onStart(boolean restarting) {
        if (root == null) return;
        if (!restarting) root.setCollapsed(false);          // новое поле ввода — клавиатура снова развёрнута
        root.keys.onNewField(info);
    }

    private InputConnection ic() { return service == null ? null : service.getCurrentInputConnection(); }

    private void emit(String s) {
        InputConnection c = ic();
        if (c != null) c.commitText(s, 1);
    }

    private void deleteOne() {
        InputConnection c = ic();
        if (c == null) {
            if (service != null) service.sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DEL);
            return;
        }
        CharSequence selected = c.getSelectedText(0);
        if (selected != null && selected.length() > 0) c.commitText("", 1);
        else c.deleteSurroundingText(1, 0);
    }

    private void doEnter() {
        InputConnection c = ic();
        int action = info == null ? 0 : (info.imeOptions & EditorInfo.IME_MASK_ACTION);
        boolean noAction = info != null && (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        boolean multiline = info != null && (info.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        if (c != null && !multiline && !noAction && action != EditorInfo.IME_ACTION_NONE
                && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            c.performEditorAction(action);
        } else if (service != null) {
            service.sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_ENTER);
        }
    }

    private boolean isPassword() {
        if (info == null) return false;
        int cls = info.inputType & InputType.TYPE_MASK_CLASS;
        int var = info.inputType & InputType.TYPE_MASK_VARIATION;
        if (cls == InputType.TYPE_CLASS_TEXT) {
            return var == InputType.TYPE_TEXT_VARIATION_PASSWORD || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                    || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        }
        return cls == InputType.TYPE_CLASS_NUMBER && var == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }

    // ---------------------------------------------------------------- раскладки

    private static List<String> letters(String s) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < s.length(); i++) out.add(String.valueOf(s.charAt(i)));
        return out;
    }

    private static String[] layout(String code) {
        if ("ru".equals(code)) return new String[]{"йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"};
        if ("uk".equals(code)) return new String[]{"йцукенгшщзхї", "фівапролджє", "ячсмитьбю"};
        return new String[]{"qwertyuiop", "asdfghjkl", "zxcvbnm"};
    }

    private static String langLabel(String code) {
        if ("ru".equals(code)) return "RU";
        if ("uk".equals(code)) return "UA";
        return "EN";
    }

    private static String altFor(String code, String ch) {
        if ("ru".equals(code) && "е".equals(ch)) return "ё";
        if ("uk".equals(code) && "г".equals(ch)) return "ґ";
        return null;
    }

    // ---------------------------------------------------------------- вью

    private static final class Key {
        final int type;
        final String base;
        final String alt;
        final float weight;
        final RectF r = new RectF();
        boolean pressed;

        Key(int type, String base, String alt, float weight) {
            this.type = type;
            this.base = base;
            this.alt = alt;
            this.weight = weight;
        }
    }

    private final class Root extends FrameLayout {
        final Keys keys;
        final Fab fab;

        Root(Context c) {
            super(c);
            keys = new Keys(c);
            fab = new Fab(c);
            int size = (int) (collapsedDp * c.getResources().getDisplayMetrics().density);
            int margin = (int) (8 * c.getResources().getDisplayMetrics().density);
            addView(keys, new FrameLayout.LayoutParams(-1, -2));
            FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(size, size, Gravity.BOTTOM | Gravity.END);
            fl.setMargins(margin, margin, margin, margin);
            addView(fab, fl);
            setCollapsed(false);
        }

        void setCollapsed(boolean collapsed) {
            keys.setVisibility(collapsed ? View.GONE : View.VISIBLE);
            fab.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        }

        boolean isCollapsed() { return fab.getVisibility() == View.VISIBLE; }

        /** Область, принимающая касания в режиме «поверх содержимого»: пока свёрнуто — только кнопка. */
        Rect touchRegion() {
            if (!isCollapsed() || fab.getWidth() == 0) return null;
            int[] loc = new int[2];
            fab.getLocationInWindow(loc);
            return new Rect(loc[0], loc[1], loc[0] + fab.getWidth(), loc[1] + fab.getHeight());
        }
    }

    private final class Fab extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);

        Fab(Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            setClickable(true);
            setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (root != null) root.setCollapsed(false);
                }
            });
        }

        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - 2f;
            boolean dark = theme == 0;
            fill.setColor(Color.argb((int) (Math.min(100, keyAlpha + 15) * 2.55f), dark ? 0 : 255, dark ? 0 : 255, dark ? 0 : 255));
            c.drawCircle(cx, cy, r, fill);
            line.setColor(dark ? Color.WHITE : Color.BLACK);
            line.setStrokeWidth(Math.max(2f, r * 0.1f));
            c.drawCircle(cx, cy, r, line);
            c.drawLine(cx - r * 0.38f, cy + r * 0.18f, cx, cy - r * 0.22f, line);       // шеврон вверх
            c.drawLine(cx, cy - r * 0.22f, cx + r * 0.38f, cy + r * 0.18f, line);
        }
    }

    private final class Press {
        final Key key;
        boolean consumed;
        Runnable task;

        Press(Key key) { this.key = key; }
    }

    private final class Keys extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<List<Key>> rows = new ArrayList<List<Key>>();
        private final SparseArray<Press> pointers = new SparseArray<Press>();
        private final float gap;
        private int page;          // 0 — буквы, 1 и 2 — символы
        private int lang;
        private int shift;         // 0 выкл, 1 одна заглавная, 2 Caps Lock
        private long lastShiftTap;

        Keys(Context c) {
            super(c);
            gap = 4f * c.getResources().getDisplayMetrics().density;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            text.setTextAlign(Paint.Align.CENTER);
            rebuildRows();
        }

        private float keyH() { return keyHeightDp * getResources().getDisplayMetrics().density; }

        // ---- раскладка

        private List<Key> charRow(String chars, String code) {
            List<Key> row = new ArrayList<Key>();
            for (String ch : letters(chars)) row.add(new Key(T_CHAR, ch, code == null ? null : altFor(code, ch), 1f));
            return row;
        }

        private void rebuildRows() {
            rows.clear();
            String code = langs.get(lang % langs.size());
            if (page == 0) {
                String[] l = layout(code);
                if (numberRow) rows.add(charRow("1234567890", null));
                rows.add(charRow(l[0], code));
                rows.add(charRow(l[1], code));
                List<Key> r3 = new ArrayList<Key>();
                r3.add(new Key(T_SHIFT, "", null, 1.5f));
                r3.addAll(charRow(l[2], code));
                r3.add(new Key(T_DEL, "", null, 1.5f));
                rows.add(r3);
            } else {
                rows.add(charRow(page == 1 ? "1234567890" : "[]{}<>^~`|", null));
                rows.add(charRow(page == 1 ? "@#$%&-+()/" : "\u00a3\u20ac\u00a5\u00a2\u00b0=\\_\u00a7\u00a9", null));
                List<Key> r3 = new ArrayList<Key>();
                r3.add(new Key(T_SHIFT, page == 1 ? "=\\<" : "1/2", null, 1.5f));
                r3.addAll(charRow(page == 1 ? "*\"':;!?" : "\u00ae\u2122\u00b1\u00f7\u00d7\u00bf\u00a1", null));
                r3.add(new Key(T_DEL, "", null, 1.5f));
                rows.add(r3);
            }
            List<Key> b = new ArrayList<Key>();
            b.add(new Key(T_SYMS, page == 0 ? "?123" : "ABC", null, 1.4f));
            if (langs.size() > 1) b.add(new Key(T_LANG, langLabel(code), null, 1f));
            b.add(new Key(T_PICKER, "", null, 1f));
            b.add(new Key(T_CHAR, ",", null, 0.8f));
            b.add(new Key(T_SPACE, " ", null, langs.size() > 1 ? 3.8f : 4.8f));
            b.add(new Key(T_CHAR, ".", null, 0.8f));
            b.add(new Key(T_COLLAPSE, "", null, 1f));
            b.add(new Key(T_ENTER, "", null, 1.4f));
            rows.add(b);
            requestLayout();
            invalidate();
        }

        void onNewField(EditorInfo ei) {
            cancelAll();
            int cls = ei == null ? InputType.TYPE_CLASS_TEXT : (ei.inputType & InputType.TYPE_MASK_CLASS);
            boolean numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE
                    || cls == InputType.TYPE_CLASS_DATETIME;
            page = numeric ? 1 : 0;
            shift = 0;
            rebuildRows();
            autoCaps();
        }

        void cancelAll() {
            for (int i = 0; i < pointers.size(); i++) {
                Press p = pointers.valueAt(i);
                p.key.pressed = false;
                if (p.task != null) main.removeCallbacks(p.task);
            }
            pointers.clear();
            invalidate();
        }

        private void autoCaps() {
            if (page != 0 || shift == 2 || info == null || isPassword()) return;
            InputConnection c = ic();
            if (c != null && c.getCursorCapsMode(info.inputType) != 0 && shift == 0) shift = 1;
            invalidate();
        }

        // ---- размеры

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec);
            float h = rows.size() * keyH() + (rows.size() + 1) * gap;
            setMeasuredDimension(w, (int) Math.ceil(h));
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            layoutKeys(w);
        }

        private void layoutKeys(int w) {
            float maxUnits = 0f;
            for (int i = 0; i < rows.size() - 1; i++) maxUnits = Math.max(maxUnits, sum(rows.get(i)));
            float kh = keyH();
            float y = gap;
            for (int i = 0; i < rows.size(); i++) {
                List<Key> row = rows.get(i);
                boolean bottom = i == rows.size() - 1;
                float units = bottom ? sum(row) : maxUnits;
                float unitW = (w - 2 * gap) / units;
                float x = (w - sum(row) * unitW) / 2f;
                for (Key k : row) {
                    float kw = k.weight * unitW;
                    k.r.set(x + gap / 2f, y, x + kw - gap / 2f, y + kh);
                    x += kw;
                }
                y += kh + gap;
            }
        }

        private float sum(List<Key> row) {
            float s = 0f;
            for (Key k : row) s += k.weight;
            return s;
        }

        private Key hit(float x, float y) {
            float pad = gap / 2f;
            for (List<Key> row : rows) {
                for (Key k : row) {
                    if (x >= k.r.left - pad && x <= k.r.right + pad && y >= k.r.top - pad && y <= k.r.bottom + pad) return k;
                }
            }
            return null;
        }

        // ---- касания

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    int idx = e.getActionIndex();
                    down(e.getPointerId(idx), e.getX(idx), e.getY(idx), true);
                    return true;
                }
                case MotionEvent.ACTION_MOVE:
                    for (int i = 0; i < e.getPointerCount(); i++) move(e.getPointerId(i), e.getX(i), e.getY(i));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    up(e.getPointerId(e.getActionIndex()), true);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    cancelAll();
                    return true;
                default:
                    return true;
            }
        }

        private void down(int id, float x, float y, boolean buzz) {
            Key k = hit(x, y);
            if (k == null) return;
            final Press p = new Press(k);
            pointers.put(id, p);
            k.pressed = true;
            if (buzz && haptic) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (k.type == T_DEL) {
                p.task = new Runnable() {
                    @Override public void run() {
                        p.consumed = true;
                        deleteOne();
                        main.postDelayed(this, REPEAT_STEP_MS);
                    }
                };
                main.postDelayed(p.task, REPEAT_DELAY_MS);
            } else if (k.alt != null) {
                p.task = new Runnable() {
                    @Override public void run() {
                        p.consumed = true;
                        String s = (page == 0 && shift != 0) ? p.key.alt.toUpperCase(Locale.getDefault()) : p.key.alt;
                        emit(s);
                        if (shift == 1) shift = 0;
                        autoCaps();
                        invalidate();
                    }
                };
                main.postDelayed(p.task, LONG_PRESS_MS);
            }
            invalidate();
        }

        private void move(int id, float x, float y) {
            Press p = pointers.get(id);
            if (p == null) return;
            Key nk = hit(x, y);
            if (nk == p.key) return;
            release(p);
            pointers.remove(id);
            if (nk != null) down(id, x, y, false);
        }

        private void release(Press p) {
            p.key.pressed = false;
            if (p.task != null) main.removeCallbacks(p.task);
        }

        private void up(int id, boolean commitKey) {
            Press p = pointers.get(id);
            if (p == null) return;
            release(p);
            pointers.remove(id);
            if (commitKey && !p.consumed) commit(p.key);
            invalidate();
        }

        private void commit(Key k) {
            switch (k.type) {
                case T_CHAR: {
                    String s = k.base;
                    if (page == 0 && shift != 0) s = s.toUpperCase(Locale.getDefault());
                    emit(s);
                    if (shift == 1) shift = 0;
                    autoCaps();
                    break;
                }
                case T_SPACE:
                    emit(" ");
                    autoCaps();
                    break;
                case T_DEL:
                    deleteOne();
                    autoCaps();
                    break;
                case T_ENTER:
                    doEnter();
                    shift = 0;
                    autoCaps();
                    break;
                case T_SHIFT:
                    if (page == 0) {
                        long now = SystemClock.uptimeMillis();
                        if (shift == 2) shift = 0;
                        else if (shift == 1 && now - lastShiftTap < 400L) shift = 2;
                        else shift = shift == 0 ? 1 : 0;
                        lastShiftTap = now;
                    } else {
                        page = page == 1 ? 2 : 1;
                        rebuildRows();
                    }
                    break;
                case T_SYMS:
                    page = page == 0 ? 1 : 0;
                    rebuildRows();
                    if (page == 0) autoCaps();
                    break;
                case T_LANG:
                    lang = (lang + 1) % langs.size();
                    rebuildRows();
                    break;
                case T_PICKER:
                    showPicker(service);
                    break;
                case T_COLLAPSE:
                    if (root != null) root.setCollapsed(true);
                    break;
                default:
                    break;
            }
            invalidate();
        }

        // ---- рисование

        @Override protected void onDraw(Canvas c) {
            boolean dark = theme == 0;
            int base = dark ? 0 : 255;
            int fg = dark ? Color.WHITE : Color.BLACK;
            float kh = keyH();
            text.setTextSize(kh * 0.42f);
            text.setColor(fg);
            float radius = kh * 0.18f;
            line.setStrokeWidth(Math.max(1f, kh * 0.025f));
            for (List<Key> row : rows) {
                for (Key k : row) {
                    boolean special = k.type != T_CHAR && k.type != T_SPACE;
                    int a = (int) (Math.min(100, keyAlpha + (special ? 12 : 0)) * 2.55f);
                    if (k.pressed) {
                        fill.setColor(Color.argb(Math.min(255, a + 110), dark ? 80 : 170, dark ? 80 : 170, dark ? 80 : 170));
                    } else {
                        fill.setColor(Color.argb(a, base, base, base));
                    }
                    c.drawRoundRect(k.r, radius, radius, fill);
                    line.setColor(Color.argb(70, dark ? 255 : 0, dark ? 255 : 0, dark ? 255 : 0));
                    c.drawRoundRect(k.r, radius, radius, line);
                    drawKey(c, k, fg);
                }
            }
        }

        private void drawKey(Canvas c, Key k, int fg) {
            float cx = k.r.centerX(), cy = k.r.centerY();
            float s = Math.min(k.r.width(), k.r.height()) * 0.5f;
            line.setColor(fg);
            line.setStrokeWidth(Math.max(2f, s * 0.12f));
            switch (k.type) {
                case T_SHIFT:
                    if (page == 0) {
                        c.drawLine(cx, cy + s * 0.5f, cx, cy - s * 0.5f, line);
                        c.drawLine(cx, cy - s * 0.5f, cx - s * 0.45f, cy, line);
                        c.drawLine(cx, cy - s * 0.5f, cx + s * 0.45f, cy, line);
                        if (shift == 2) c.drawLine(cx - s * 0.45f, cy + s * 0.8f, cx + s * 0.45f, cy + s * 0.8f, line);
                        else if (shift == 1) c.drawCircle(cx, cy + s * 0.8f, s * 0.08f, line);
                    } else {
                        label(c, k.base, cx, cy);
                    }
                    break;
                case T_DEL: {
                    Path p = new Path();
                    p.moveTo(cx - s * 0.8f, cy);
                    p.lineTo(cx - s * 0.3f, cy - s * 0.5f);
                    p.lineTo(cx + s * 0.8f, cy - s * 0.5f);
                    p.lineTo(cx + s * 0.8f, cy + s * 0.5f);
                    p.lineTo(cx - s * 0.3f, cy + s * 0.5f);
                    p.close();
                    c.drawPath(p, line);
                    c.drawLine(cx - s * 0.05f, cy - s * 0.2f, cx + s * 0.45f, cy + s * 0.2f, line);
                    c.drawLine(cx + s * 0.45f, cy - s * 0.2f, cx - s * 0.05f, cy + s * 0.2f, line);
                    break;
                }
                case T_ENTER:
                    c.drawLine(cx + s * 0.7f, cy - s * 0.5f, cx + s * 0.7f, cy + s * 0.1f, line);
                    c.drawLine(cx + s * 0.7f, cy + s * 0.1f, cx - s * 0.6f, cy + s * 0.1f, line);
                    c.drawLine(cx - s * 0.6f, cy + s * 0.1f, cx - s * 0.15f, cy - s * 0.3f, line);
                    c.drawLine(cx - s * 0.6f, cy + s * 0.1f, cx - s * 0.15f, cy + s * 0.5f, line);
                    break;
                case T_PICKER:
                    c.drawCircle(cx, cy, s * 0.6f, line);
                    c.drawOval(new RectF(cx - s * 0.28f, cy - s * 0.6f, cx + s * 0.28f, cy + s * 0.6f), line);
                    c.drawLine(cx - s * 0.6f, cy, cx + s * 0.6f, cy, line);
                    break;
                case T_COLLAPSE:
                    c.drawLine(cx - s * 0.5f, cy - s * 0.2f, cx, cy + s * 0.3f, line);
                    c.drawLine(cx, cy + s * 0.3f, cx + s * 0.5f, cy - s * 0.2f, line);
                    break;
                case T_SPACE: {
                    text.setTextSize(keyH() * 0.26f);
                    text.setAlpha(150);
                    label(c, langLabel(langs.get(lang % langs.size())), cx, cy);
                    text.setAlpha(255);
                    text.setTextSize(keyH() * 0.42f);
                    break;
                }
                case T_SYMS:
                case T_LANG:
                    text.setTextSize(keyH() * 0.34f);
                    label(c, k.base, cx, cy);
                    text.setTextSize(keyH() * 0.42f);
                    break;
                default: {
                    String s2 = k.base;
                    if (page == 0 && shift != 0) s2 = s2.toUpperCase(Locale.getDefault());
                    label(c, s2, cx, cy);
                    break;
                }
            }
        }

        private void label(Canvas c, String s, float cx, float cy) {
            Paint.FontMetrics fm = text.getFontMetrics();
            c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, text);
        }
    }
}
