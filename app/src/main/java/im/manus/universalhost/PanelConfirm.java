package im.manus.universalhost;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Подтверждение и выбор панелью внутри окна вместо системного диалога: на части телефонов диалог рисуется
 * белым шрифтом на белом фоне. Чёрный фон, белый текст, белая рамка; закрывается кнопкой, касанием вне панели
 * и «Назад».
 */
public final class PanelConfirm {
    private PanelConfirm() { }

    /** Выбор пункта списка. */
    public interface OnPick {
        void pick(int index);
    }

    private static final class Overlay {
        FrameLayout scrim;
        LinearLayout box;
        Runnable close;
        float d;
    }

    /** null: окна-хозяина нет, панель не показана. */
    private static Overlay open(Context ctx, int gravity) {
        ViewGroup host = null;
        if (ctx instanceof Activity) {
            try { host = (ViewGroup) ((Activity) ctx).findViewById(android.R.id.content); } catch (Throwable t) { }
        }
        if (host == null) return null;
        final ViewGroup fHost = host;
        final Overlay o = new Overlay();
        o.d = ctx.getResources().getDisplayMetrics().density;
        o.scrim = new FrameLayout(ctx);
        o.scrim.setBackgroundColor(0xCC000000);
        o.scrim.setClickable(true);
        o.close = new Runnable() {
            @Override public void run() { fHost.removeView(o.scrim); }
        };
        o.scrim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { o.close.run(); }
        });
        o.scrim.setFocusableInTouchMode(true);
        o.scrim.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent ev) {
                if (keyCode == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP) o.close.run();
                return keyCode == KeyEvent.KEYCODE_BACK;
            }
        });
        o.box = new LinearLayout(ctx);
        o.box.setOrientation(LinearLayout.VERTICAL);
        o.box.setClickable(true);                       // касание внутри панели не закрывает её
        o.box.setPadding((int) (20 * o.d), (int) (18 * o.d), (int) (20 * o.d), (int) (18 * o.d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF111111);
        bg.setStroke(Math.max(1, (int) o.d), Color.WHITE);
        bg.setCornerRadius(12 * o.d);
        o.box.setBackground(bg);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
        int m = (int) (gravity == Gravity.CENTER ? 24 * o.d : 8 * o.d);
        blp.setMargins(m, m, m, m);
        o.scrim.addView(o.box, blp);
        fHost.addView(o.scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return o;
    }

    private static TextView title(Context ctx, Overlay o, String text) {
        TextView q = new TextView(ctx);
        q.setText(text);
        q.setTextSize(20);
        q.setTextColor(Color.WHITE);
        q.setPadding(0, 0, 0, (int) (12 * o.d));
        return q;
    }

    /** false: окна-хозяина нет, панель не показана (вызывающий решает, что делать без подтверждения). */
    public static boolean show(Context ctx, String title, String yes, final Runnable onYes) {
        final Overlay o = open(ctx, Gravity.CENTER);
        if (o == null) return false;
        o.box.addView(title(ctx, o, title));
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {"✕ Отмена", yes};
        for (int k = 0; k < 2; k++) {
            final boolean ok = k == 1;
            TextView b = new TextView(ctx);
            b.setText(labels[k]);
            b.setTextSize(18);
            b.setTextColor(Color.WHITE);
            b.setGravity(Gravity.CENTER);
            b.setPadding((int) (12 * o.d), (int) (14 * o.d), (int) (12 * o.d), (int) (14 * o.d));
            GradientDrawable bb = new GradientDrawable();
            bb.setColor(ok ? 0xFF333333 : 0x00000000);
            bb.setStroke(Math.max(1, (int) o.d), Color.WHITE);
            bb.setCornerRadius(8 * o.d);
            b.setBackground(bb);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    o.close.run();
                    if (ok) onYes.run();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (k == 0) lp.rightMargin = (int) (8 * o.d);
            row.addView(b, lp);
        }
        o.box.addView(row);
        o.scrim.requestFocus();
        return true;
    }

    /** Список пунктов внизу окна; [selected] отмечен «●» (-1: без отметки). false: окна-хозяина нет. */
    public static boolean pick(Context ctx, String title, String[] labels, int selected, final OnPick cb) {
        final Overlay o = open(ctx, Gravity.BOTTOM);
        if (o == null) return false;
        o.box.addView(title(ctx, o, title));
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView r = new TextView(ctx);
            r.setText((selected >= 0 ? (i == selected ? "● " : "○ ") : "") + labels[i]);
            r.setTextSize(18);
            r.setTextColor(Color.WHITE);
            r.setPadding((int) (8 * o.d), (int) (12 * o.d), (int) (8 * o.d), (int) (12 * o.d));
            r.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    o.close.run();
                    cb.pick(idx);
                }
            });
            list.addView(r);
        }
        final int cap = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.65f);
        ScrollView sc = new ScrollView(ctx) {
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
            }
        };
        sc.addView(list);
        o.box.addView(sc);
        o.scrim.requestFocus();
        return true;
    }
}
