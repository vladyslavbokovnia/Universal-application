package im.manus.universalhost

import android.service.notification.NotificationListenerService

/**
 * Пустой слушатель уведомлений. Сам он ничего не делает: наличие включённого слушателя даёт
 * хосту право MediaSessionManager.getActiveSessions, через которое модули управляют плеерами
 * напрямую (play/pause, треки, точная перемотка), независимо от приложения на переднем плане.
 */
class CoreNotificationListener : NotificationListenerService()
