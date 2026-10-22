#!/usr/bin/env python3
"""Патч для modules/Browser.java (идёт первым шагом workflow, до browser_scripts.py).
1. Кнопка: римские цифры вместо арабских, цифра вписана в круг так, что углами касается его края;
   кнопка и кольцо значков крупнее на 40%, кнопка менее прозрачная.
2. Скрытие как раньше у панели: прячется при прокрутке вниз, возвращается при прокрутке вверх или в начале страницы
   (таймер возврата убран; выключатель «Прятать кнопку при прокрутке» остаётся в настройках).
3. Снимок вкладки берётся с экрана через PixelCopy, то есть ровно то место и тот масштаб, что видны сейчас.
4. Масштабирование щипком разрешается на всех сайтах (переписывается viewport; выключатель в настройках).
Использование: python3 browser_hub.py modules/Browser.java"""
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "modules/Browser.java"
src = open(path, encoding="utf-8").read()


def rep(old, new, count=1):
    global src
    n = src.count(old)
    if n != count:
        sys.exit("ANCHOR FAILED (%d found, %d expected): %s" % (n, count, old[:100].replace("\n", "\\n")))
    src = src.replace(old, new)


# ---------------------------------------------------------------- константы и скрипт масштабирования
ZOOM_JS = [
    "(function(){try{if(window.__zf)return;window.__zf=1;",
    "function f(){try{var ms=document.querySelectorAll('meta[name=viewport i]');",
    "for(var i=0;i<ms.length;i++){var m=ms[i],c=m.getAttribute('content')||'',n=c;",
    "n=n.replace(/user-scalable *= *[^,; ]*/ig,'user-scalable=yes').replace(/maximum-scale *= *[^,; ]*/ig,'maximum-scale=10').replace(/minimum-scale *= *[^,; ]*/ig,'minimum-scale=0.1');",
    "if(!/user-scalable/i.test(n))n+=(n?',':'')+'user-scalable=yes';",
    "if(!/maximum-scale/i.test(n))n+=',maximum-scale=10';",
    "if(n!==c)m.setAttribute('content',n);}}catch(e){}}",
    "var pend=false;function g(){if(pend)return;pend=true;setTimeout(function(){pend=false;f();},50);}",
    "f();var o=new MutationObserver(g);",
    "o.observe(document,{childList:true,subtree:true,attributes:true,attributeFilter:['content']});",
    "window.addEventListener('load',function(){f();setTimeout(function(){o.disconnect();},3000);});",
    "}catch(e){}})();",
]
java_zoom = "\n".join(
    '            %s"%s"' % ("" if i == 0 else "+ ", line) for i, line in enumerate(ZOOM_JS))
rep("private static final float HUB_ALPHA = 0.45f;",
    "private static final float HUB_ALPHA = 0.62f;\n"
    "    /** Разрешает масштабирование щипком там, где страница запретила его через viewport. */\n"
    "    private static final String FORCE_ZOOM_JS =\n" + java_zoom + ";")

# ---------------------------------------------------------------- поля
rep("        private TextView hub;\n",
    "        private TextView hub;\n"
    "        private final java.util.Map<String, Long> pixelAt = new java.util.HashMap<String, Long>();\n")

# ---------------------------------------------------------------- кнопка: размер, фон, римские цифры
rep("            hub = new TextView(act);\n", "            hub = new HubView(act);\n")
rep('            hub.setText("1");\n            hub.setBackground(round(0x55000000, 28, 0x55FFFFFF, 1));\n',
    '            hub.setText("I");\n            hub.setBackground(round(0xCC000000, 39, 0x99FFFFFF, 2));\n')
rep("addView(hub, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));",
    "addView(hub, new FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER));")
rep("float maxX = Math.max(0f, getWidth() / 2f - dp(28));", "float maxX = Math.max(0f, getWidth() / 2f - dp(39));")
rep("float maxY = Math.max(0f, getHeight() / 2f - dp(28));", "float maxY = Math.max(0f, getHeight() / 2f - dp(39));")
rep("            hub.setText(String.valueOf(tabs.size()));\n", "            hub.setText(roman(tabs.size()));\n")

