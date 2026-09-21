package ist.solo.notifications;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Everything currently notifying, in one place, so a notification doesn't have
 * to pull you out of the phone to deal with it.
 *
 * Nothing is stored. The list is read from the live listener each time the
 * screen opens; close it and there is no record anywhere.
 */
public class MainActivity extends Activity {

    private Hidden hidden;
    private LinearLayout rows;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hidden = new Hidden(this);

        ScrollView scroller = new ScrollView(this);
        scroller.setBackgroundColor(Style.BACKGROUND);
        scroller.setFillViewport(true);

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        rows.setPadding(pad, dp(16), pad, dp(32));
        scroller.addView(rows, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(scroller);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        rows.removeAllViews();

        Listener listener = Listener.get();
        if (listener == null) {
            renderNoAccess();
            return;
        }

        StatusBarNotification[] active;
        try {
            active = listener.getActiveNotifications();
        } catch (SecurityException e) {
            // Access can be revoked while we are running.
            renderNoAccess();
            return;
        }

        List<StatusBarNotification> shown = new ArrayList<>();
        int suppressed = 0;
        for (StatusBarNotification sbn : active == null ? new StatusBarNotification[0] : active) {
            if (hidden.contains(sbn.getPackageName())) {
                suppressed++;
            } else {
                shown.add(sbn);
            }
        }
        Collections.sort(shown, new Comparator<StatusBarNotification>() {
            @Override public int compare(StatusBarNotification a, StatusBarNotification b) {
                return Long.compare(b.getPostTime(), a.getPostTime()); // newest first
            }
        });

        if (shown.isEmpty()) {
            rows.addView(line(suppressed == 0
                    ? "Nothing waiting"
                    : "Nothing waiting (" + suppressed + " hidden)",
                    Style.MUTED, 26f, null, null));
            return;
        }

        for (final StatusBarNotification sbn : shown) {
            rows.addView(notificationRow(sbn));
        }

        rows.addView(line("Clear all", Style.MUTED, 24f, () -> {
            Listener l = Listener.get();
            if (l != null) l.cancelAllNotifications();
            render();
        }, null));

        if (suppressed > 0) {
            rows.addView(line(suppressed + " hidden", Style.MUTED, 18f, this::hiddenDialog, null));
        }
    }

    private void renderNoAccess() {
        rows.addView(line("No notification access", Style.FOREGROUND, 28f, null, null));
        rows.addView(line(
                "Grant it once over adb:\n\n"
                        + "adb shell cmd notification allow_listener \\\n"
                        + "  ist.solo.notifications/ist.solo.notifications.Listener\n\n"
                        + "Use allow_listener rather than writing "
                        + "enabled_notification_listeners directly — that replaces the "
                        + "whole list and would revoke any other listener.",
                Style.MUTED, 15f, null, null));
    }

    /** Two lines: what it says, then who it came from and when. */
    private LinearLayout notificationRow(final StatusBarNotification sbn) {
        Bundle extras = sbn.getNotification().extras;
        String title = text(extras.getCharSequence("android.title"));
        String body = text(extras.getCharSequence("android.text"));
        String app = appLabel(sbn.getPackageName());

        if (title.isEmpty()) {
            title = body.isEmpty() ? app : body;
            body = "";
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(14), 0, dp(14));

        TextView head = new TextView(this);
        head.setText(title);
        head.setTextColor(Style.FOREGROUND);
        head.setTypeface(Typeface.create(Style.FONT_FAMILY, Typeface.NORMAL));
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        head.setMaxLines(2);
        head.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(head);

        TextView sub = new TextView(this);
        sub.setText(body.isEmpty() ? app : app + " · " + body);
        sub.setTextColor(Style.MUTED);
        sub.setTypeface(Typeface.create(Style.FONT_FAMILY, Typeface.NORMAL));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        sub.setMaxLines(2);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(sub);

        row.setOnClickListener(v -> open(sbn));
        row.setOnLongClickListener(v -> {
            actions(sbn);
            return true;
        });
        return row;
    }

    private TextView line(String s, int colour, float sp, final Runnable onTap, final Runnable ignored) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(colour);
        tv.setTypeface(Typeface.create(Style.FONT_FAMILY, Typeface.NORMAL));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setGravity(Gravity.START);
        tv.setPadding(0, dp(18), 0, dp(18));
        if (onTap != null) tv.setOnClickListener(v -> onTap.run());
        return tv;
    }

    // ---- actions -----------------------------------------------------------

    /** Tap: go where the notification wanted to send you, then dismiss it. */
    private void open(StatusBarNotification sbn) {
        PendingIntent intent = sbn.getNotification().contentIntent;
        if (intent == null) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(sbn.getPackageName());
            if (launch == null) {
                Toast.makeText(this, "Nothing to open", Toast.LENGTH_SHORT).show();
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
            return;
        }
        try {
            intent.send();
            if (sbn.isClearable()) dismiss(sbn);
        } catch (PendingIntent.CanceledException e) {
            Toast.makeText(this, "That notification has expired", Toast.LENGTH_SHORT).show();
            render();
        }
    }

    private void actions(final StatusBarNotification sbn) {
        final String pkg = sbn.getPackageName();
        final String app = appLabel(pkg);
        String[] items = {
                "Dismiss",
                "Hide " + app + " here",
                "Silence " + app + " in Android",
        };
        new AlertDialog.Builder(this)
                .setTitle(app)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            dismiss(sbn);
                            break;
                        case 1:
                            // Ours: stops it appearing on this screen. It still
                            // posts, still buzzes, still sits in the real shade.
                            hidden.hide(pkg);
                            render();
                            break;
                        case 2:
                            // Android's: we cannot change another app's
                            // notification settings, only open the screen.
                            openSystemNotificationSettings(pkg);
                            break;
                    }
                })
                .show();
    }

    private void dismiss(StatusBarNotification sbn) {
        Listener l = Listener.get();
        if (l != null) l.cancelNotification(sbn.getKey());
        render();
    }

    private void hiddenDialog() {
        final List<String> pkgs = new ArrayList<>(hidden.all());
        if (pkgs.isEmpty()) return;
        Collections.sort(pkgs);
        String[] labels = new String[pkgs.size()];
        for (int i = 0; i < pkgs.size(); i++) labels[i] = appLabel(pkgs.get(i));

        new AlertDialog.Builder(this)
                .setTitle("Hidden here")
                .setItems(labels, (d, which) -> {
                    hidden.show(pkgs.get(which));
                    render();
                })
                .show();
    }

    private void openSystemNotificationSettings(String pkg) {
        Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "No settings screen for that app", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- helpers -----------------------------------------------------------

    private String appLabel(String pkg) {
        PackageManager pm = getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static String text(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
