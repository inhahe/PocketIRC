# Pocket IRC

A small, fast, modern IRC client for Android phones and tablets, written in
Kotlin with Jetpack Compose and Material 3.

## Features

* Multi-network with auto-reconnect, per-server token-bucket rate limiting
* App-wide default identity (nicks, real name, username) with per-network overrides
* SASL PLAIN, server PASS, IRCv3 message-tags, MONITOR / ISON notify list
* Mention, PM, and notice system notifications with inline reply — respond
  directly from the notification without opening the app — plus notify-list
  online/offline notifications
* `/alert` and `/on -d` hook notifications routed through any number of
  user-defined notification channels, each with configurable sound, vibration,
  lights, and badge
* Tablet/landscape persistent split layout (server tree + chat + nick list)
* Searchable, exportable per-buffer history persisted in Room
* Channel info screen with mode flags, ban / except / invite / quiet lists
* `/list` channel browser, `/who`, `/whois` reply routing back to the
  originating buffer
* `/on` event hooks (chanmsg, privmsg, join, part, quit, kick, nick, etc.)
  with nick mask, channel, network, and text-pattern filters and
  `{var}` / `$var` substitution. Supports `-d` (notification), `-s` (sound),
  `-x` (suppress default), `-p` (persist across restarts).
* Editable startup script run at app launch
* mIRC color and formatting input via long-press send / leading icon
* IPv4/IPv6 family pinning per endpoint with custom TLS hostname verification
* No analytics, no tracking, no Google Play Services

## Status

Pre-1.0 — usable but the API and on-disk formats may shift between releases.

## Building

Requires JDK 17 and an Android SDK with platform 35.

```
./gradlew assembleDebug         # debug APK at app/build/outputs/apk/debug/
./gradlew assembleRelease       # unsigned release APK
./gradlew bundleRelease         # release AAB
```

## Documentation

* [Slash command reference](docs/COMMANDS.md) — every built-in `/command`
  with usage and a description.

## Contact

* **Bug reports & crash reports**: `inhahe6@gmail.com`, or open
  an issue at <https://github.com/inhahe/pocketirc/issues>.
* **Source**: <https://github.com/inhahe/pocketirc>

The in-app crash dialog can pre-fill either of these for you — see the
[privacy policy](PRIVACY.md) for the full details on how that works.

## License

MIT — see [LICENSE](LICENSE).