HUBVIEW = r'''        /** Число вкладок римскими цифрами: подбирается размер, при котором углы рамки текста касаются края круга. */
        private final class HubView extends TextView {
            private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.Rect rb = new android.graphics.Rect();

            HubView(Context c) {
                super(c);
                tp.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD));
            }

            @Override protected void onDraw(Canvas c) {
                String s = String.valueOf(getText());
                if (s.length() == 0) return;
                float d = Math.min(getWidth(), getHeight());
                tp.setColor(getCurrentTextColor());
                tp.setTextSize(100f);
                tp.getTextBounds(s, 0, s.length(), rb);
                float w = rb.width();
                float hh = rb.height();
                if (w <= 0f || hh <= 0f) return;
                float k = 0.98f * d / (float) Math.hypot(w, hh);
                tp.setTextSize(100f * k);
                tp.getTextBounds(s, 0, s.length(), rb);
                c.drawText(s, getWidth() / 2f - rb.exactCenterX(), getHeight() / 2f - rb.exactCenterY(), tp);
            }
        }

        private String roman(int n) {
            if (n <= 0) return "0";
            int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
            String[] r = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < v.length; i++) {
                while (n >= v[i]) {
                    b.append(r[i]);
                    n -= v[i];
                }
            }
            return b.toString();
        }

'''
rep("        private final class RingItem {", HUBVIEW + "        private final class RingItem {")

# ---------------------------------------------------------------- кольцо крупнее на 40%
rep("            float r = dp(96);\n            float half = dp(26);\n", "            float r = dp(130);\n            float half = dp(73) / 2f;\n")
rep("TextView v = glyph(it.glyph, C_TEXT);\n                v.setBackground(round(0xEE202124, 26, C_TEXT2, 1));\n",
    "TextView v = glyph(it.glyph, C_TEXT);\n                v.setTextSize(30);\n                v.setBackground(round(0xEE202124, 37, C_TEXT2, 1));\n")
rep("new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP | Gravity.START);", "new FrameLayout.LayoutParams(dp(73), dp(73), Gravity.TOP | Gravity.START);")
rep("float bestD = dp(38);", "float bestD = dp(53);")

# ---------------------------------------------------------------- скрытие как раньше: без таймера возврата
rep("            if (dockHidden) {\n                h.removeCallbacks(hubReturn);\n                h.postDelayed(hubReturn, 1000);\n            }\n", "")
rep('"Кнопка прячется, пока страницу листают, и возвращается через секунду после остановки, при прокрутке в обратную сторону или в начале страницы"',
    '"Кнопка прячется, когда страницу листают вниз, и возвращается при прокрутке вверх или в начале страницы"')

# ---------------------------------------------------------------- снимок вкладки: ровно то, что видно на экране
rep("        private void captureThumb(Tab t) {\n            WebView w = t == null ? null : t.wv;\n",
    "        private void captureThumb(Tab t) {\n"
    "            if (t != null) {\n"
    "                Long px = pixelAt.get(String.valueOf(t.uid));\n"
    "                if (px != null && SystemClock.uptimeMillis() - px.longValue() < 4000) return;   // свежий снимок с экрана не затираем\n"
    "            }\n"
    "            WebView w = t == null ? null : t.wv;\n")

