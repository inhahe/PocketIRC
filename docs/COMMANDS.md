# Pocket IRC slash command reference

This is the same content surfaced by `/help` and the help icon in the top app
bar, in alphabetical-ish (registration) order.

Conventions:

- `<x>` = required argument
- `[x]` = optional argument
- `...` = one or more
- `|` = alternatives

---

### `/join <#channel>`

Join a channel.

### `/part [#chan] [reason]`

Leave the current or named channel.

### `/cycle`

Part and rejoin the current channel.

### `/hop`

Alias for `/cycle`.

### `/msg <nick> <text>`

Send a private message; opens a query buffer.

### `/query <nick> <text>`

Alias for `/msg`.

### `/me <text>`

CTCP ACTION in the current buffer (`* nick text`).

### `/say <text>`

Send `<text>` to the current buffer as a normal message. Useful when text
starts with `/` and would otherwise parse as a command. From a `/on` hook
context, sends to the channel/nick that fired the event.

### `/nick <newnick>`

Change your nickname on this server.

### `/quit [reason]`

Disconnect this server.

### `/raw <line>`

Send a raw IRC line. Use with care.

### `/quote <line>`

Alias for `/raw`.

### `/whois <nick>`

Look up nick info; reply routed back to the current buffer.

### `/away [reason]`

Mark yourself away (or back if reason is empty).

### `/topic [#chan] [text]`

Show or set the channel topic.

### `/kick [#chan] <nick> [reason]`

Kick a user from the channel.

### `/mode <target> <flags...>`

Set channel or user modes.

### `/unban [#chan] <mask>`

Remove a ban (`-b`) on the given mask.

### `/invite <nick> [#chan]`

Invite a user to a channel.

### `/names [#chan]`

Refresh the nick list for a channel.

### `/notice <target> <text>`

Send a NOTICE to a nick or channel.

### `/ctcp <nick> <TAG> [data]`

Send a raw CTCP request.

### `/ping <nick>`

CTCP PING with current timestamp (latency check).

### `/list [filter]`

Open the channel list browser. Filters: `>50`, `<500`, `T<60`, `*python*`

### `/save`

Export the current buffer.

### `/export`

Alias for `/save`.

### `/chaninfo [#chan]`

Open the channel info screen for the current or named channel.

### `/chanmodes [#chan]`

Alias for `/chaninfo`.

### `/motd [target]`

Show the message of the day.

### `/lusers`

Show network user counts.

### `/admin [target]`

Show server admin information.

### `/time [target]`

Show server time.

### `/version [target]`

Show server software version.

### `/stats <query> [target]`

Server statistics.

### `/info [target]`

Show server build info.

### `/links [args]`

Show server-to-server links.

### `/who <mask|#chan>`

WHO query for users matching a pattern.

### `/userhost <nick> [<nick>...]`

Look up `user@host` for up to five nicks.

### `/server [name | host[:port]] [flags...]`

Connect to or reconfigure an IRC server. `/connect` is an alias.

**Three modes**, depending on what you give it:

- **No args**: dial the server of the current buffer.
- **A single bare name**: reconnect a saved or live server by display name.
  Matches the configured name, the hostname, the server id, or the
  `ISUPPORT NETWORK=` name the server announced.
- **Host + flags in a current network's window**: redial the **same** network
  entry against the new host. The id, buffers, channels, history, nick, SASL,
  autojoin, and notify list all stay put — only the underlying socket changes.
  Designed for netsplits and "this server is laggy, hop to a sibling" cases.
  Add `-p` to persist the change to DataStore (default is ephemeral and
  reverts on next launch). Add `-m` to instead create a brand-new entry
  alongside the current one.
- **Host + flags with no current network**: create a new ephemeral
  connection. Use `-p` to persist.

