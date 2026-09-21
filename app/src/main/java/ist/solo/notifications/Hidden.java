package ist.solo.notifications;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * Which apps this screen declines to show.
 *
 * Worth being precise, because "mute" means two different things and they look
 * identical in a menu:
 *
 *   - Hiding an app here is *this app's* setting. The notification is still
 *     posted, still buzzes, still sits in the real shade. We just don't list
 *     it.
 *   - Actually silencing an app is *Android's* setting, and no app can change
 *     another app's notification settings. The best anyone can do is send you
 *     to the system screen for it, which is what the "Silence in Android"
 *     action does.
 *
 * The UI names them differently for that reason.
 */
final class Hidden {

    private static final String PREFS = "notifications";
    private static final String KEY = "hidden_packages";

    private final SharedPreferences prefs;

    Hidden(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    Set<String> all() {
        // Defensive copy: SharedPreferences returns a set you must not mutate.
        return new HashSet<>(prefs.getStringSet(KEY, new HashSet<>()));
    }

    boolean contains(String pkg) {
        return all().contains(pkg);
    }

    void hide(String pkg) {
        Set<String> next = all();
        next.add(pkg);
        prefs.edit().putStringSet(KEY, next).apply();
    }

    void show(String pkg) {
        Set<String> next = all();
        next.remove(pkg);
        prefs.edit().putStringSet(KEY, next).apply();
    }
}
