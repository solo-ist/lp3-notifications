package ist.solo.notifications;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
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

    /** 0 = what's waiting, 1 = the hidden panel to its right. */
    private int panel = 0;

    private Hidden hidden;
    private HiddenNotifications hiddenOnes;
    private LinearLayout rows;
    private GestureDetector gestures;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hidden = new Hidden(this);
        hiddenOnes = new HiddenNotifications(this);

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

        // Swipe left to reach the hidden panel, right to come back — the same
        // gesture either way, so it reads as one surface with two pages
        // rather than a screen that opens a dialog.
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                // Ignore anything mostly vertical: the list scrolls that way.
                if (Math.abs(dx) < Math.abs(dy) * 1.5f || Math.abs(dx) < dp(64)) return false;
                // Only the hidden panel uses a whole-screen fling, to get
                // back. On the active list the horizontal axis belongs to the
                // rows, which swipe to dismiss. Use the "Hidden →" row to go
                // the other way.
                if (panel == 1 && dx > 0) {
                    panel = 0;
                    render();
                    return true;
                }
                return false;
            }
        });

        setContentView(scroller);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (gestures != null) gestures.onTouchEvent(event);
        return super.dispatchTouchEvent(event);
    }

    @Override
    public void onBackPressed() {
        // Back should come out of the hidden panel before it leaves the app.
        if (panel != 0) {
            panel = 0;
            render();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Opening the app should always land on what's waiting.
        panel = 0;
        render();
    }

    private void render() {
        rows.removeAllViews();

        // Two different failures that look the same if you only check for the
        // service instance: access genuinely not granted, versus granted but
        // not yet bound. After a process kill — force-stop, reboot, low memory
        // — the activity can start before onListenerConnected fires, and
        // treating that as "not granted" sends you to fix something that isn't
        // broken.
        if (!hasAccess()) {
            renderNoAccess();
            return;
        }

        Listener listener = Listener.get();
        if (listener == null) {
            renderConnecting();
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

        // Forget hidden entries whose notification is gone, so the store stays
        // a description of right now rather than a log of what you've seen.
        hiddenOnes.prune(active);

        if (panel == 1) {
            renderHidden(active);
        } else {
            renderActive(active);
        }
    }

    // ---- panel 0: what's waiting -------------------------------------------

    private void renderActive(StatusBarNotification[] active) {
        List<StatusBarNotification> shown = new ArrayList<>();
        int hiddenCount = 0;
        for (StatusBarNotification sbn : active == null ? new StatusBarNotification[0] : active) {
            if (hidden.contains(sbn.getPackageName()) || hiddenOnes.contains(sbn)) {
                hiddenCount++;
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
            rows.addView(line("Nothing waiting", Style.MUTED, 26f, null, null));
        } else {
            for (final StatusBarNotification sbn : shown) {
                rows.addView(notificationRow(sbn));
            }

            // Only offer Clear all if something would actually clear. Ongoing
            // notifications are kept posted by their app, so the button would
            // otherwise sit there doing nothing.
            boolean anyClearable = false;
            for (StatusBarNotification sbn : shown) {
                if (sbn.isClearable()) { anyClearable = true; break; }
            }
            if (anyClearable) {
                rows.addView(line("Clear all", Style.MUTED, 24f, () -> {
                    Listener l = Listener.get();
                    if (l != null) l.cancelAllNotifications();
                    render();
                }, null));
            }
        }

        // The way through to the panel on the right. Shown always, so the
        // panel is discoverable without knowing the swipe exists — and with a
        // count when there is something over there.
        String label = hiddenCount > 0 ? "Hidden (" + hiddenCount + ")  \u2192" : "Hidden  \u2192";
        rows.addView(line(label, Style.MUTED, 20f, () -> {
            panel = 1;
            render();
        }, null));
    }

    // ---- panel 1: what you've hidden ---------------------------------------

    private void renderHidden(StatusBarNotification[] active) {
        rows.addView(line("\u2190  Hidden", Style.MUTED, 20f, () -> {
            panel = 0;
            render();
        }, null));

        final List<String> hiddenApps = new ArrayList<>(hidden.all());
        Collections.sort(hiddenApps);

        final List<StatusBarNotification> hiddenNotifications = new ArrayList<>();
        for (StatusBarNotification sbn : active == null ? new StatusBarNotification[0] : active) {
            if (!hidden.contains(sbn.getPackageName()) && hiddenOnes.contains(sbn)) {
                hiddenNotifications.add(sbn);
            }
        }

        if (hiddenApps.isEmpty() && hiddenNotifications.isEmpty()) {
            rows.addView(line("Nothing hidden", Style.MUTED, 26f, null, null));
            return;
        }

        if (!hiddenApps.isEmpty()) {
            for (final String pkg : hiddenApps) {
                LinearLayout row = twoLine(appLabel(pkg), "App \u00b7 tap to show again");
                row.setOnClickListener(v -> { hidden.show(pkg); render(); });
                rows.addView(row);
            }
        }

        for (final StatusBarNotification sbn : hiddenNotifications) {
            String t = text(sbn.getNotification().extras.getCharSequence("android.title"));
            String title = t.isEmpty() ? appLabel(sbn.getPackageName()) : t;
            LinearLayout row = twoLine(title, appLabel(sbn.getPackageName()) + " \u00b7 tap to show again");
            final String entry = HiddenNotifications.fingerprint(sbn);
            row.setOnClickListener(v -> { hiddenOnes.show(entry); render(); });
            rows.addView(row);
        }
    }

    /** A row in the same shape as a notification: title over a muted line. */
    private LinearLayout twoLine(String title, String sub) {
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

        TextView s2 = new TextView(this);
        s2.setText(sub);
        s2.setTextColor(Style.MUTED);
        s2.setTypeface(Typeface.create(Style.FONT_FAMILY, Typeface.NORMAL));
        s2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        s2.setMaxLines(1);
        s2.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(s2);
        return row;
    }

    /** Is our listener in the system's allow-list? Readable without any permission. */
    private boolean hasAccess() {
        String allowed = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        if (allowed == null || allowed.isEmpty()) return false;
        String me = new ComponentName(this, Listener.class).flattenToString();
        String meShort = new ComponentName(this, Listener.class).flattenToShortString();
        for (String entry : allowed.split(":")) {
            String e = entry.trim();
            if (e.equals(me) || e.equals(meShort)) return true;
        }
        return false;
    }

    /**
     * Granted, but the system hasn't bound us yet. Ask it to, and look again
     * shortly rather than telling the user to fix a permission they already
     * gave.
     */
    private void renderConnecting() {
        rows.addView(line("Connecting\u2026", Style.MUTED, 26f, null, null));
        try {
            NotificationListenerService.requestRebind(new ComponentName(this, Listener.class));
        } catch (Exception ignored) {
            // Best effort; the retry below covers it either way.
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) render();
        }, 700);
    }

    private void renderNoAccess() {
        rows.addView(line("No notification access", Style.FOREGROUND, 28f, null, null));
        // Use the real package name: debug builds carry a .debug suffix, so a
        // hardcoded string would tell you to grant a component that isn't
        // the one installed.
        String component = getPackageName() + "/" + Listener.class.getName();
        rows.addView(line(
                "Grant it once over adb:\n\n"
                        + "adb shell cmd notification allow_listener \\\n"
                        + "  " + component + "\n\n"
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

        // Don't repeat yourself: some system notifications use the app name as
        // their title, which otherwise renders as "Android System / Android
        // System".
        String subtitle;
        if (body.isEmpty()) {
            subtitle = app.equals(title) ? "" : app;
        } else {
            subtitle = app.equals(title) ? body : app + " · " + body;
        }

        TextView sub = new TextView(this);
        sub.setText(subtitle);
        sub.setVisibility(subtitle.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
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
        if (sbn.isClearable()) attachSwipeToDismiss(row, sbn);
        return row;
    }

    /**
     * Drag a clearable row sideways to dismiss it, as the system shade does.
     *
     * The row owns the whole gesture. Returning false from ACTION_DOWN to
     * "let the click through" does not work — a view that declines the down
     * event receives neither the moves nor the up — so tap and long-press are
     * dispatched here instead, via a GestureDetector.
     *
     * Only clearable rows get this. An ongoing one would slide back and do
     * nothing, reading as broken rather than refused; those keep "Hide this
     * one" under long-press.
     */
    private void attachSwipeToDismiss(final android.view.View row, final StatusBarNotification sbn) {
        final float slop = dp(10);
        final float commit = dp(100);

        final GestureDetector taps = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent e) { return true; }
                    @Override public boolean onSingleTapUp(MotionEvent e) {
                        row.performClick();
                        return true;
                    }
                    @Override public void onLongPress(MotionEvent e) {
                        row.performLongClick();
                    }
                });

        row.setOnTouchListener(new android.view.View.OnTouchListener() {
            private float startX, startY;
            private boolean horizontal;

            @Override
            public boolean onTouch(android.view.View v, MotionEvent e) {
                taps.onTouchEvent(e);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = e.getRawX();
                        startY = e.getRawY();
                        horizontal = false;
                        return true;

                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - startX;
                        float dy = e.getRawY() - startY;
                        if (!horizontal && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                            horizontal = true;
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        if (horizontal) {
                            v.setTranslationX(dx);
                            v.setAlpha(Math.max(0.15f, 1f - Math.abs(dx) / (commit * 1.6f)));
                        }
                        return true;
                    }

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        float dx = e.getRawX() - startX;
                        if (horizontal && e.getActionMasked() == MotionEvent.ACTION_UP
                                && Math.abs(dx) > commit) {
                            dismiss(sbn);
                        } else if (horizontal) {
                            v.animate().translationX(0f).alpha(1f).setDuration(120).start();
                        }
                        return true;
                    }
                }
                return true;
            }
        });
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
            // Opening is not dismissing. Tapping used to clear the
            // notification too, which meant you could not look at something
            // without destroying it.
            //
            // The options matter. Android 14 blocks the activity start
            // otherwise: the PendingIntent's creator is usually a cached
            // background process, and sending its intent does not by itself
            // grant it permission to start an activity —
            //   Background activity launch blocked [callingPackage: …;
            //    callingUidHasAnyVisibleWindow: false; procState: CACHED]
            // so the tap silently did nothing. Granting the privilege is what
            // the system shade does when you tap a notification, and it is
            // legitimate here for the same reason: a user in a foreground app
            // asked for it.
            ActivityOptions opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            intent.send(this, 0, null, null, null, null, opts.toBundle());
        } catch (PendingIntent.CanceledException e) {
            Toast.makeText(this, "That notification has expired", Toast.LENGTH_SHORT).show();
            render();
        }
    }

    private void actions(final StatusBarNotification sbn) {
        final String pkg = sbn.getPackageName();
        final String app = appLabel(pkg);
        final boolean clearable = sbn.isClearable();

        // Offering Dismiss on an ongoing notification is a lie: the app keeps
        // it posted, so cancelNotification() is a no-op and the row simply
        // doesn't go away. Say so instead.
        String[] items = clearable
                ? new String[] {
                        "Dismiss this notification",
                        "Hide " + app + " here",
                        "Silence " + app + " in Android",
                }
                : new String[] {
                        // Not "Dismiss": the notification stays posted in the
                        // real shade, we only stop listing it. It returns if
                        // its content changes.
                        "Hide this one",
                        "Hide " + app + " here",
                        "Silence " + app + " in Android",
                };

        // The reason goes in the title, not setMessage(): an AlertDialog shows
        // a message OR a list, never both, and setting one silently suppresses
        // the other — which would leave an ongoing notification with no
        // actions at all.
        String heading = clearable ? app : app + "\nOngoing — can't be dismissed";

        new AlertDialog.Builder(this)
                .setTitle(heading)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            if (clearable) {
                                dismiss(sbn);
                            } else {
                                hiddenOnes.hide(sbn);
                                render();
                            }
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
