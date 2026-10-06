package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;
import android.widget.TextView;
import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.SettingItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AnTTS как DEX-модуль (порт github.com/vladyslavbokovnia/AnTTS, MIT).
 *
 * - полоса сверху: чёрный фон, тонкая белая цифра месячного мобильного трафика (ГБ), полоса прогресса;
 *   тап — старт/пауза, горизонтальный свайп — к соседнему блоку текста;
 * - озвучка основного содержимого страницы блок за блоком, с прокруткой (плавной или по страницам);
 * - озвучка поля ввода: предложение у курсора (после голосового ввода или по тапу на полосу);
 * - учёт мобильного трафика за период с выбранного дня месяца (нужен доступ к статистике использования).
 */
public class AnTTSModule implements IPlugin, ISettingsProvider {

    private static final String PREFS = "module_AnTTS";            // настройки, которые пишет хост
    private static final long REFRESH_DEBOUNCE_MS = 200L;
    private static final long TRAFFIC_REFRESH_MS = 2000L;
    private static final int MAX_INVISIBLE_RETRIES = 20;

    // значения по умолчанию совпадают со схемой настроек ниже
    private SharedPreferences cfg;
    private int barHeightDp = 28;
    private int backgroundAlpha = 82;
    private int progressAlpha = 90;
    private int trafficStartDay = 1;
    private boolean smoothScroll = true;
    private boolean speakInput = true;

    private AccessibilityService svc;
    private Handler main;
    private TextToSpeech tts;
    private boolean ttsReady;
    private Bar bar;
    private volatile boolean active;

    private final List<Block> blocks = new ArrayList<Block>();
    private int current;
    private boolean reading;
    private boolean speakingInput;
    private String lastSnapshot = "";
    private AccessibilityNodeInfo pendingInputNode;
    private int lastRecordedCursor = -1;
    private long inputRevision;
    private int inputSentenceIndex;
    private String activeUtteranceId;
    private long speechGeneration;
    private int invisibleRetries;

    // ---------------------------------------------------------------- IPlugin

    @Override public String getName() { return "AnTTS"; }
    @Override public int getVersion() { return 1; }
    @Override public String getDescription() {
        return "Озвучка страницы и поля ввода, полоса прогресса, учёт мобильного трафика";
    }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override
    public void init(Context context) {
        stop();
        if (!(context instanceof AccessibilityService)) return;   // нужны окно и дерево узлов службы
        svc = (AccessibilityService) context;
        main = new Handler(Looper.getMainLooper());
        cfg = svc.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadConfig();
        blocks.clear();
        current = 0;
        reading = false;
        speakingInput = false;
        lastSnapshot = "";
        pendingInputNode = null;
        lastRecordedCursor = -1;
        invisibleRetries = 0;
        active = true;
        try {
            tts = new TextToSpeech(svc, new TextToSpeech.OnInitListener() {
                @Override public void onInit(int status) { onTtsInit(status); }
            });
            bar = new Bar();
            bar.show();
            refreshBlocks();
        } catch (Throwable t) {
            stop();
        }
    }

    @Override
    public void stop() {
        active = false;
        if (main != null) main.removeCallbacksAndMessages(null);
        reading = false;
        speakingInput = false;
        speechGeneration++;
        activeUtteranceId = null;
        pendingInputNode = null;
        if (bar != null) { bar.hide(); bar = null; }
        if (tts != null) {
            try { tts.stop(); } catch (Throwable ignored) { }
            try { tts.shutdown(); } catch (Throwable ignored) { }
            tts = null;
        }
        ttsReady = false;
        blocks.clear();
        svc = null;
    }

