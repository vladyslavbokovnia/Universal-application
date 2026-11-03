package im.manus.universalhost

import android.app.admin.DeviceAdminReceiver

/**
 * Администратор устройства для модулей. Единственное право — force-lock: модуль ClockWallpaper
 * выключает экран после заставки (DevicePolicyManager.lockNow()). Включается из настроек модуля.
 */
class ModuleAdminReceiver : DeviceAdminReceiver()
