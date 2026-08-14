package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import im.manus.universalhost.IPlugin;
import java.util.Map;

public class RecentAppsModule implements IPlugin {
    private WindowManager windowManager;
    private ImageView overlayIcon;
    private AccessibilityService accessibilityService;
    private WindowManager.LayoutParams params;
    private SharedPreferences prefs;

    @Override
    public String getName() { return "RecentAppsOverlay"; }
    @Override
    public int getVersion() { return 2; }
    @Override
    public String getDescription() { return "Плавающая кнопка недавних приложений с перетаскиванием"; }
    @Override
    public String getIconName() { return "ic_launcher"; }

    @Override
    public void init(Context context) {
        if (context instanceof AccessibilityService) {
            this.accessibilityService = (AccessibilityService) context;
        }
        
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        prefs = context.getSharedPreferences("plugin_pos_" + getName(), Context.MODE_PRIVATE);
        
        overlayIcon = new ImageView(context);
        overlayIcon.setImageResource(android.R.drawable.ic_dialog_dialer); 
        overlayIcon.setBackgroundColor(Color.parseColor("#80000000")); // Полупрозрачный черный фон
        overlayIcon.setPadding(10, 10, 10, 10);
        
        // Загружаем сохраненную позицию
        int savedX = prefs.getInt("x", 200);
        int savedY = prefs.getInt("y", 0);

        params = new WindowManager.LayoutParams(
            120, 120, // Немного больше для удобства
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
        
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = savedX;
        params.y = savedY;

        overlayIcon.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private boolean isMoving = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isMoving = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isMoving = true;
                        }
                        params.x = initialX + dx;
                        params.y = initialY + dy;
                        windowManager.updateViewLayout(overlayIcon, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!isMoving) {
                            // Если не двигали, значит это клик
                            if (accessibilityService != null) {
                                accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
                            }
                        } else {
                            // Сохраняем позицию
                            prefs.edit().putInt("x", params.x).putInt("y", params.y).apply();
                        }
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(overlayIcon, params);
    }

    @Override
    public void stop() {
        if (windowManager != null && overlayIcon != null) {
            try {
                windowManager.removeView(overlayIcon);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public Object execute(Map<String, ?> data) { return null; }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event, AccessibilityService service) {
        this.accessibilityService = service;
    }
}
