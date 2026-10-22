#!/usr/bin/env python3
"""Патч для modules/Browser.java поверх уже применённых browser_hub.py и первой версии экрана скриптов.
Заменяет экран «Скрипты и их настройки» (переключатели скриптов и сырые значения GM_setValue) на экран «Скрипты»
как в окне Tampermonkey: для каждого скрипта строка с переключателем включения, под ней его команды
из GM_registerMenuCommand со значком шестерёнки. Нажатие запускает команду, а список сам обновляется,
потому что скрипты переименовывают команду после нажатия («Жесты наклона: Вкл» -> «Жесты наклона: Выкл»).
Использование: python3 browser_scripts.py modules/Browser.java"""
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "modules/Browser.java"
src = open(path, encoding="utf-8").read()


def rep(old, new, count=1):
    global src
    n = src.count(old)
    if n != count:
        sys.exit("ANCHOR FAILED (%d found, %d expected): %s" % (n, count, old[:90].replace("\n", "\\n")))
    src = src.replace(old, new)


def rep_between(start, end, new):
    global src
    if src.count(start) != 1:
        sys.exit("START ANCHOR FAILED (%d found): %s" % (src.count(start), start[:90]))
    a = src.index(start)
    b = src.find(end, a)
    if b < 0:
        sys.exit("END ANCHOR FAILED: " + end[:90])
    src = src[:a] + new + src[b:]


SCRIPTS_CODE = r'''        private boolean scriptsDirty;
        private int scriptsScrollY;
        private View scriptsLayer;
        private ScrollView scriptsScroll;

        private View scriptSwitchRow(String text, boolean on, android.widget.CompoundButton.OnCheckedChangeListener l) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(10), dp(4), dp(10));
            final android.widget.Switch sw = new android.widget.Switch(act);
            sw.setChecked(on);
            sw.setContentDescription(text);
            sw.setOnCheckedChangeListener(l);
            row.addView(sw, new LinearLayout.LayoutParams(-2, -2));
            TextView tv = label(text, 18, C_TEXT, true);
            tv.setPadding(dp(14), 0, 0, 0);
            row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { sw.toggle(); }
            });
            return row;
        }

        /** Экран как окно Tampermonkey: скрипты с переключателями, под каждым его команды меню. Изменения включения применяются обновлением страницы при закрытии. */
        private void showScripts(Tab t) {
            scriptsDirty = false;
            scriptsScrollY = 0;
            buildScripts(t);
        }

        private void buildScripts(final Tab t) {
            closeLayer(false);                      // обновление списка не должно запускать обновление страницы
            final ScrollView sv = new ScrollView(act);
            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(14), dp(14), dp(14), dp(28));
            sv.addView(col, new ViewGroup.LayoutParams(-1, -2));
            col.addView(panelTitle("Скрипты"));
            List<UserScript> scripts = ScriptStore.list();
            if (scripts.isEmpty()) {
                col.addView(label("Скриптов нет. Папка: " + ScriptStore.dir().getPath(), 15, C_TEXT2, false));
            }
            for (final UserScript s : scripts) {
                col.addView(scriptSwitchRow("📄  " + s.name + (s.version.length() > 0 ? " " + s.version : ""),
                        sp.getBoolean("script_on:" + s.fileName, true),
                        new android.widget.CompoundButton.OnCheckedChangeListener() {
                            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                                sp.edit().putBoolean("script_on:" + s.fileName, on).apply();
                                scriptsDirty = true;
                            }
                        }));
                for (final MenuCmd m : new ArrayList<MenuCmd>(t.menu)) {
                    if (!m.inj.script.fileName.equals(s.fileName)) continue;
                    LinearLayout row = new LinearLayout(act);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(20), dp(10), dp(4), dp(10));
                    TextView gear = label("⚙", 22, C_TEXT2, false);
                    row.addView(gear, new LinearLayout.LayoutParams(dp(34), -2));
                    row.addView(label(m.name, 16, C_TEXT, false), new LinearLayout.LayoutParams(0, -2, 1f));
                    row.setClickable(true);
                    row.setContentDescription(m.name);
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (t.wv != null) t.wv.evaluateJavascript("try{window['" + m.inj.disp + "'].m(" + m.id + ")}catch(e){}", null);
                            final View mine = scriptsLayer;
                            h.postDelayed(new Runnable() {
                                @Override public void run() {
                                    if (closed || layer == null || layer != mine) return;
                                    if (scriptsScroll != null) scriptsScrollY = scriptsScroll.getScrollY();
                                    buildScripts(t);       // скрипт мог переименовать команду, например «Жесты наклона: Вкл» -> «Выкл»
                                }
                            }, 450);
                        }
                    });
                    col.addView(row);
                }
            }
            showLayer(sv, true, new Runnable() {
                @Override public void run() {
                    if (scriptsDirty && t.wv != null) {
                        scriptsDirty = false;
                        t.wv.reload();
                    }
                }
            }, "Скрипты");
            scriptsLayer = layer;
            scriptsScroll = sv;
            sv.post(new Runnable() {
                @Override public void run() { sv.scrollTo(0, scriptsScrollY); }
            });
        }

'''

rep_between("        private boolean scriptsDirty;\n\n        private View switchRow(",
            "        private void showScriptCommands(final Tab t) {",
            SCRIPTS_CODE)
rep('"scripts", "📜", "Скрипты и их настройки"', '"scripts", "📜", "Скрипты"')

if src.count("{") != src.count("}"):
    sys.exit("BRACE BALANCE FAILED: %d open, %d close" % (src.count("{"), src.count("}")))

open(path, "w", encoding="utf-8").write(src)
print("patched OK:", path)
