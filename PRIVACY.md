# Pocket IRC — Privacy Policy

_Last updated: 2026_

Pocket IRC is an open-source IRC client for Android. This document explains
exactly what the app does and does not do with your data. The short version:
**Pocket IRC collects nothing about you, contains no analytics, tracking,
or advertising of any kind, and does not transmit anything anywhere unless
you explicitly choose to do so.** The only outbound network connections the
app initiates on its own are to the IRC servers you have configured.

## What Pocket IRC stores on your device

All of the following are stored locally on your device only. They are never
uploaded anywhere, shared with the developer, or transmitted to any third
party.

- **Server configurations** — host names, ports, your chosen nicknames,
  optional SASL credentials, optional server password, autojoin channel
  list, notify list, and per-server settings. Stored in Android's encrypted
  preferences (DataStore). Credentials are stored as plain text in
  DataStore the same way most IRC clients store them; if you need
  hardware-backed secret storage, do not use SASL passwords with this app.
- **Chat history** — messages you have sent and received in each buffer,
  with timestamps and sender nicks. Stored in a local Room (SQLite)
  database. Trimmed periodically to the most recent ~1000 lines per
  buffer. Cleared when you uninstall the app.
- **App settings** — your preferences (theme, font size, timestamp format,
  notification toggles, default `/alert` sound URI, the editable startup
  script, the persisted `/on` event hooks, etc.). Stored in DataStore.
- **Buffer state** — which buffers are open, which one is currently
  active, unread counters, and "last read" markers. Stored in DataStore.

You can wipe all of this at any time by clearing the app's data in
Android settings, or by uninstalling the app.

## What Pocket IRC sends over the network

Pocket IRC connects only to:

- **The IRC servers you explicitly add and connect to.** Pocket IRC
  speaks the standard IRC protocol (RFC 1459 / 2812 plus IRCv3
  extensions) to those servers. The data sent over those connections is
  whatever you and the IRC protocol require: nickname, username, real
  name, server password, SASL credentials, channel joins, messages you
  type, and the IRCv3 capabilities the app negotiates with the server
  (such as `message-tags` for typing notifications).
- **Nothing else.** No analytics, no crash reporting, no telemetry, no
  configuration check-in, no update check, no advertising network, no
  attribution SDK, no remote feature flags. The app contains zero
  third-party SDKs that "phone home." Specifically: no Google Play
  Services, no Firebase, no Crashlytics, no AdMob, no Google Analytics,
  no Facebook SDK, no Amplitude, no Mixpanel, no Sentry, no Bugsnag, no
  install-referrer libraries, no remote-config services.

You can verify this by reading the source code, which is
[publicly available](https://github.com/inhahe/pocketirc), and by
inspecting the app's network connections with any traffic-monitoring
tool. The only outbound TCP connections are to the hostnames you
configured as IRC servers.

## What the IRC servers themselves see

When you connect to an IRC server, that server's operators can see:

- Your IP address (this is fundamental to TCP/IP and unavoidable for any
  IRC client). Use a VPN or Tor if you wish to obscure it.
- Your nickname, username, and real-name field as you configured them.
- The contents of every message and command you send.
- The channels you join.
- Any IRCv3 capabilities your client negotiates.

This is true of every IRC client, not just Pocket IRC. Pocket IRC has
no influence over what an IRC server logs or shares.

## Crash and error reporting

If something goes wrong inside Pocket IRC — either an unhandled crash or a
caught error inside one of the app's features — the app **shows you a
dialog** with the stack trace and four buttons: **GitHub**, **Email**,
**Copy**, and **Dismiss**.

- **GitHub** opens your web browser to the project's issue tracker with
  the title and trace pre-filled. You can review what is about to be
  submitted, edit it, and choose whether to actually file the issue.
- **Email** opens your installed mail client with the report pre-filled
  to the project's contact address. You can edit and decide whether to
  send.
- **Copy** copies the trace to the clipboard so you can paste it
  somewhere else of your choosing.
- **Dismiss** discards the report.

In every case, **the app itself does not transmit anything**. The
information only leaves your device if you, after seeing the dialog,
choose to file the issue or send the email yourself. If you tap Dismiss,
the report is deleted and never sent. Pocket IRC has no telemetry
infrastructure to send anything automatically even if it wanted to.

The crash file (when it exists) is stored in the app's private storage
directory on your device until you act on it.

## Permissions

Pocket IRC requests only the Android permissions it needs to function:

- **`INTERNET`** — to open TCP sockets to IRC servers.
- **`POST_NOTIFICATIONS`** (Android 13+, runtime permission) — to show
  notifications for mentions, private messages, notices, watched-nick
  state changes, `/alert` calls, and `/on -d` hooks. Granting this
  permission is optional; if you decline, the app continues to work
  without notifications.
- **`FOREGROUND_SERVICE`** — to keep the IRC connection alive in the
  background so messages don't drop when the app isn't on screen.

Pocket IRC does not request access to your contacts, location,
microphone, camera, storage, SMS, call logs, or any other personal
data.

## Children

Pocket IRC is a general-purpose communication tool with no
age-restricted content of its own, but the IRC networks you connect to
may host chat that is unsuitable for minors. Pocket IRC does not
collect personal information from anyone, including children. If you
are responsible for a minor, decide for yourself whether IRC is
appropriate for them; the app neither targets nor excludes them.

## Changes to this policy

If a future version of Pocket IRC ever changes how it handles data,
this document will be updated and the change will be noted in the
project's changelog. Pocket IRC will never start collecting telemetry
or analytics in a future version. If that ever changed, it would be a
fundamental change to the project and the source code would be the
public record.

## Contact

Pocket IRC is a hobby project distributed under the MIT License. It
has no support staff. The best way to ask questions, report a bug, or
raise a privacy concern is to open an issue at:

<https://github.com/inhahe/pocketirc/issues>

## Open source

The complete source code of Pocket IRC is available at
<https://github.com/inhahe/pocketirc> under the MIT License. You are
welcome to inspect, build, modify, and redistribute it.
