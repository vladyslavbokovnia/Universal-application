package im.manus.plugins;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.accessibilityservice.AccessibilityService;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import im.manus.universalhost.IPlugin;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class RecentAppsPanelModule implements IPlugin {
    private WindowManager windowManager;
    private HorizontalScrollView scrollView;
    private LinearLayout container;
    private Context context;
    private AccessibilityService accessibilityService;

    @Override
    public String getName() { return "RecentAppsPanel"; }
    @Override
    public int getVersion() { return 1; }
    @Override
    public String getDescription() { return "Прокручиваемая панель недавних приложений в статус-баре"; }
    @Override
    public String getIconName() { return "ic_launcher"; }

    @Override
    public void init(Context context) {
        this.context = context;
        if (context instanceof AccessibilityService) {
            this.accessibilityService = (AccessibilityService) context;
        }
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        // Создаем контейнер
        scrollView = new HorizontalScrollView(context);
        scrollView.setBackgroundColor(Color.parseColor("#CC000000")); // 80% прозрачный черный
        
        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setPadding(20, 10, 20, 10);
        container.setGravity(Gravity.CENTER_VERTICAL);
        
        scrollView.addView(container);

        // Параметры окна: ширина экрана, высота ~2 статус-бара (160px)
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            180, 
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP;
        params.x = 0;
        params.y = 0;

        windowManager.addView(scrollView, params);
        
        updateRecentApps();
    }

    private void updateRecentApps() {
        if (container == null) return;
        container.removeAllViews();

        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        long time = System.currentTimeMillis();
        List<UsageStats> appList = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 3600 * 24, time);

        if (appList != null && !appList.isEmpty()) {
            // Сортируем по времени последнего использования
            Collections.sort(appList, new Comparator<UsageStats>() {
                @Override
                public int compare(UsageStats a, UsageStats b) {
                    return Long.compare(b.getLastTimeUsed(), a.getLastTimeUsed());
                }
            });

            PackageManager pm = context.getPackageManager();
            int count = 0;
            for (UsageStats usageStats : appList) {
                if (count > 15) break; // Ограничим до 15 приложений
                final String packageName = usageStats.getPackageName();
                
                // Пропускаем наше собственное приложение
                if (packageName.equals(context.getPackageName())) continue;

                try {
                    Drawable icon = pm.getApplicationIcon(packageName);
                    ImageView iconView = new ImageView(context);
                    iconView.setImageDrawable(icon);
                    
                    LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(120, 120);
                    iconParams.setMargins(15, 0, 15, 0);
                    iconView.setLayoutParams(iconParams);
                    
                    iconView.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
                            if (launchIntent != null) {
                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                context.startActivity(launchIntent);
                            }
                        }
                    });
                    
                    container.addView(iconView);
                    count++;
                } catch (Exception e) {
                    // Игнорируем приложения без иконок
                }
            }
        }
    }

    @Override
    public void stop() {
        if (windowManager != null && scrollView != null) {
            try {
                windowManager.removeView(scrollView);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public Object execute(Map<String, ?> data) {
        updateRecentApps();
        return null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event, AccessibilityService service) {
        // Обновляем панель при смене окон
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            updateRecentApps();
        }
    }
}
