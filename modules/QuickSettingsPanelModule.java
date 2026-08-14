package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;
import im.manus.universalhost.IPlugin;
import java.util.Map;

public class QuickSettingsPanelModule implements IPlugin {
    private WindowManager windowManager;
    private HorizontalScrollView scrollView;
    private LinearLayout container;
    private Context context;
    private AccessibilityService accessibilityService;

    @Override
    public String getName() { return "QuickSettingsPanel"; }
    @Override
    public int getVersion() { return 1; }
    @Override
    public String getDescription() { return "Панель быстрых настроек поверх статус-бара"; }
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
        scrollView.setBackgroundColor(Color.BLACK);
        
        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setPadding(20, 10, 20, 10);
        container.setGravity(Gravity.CENTER_VERTICAL);
        
        scrollView.addView(container);

        // Параметры окна: ширина экрана, высота ~1.5 статус-бара
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            140, 
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP;
        params.y = 0; // Прямо поверх статус-бара

        windowManager.addView(scrollView, params);
        
        setupButtons();
    }

    private void setupButtons() {
        // Wi-Fi
        addButton(android.R.drawable.ic_dialog_info, "Wi-Fi", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                context.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        });

        // Мобильные данные
        addButton(android.R.drawable.ic_menu_share, "Data", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                context.startActivity(new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        });

        // Bluetooth
        addButton(android.R.drawable.stat_sys_data_bluetooth, "BT", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                context.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        });

        // Яркость
        addButton(android.R.drawable.ic_menu_compass, "Bright", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                context.startActivity(new Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        });

        // Фонарик (через системные настройки или быстрые действия)
        addButton(android.R.drawable.ic_menu_camera, "Flash", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(context, "Flash toggle via settings", Toast.LENGTH_SHORT).show();
                // На многих устройствах прямой доступ к фонарику требует CameraManager
            }
        });
        
        // Звук
        addButton(android.R.drawable.ic_lock_silent_mode, "Sound", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                context.startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        });
    }

    private void addButton(int iconRes, String tag, View.OnClickListener listener) {
        ImageView iconView = new ImageView(context);
        iconView.setImageResource(iconRes);
        iconView.setColorFilter(Color.WHITE);
        iconView.setPadding(20, 10, 20, 10);
        
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(120, 120);
        lp.setMargins(10, 0, 10, 0);
        iconView.setLayoutParams(lp);
        
        iconView.setOnClickListener(listener);
        container.addView(iconView);
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
    public Object execute(Map<String, ?> data) { return null; }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event, AccessibilityService service) {}
}