THUMB = r'''        /** Снимок того, что сейчас видно на экране (с масштабом и прокруткой), через PixelCopy; WebView.draw иногда рисует другое место. */
        private void captureThumbThen(final Tab t, final Runnable after) {
            final WebView w = t == null ? null : t.wv;
            if (Build.VERSION.SDK_INT < 26 || w == null || t != cur || layer != null || t.blocked || w.getParent() == null
                    || w.getWidth() <= 0 || w.getHeight() <= 0 || act.getWindow() == null) {
                captureThumb(t);
                if (after != null) after.run();
                return;
            }
            final boolean hubWas = hub != null && hub.getVisibility() == View.VISIBLE;
            if (hubWas) hub.setVisibility(View.INVISIBLE);       // кнопка не должна попасть на снимок
            final boolean[] done = new boolean[1];
            final Runnable finish = new Runnable() {
                @Override public void run() {
                    if (done[0]) return;
                    done[0] = true;
                    if (hubWas && hub != null && !dockHidden) hub.setVisibility(View.VISIBLE);
                    if (after != null) after.run();
                }
            };
            h.postDelayed(finish, 1500);                         // страховка, если копирование не ответит
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        if (done[0]) return;
                        if (closed || t.wv != w || w.getParent() == null) {
                            finish.run();
                            return;
                        }
                        int[] loc = new int[2];
                        w.getLocationInWindow(loc);
                        android.graphics.Rect srcRect = new android.graphics.Rect(loc[0], loc[1], loc[0] + w.getWidth(), loc[1] + w.getHeight());
                        int tw = dp(170);
                        int th = Math.round(tw * (float) w.getHeight() / w.getWidth());
                        final Bitmap full = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
                        android.view.PixelCopy.request(act.getWindow(), srcRect, full, new android.view.PixelCopy.OnPixelCopyFinishedListener() {
                            @Override public void onPixelCopyFinished(int result) {
                                try {
                                    if (!closed && result == android.view.PixelCopy.SUCCESS) {
                                        Bitmap small = full.copy(Bitmap.Config.RGB_565, false);
                                        Bitmap use = small != null ? small : full;
                                        t.thumb = use;
                                        pixelAt.put(String.valueOf(t.uid), Long.valueOf(SystemClock.uptimeMillis()));
                                        saveBitmapAsync(use, new File(thumbDir(), t.uid + ".jpg"), false);
                                    } else if (!closed) {
                                        captureThumb(t);
                                    }
                                } catch (Throwable ignored) { }
                                finish.run();
                            }
                        }, h);
                    } catch (Throwable ex) {
                        captureThumb(t);
                        finish.run();
                    }
                }
            }, 90);
        }

'''
rep("        private void saveTabs() {\n            if (closed) return;\n", THUMB + "        private void saveTabs() {\n            if (closed) return;\n")

rep("            if (cur != null && cur != t) {\n                captureThumb(cur);\n",
    "            if (cur != null && cur != t) {\n                if (layer == null) captureThumb(cur);       // под панелью вкладок снимок уже взят при её открытии\n")
rep("        private void openTabsRoot() {\n            openGroup = null;\n            showTabs();\n        }\n",
    "        private void openTabsRoot() {\n"
    "            openGroup = null;\n"
    "            if (layer != null) {\n"
    "                showTabs();\n"
    "                return;\n"
    "            }\n"
    "            captureThumbThen(cur, new Runnable() {\n"
    "                @Override public void run() { showTabs(); }\n"
    "            });\n"
    "        }\n")
rep("        private void showTabs() {\n            captureThumb(cur);\n", "        private void showTabs() {\n")
rep("        private void newTabWithStart() {\n            if (cur == null) return;\n            newTab(home(), cur.profile, null, true);\n            showStart(true, true);\n        }\n",
    "        private void newTabWithStart() {\n"
    "            if (cur == null) return;\n"
    "            final Tab from = cur;\n"
    "            captureThumbThen(from, new Runnable() {\n"
    "                @Override public void run() {\n"
    "                    newTab(home(), from.profile, null, true);\n"
    "                    showStart(true, true);\n"
    "                }\n"
    "            });\n"
    "        }\n")

# ---------------------------------------------------------------- масштабирование на всех сайтах
rep("            s.setBuiltInZoomControls(true);\n", "            s.setSupportZoom(true);\n            s.setBuiltInZoomControls(true);\n")
rep("            syncDocStart(t);\n            w.setWebViewClient(new Client(t));\n",
    "            syncDocStart(t);\n            if (sp.getBoolean(\"force_zoom\", true)) addDocStart(w, FORCE_ZOOM_JS);\n            w.setWebViewClient(new Client(t));\n")
rep("                injectScripts(t, url, false);\n                syncDocStart(t);\n",
    "                injectScripts(t, url, false);\n                syncDocStart(t);\n                if (sp.getBoolean(\"force_zoom\", true)) v.evaluateJavascript(FORCE_ZOOM_JS, null);\n")
rep('        l.add(SettingItem.toggle("open_tabs_first",',
    '        l.add(SettingItem.toggle("force_zoom", "Масштабирование на всех сайтах",\n'
    '                "Разрешает масштаб щипком и там, где страница запретила его (user-scalable=no); применяется при загрузке страницы", true));\n'
    '        l.add(SettingItem.toggle("open_tabs_first",')

if src.count("{") != src.count("}"):
    sys.exit("BRACE BALANCE FAILED: %d open, %d close" % (src.count("{"), src.count("}")))

open(path, "w", encoding="utf-8").write(src)
print("patched OK:", path)
