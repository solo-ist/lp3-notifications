# Notifications — a top-level notification screen for the Light Phone III

One entry in the LightOS toolbox that shows everything currently notifying, so
dealing with a notification doesn't mean being pulled out of the phone and into
an app.

- **Tap** a notification to go where it wanted to send you (its `contentIntent`).
  Tapping only opens — it never dismisses
- **Swipe a row sideways** to dismiss it, as the system shade does
- **Long-press** for Dismiss / Hide that app here / Silence that app in Android
- **Clear all** at the end of the list

Opening and dismissing are deliberately separate gestures. An earlier version
dismissed on tap, which meant you couldn't look at something without
destroying it.

Tapping sends the notification's `PendingIntent` with
`setPendingIntentBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_ALLOWED)`.
Without it Android 14 blocks the launch — the notification's app is nearly
always a cached background process, and sending its intent does not by itself
grant it permission to start an activity. It fails silently: no crash, no
toast, just a `Background activity launch blocked` line in logcat. The system
shade grants the same privilege when you tap a notification.

## It stores nothing

`NotificationListenerService.getActiveNotifications()` already *is* the live
state of the shade, so there is no cache to keep and no history to write. The
list is read when the screen opens and forgotten when it closes.

That is a deliberate constraint rather than a shortcut. A notification listener
sees the content of every notification on the device — including Signal message
bodies by way of Molly, which goes to some trouble to encrypt its own storage.
Writing that to a second app's disk would quietly undo it. So: nothing on disk,
no scrollback, and `allowBackup="false"` with explicit data-extraction rules so
it can't leave the device either.

Two small things are persisted, both deliberately bounded:

- the package names of apps you've hidden, which contain no notification data
- fingerprints of individual notifications you've hidden — see below

Neither is a history. There is no record of what arrived, only of what you
asked not to see.

## "Mute" means two different things

They look identical in a menu, so the UI names them apart:

| Action | Whose setting | What it does |
|---|---|---|
| **Hide *app* here** | this app's | Stops it appearing on this screen. It still posts, still buzzes, still sits in the real shade. |
| **Silence *app* in Android** | Android's | Actually silences it — but no app can change another app's notification settings, so this only *opens the system screen* for it. |

## The hidden panel

Anything you've hidden — whole apps, or individual notifications — lives on a
second panel to the right of the list. **Swipe left** to reach it, **swipe
right** or press **Back** to return, or tap the `Hidden (n) →` row at the
bottom of the list.

It uses the same treatment as the notifications themselves: title over a muted
line, no chrome. Each row says what hiding it means and tapping it undoes that.

This replaced a count-plus-dialog at the foot of the list, which was a poor
idea in practice: only about eight rows fit the screen, so with a normal
number of notifications the control was several scrolls below the fold and
effectively undiscoverable.

## Granting access

The system binds the listener only once notification access is granted. On
LightOS there is no Settings screen for it, so do it over adb:

```sh
adb shell cmd notification allow_listener \
  ist.solo.notifications/ist.solo.notifications.Listener
```

**Use `allow_listener`, not `settings put secure enabled_notification_listeners`.**
The latter replaces the whole list and would silently revoke any other listener
— BrightControl's lock-screen one, for instance.

Verify with `adb shell cmd notification allowed_listeners` (or, on builds where
that subcommand is missing, `adb shell settings get secure
enabled_notification_listeners`).

## What will actually show up here

Less than you'd expect, and that's the point. The LP3 has no Google Play
Services, so FCM push cannot work — Slack, Todoist, Bluesky and Claude will
never post anything. In practice this screen shows:

- **Molly** (Signal), via LightOS's UnifiedPush distributor
- **Home Assistant**, via its own WebSocket — the minimal flavour, not the Play one
- **LightOS system events** — missed calls, SMS, alarms, timers
- **Locally scheduled notifications** from anything that schedules its own

A single quiet place for the few things that genuinely arrive is a different
product from an inbox for everything, and a better fit for this phone.

## Toolbox visibility

Like [Menu](https://github.com/solo-ist/lp3-menu), this declares an empty
receiver for `com.thelightphone.sdk.ACTION_SDK_MARKER`, which is how LightOS
decides what counts as a "tool". With `LIGHTOS_SHOW_EXTERNAL_TOOLS=0` an app
without that marker doesn't appear in the toolbox at all.

That mechanism is undocumented — nothing describes it as an extension point —
so assume a LightOS update can close it.

## Build

No dependencies beyond the Android platform.

```sh
NOTIFICATIONS_SIGNING_PASSWORD=$(op read "op://Private/Notifications signing key/password") \
  ./scripts/release.sh
```

Release builds sign with a private identity at
`~/.android-keys/soloist-notifications.jks`; debug builds deliberately use a
different key and carry `applicationIdSuffix = ".debug"`, so a debuggable build
can never replace the release app while satisfying its certificate pin.

`scripts/verify-release.sh` is the gate: it requires an already-enrolled pin,
demands exactly one signer matching it, and rejects anything debuggable or with
backups enabled.

## Ongoing notifications

Some notifications can't be dismissed at all — media playback, foreground
services, "Controls is displaying over other apps". Their app keeps them
posted, so `cancelNotification()` is a no-op.

Rather than offer a Dismiss that silently does nothing, the long-press dialog
drops it and says why in its heading, offering **Hide this one** instead.
"Clear all" likewise only appears when at least one notification would actually
clear.

*Hide this one* is honest about being local: the notification stays posted in
the real shade, we simply stop listing it. It comes back when its **content
changes**, which is the distinction that makes it useful — "Controls is
displaying over other apps" never changes so it stays gone, while Spotify
returns on the next track and Home Assistant on the next reconnect.

What's stored for this is `StatusBarNotification.getKey()` — `pkg|user|id|tag`,
carrying no content — plus a truncated SHA-256 of the title and text. The hash
is a one-way fingerprint, not content, and is what lets a changed notification
reappear; a key alone is stable across updates, so hiding Spotify once would
hide it forever.

Every render prunes entries whose notification is no longer posted, so the
store can only ever describe what is on the device right now. It cannot
accumulate into a log of what you've seen.

One Android constraint worth knowing if you touch this code: an `AlertDialog`
shows a message **or** a list, never both — calling `setMessage()` alongside
`setItems()` silently suppresses the items. The explanation therefore lives in
the title.

## Status

Working on a Light Phone III on LightOS `582-release-lp3`, verified against
real notifications: the list renders, app names resolve, long-press offers the
three actions, and Dismiss cancels the notification system-wide (confirmed gone
from `dumpsys notification`, not merely hidden from the list).

Known rough edges:

- Rows are tall, so roughly three or four fit a screen. That suits the phone's
  typography but makes a busy list long.
- No refresh while open: the list is read on resume, so a notification arriving
  while you're looking at the screen won't appear until you leave and return.
