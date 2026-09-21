package ist.solo.notifications;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.lang.ref.WeakReference;

/**
 * The system's notification feed.
 *
 * Deliberately stores nothing. {@link #getActiveNotifications()} already *is*
 * the live state of the shade, so there is no cache to keep, no history to
 * write, and nothing on disk to leak. A notification listener sees the content
 * of every notification on the device — including Signal message bodies by way
 * of Molly — and the safest thing to do with that is hold it only for as long
 * as the screen is showing it.
 *
 * The service is bound by the system once notification access is granted:
 *
 *   adb shell cmd notification allow_listener \
 *     ist.solo.notifications/ist.solo.notifications.Listener
 *
 * Use `cmd notification allow_listener`, not
 * `settings put secure enabled_notification_listeners` — the latter replaces
 * the whole list and would silently revoke any other listener, such as
 * BrightControl's lock-screen one.
 */
public class Listener extends NotificationListenerService {

    /**
     * Weak so a destroyed service can be collected; the activity treats a null
     * here as "access not granted yet".
     */
    private static WeakReference<Listener> connected = new WeakReference<>(null);

    static Listener get() {
        return connected.get();
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        connected = new WeakReference<>(this);
    }

    @Override
    public void onListenerDisconnected() {
        connected = new WeakReference<>(null);
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // Nothing to do. The list is read on demand when the screen opens, so
        // there is no point mirroring state we would then have to keep correct.
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Likewise.
    }
}