    /** Старт/пауза чтения, как тап по полосе. */
    @Override
    public Object execute(Map<String, ?> data) {
        if (active) startOrPause();
        return null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event, AccessibilityService service) {
        if (!active || event == null) return;
        CharSequence pkgCs = event.getPackageName();
        if (pkgCs != null && pkgCs.toString().equals(svc.getPackageName())) return;   // окна самого хоста

        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null && isEditable(source)) {
                pendingInputNode = source;
                int from = event.getFromIndex();
                if (from >= 0) lastRecordedCursor = from + event.getAddedCount();
                inputRevision++;
            }
            return;
        }
        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null && isEditable(source)) {
                pendingInputNode = source;
                int from = event.getFromIndex();
                if (from >= 0) lastRecordedCursor = from;
            }
            return;
        }
        if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null && isEditable(source)) {
                pendingInputNode = source;
                if (source.getTextSelectionStart() >= 0) lastRecordedCursor = source.getTextSelectionStart();
            }
        } else if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            pendingInputNode = null;
            lastRecordedCursor = -1;
            if (reading) {
                reading = false;
                if (tts != null) tts.stop();
                if (bar != null) bar.setPlaying(false);
            }
        }
        // дерево перечитываем не на каждое событие (в хосте приходят все типы), а с задержкой
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || type == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            if (!reading || blocks.isEmpty()) {
                main.removeCallbacks(refreshTask);
                main.postDelayed(refreshTask, REFRESH_DEBOUNCE_MS);
            }
        }
    }

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() { if (active) refreshBlocks(); }
    };

    // ---------------------------------------------------------------- настройки (экран в хосте)

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private void loadConfig() {
        barHeightDp = clamp(cfg.getInt("bar_height_dp", 28), 16, 64);
        backgroundAlpha = clamp(cfg.getInt("background_alpha", 82), 0, 100);
        progressAlpha = clamp(cfg.getInt("progress_alpha", 90), 10, 100);
        trafficStartDay = clamp(cfg.getInt("traffic_start_day", 1), 1, 31);
        smoothScroll = cfg.getInt("scroll_mode", 0) == 0;
        speakInput = cfg.getBoolean("speak_input", true);
    }

    @Override
    public List<SettingItem> getSettingsSchema() {
        List<SettingItem> l = new ArrayList<SettingItem>();
        l.add(SettingItem.section("Полоса"));
        l.add(SettingItem.slider("bar_height_dp", "Высота полосы", "dp", 16, 64, 2, 28));
        l.add(SettingItem.slider("background_alpha", "Непрозрачность фона", "%", 0, 100, 5, 82));
        l.add(SettingItem.slider("progress_alpha", "Непрозрачность полосы прогресса", "%", 10, 100, 5, 90));
        l.add(SettingItem.section("Чтение"));
        l.add(SettingItem.choice("scroll_mode", "Прокрутка", "", Arrays.asList("Плавная", "По страницам"), 0));
        l.add(SettingItem.toggle("speak_input", "Озвучивать поле ввода",
                "Читает предложение у курсора (по тапу на полосу, когда выбрано поле ввода)", true));
        l.add(SettingItem.section("Мобильный трафик"));
        l.add(SettingItem.slider("traffic_start_day", "День начала учётного периода", "", 1, 31, 1, 1));
        return l;
    }

    @Override
    public void onSettingChanged(String key) {
        if (svc == null || !active) return;
        Context c = svc;
        stop();
        init(c);        // размеры и вид полосы читаются при создании
    }

    @Override
    public Bitmap createIcon(int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float s = size / 96f;
        p.setColor(Color.WHITE);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4f * s);
        p.setStrokeJoin(Paint.Join.ROUND);
        c.drawRoundRect(new RectF(10 * s, 30 * s, 86 * s, 66 * s), 8 * s, 8 * s, p);   // полоса
        p.setStyle(Paint.Style.FILL);
        Path tri = new Path();                                                          // «играть»
        tri.moveTo(40 * s, 40 * s);
        tri.lineTo(40 * s, 56 * s);
        tri.lineTo(56 * s, 48 * s);
        tri.close();
        c.drawPath(tri, p);
        return b;
    }

    // ---------------------------------------------------------------- речь

    private void onTtsInit(int status) {
        if (status != TextToSpeech.SUCCESS || tts == null || !active) return;
        ttsReady = true;
        tts.setLanguage(Locale.getDefault());
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) { }

            @Override public void onError(final String utteranceId) {
                if (main == null) return;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (active && utteranceId != null && utteranceId.equals(activeUtteranceId)) {
                            speakingInput = false;
                            reading = false;
                            if (bar != null) bar.setPlaying(false);
                        }
                    }
                });
            }

            @Override public void onDone(final String utteranceId) {
                if (main == null) return;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (!active || utteranceId == null || !utteranceId.equals(activeUtteranceId)) return;
                        if (speakingInput) {
                            speakingInput = false;
                            if (bar != null) bar.setPlaying(false);
                        } else if (reading) {
                            advanceAfterSpeech();
                        }
                    }
                });
            }
        });
    }

    private boolean isEditable(AccessibilityNodeInfo node) {
        if (node.isEditable()) return true;
        CharSequence cn = node.getClassName();
        String className = cn == null ? "" : cn.toString().toLowerCase(Locale.ROOT);
        return className.contains("edittext") || className.contains("autocompletetextview");
    }

    private AccessibilityNodeInfo findActiveInputNode() {
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root != null) {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused != null && isEditable(focused)) return focused;
        }
        if (pendingInputNode != null && pendingInputNode.refresh() && isEditable(pendingInputNode)) {
            return pendingInputNode;
        }
        try {
            for (AccessibilityWindowInfo window : svc.getWindows()) {
                AccessibilityNodeInfo winRoot = window.getRoot();
                if (winRoot == null) continue;
                AccessibilityNodeInfo focused = winRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (focused != null && isEditable(focused)) return focused;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** Озвучивает предложение у курсора в выбранном поле ввода. false — поля нет, читаем страницу. */
    private boolean speakPendingInput() {
        if (!speakInput || !ttsReady) return false;
        AccessibilityNodeInfo node = findActiveInputNode();
        if (node == null) return false;
        node.refresh();
        CharSequence cs = node.getText();
        String text = cs == null ? "" : cs.toString();
        if (text.trim().isEmpty()) return false;

        List<SentenceSpan> sentences = InputSentenceParser.parse(text);
        if (sentences.isEmpty()) return false;

        int cursor;
        if (node.getTextSelectionStart() >= 0) cursor = node.getTextSelectionStart();
        else if (lastRecordedCursor >= 0 && lastRecordedCursor <= text.length()) cursor = lastRecordedCursor;
        else cursor = text.length();

        inputSentenceIndex = InputSentenceParser.indexAt(sentences, cursor);
        speakInputSentence(node, inputSentenceIndex);
        return true;
    }

    private void speakInputSentence(AccessibilityNodeInfo node, int index) {
        CharSequence cs = node.getText();
        List<SentenceSpan> sentences = InputSentenceParser.parse(cs == null ? "" : cs.toString());
        if (sentences.isEmpty() || tts == null) return;
        inputSentenceIndex = clamp(index, 0, sentences.size() - 1);
        String spoken = sentences.get(inputSentenceIndex).text;
        if (spoken.trim().isEmpty()) return;

        speakingInput = true;
        if (bar != null) bar.setPlaying(true);
        activeUtteranceId = "antts-input-" + inputRevision + "-" + System.nanoTime();
        tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, activeUtteranceId);
    }

    // ---------------------------------------------------------------- чтение страницы

    private int dp(int v) { return (int) (v * svc.getResources().getDisplayMetrics().density); }

    private int statusBarHeight() {
        int id = svc.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? clamp(svc.getResources().getDimensionPixelSize(id), 0, 160) : dp(24);
    }

    private int navigationBarHeight() {
        int id = svc.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? clamp(svc.getResources().getDimensionPixelSize(id), 0, 160) : dp(48);
    }

    private ScreenMetrics screenMetrics() {
        android.util.DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        int top = Math.max(statusBarHeight(), dp(barHeightDp));
        int bottom = dm.heightPixels - navigationBarHeight();
        return new ScreenMetrics(dm.widthPixels, dm.heightPixels, dm.density, top, bottom);
    }

    private void refreshBlocks() {
        if (svc == null) return;
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return;
        List<Block> fresh;
        try {
            fresh = Extractor.extract(root, screenMetrics());
        } catch (Exception e) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Block b : fresh) {
            CharSequence id = b.node.getViewIdResourceName();
            sb.append(b.text).append('|').append(b.rect.top).append('|').append(b.rect.bottom)
                    .append('|').append(id).append('\u0000');
        }
        String snapshot = sb.toString();
        if (!fresh.isEmpty() && !snapshot.equals(lastSnapshot)) {
            blocks.clear();
            blocks.addAll(fresh);
            lastSnapshot = snapshot;
            current = clamp(current, 0, blocks.size() - 1);
            if (bar != null) bar.setProgress(current, blocks.size());
        }
    }

    private void startOrPause() {
        if (reading || speakingInput) {
            reading = false;
            speakingInput = false;
            if (tts != null) tts.stop();
            if (bar != null) bar.setPlaying(false);
            return;
        }
        if (speakPendingInput()) return;
        refreshBlocks();
        if (blocks.isEmpty() || !ttsReady) return;
        current = 0;
        reading = true;
        invisibleRetries = 0;
        if (bar != null) bar.setPlaying(true);
        speakCurrent();
    }

    private void speakCurrent() {
        if (!reading || blocks.isEmpty() || tts == null) return;
        current = clamp(current, 0, blocks.size() - 1);
        Block block = blocks.get(current);
        if (!block.node.isVisibleToUser()) {
            if (++invisibleRetries > MAX_INVISIBLE_RETRIES) {      // блок так и не показался — идём дальше
                invisibleRetries = 0;
                if (current + 1 < blocks.size()) { current++; speakCurrent(); } else finishReading();
                return;
            }
            bringIntoView(block.node);
            final long generation = speechGeneration;
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    if (active && reading && speechGeneration == generation) speakCurrent();
                }
            }, 180L);
            return;
        }
        invisibleRetries = 0;
        block.node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        activeUtteranceId = "antts-block-" + current + "-" + (speechGeneration++) + "-" + System.nanoTime();
        tts.speak(block.text, TextToSpeech.QUEUE_FLUSH, null, activeUtteranceId);
        if (bar != null) bar.setProgress(current, blocks.size());
    }

    private void advanceAfterSpeech() {
        if (!reading) return;
        if (current + 1 < blocks.size()) {
            Block next = blocks.get(current + 1);
            if (smoothScroll && nearBottom(next.node)) {
                final long generation = speechGeneration;
                final String nextText = next.text;
                smoothScrollBy(next.node, new Runnable() {
                    @Override public void run() {
                        if (active && reading && speechGeneration == generation) {
                            refreshBlocks();
                            int matched = -1;
                            for (int i = 0; i < blocks.size(); i++) {
                                if (blocks.get(i).text.equals(nextText)) { matched = i; break; }
                            }
                            current = matched >= 0 ? matched : Math.min(current + 1, blocks.size() - 1);
                            speakCurrent();
                        }
                    }
                });
            } else {
                current++;
                speakCurrent();
            }
        } else {
            scrollAndLoadNextPage();
        }
    }

    private boolean nearBottom(AccessibilityNodeInfo node) {
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        return rect.bottom > svc.getResources().getDisplayMetrics().heightPixels - navigationBarHeight() - dp(72);
    }

    private AccessibilityNodeInfo scrollParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo parent = node.getParent();
        while (parent != null) {
            if (parent.isScrollable()) return parent;
            parent = parent.getParent();
        }
        return null;
    }

    /** Плавная прокрутка: медленно тянет прокручиваемый контейнер вместо прыжка на страницу. */
    private void smoothScrollBy(AccessibilityNodeInfo node, final Runnable onDone) {
        AccessibilityNodeInfo target = scrollParent(node);
        if (target == null) { onDone.run(); return; }
        Rect rect = new Rect();
        target.getBoundsInScreen(rect);
        if (rect.height() < 100) { onDone.run(); return; }
        float x = (rect.left + rect.right) / 2f;
        float startY = rect.bottom - rect.height() * 0.1f;
        float endY = rect.top + rect.height() * 0.1f;
        Path path = new Path();
        path.moveTo(x, startY);
        path.lineTo(x, endY);
        boolean dispatched = false;
        try {
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0L, 700L))
                    .build();
            dispatched = svc.dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) { onDone.run(); }
                @Override public void onCancelled(GestureDescription g) { onDone.run(); }
            }, main);
        } catch (Throwable ignored) { }
        if (!dispatched) onDone.run();      // нет права жестов — просто читаем дальше
    }

    private void scrollAndLoadNextPage() {
        if (!reading) return;
        AccessibilityNodeInfo scrollable = findScrollable(svc.getRootInActiveWindow());
        if (scrollable == null) { finishReading(); return; }
        final String before = lastSnapshot;
        if (!scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            finishReading();
            return;
        }
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (!active || !reading) return;
                refreshBlocks();
                if (!blocks.isEmpty() && !lastSnapshot.equals(before)) {
                    current = 0;
                    speakCurrent();
                } else {
                    finishReading();
                }
            }
        }, 500L);
    }

    private void finishReading() {
        reading = false;
        activeUtteranceId = null;
        if (bar != null) bar.setPlaying(false);
    }

    private void move(int delta) {
        AccessibilityNodeInfo inputNode = findActiveInputNode();
        if (!reading && inputNode != null) {
            CharSequence cs = inputNode.getText();
            List<SentenceSpan> sentences = InputSentenceParser.parse(cs == null ? "" : cs.toString());
            if (!sentences.isEmpty()) {
                if (tts != null) tts.stop();
                speakInputSentence(inputNode, inputSentenceIndex + delta);
                return;
            }
        }
        refreshBlocks();
        if (blocks.isEmpty()) return;
        current = clamp(current + delta, 0, blocks.size() - 1);
        if (!reading) {
            reading = true;
            if (bar != null) bar.setPlaying(true);
        }
        if (tts != null) tts.stop();
        speakCurrent();
    }

    private void bringIntoView(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo parent = node.getParent();
        while (parent != null) {
            if (parent.isScrollable() && !node.isVisibleToUser()) {
                parent.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                break;
            }
            parent = parent.getParent();
        }
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo root) {
        if (root == null) return null;
        List<AccessibilityNodeInfo> candidates = new ArrayList<AccessibilityNodeInfo>();
        collectScrollables(root, candidates, 0);
        AccessibilityNodeInfo best = null;
        long bestArea = -1;
        for (AccessibilityNodeInfo n : candidates) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.height() >= dp(120) && !isSideRailOrDrawer(n, r)) {
                long a = (long) r.width() * (long) r.height();
                if (a > bestArea) { bestArea = a; best = n; }
            }
        }
        if (best != null) return best;
        for (AccessibilityNodeInfo n : candidates) {       // запасной вариант — самый большой
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            long a = (long) r.width() * (long) r.height();
            if (a > bestArea) { bestArea = a; best = n; }
        }
        return best;
    }

    private boolean isSideRailOrDrawer(AccessibilityNodeInfo node, Rect rect) {
        int widthPx = svc.getResources().getDisplayMetrics().widthPixels;
        int railMaxW = dp(96);
        if (rect.left <= 0 && rect.right <= railMaxW) return true;
        if (rect.right >= widthPx && rect.left >= widthPx - railMaxW) return true;
        CharSequence cn = node.getClassName();
        String className = cn == null ? "" : cn.toString().toLowerCase(Locale.ROOT);
        return className.contains("drawer") || className.contains("navigationrail");
    }

    private void collectScrollables(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 100) return;
        if (node.isScrollable()) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collectScrollables(node.getChild(i), out, depth + 1);
    }

    // ---------------------------------------------------------------- мобильный трафик

    private long periodStartMillis() {
        long now = System.currentTimeMillis();
        Calendar cycle = Calendar.getInstance();
        cycle.setTimeInMillis(now);
        cycle.set(Calendar.DAY_OF_MONTH, Math.min(trafficStartDay, cycle.getActualMaximum(Calendar.DAY_OF_MONTH)));
        cycle.set(Calendar.HOUR_OF_DAY, 0);
        cycle.set(Calendar.MINUTE, 0);
        cycle.set(Calendar.SECOND, 0);
        cycle.set(Calendar.MILLISECOND, 0);
        if (now < cycle.getTimeInMillis()) cycle.add(Calendar.MONTH, -1);
        return cycle.getTimeInMillis();
    }

    /** Мобильный трафик (приём + передача) за учётный период, ГБ; «—», если нет доступа к статистике. */
    private String monthlyText() {
        try {
            NetworkStatsManager m = (NetworkStatsManager) svc.getSystemService(Context.NETWORK_STATS_SERVICE);
            NetworkStats.Bucket b = m.querySummaryForDevice(
                    ConnectivityManager.TYPE_MOBILE, null, periodStartMillis(), System.currentTimeMillis());
            long bytes = Math.max(0L, b.getRxBytes()) + Math.max(0L, b.getTxBytes());
            return String.format(Locale.getDefault(), "%.2f", bytes / 1e9);
        } catch (Throwable t) {
            return "\u2014";
        }
    }

    // ---------------------------------------------------------------- полоса

    private final class Bar {
        private final WindowManager wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
        private final FrameLayout root = new FrameLayout(svc);
        private final View progress = new View(svc);
        private final TextView traffic = new TextView(svc);
        private boolean shown;
        private float downX;
        private String trafficText = "";

        private final Runnable trafficRefresh = new Runnable() {
            @Override public void run() {
                if (!shown) return;
                String t = monthlyText();
                if (!t.equals(trafficText)) {            // не перерисовываем, если цифра не изменилась
                    trafficText = t;
                    traffic.setText(t);
                }
                main.postDelayed(this, TRAFFIC_REFRESH_MS);
            }
        };

        void show() {
            if (shown) return;
            root.setBackgroundColor(Color.TRANSPARENT);
            View black = new View(svc);
            black.setBackgroundColor(Color.argb((int) (backgroundAlpha * 2.55), 0, 0, 0));
            root.addView(black, new FrameLayout.LayoutParams(-1, -1));
            progress.setBackgroundColor(Color.argb((int) (progressAlpha * 2.55), 255, 255, 255));
            root.addView(progress, new FrameLayout.LayoutParams(0, -1));

            trafficText = monthlyText();
            traffic.setText(trafficText);
            traffic.setTextSize(27f);
            traffic.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
            traffic.setIncludeFontPadding(false);
            traffic.setGravity(Gravity.CENTER);
            traffic.setTextColor(Color.WHITE);
            traffic.setPadding(0, 0, 0, 0);
            root.addView(traffic, new FrameLayout.LayoutParams(-1, -1));

            root.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = e.getRawX();
                            return true;
                        case MotionEvent.ACTION_UP: {
                            float dx = e.getRawX() - downX;
                            if (Math.abs(dx) >= 28f) move(dx > 0 ? 1 : -1); else startOrPause();
                            return true;
                        }
                        default:
                            return true;
                    }
                }
            });

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    -1, dp(barHeightDp), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.x = 0;
            lp.y = 0;
            wm.addView(root, lp);
            shown = true;
            main.postDelayed(trafficRefresh, TRAFFIC_REFRESH_MS);
        }

        void setProgress(final int index, final int total) {
            if (total <= 0 || !shown) return;
            root.post(new Runnable() {
                @Override public void run() {
                    int full = root.getWidth() > 0 ? root.getWidth()
                            : svc.getResources().getDisplayMetrics().widthPixels;
                    int width = clamp((int) (full * (index + 1f) / total), 1, full);
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) progress.getLayoutParams();
                    if (lp.width != width) {
                        lp.width = width;
                        progress.setLayoutParams(lp);
                    }
                }
            });
        }

        void setPlaying(boolean playing) {
            root.setContentDescription(playing ? "AnTTS: чтение включено" : "AnTTS: чтение остановлено");
        }

        void hide() {
            if (!shown) return;
            shown = false;
            if (main != null) main.removeCallbacks(trafficRefresh);
            try { wm.removeView(root); } catch (Throwable ignored) { }
        }
    }

    // ---------------------------------------------------------------- модель

    private static final class ScreenMetrics {
        final int screenWidth;
        final int screenHeight;
        final float density;
        final int topBoundary;
        final int bottomBoundary;
        ScreenMetrics(int w, int h, float density, int top, int bottom) {
            this.screenWidth = w; this.screenHeight = h; this.density = density;
            this.topBoundary = top; this.bottomBoundary = bottom;
        }
    }

    private static final class Block {
        final String text;
        final AccessibilityNodeInfo node;
        final Rect rect = new Rect();
        Block(String text, AccessibilityNodeInfo node) {
            this.text = text;
            this.node = node;
            node.getBoundsInScreen(rect);
        }
    }

    private static final class SentenceSpan {
        final int contentStart;
        final int contentEnd;
        final int spanEnd;
        final String text;
        SentenceSpan(int contentStart, int contentEnd, int spanEnd, String text) {
            this.contentStart = contentStart; this.contentEnd = contentEnd;
            this.spanEnd = spanEnd; this.text = text;
        }
    }

    /** Делит текст поля ввода на предложения и находит предложение у курсора. */
    private static final class InputSentenceParser {
        private static final String CLOSERS = "\"'\u00bb\u201d\u2019)]";

        private static boolean isTerminator(char ch) {
            return ch == '.' || ch == '!' || ch == '?' || ch == '\u2026';
        }

        static List<SentenceSpan> parse(String text) {
            List<SentenceSpan> spans = new ArrayList<SentenceSpan>();
            if (text.trim().isEmpty()) return spans;
            int start = 0;
            int len = text.length();
            while (start < len) {
                while (start < len && Character.isWhitespace(text.charAt(start))) start++;
                if (start >= len) break;

                int end = start;
                while (end < len) {
                    char ch = text.charAt(end);
                    if (ch == '\n' || ch == '\r') { end++; break; }
                    if (isTerminator(ch)) {
                        if (ch == '.' && end > start && Character.isDigit(text.charAt(end - 1))
                                && end + 1 < len && Character.isDigit(text.charAt(end + 1))) {
                            end++;                       // десятичная точка внутри числа
                            continue;
                        }
                        while (end < len && isTerminator(text.charAt(end))) end++;
                        while (end < len && CLOSERS.indexOf(text.charAt(end)) >= 0) end++;
                        break;
                    }
                    end++;
                }

                int spanEnd = end;
                while (spanEnd < len && Character.isWhitespace(text.charAt(spanEnd))) spanEnd++;

                String sentence = text.substring(start, end).trim();
                if (!sentence.isEmpty()) spans.add(new SentenceSpan(start, end, spanEnd, sentence));
                start = spanEnd;
            }
            return spans;
        }

        static int indexAt(List<SentenceSpan> sentences, int cursor) {
            if (sentences.isEmpty()) return 0;
            if (cursor <= sentences.get(0).contentStart) return 0;
            for (int i = 0; i < sentences.size(); i++) {
                SentenceSpan s = sentences.get(i);
                if (cursor >= s.contentStart && cursor <= s.spanEnd) return i;
            }
            return sentences.size() - 1;
        }
    }

    /** Отбор основного текста страницы: без панелей, кнопок, реклам и полей ввода. */
    private static final class Extractor {
        private static final int MAX_ANCESTOR_DEPTH = 4;
        private static final int MAX_DEPTH = 100;
        private static final int MAX_NODES = 8000;

        private static final String[] TOP_PANEL = {
                "toolbar", "actionbar", "action_bar", "appbar", "app_bar",
                "topbar", "top_bar", "header_bar", "header_container", "header_view",
                "url_bar", "urlbar", "omnibox", "location_bar", "search_bar", "searchbar",
                "tablayout", "tab_layout", "tabbar", "tab_bar", "tabstrip"};
        private static final String[] BOTTOM_PANEL = {
                "bottomnavigation", "bottom_navigation", "bottom_nav", "bottomnav",
                "bottomappbar", "bottom_app_bar", "bottombar", "bottom_bar",
                "footer", "navigation_bar", "navbar", "nav_bar", "snackbar",
                "action_bar_bottom"};
        private static final String[] SIDE_PANEL = {
                "navigationrail", "navigation_rail", "nav_rail", "navrail",
                "drawer", "drawerlayout", "sidebar", "side_bar", "side_nav", "sidenav",
                "sidesheet", "side_sheet", "sidepanel", "side_panel"};
        private static final String[] CONTROL_CLASS = {
                "button", "imagebutton", "switch", "checkbox", "radiobutton",
                "togglebutton", "chip", "tab", "menuitem", "spinner", "seekbar",
                "floatingactionbutton", "ratingbar", "progressbar"};
        private static final String[] AD = {"adview", "ad_view", "banner", "advertisement"};

        static List<Block> extract(AccessibilityNodeInfo root, ScreenMetrics m) {
            List<AccessibilityNodeInfo> candidates = new ArrayList<AccessibilityNodeInfo>();
            collect(root, candidates, m, 0, new int[]{0});

            Map<String, Block> unique = new LinkedHashMap<String, Block>();
            for (AccessibilityNodeInfo node : candidates) {
                String text = str(node.getText());
                if (text.length() < 2 || !hasLetter(text)) continue;
                Block b = new Block(text, node);
                String key = text + "|" + (b.rect.top / 12) + "|" + (b.rect.left / 12);
                if (!unique.containsKey(key)) unique.put(key, b);
            }

            // сверху вниз; узлы на одной «строке» (в пределах допуска) — слева направо
            List<Block> byTop = new ArrayList<Block>(unique.values());
            Collections.sort(byTop, new Comparator<Block>() {
                @Override public int compare(Block a, Block b) { return a.rect.top < b.rect.top ? -1 : (a.rect.top == b.rect.top ? 0 : 1); }
            });
            final int tolerance = (int) (m.density * 8);
            List<Block> result = new ArrayList<Block>();
            int i = 0;
            while (i < byTop.size()) {
                int rowTop = byTop.get(i).rect.top;
                List<Block> row = new ArrayList<Block>();
                while (i < byTop.size() && Math.abs(byTop.get(i).rect.top - rowTop) <= tolerance) {
                    row.add(byTop.get(i));
                    i++;
                }
                Collections.sort(row, new Comparator<Block>() {
                    @Override public int compare(Block a, Block b) { return a.rect.left < b.rect.left ? -1 : (a.rect.left == b.rect.left ? 0 : 1); }
                });
                result.addAll(row);
            }
            return result;
        }

        private static String str(CharSequence cs) { return cs == null ? "" : cs.toString().trim(); }

        private static boolean hasLetter(String s) {
            for (int i = 0; i < s.length(); i++) if (Character.isLetter(s.charAt(i))) return true;
            return false;
        }

        private static int dp(ScreenMetrics m, int v) { return (int) (v * m.density); }

        private static String classNameOf(AccessibilityNodeInfo n) {
            CharSequence cn = n.getClassName();
            return cn == null ? "" : cn.toString().toLowerCase(Locale.ROOT);
        }

        private static String viewIdOf(AccessibilityNodeInfo n) {
            String id = n.getViewIdResourceName();
            return id == null ? "" : id.toLowerCase(Locale.ROOT);
        }

        private static boolean any(String s, String[] markers) {
            for (String mk : markers) if (s.contains(mk)) return true;
            return false;
        }

        private static boolean any(String a, String b, String[] markers) {
            return any(a, markers) || any(b, markers);
        }

        private static int textLen(AccessibilityNodeInfo n) { return str(n.getText()).length(); }

        private static boolean isEditableNode(AccessibilityNodeInfo n) {
            if (n.isEditable()) return true;
            String c = classNameOf(n);
            return c.contains("edittext") || c.contains("autocompletetextview");
        }

        private static void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out,
                                    ScreenMetrics m, int depth, int[] counter) {
            if (node == null || depth > MAX_DEPTH || ++counter[0] > MAX_NODES) return;
            if (!node.isVisibleToUser()) return;

            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            if (rect.bottom <= m.topBoundary || rect.top >= m.bottomBoundary) return;
            if (rect.right <= 0 || rect.left >= m.screenWidth) return;
            if (rect.width() <= 0 || rect.height() <= 0) return;
            if (isAuxiliaryPanel(node, rect, m)) return;

            String value = str(node.getText());
            if (!value.isEmpty() && !isAuxiliaryControl(node, rect, m)) {
                if (!hasTextInChildren(node, 0)) out.add(node);
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                collect(node.getChild(i), out, m, depth + 1, counter);
            }
        }

        private static boolean hasTextInChildren(AccessibilityNodeInfo node, int depth) {
            if (depth > MAX_DEPTH) return false;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) continue;
                if (!str(child.getText()).isEmpty()) return true;
                if (hasTextInChildren(child, depth + 1)) return true;
            }
            return false;
        }

        private static boolean isAuxiliaryPanel(AccessibilityNodeInfo node, Rect rect, ScreenMetrics m) {
            String cls = classNameOf(node);
            String id = viewIdOf(node);
            int tlen = textLen(node);

            // верхние панели
            if (any(cls, id, TOP_PANEL)) return true;
            if (rect.top <= m.topBoundary + dp(m, 12)
                    && rect.bottom <= m.topBoundary + dp(m, 80)
                    && rect.width() >= m.screenWidth * 0.75
                    && !node.isScrollable() && tlen < 50) {
                if (cls.contains("layout") || cls.contains("group") || cls.contains("view")) {
                    if (id.contains("head") || id.contains("bar") || id.contains("nav")) return true;
                }
            }

            // нижние панели
            if (any(cls, id, BOTTOM_PANEL)) return true;
            if (rect.bottom >= m.bottomBoundary - dp(m, 12)
                    && rect.top >= m.bottomBoundary - dp(m, 80)
                    && rect.width() >= m.screenWidth * 0.75
                    && !node.isScrollable() && tlen < 50) {
                if (id.contains("bottom") || id.contains("foot") || id.contains("nav") || id.contains("bar")) return true;
            }

            // боковые панели
            if (any(cls, id, SIDE_PANEL)) return true;
            int railMaxW = dp(m, 96);
            int minRailH = (int) (m.screenHeight * 0.35);
            boolean railLike = id.contains("rail") || id.contains("side") || id.contains("nav") || cls.contains("navigation");
            if (rect.left <= 0 && rect.right <= railMaxW && rect.height() >= minRailH && tlen < 50 && railLike) return true;
            if (rect.right >= m.screenWidth && rect.left >= m.screenWidth - railMaxW
                    && rect.height() >= minRailH && tlen < 50 && railLike) return true;

            // реклама и плавающая кнопка
            if (any(cls, id, AD)) return true;
            return cls.contains("floatingactionbutton") || id.contains("fab");
        }

        private static boolean isAuxiliaryControl(AccessibilityNodeInfo node, Rect rect, ScreenMetrics m) {
            if (isEditableNode(node)) return true;

            String text = str(node.getText());
            String cls = classNameOf(node);
            String id = viewIdOf(node);

            if (any(cls, CONTROL_CLASS)) return true;
            if (any(id, TOP_PANEL) || any(id, BOTTOM_PANEL) || any(id, SIDE_PANEL)) return true;
            if (any(cls, id, AD)) return true;
            if ((node.isClickable() || node.isCheckable()) && text.length() < 45) return true;

            AccessibilityNodeInfo parent = node.getParent();
            int depth = 0;
            while (parent != null && depth < MAX_ANCESTOR_DEPTH) {
                String pc = classNameOf(parent);
                String pid = viewIdOf(parent);
                if (any(pc, pid, TOP_PANEL) || any(pc, pid, BOTTOM_PANEL) || any(pc, pid, SIDE_PANEL)) return true;
                if (any(pc, CONTROL_CLASS)) return true;
                if (any(pc, pid, AD)) return true;
                if (parent.isClickable() && text.length() < 45) {
                    Rect pr = new Rect();
                    parent.getBoundsInScreen(pr);
                    if (pr.height() <= dp(m, 64) || pr.width() <= dp(m, 220)) return true;
                }
                parent = parent.getParent();
                depth++;
            }
            return false;
        }
    }
}