| Flag         | Meaning                                                 |
|--------------|---------------------------------------------------------|
| `-e`         | Force TLS                                               |
| `-t`         | STARTTLS (treated as TLS)                               |
| `-nick N`    | Nickname                                                |
| `-altnick N` | Alternate nickname                                      |
| `-user U`    | Username                                                |
| `-realname R`| Real name (use quotes for spaces)                       |
| `-name N`    | Display name shown in the network tree. If omitted, the tree shows the host until the server announces `NETWORK=` via ISUPPORT, then switches to that. |
| `-w PASS`    | Server password (PASS command)                          |
| `-l USER P`  | SASL PLAIN credentials                                  |
| `-p`         | **Persist**: save the server to DataStore so it auto-connects on next launch. |
| `-n`         | Don't connect now. The server is still added to the network tree as an idle entry — navigate to its `(status)` window and use `/reconnect` to dial it later. Combined with `-p`, the entry is also saved to disk with `autoReconnect` off so it stays dormant across app restarts. |
| `-m`         | Force creating a NEW network entry instead of modifying the current one. Use this when you want a parallel second connection rather than redialing the current entry against a new host. |

Port can also be prefixed: `+6697` = TLS.

Examples (assume you're in the (status) buffer of the "Libera" network):

```
/server cherryh.libera.chat                    # netsplit hop, ephemeral
/server -p cherryh.libera.chat                 # netsplit hop, persisted
/server -p -nick newnick                       # change just the saved nick
/server                                        # redial current
/server -m -e irc.oftc.net:6697                # spawn parallel OFTC entry
/server -m -p -e irc.oftc.net:6697             # spawn AND save it
/server -m -p -n -e irc.oftc.net:6697          # spawn, save, don't dial
/server -e -nick mynick -l mynick s3cret -m irc.libera.chat:6697  # second Libera with different nick
```

### `/connect [name | host[:port]] [flags...]`

Alias for `/server` with identical behavior — use whichever verb feels more
natural.

- **No args**: dial the server of the current buffer.
- **A name**: reconnect by display name. Matches saved configs and live
  connections by configured name, hostname, server id, or `ISUPPORT NETWORK=`
  announcement (all case-insensitive).
- **Flags**: create or modify a connection. See `/server` below for the full
  flag list.

### `/reconnect`

Reconnect the current server using its first endpoint.

### `/nextserver`

Cycle the current network's connection to the next endpoint in its endpoint
list, wrapping around to the first when it runs out. Useful during netsplits
and for laggy individual servers when you've configured multiple endpoints
for the network. The same network entry is reused — buffers, channels,
history, nick, and SASL all stay put.

Alias: `/failover`.

### `/clear`

Clear the current buffer.

### `/echo <text>`

Print a local-only line in this buffer.

### `/help [command]`

List commands, or show usage for a single command. Also accessible via the
help icon in the top app bar.

### `/set` / `/set <key>` / `/set <key> <value>`

View or change an app setting. With no args, lists every key and its current
value as system lines in the buffer. With one arg, prints that key and its
description. With two, updates it.

| Key                          | Type   | Description                                                 |
|------------------------------|--------|-------------------------------------------------------------|
| `color_nicks`                | bool   | Tint each nickname a unique color                           |
| `theme`                      | enum   | `system`, `light`, or `dark`                                |
| `font_size`                  | int    | Chat font size in sp (10–28)                                |
| `timestamp_format`           | string | Java SimpleDateFormat (`HH:mm`, `hh:mm a`, `MMM d HH:mm`)   |
| `monospace`                  | bool   | Render chat in a fixed-width font                           |
| `send_on_enter`              | bool   | Enter sends; Shift/Ctrl+Enter inserts a newline             |
| `quit_message`               | string | Default `/quit` reason when none is given                   |
| `mention_notifications`      | bool   | Notify on channel mentions of your nick                     |
| `privmsg_notifications`      | bool   | Notify on private messages                                  |
| `notice_notifications`       | bool   | Notify on user-sourced NOTICEs                              |
| `notification_reply`         | bool   | Add an inline Reply action to mention notifications         |
| `notify_list_notifications`  | bool   | Notify when a watched nick comes online or offline          |
| `default_alert_sound`        | string | `content://` URI for the default `/alert` sound             |

Bool values accept: `true`/`false`, `on`/`off`, `yes`/`no`, `1`/`0`.

The `startup_script` and `on_hooks_json` settings are intentionally **not**
exposed via `/set` — they're multi-line / structured and live in the Settings
screen and `/on` command respectively.

Examples:

```
/set send_on_enter on
/set theme dark
/set font_size 18
/set                        # list every key with its current value
/set timestamp_format       # show one key
```

### `/window [-k network] <#chan|nick|status>`

Switch the active buffer. Aliases: `/win`, `/buffer`. Without `-k`, the
current server is searched first and any server is searched as a fallback.
With `-k`, the lookup is strict to that one server (matched by display name
or server id, case-insensitive).

You can also use the shorthand `network/name` form instead of `-k`:

```
/window Libera/#pocketirc
/window -k Libera #pocketirc
/win #pocketirc
/buffer status
/win -k OFTC NickServ
```

Use `status` (or `(status)`) to jump to a server's status window. The
command does **not** create new buffers — it only switches to ones that are
already open. Use `/join` or `/msg` to open new ones first.

### `/win [-k network] <#chan|nick|status>`

Alias for `/window`.

### `/buffer [-k network] <#chan|nick|status>`

Alias for `/window`.

### `/alert [-s sound-uri] <text>`

Fire a system notification immediately. Default sound is the system
notification tone unless `-s` names a `content://` URI.

### `/notify` / `/notify <nick>...` / `/notify -r <nick>...`

Manage this server's notify list (the watched-for-online nicks). No args
shows the current list, names add to the list, `-r names` removes from it.
Uses MONITOR if the server supports it, ISON polling otherwise.

### `/noop`

Do nothing. Useful as a placeholder action for `/on` hooks that only want
to trigger `-d` / `-s` side effects.

### `/on [-p] [-name N] [-n mask] [-c #ch] [-k network] [-pat pattern] [-d] [-s sound] [-x] <event> [command]`

Register an event hook.

**Events:** `chanmsg`, `privmsg`, `action`, `notice`, `join`, `part`, `quit`,
`kick`, `nick`, `topic`, `invite`, `connect`, `disconnect`, `notify_online`,
`notify_offline`, `numeric`.

**Filters:**

| Flag        | Meaning                                                          |
|-------------|------------------------------------------------------------------|
| `-n mask`   | nick!user@host wildcard (`*` `?`)                                |
| `-c #chan`  | channel filter (literal)                                         |
| `-k network`| match only on this network name                                  |
| `-pat P`    | glob (`*foo*`) or `/regex/[ims]` over the message text           |
| `-name N`   | give the hook an explicit name                                   |
| `-p`        | persist across restarts (saved to settings)                      |
| `-d`        | raise a system notification when the hook fires                  |
| `-s sound`  | notification sound URI (implies `-d`)                            |
| `-x`        | suppress default handling — drop the event so it isn't rendered, |
|             | doesn't notify, doesn't bump unread                              |

**Action variables:**

`{nick}` `{user}` `{channel}` `{target}` `{text}` `{server}`
`{oldnick}` `{newnick}` `{reason}`

Both `{name}` and `$name` syntax are accepted. Use `$$` for a literal `$`.
Unknown placeholders are left untouched so typos are visible.

**List hooks:**

```
/on -l [event]
```

**Remove a hook:**

```
/on -r [-p] <event> <name>
```

**Example:**

```
/on -p -c #pocketirc chanmsg /echo {nick} said: {text}
/on -p -d -k Libera notify_online /alert {nick} is online
/on -p -x -n spambot!*@* chanmsg /noop
```
