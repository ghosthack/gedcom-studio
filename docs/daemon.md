# The gedcom-shell daemon

The daemon is one background process that holds your open GEDCOM files. The shell, the web
app and the desktop app all work through it, so they share one set of edits: change a date
in the browser and the shell sees it at its next command; the desktop app updates within a
second. Nothing reaches the file until someone saves.

```
                    ┌────────────── gedcom-shell daemon ──────────────┐
 gedcom-shell  ───► │  shell sessions ─┐                              │
 (terminal)         │                  ├─► Store (lock + revision) ─► Genealogy ─► FILE.ged
 web app       ───► │  HTTP API  ──────┤      one per open file       │   (written on save)
 desktop app   ───► │  /api/v1/...  ───┘                              │
                    └─────────────────────────────────────────────────┘
```

## Starting and stopping

You rarely need to: `gedcom-shell FILE.ged`, `gedcom-shell web` and the desktop app start
the daemon when it isn't running. By hand:

```sh
gedcom-shell daemon start      # in the background
gedcom-shell daemon status     # address, pid, open files, unsaved edits
gedcom-shell daemon pair       # the address that pairs a browser (carries the token)
gedcom-shell daemon stop       # refuses while files have unsaved edits…
gedcom-shell daemon stop --force   # …unless forced: copies go to ~/.gedcom-shell/recovery/
gedcom-shell daemon run        # in this terminal, logging to it (for launchd/systemd)
```

The shell keeps working without it: `gedcom-shell -S FILE.ged` (or
`GEDCOM_SHELL_STANDALONE=1`) reads the file in its own process, as before. Scripted runs
(`-c`, piped input) use the daemon only when it is already running, so a script never
starts a background process. In a script that shares the daemon, end with `save`; edits
otherwise stay in the daemon, not in the file.

### Unsaved edits are never dropped

- Leaving a shell, closing the web page or quitting the desktop app keeps the edits in the
  daemon; any front end can save them later.
- `daemon stop` refuses while there are unsaved edits. With `--force`, or when the daemon
  is stopped by a signal (logout, `kill`), it first writes each file with unsaved edits to
  `~/.gedcom-shell/recovery/NAME.YYYYMMDD-HHMMSS.ged`. The original file is left as it was.
- `save` refuses when another program changed the file since it was read (it would
  overwrite those changes). `save -f` in the shell, or *Overwrite* in the apps, forces it.

### Keeping it running

The daemon is happy to run for weeks. To have it start at login:

**macOS** — `~/Library/LaunchAgents/com.github.ghosthack.gedcom-shell.plist`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>com.github.ghosthack.gedcom-shell</string>
  <key>ProgramArguments</key>
  <array>
    <string>/usr/bin/java</string>
    <string>-jar</string>
    <string>/PATH/TO/gedcom-shell/core/target/gedcom-shell.jar</string>
    <string>daemon</string>
    <string>run</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
  <key>StandardOutPath</key><string>/Users/YOU/.gedcom-shell/daemon.log</string>
  <key>StandardErrorPath</key><string>/Users/YOU/.gedcom-shell/daemon.log</string>
</dict>
</plist>
```
then `launchctl load ~/Library/LaunchAgents/com.github.ghosthack.gedcom-shell.plist`.

**Linux** — `~/.config/systemd/user/gedcom-shell.service`:

```ini
[Unit]
Description=gedcom-shell daemon

[Service]
ExecStart=/usr/bin/java -jar /PATH/TO/gedcom-shell/core/target/gedcom-shell.jar daemon run
Restart=on-failure

[Install]
WantedBy=default.target
```
then `systemctl --user enable --now gedcom-shell`.

`KeepAlive`/`Restart=on-failure` bring it back after a crash, not after `daemon stop`.

## Reaching it from other devices

By default the daemon listens on `127.0.0.1:7380`: this computer only.

### Over Tailscale (recommended)

[Tailscale](https://tailscale.com) gives your devices a private network with real HTTPS
names. Keep the daemon on `127.0.0.1` and let Tailscale carry it:

```sh
tailscale serve --bg 7380
```

Your phone or laptop on the same tailnet can now open
`https://MACHINE.TAILNET.ts.net/`. Pair it once with the address `gedcom-shell daemon pair`
prints, with the tailnet name in place of `http://127.0.0.1:7380/`. HTTPS also lets the web
app install as an app on the phone's home screen. Tailscale only serves to your tailnet;
don't use `tailscale funnel`, which would publish it to the internet.

### On the local network

Listen beyond this computer with `bind` in `~/.gedcom-shell/daemon.properties` (or
`daemon run --bind`):

```properties
bind=0.0.0.0
port=7380
tls.keystore=~/.gedcom-shell/daemon.p12
tls.password=choose-a-password
```

Without TLS, anyone on the network can read the traffic, token included; the daemon says
so when it starts. Make a certificate with the JDK's `keytool`:

```sh
keytool -genkeypair -alias gedcom-shell -keyalg EC -groupname secp256r1 -validity 3650 \
  -dname "CN=gedcom-shell" -ext "SAN=dns:MYMAC.local,ip:192.168.1.20" \
  -storetype PKCS12 -keystore ~/.gedcom-shell/daemon.p12 -storepass choose-a-password
```

Browsers will warn about a certificate they don't know until you trust it on each device;
Tailscale avoids that.

## Settings and files

All in `~/.gedcom-shell/` (`GEDCOM_SHELL_HOME` or `-Dgedcom.shell.home` moves it):

| | |
|---|---|
| `daemon.properties` | `port` (7380), `bind` (127.0.0.1), `tls.keystore`, `tls.password` (or `GEDCOM_SHELL_TLS_PASSWORD`) |
| `token` | the API token, readable only by you; `gedcom-shell daemon token --rotate` replaces it |
| `daemon.json` | where the running daemon listens, for clients to find it |
| `daemon.lock` | held by the running daemon (an OS lock: a crashed daemon leaves no stale lock) |
| `daemon.log` | output of a daemon started in the background |
| `recent.json` | files opened lately, so their ids keep working after a restart |
| `recovery/` | copies of unsaved edits from a forced or signalled stop |

## Security

- **A token on every request**, compared in constant time. It is 256 random bits, kept in
  a file only you can read. Requests from this computer need it too: a proxy such as
  `tailscale serve` makes remote requests look local, and other programs and web pages on
  this computer must not be able to edit your tree. The web app sends it in an
  `Authorization` header, never in an address or a cookie, so other sites can't make your
  browser use it (no CSRF), and DNS-rebinding pages, which can't read it, get nothing but
  the web app's own files.
- **Pictures** are served from signed addresses (an HMAC of the address under a key derived
  from the token), so `<img>` tags work without exposing the token. Only pictures the
  GEDCOM file links to are served.
- **The web app** is served with a strict Content-Security-Policy (scripts and styles only
  from the daemon itself, no framing), and shows file contents as text, never as HTML.
- **Only you**: the daemon runs as you, with your permissions. Anyone with the token can
  read and edit any GEDCOM file you can, and save copies where you can write. Treat the
  pairing address like a password; rotate the token if it leaks.

## Troubleshooting

- *"the daemon could not start"*: the message ends with the last lines of
  `~/.gedcom-shell/daemon.log`. A busy port shows there: set another `port`.
- *Two daemons?* There can't be: the second sees the lock and exits.
- *Different versions*: after rebuilding, `gedcom-shell daemon stop` (saving first) so the
  next start runs the new code. `gedcom-shell daemon status` shows the running version.
- See the API in [api.md](api.md).
