# musikbox

A **playlist-first** music player for [Navidrome](https://www.navidrome.org/) and any other **Subsonic**-compatible server, for **Android** and the **Linux desktop**.

Most clients are organised around albums and artists. musikbox is organised around your playlists: the ones you played recently, the ones you saved, your liked songs, and everything else. All of it is cached, so it opens instantly and works offline.

| | Android | Linux desktop |
|---|---|---|
| UI | Jetpack Compose · Material 3 | Compose Multiplatform |
| Playback | Media3 ExoPlayer + MediaSession | headless [mpv](https://mpv.io/) over JSON IPC |
| System controls | notification, lock screen, Bluetooth | MPRIS (media keys, Plasma/GNOME, KDE Connect, `playerctl`) |
| Requires | Android 12+ (targets Android 15) | Linux x86-64 with `mpv` installed; the JRE is bundled |

## What you need

musikbox is only a player. It brings no music and no streaming service, so you need your own server:

- **Your own music files** (MP3, FLAC, Opus, AAC…) on a hard disk or SSD. musikbox plays what you own, nothing else.
- **A [Navidrome](https://www.navidrome.org/docs/installation/) server** (or another Subsonic-compatible server) running on a computer that is always on and has that disk: a home server, NAS, Raspberry Pi or rented server. It indexes your files and streams them to the apps.
- **The server reachable from the internet**, so the app works away from home: an open (forwarded) port on your router to Navidrome (4533 by default), or better a reverse proxy with HTTPS on 443, plus a domain name or dynamic DNS. At home, the server's LAN address is enough, and a VPN into your home network works too. New to this? Follow the [hosting guide](#guide-host-your-own-navidrome-server).
- A Navidrome user account. Playlists are created in musikbox, in Navidrome's web UI, or come from `.m3u` files in your music folder.

## Features

### Home
- **Jump back in**: the playlists you played recently, with one-tap play.
- **Liked songs ⭐**: every song you star, newest first. Stars are stored on the server, so the list is the same on every device. Removing a song from it unstars it.
- **Saved playlists 🔖**: playlists you bookmark. They sync between your devices through your server (see [Sync](#sync-between-devices)).
- **Playlists**: every playlist on the server, filterable (All / Mine / Offline) and sortable (name, recently updated, size).
- **Search playlists**: a live filter by playlist name. If nothing matches, one tap searches the whole server for songs.
- Quick actions on any playlist (⋮, long-press or right-click): play, shuffle, download, save, open.

### Offline and caching
- Every playlist and its track list is mirrored locally. Re-syncs are incremental: only playlists whose server `changed` stamp moved are fetched again.
- Streamed songs go into an LRU cache whose size you can set, so replays don't touch the network. Cover art has its own cache.
- **Download a playlist** with its *Download* button, which shows live progress. Or let rules do it: **Download automatically → Liked songs / Saved playlists / All playlists**. Starring a song or saving a playlist then downloads it, and unstarring or unsaving removes the files. Downloads are always the original files and can be limited to Wi-Fi.
- **Download manager**:
  - A download list kept separately from the files, with one-tap *Download all*, *Re-download all*, *Delete files* and *Clear list*.
  - Per-song status: done with its size, progress, queued, or failed with the reason.
  - Automatic retries with back-off, restarted when the network comes back. Background work continues on Android.
  - An activity log you can copy.

### Discover
Navidrome provides the raw material but has no recommender of its own: play counts, last-played dates, stars, genres, newest and random albums, and similar songs from its agents (Last.fm, ListenBrainz, Deezer…). musikbox builds the recommender on top of that, in the shared `core` module:
- **Taste profile**: artist and genre affinity from your most-played and recently played albums, plus your stars.
- **Discover Mix**: only songs you've **never played**, at most 2 per artist and album, with no artist twice in a row. You can play it, shuffle it, or save it as a playlist.
- **Sections**: *Sounds like your favourites* · *More from artists you love* · *Unplayed ⟨genre⟩* · *New in your library* · *Rediscover* (loved, but not played in 60+ days) · *Outside your comfort zone*.

### Lyrics
- **From your own server first.** Navidrome serves lyrics from embedded tags and from files next to each song (`.lrc`, `.elrc`, `.ttml`, `.srt`, `.txt`). musikbox asks for them with the OpenSubsonic `getLyricsBySongId` call, and falls back to classic `getLyrics` on older servers.
- **Synced lyrics follow the song.** The line being sung is highlighted and kept a third of the way down, and you can tap or click a line to jump there. Plain lyrics just scroll.
  - Android: the lyrics button in Now Playing.
  - Desktop: *Up next | Lyrics* in Now Playing.
- **Optional LRCLIB fallback, off by default.** Turn it on in *Settings → Lyrics* to ask [LRCLIB](https://lrclib.net) for songs your server has no lyrics for. This sends the song's artist, title, album and length. LRCLIB is free and open source, so you can [run your own](https://github.com/tranxuanthang/lrclib) and enter its address. To keep everything on your server instead, fill the gaps once with [LRCGET](https://github.com/tranxuanthang/lrcget), which saves `.lrc` files next to your music.
- **Cached on the device.** Lyrics, including "none found", are cached, so each song is looked up once. Songs you download get their lyrics fetched too, for offline use. Songs with no lyrics are checked again after a day.

### Sync between devices
- **Through your server, automatically.** Saved playlists, recently played and the device key are stored as JSON in one **private, empty playlist named `.musikbox`**, because Subsonic has nowhere else to keep them. Every app merges with it on start, when it comes back to the foreground, every 2 minutes and shortly after any change. musikbox hides this playlist. Navidrome's web UI and other Subsonic apps show it, but only to you.
- **On the local network or a VPN.** Each app runs a small sync server on TCP **24533** and finds other devices by UDP broadcast on **24534**. You can also add devices by IP. This adds **Continue here**, which takes over another device's queue at its exact position.
- Merges are last-writer-wins per field, so devices can sync in any order. The download list syncs only if *Share download list* is on for both devices.
- Requests between devices are signed with HMAC-SHA256 using a key that only your account can read, and are rejected if more than ±2 minutes old.

### Playback
- Shuffle, repeat all and repeat one, an editable queue, *play next* and *add to queue*.
- Sleep timer, either after N minutes or at the end of the current track.
- The queue and position survive restarts.
- Scrobbling: *now playing* is sent when a track starts, and a play is submitted at 50% or 4 minutes.
- Streaming can use the original file or be transcoded at 320 to 96 kbps.
- **Visualizer** in Now Playing, in the style of Feishin and audioMotion: a log-spaced 32 Hz – 16 kHz spectrum with peak caps, colored from the cover art. Styles are **Bars**, **Mirror** and **Radial**. On Android it reads the audio ExoPlayer is already playing, so it needs no microphone permission.

### Editing
- Create, rename and delete playlists, add songs or whole playlists to a playlist, and remove tracks.
- Star and unstar songs.
- Search covers your cached playlists plus songs on the whole server, and falls back to the cache when you're offline.

## Privacy

- **The password is never stored.** musikbox keeps only the Subsonic salted token, `md5(password + salt)`. On the desktop, `settings.json` is created with mode 0600.
- **No analytics, no trackers, no third-party services by default.** musikbox only talks to your own server and, if you use LAN sync, to your own devices. The one optional exception is the LRCLIB lyrics fallback, which is off until you enable it and can point at your own instance.
- Plain HTTP is allowed because many servers live on a LAN. If you type a server address without a scheme, musikbox tries HTTPS first.

## Install

### Android

Download the APK from [GitHub Releases](https://github.com/agenta007/musikbox/releases/latest) and open it on your phone. Android asks once to allow installs from your browser or file manager. An F-Droid release is on its way.

### Linux

The desktop app is built from source on your machine and installed for your user only, so it needs no root. It works on any x86-64 distribution and desktop, including KDE Plasma and GNOME. It needs:
- **mpv**, which plays the audio;
- **a JDK, version 17 to 21**, only for building. The installed app brings its own Java runtime;
- **git**, to download the source.

1. **Install the tools.**
   ```bash
   sudo pacman -S mpv jdk21-openjdk git                  # Arch, Manjaro, CachyOS
   sudo apt install mpv openjdk-21-jdk git               # Debian 13, Ubuntu 24.04 and newer (Debian 12: openjdk-17-jdk)
   sudo dnf install mpv java-21-openjdk-devel git        # Fedora (mpv comes from RPM Fusion)
   ```
2. **Download the source and install.**
   ```bash
   git clone https://github.com/agenta007/musikbox.git
   cd musikbox
   desktop/install.sh
   ```
   The first build downloads Gradle and the libraries, so it takes a few minutes. If your default Java is newer than 21, point the build at JDK 21: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk desktop/install.sh`.
3. **Start musikbox** from your app menu, or run `musikbox` in a terminal.

The app is installed in `~/.local/opt/musikbox`, with a menu entry, an icon and the `musikbox` command in `~/.local/bin`.
- **To update:** run `git pull`, then `desktop/install.sh` again.
- **To uninstall:** run `desktop/install.sh --uninstall`. Your settings, library and downloads are kept.

To build a `.deb` or `.rpm` package instead, see [Building](#building).

### NixOS

NixOS keeps libraries out of the usual Linux paths, so the repo includes a `shell.nix` with everything the build and the app need: JDK 21, mpv, and the graphics and font libraries.

```bash
nix-shell -p git --run 'git clone https://github.com/agenta007/musikbox.git'
cd musikbox
nix-shell --run desktop/install.sh
```

`install.sh` notices it's on NixOS. It writes a small launcher that sets the library path, and pins the JDK, mpv and libraries from `shell.nix` as garbage-collector roots, so `nix-collect-garbage` doesn't remove them. musikbox then shows up in the KDE or GNOME app menu like any other app. On Plasma's Wayland session it runs through XWayland. Media keys and the Plasma media controls work through MPRIS.

- **To update:** run `git pull`, then `nix-shell --run desktop/install.sh` again.
- **To uninstall:** run `desktop/install.sh --uninstall`. The pinned packages are released at the next garbage collection.
- **To try it without installing:** run `nix-shell --run './gradlew :desktop:run'`.

### Windows (experimental)

On Windows 10 and 11 (x64), you build an installer (`.msi`) from source. It installs for your user only, with no admin rights.

1. **Install the tools** in PowerShell:
   ```powershell
   winget install Git.Git EclipseAdoptium.Temurin.21.JDK
   ```
   Then install mpv with [Scoop](https://scoop.sh), which also puts it on your PATH:
   ```powershell
   Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
   Invoke-RestMethod -Uri https://get.scoop.sh | Invoke-Expression
   scoop bucket add extras
   scoop install extras/mpv
   ```
   You can also download mpv from [mpv.io](https://mpv.io/installation/) and unpack it, for example to `C:\Programs\mpv`. Then tell musikbox where it is: `setx MUSIKBOX_MPV "C:\Programs\mpv\mpv.exe"`.

   Close PowerShell and open a new one, so it picks up the new PATH.
2. **Download the source and build the installer.**
   ```powershell
   git clone https://github.com/agenta007/musikbox.git
   cd musikbox
   .\gradlew.bat :desktop:packageReleaseMsi
   ```
   The first build takes a few minutes. It also downloads the WiX toolset, which builds the `.msi`.
3. **Install:** open `desktop\build\compose\binaries\main-release\msi\` and double-click the `.msi`. musikbox then appears in the Start menu.

To try it without installing, run `.\gradlew.bat :desktop:run`.

What's different on Windows:
- **No system media controls yet.** Media keys and the Windows media overlay don't control musikbox, because those use MPRIS on Linux.
- **Firewall prompt.** The first time sync starts, Windows Firewall asks whether musikbox may use the network. Allow it on private networks for LAN sync between devices (TCP 24533, UDP 24534).
- **Settings, library and downloads** are kept in `%APPDATA%\musikbox`, and the caches in `%LOCALAPPDATA%\musikbox\cache`.

## Guide: host your own Navidrome server

This guide is for beginners. It has two parts:
1. **At home:** Navidrome runs on a computer at home, and the music plays on your home Wi-Fi.
2. **Anywhere, through port forwarding on your router:** the same server, also reachable over mobile data and from other networks.

Do part 1 first. Part 2 builds on it.

> **Stuck?** Ask an AI assistant, such as Claude or ChatGPT. Tell it your operating system, your router's brand and model, which step you're on, and paste the exact error message. Router menus differ between brands, so an assistant that knows your model can tell you exactly where to click.

### 1. At home (LAN)

You need a computer that stays on and holds your music: an old PC or laptop, a NAS, or a Raspberry Pi 4/5. The examples use Linux with Docker. Navidrome also has packages for Debian/Ubuntu (`.deb`), Fedora (`.rpm`), Arch (`navidrome` in the AUR), macOS and Windows; see [its installation docs](https://www.navidrome.org/docs/installation/).

**Install Docker** (Debian/Ubuntu shown; on Arch use `sudo pacman -S docker docker-compose`):
```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER      # then log out and back in
```

**Create `~/navidrome/compose.yaml`**, and change `/path/to/your/music` to the folder that holds your music:
```yaml
services:
  navidrome:
    image: deluan/navidrome:latest
    user: 1000:1000                  # your user id and group id: run `id` to check
    ports:
      - "4533:4533"
    restart: unless-stopped
    environment:
      ND_SCANSCHEDULE: 1h            # look for new music every hour
    volumes:
      - ./data:/data                 # Navidrome's database and settings
      - /path/to/your/music:/music:ro
```

**Start it:**
```bash
cd ~/navidrome
docker compose up -d
docker compose logs -f             # watch it scan your music; Ctrl+C to stop watching
```

**Find the server's address** on your network: run `hostname -I` on Linux, or `ipconfig` on Windows. It looks like `192.168.1.50`.

**Create your account:** open `http://192.168.1.50:4533` in a browser on any device at home. The first account you create is the admin.

**Connect musikbox:** enter `http://192.168.1.50:4533` as the server, with your username and password.

Two things keep it working:
- **Give the server a fixed address.** In your router's settings, look for *DHCP reservation* or *static lease*, and reserve the address for the server. Otherwise it can change after a restart, and the apps stop finding the server.
- **Allow the port through the firewall**, if your server has one: run `sudo ufw allow 4533/tcp` on Ubuntu, or `sudo firewall-cmd --add-port=4533/tcp --permanent && sudo firewall-cmd --reload` on Fedora.

### 2. Anywhere, through port forwarding on your router

**What port forwarding is:** your router has one public address on the internet, and every device at home hides behind it. When a request arrives from outside, the router doesn't know which device it's for, so it drops it. A port-forwarding rule tells the router: "send everything that arrives on this port to this device". Here, web traffic (ports 80 and 443) goes to your server, where [Caddy](https://caddyserver.com/) adds HTTPS and passes it on to Navidrome.

**Before you start:**
- **Use a strong password** for every Navidrome account, because anyone on the internet can try to log in.
- **Don't forward port 4533 directly.** That's plain HTTP, so your login could be read on public Wi-Fi. Caddy gets a free HTTPS certificate and renews it by itself.
- **Keep Navidrome updated:** run `docker compose pull && docker compose up -d` now and then.

**Steps:**

1. **Check that your connection allows it.**
   - Compare the *WAN* or *internet* IP address on your router's status page with the address [ifconfig.me](https://ifconfig.me) shows.
   - If they're the same, port forwarding will work.
   - If they differ, or the router's address starts with `100.64`–`100.127`, your provider shares one public address between many customers (CGNAT), and forwarded ports never reach you. Ask your provider for a public IPv4 address: it's often free, sometimes paid.
2. **Get a name for your home.** Your home IP address changes from time to time, so use a free dynamic DNS service such as [DuckDNS](https://www.duckdns.org/).
   - Log in, choose a name, and you get `yourname.duckdns.org`.
   - Its updater, or the DDNS setting many routers have, keeps the name pointing at your home address.
   - In the steps below, replace `yourname.duckdns.org` with your own name.
   - If you own a domain, create a record at your DNS provider instead.
3. **Forward the ports.**
   - Open your router's settings, usually at `http://192.168.1.1` or `http://192.168.0.1`. The login is often printed on a sticker on the router.
   - Find *Port forwarding*. Some routers call it *Virtual server*, *NAT* or *Port mapping*.
   - Add two rules, both **TCP**, both pointing at your server's fixed LAN address from part 1 (for example `192.168.1.50`):
     - external port **80** → internal port **80**;
     - external port **443** → internal port **443**.
   - Save. Some routers need a restart before the rules apply.
4. **Install Caddy on the server.** It's in most distributions as `caddy`: run `sudo apt install caddy` or `sudo pacman -S caddy`. Then set `/etc/caddy/Caddyfile` to:
   ```
   yourname.duckdns.org {
       reverse_proxy localhost:4533
   }
   ```
   Reload it with `sudo systemctl reload caddy`. Caddy gets the HTTPS certificate by itself within a minute. If the server has a firewall, allow 80 and 443: `sudo ufw allow 80,443/tcp`.
5. **Test from outside your home.** Turn Wi-Fi off on your phone and open `https://yourname.duckdns.org` over mobile data. Many routers can't reach their own public address from inside the home, so a test on home Wi-Fi can fail even when everything works.
6. **Connect musikbox:** set the server to `https://yourname.duckdns.org`. This one address works both at home and away.

**If it doesn't load:**
- **On the server, check that Caddy is running and got its certificate:** `sudo systemctl status caddy` and `journalctl -u caddy -n 50`.
- **Check the router rules point at the server's current LAN address** (`hostname -I`).
- **Check that the DuckDNS name points at your home:** `nslookup yourname.duckdns.org` should show the address from ifconfig.me.

## Tutorial: bring your Spotify playlists

Navidrome only plays files you have on disk. This tutorial turns your Spotify playlists into music files and Navidrome playlists. You export the playlists with Exportify, and the Python scripts in [`tools/`](tools/) download each song and write the playlists. Read the scripts before you run them: each one starts with a docstring that explains what it does, and `--help` lists every option. Only download music you're allowed to keep where you live.

In short: export the playlists as CSV files, put them in `tools/csv/`, and run `python3 archive.py` in `tools/` to download the music.

### 1. Export your playlists with Exportify

[Exportify](https://github.com/watsonbox/exportify) saves Spotify playlists as CSV files. You can use it in either of two ways:

- **Web version:** open [exportify.net](https://exportify.net).
- **Locally:** clone it and run it yourself, so your Spotify login never leaves your machine:
  ```bash
  git clone https://github.com/watsonbox/exportify.git
  cd exportify
  yarn install
  yarn start          # then open the address it prints
  ```
  If the local copy asks for a Spotify client ID, follow its README.

Then:
1. Click **Get Started** and log in with your Spotify account.
2. Click **Export** next to one playlist, or **Export All** to get a zip with every playlist.
3. Put the `.csv` files, unzipped, into `tools/csv/`. Each file's name becomes the playlist's name, so rename the files if you want different names.
4. Install what the scripts need (step 2), then run `archive.py` to download the music (step 3).

### 2. Install what the scripts need

```bash
pip install yt-dlp mutagen        # mutagen is only used by gen_playlists.py
sudo pacman -S ffmpeg             # or: sudo apt install ffmpeg
curl -fsSL https://deno.land/install.sh | sh
```

yt-dlp needs a JavaScript runtime (deno, or node) to solve YouTube's signature check. Without one, every track fails with "Requested format is not available".

### 3. Download the songs: `archive.py`

Once the CSV files are in `tools/csv/`, run `archive.py` from the `tools/` folder. It downloads every song in every playlist:

```bash
cd tools
python3 archive.py --dry-run   # search and print the picks, download nothing
python3 archive.py             # download everything in csv/
```

What it does:
- It reads every CSV in `csv/` and merges them into one list of unique tracks. A song that is on twenty playlists is downloaded once.
- It searches YouTube for "artist title" for each track and keeps the result whose length is closest to the Spotify track's.
- It saves the audio as MP3 with the album cover embedded, into `tools/tracks_spotify/`, and writes one `.m3u` playlist per CSV next to the files.
- It saves its progress in `iterator.txt`, so you can stop it with Ctrl+C and run it again later to carry on where it stopped.
- If 10 tracks in a row fail, it stops and winds the position back so those tracks are retried next time.

Useful options:

| Option | What it does |
|---|---|
| `-c NAME` | only the CSVs whose name contains NAME (repeatable) |
| `-P` | pick playlists from a searchable menu |
| `--manual` | choose the YouTube result for every track yourself |
| `--codec opus` | save another format instead of MP3 |
| `--playlists-only` | rewrite the `.m3u` files from what's already downloaded |
| `--reset` / `--start N` | start again from the first track, or from track N |
| `--cookies FILE` / `--cookies-from-browser firefox` | sign in to YouTube when it asks you to confirm you're not a bot |
| `--proxy socks5://127.0.0.1:1080` | download through a proxy |

`ytsearch.py` is the YouTube search that `archive.py` uses. You can also run it on its own to check what a search finds: `python3 ytsearch.py "artist title" -n 5`.

### 4. Give the music and playlists to Navidrome

Move `tracks_spotify/` into Navidrome's music folder. The playlists inside it refer to the songs by relative paths, so they keep working after the move. On its next scan, Navidrome imports the songs and the `.m3u` playlists, and musikbox shows them under **Playlists**.

Two scripts can write the playlists in other ways. Both have the maintainer's music folder, `/mnt/extern/navidrome/music`, built in as the default, so pass your own paths or change the constants at the top of each script:

- **`create_playlists.py`** puts every playlist into one folder and gives each its original exported name through a `#PLAYLIST:` line, which is the name Navidrome shows. It makes one playlist per CSV from the downloaded tracks, and one per folder of music elsewhere in your library. The output folder has to be inside Navidrome's music folder, or Navidrome won't import it.
  ```bash
  python3 create_playlists.py /path/to/music --out /path/to/music/playlists --dry-run
  ```
- **`gen_playlists.py`** writes a `<folder>.m3u` into every folder that holds music, so every album or artist folder becomes a playlist. It reads the tags with mutagen, and ffprobe for files mutagen can't read, and caches them in `~/.cache/gen_playlists.json`, so later runs only read new files. Run it again after adding music, and the playlists are rebuilt from what's on disk.
  ```bash
  python3 gen_playlists.py /path/to/music
  ```

## Building

The build needs **JDK 17–21**, because AGP 8.7 doesn't run on newer JDKs. Gradle uses `JAVA_HOME`; if your default JDK is newer, point it at an older one, or set `org.gradle.java.home=/usr/lib/jvm/java-21-openjdk` in `~/.gradle/gradle.properties`. Android builds also need the Android SDK (API 35), configured through `local.properties` or `ANDROID_HOME`.

```bash
# Android
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/
./gradlew :app:assembleRelease      # minified; signed with keystore.properties if present, else unsigned

# Linux desktop
./gradlew :desktop:run                          # run from source
desktop/install.sh                              # build and install to ~/.local (menu entry + `musikbox` command)
desktop/install.sh --uninstall                  # remove it; your data is kept
./gradlew :desktop:createReleaseDistributable   # self-contained app in desktop/build/compose/binaries/main-release/
./gradlew :desktop:packageDeb                   # .deb (packageRpm for Fedora)

# Windows desktop (in PowerShell)
.\gradlew.bat :desktop:packageReleaseMsi         # per-user .msi installer in desktop\build\compose\binaries\main-release\msi\

# Tests
./gradlew :core:test :app:testDebugUnitTest
MB_TEST_SERVER=http://127.0.0.1:4533 ./gradlew :core:test --rerun   # also run the tests that need a real Navidrome (they write test data to it)
```

`deploy.sh` is the maintainer's release script. It bumps `version.properties`, builds, tests, signs and uploads the APK, then commits the version and tags it `v<version>`, which is what F-Droid builds from. Its upload target defaults to the maintainer's server, so set `MB_DEPLOY_REMOTE`, `MB_DEPLOY_DIR` and `MB_DEPLOY_URL` to use your own. Release keys (`keystore/`, `keystore.properties`) are never committed.

### Desktop data

On Linux, the desktop app follows the XDG base directories. On Windows, the same files are in `%APPDATA%\musikbox`, and the caches in `%LOCALAPPDATA%\musikbox\cache`.

| Path | Contents |
|---|---|
| `~/.config/musikbox/settings.json` | settings and the token (mode 0600) |
| `~/.local/share/musikbox/library.json` | cached playlists and tracks |
| `~/.local/share/musikbox/queue.json` | the saved queue |
| `~/.local/share/musikbox/downloads/` | downloaded songs |
| `~/.cache/musikbox/` | stream and artwork caches |

mpv runs with `--no-config`, so your own mpv setup can't interfere. To pass extra options, set `MUSIKBOX_MPV_OPTS`, for example `MUSIKBOX_MPV_OPTS="--audio-device=pipewire/…" musikbox`. On Linux, a watchdog stops mpv within about 2 seconds if the app dies. On Windows, mpv is stopped when the app quits; after a crash, end it in Task Manager.

Keyboard shortcuts:

| Key | Action |
|---|---|
| Space | play / pause |
| Ctrl+→ / Ctrl+← | next / previous track |
| Ctrl+↑ / Ctrl+↓ | volume up / down |
| Ctrl+F | search |
| Alt+← | back |
| Ctrl+Q | quit |

## Project layout

```
core/      pure JVM, shared by both apps
           data/remote   Subsonic client and DTOs, sign-in (HTTPS→HTTP fallback), token credentials
           data          liked / saved / download-rule logic
           discover/     taste profile and Discover sections
           sync/         CloudSync (.musikbox playlist) and SyncEngine (LAN HTTP + UDP discovery)
           visualizer/   FFT spectrum analyzer
app/       Android: Room cache, DataStore settings, Media3 playback and downloads, WorkManager, Compose UI
desktop/   Linux: JSON store, mpv player, MPRIS (dbus-java), Compose Desktop UI
```

On Android, playback reads through two caches before it reaches the network: **download cache** (never evicted) → **stream cache** (LRU) → **HTTP**. Cache keys are `song:<id>:<bitrate>`, so the auth token in the URL never becomes part of a key, and transcoded bytes never mix with original files.

## License

musikbox is free software, licensed under the [GNU General Public License v3.0 or later](LICENSE).
