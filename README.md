# musikbox

A playlist-first music player for **Navidrome** and any **Subsonic**-compatible server, for **Android** and the **Linux desktop**.
Most clients are organised around albums and artists. musikbox is organised around your playlists.

- **Android**: Kotlin · Jetpack Compose · Material 3 · Media3 (ExoPlayer + MediaSession) · Room. Targets Android 15 (API 35) and runs on Android 12+.
- **Linux desktop**: Kotlin · Compose Multiplatform · mpv for playback · MPRIS over D-Bus. See [Linux desktop app](#linux-desktop-app).

## Features

**Playlist-first home**
- *Jump back in*: playlists you played recently, with one-tap play.
- *Liked songs* (⭐): every song you star, newest first, as its own list at the top. Stars live on the server, so they're the same everywhere; removing a song from the list unstars it.
- *Saved playlists* (🔖): playlists you bookmark. Subsonic can't star playlists, so they sync between your devices through the server (see *Devices & sync*).
- *Playlists*: every playlist on the server.
- *Search playlists*: a live filter by playlist name at the top of the start screen. If nothing matches, one tap searches the whole server for songs with the same text.
- *Downloads*: each downloaded playlist with its progress ("Downloading · 3 of 8") or its size on disk.
- *Your playlists*: every playlist, filterable (All / Mine / Offline) and sortable (name, recently updated, size).
- Tap ⋮ on a playlist, or long-press any playlist tile, for quick actions: play, shuffle, **download / remove download**, favorite, open.

**Everything cached**
- Every playlist and its full track list is mirrored into a local Room database, so the library opens instantly and you can browse it offline.
- Sync is incremental: only playlists whose server `changed` stamp moved are fetched again. Sync runs on launch, on pull-to-refresh and every 6 hours in the background (WorkManager).
- Every streamed song lands in an LRU audio cache (size is configurable), so replays don't touch the network.
- **Download a playlist** from the labelled *Download* button in its header (it shows live `3 / 8` progress, then *Downloaded*) or from its ⋮ menu. This pins its songs to disk permanently. Or let rules do it: **Download automatically → Liked songs / Saved playlists / All playlists** (Settings or Downloads). Starring a song or saving a playlist then downloads it, and unstarring or unsaving removes the files. New songs added to an offline playlist download automatically. Downloads can be limited to Wi-Fi.
- Cover art has its own 512 MB disk cache.

**Download manager** (home → Downloads → *Manage*, or Settings → *Manage downloads*)
- **Download list**: the playlists you keep offline, stored separately from the files. Add several at once, or remove one.
- **One-tap list actions**: *Download all* (resume, fetch what's missing, retry failures), *Re-download all* (delete every file and fetch the list again), *Delete downloaded files* (frees the space, keeps the list, pauses until *Download all*), and *Clear download list*.
- **Live status**: progress and speed, *Waiting for Wi-Fi*, *Paused*, and failures. Each playlist shows its own status; tap it for every song's state (✓ size, ↓ %, queued, or ✗ with a reason such as "HTTP 404" or "Server closed the connection").
- **Automatic restart**: failed songs retry with exponential back-off (15 s → 15 min, 8 tries). Everything failed is re-queued when the network comes back, on every app start and after every sync. A WorkManager job (`DownloadWorker`) carries on in the background after the app is closed, respecting *Wi-Fi only*.
- **Activity log**: downloads and syncs, kept on the device (600 entries) and mirrored to logcat (`adb logcat -s musikbox`). It has a Verbose toggle and Copy, Share and Clear buttons. URLs (which contain the auth token) never appear in error messages.

**Discover** (Android: ✨ on home · desktop: sidebar → Discover)

Navidrome has no recommender of its own. It provides the raw material: per-user play counts and last-played dates, stars, genres, random and newest album lists, and similar songs from its agents (Deezer, ListenBrainz, Last.fm or AudioMuse). musikbox builds the recommender on top, in `core/…/discover/Discover.kt`, shared by both apps:
- **Taste profile:** artist and genre affinity from your most-played albums (log-scaled), recently played albums and starred songs, albums and artists.
- **Discover Mix:** only songs you've **never played**, scored by affinity plus how they were found (similar songs score highest). At most 2 per artist and album, and never the same artist twice in a row. You can play it, shuffle it or **save it as a playlist**.
- **Sections:** *Sounds like your favourites* (similar songs) · *More from artists you love* (their unheard albums) · *Unplayed ⟨genre⟩* · *New in your library* · *Rediscover* (loved, but not played in 60+ days) · *Outside your comfort zone* (never played, outside your usual genres).

**Devices & sync** (Android: Settings → Devices & sync · desktop: sidebar → Devices)
- Each app runs a small sync server on **TCP 24533** and lists its **listen addresses** (LAN and VPN, e.g. WireGuard; container bridges are hidden). Port 24533 is outside the OS's ephemeral range (32768+), so it can't collide with an outgoing connection's local port. If it's taken anyway, the app uses the next free port and shows that one.
- **Other devices** are found automatically by a UDP broadcast on **24534**. You can also add any IP or IP:port by hand, for VPNs or networks that block broadcast. Each device shows its status, when it last synced, and what it's playing.
- **Through your server, automatically:** saved playlists, recently played and the device key are stored as JSON in one **private, empty playlist named `.musikbox`** on your Navidrome. Subsonic has nowhere else to keep them. Every app merges with it on start, when it comes to the foreground, every 2 minutes and shortly after any change, so devices never need to meet on a network. musikbox hides the playlist; Navidrome's web UI and other Subsonic apps show it, to you only. Liked songs need none of this, since stars are server data.
- **What syncs:** saved playlists and recently played. The **download list** syncs too if *Share download list* is on for both devices. Merges are last-writer-wins per field, so any device can sync with any other, in any order.
- **Continue here:** take over another device's queue at its exact position. The other device pauses.
- **Security:** device-to-device requests are signed with HMAC-SHA256 using the account's key from `.musikbox` (only your account can read it), and rejected if the signature is wrong or the timestamp is more than ±2 min off. The first device seeds the key with the one it derived from your password at sign-in (PBKDF2-SHA256, 120k rounds; the password is never stored), so older 1.0.3 installs stay compatible.

**Visualizer** (Now Playing: Android 〰 button, desktop style chips)
- A Feishin / audioMotion-style spectrum: 32 Hz – 16 kHz in log-spaced bands, fast attack and slow release, falling peak caps, colored from the artwork, with gentle auto-gain for quiet masters. Styles: **Bars**, **Mirror**, **Radial** (around a round cover) or Off. Your choice is remembered.
- **Android** reads the exact audio ExoPlayer is playing through a `TeeAudioProcessor`, so it needs no microphone permission and copies nothing while hidden. **Desktop** has mpv decode a copy of the track (about 0.1 s per song) and indexes it by mpv's playhead, smoothed to 60 fps. Analysis lives in `core/…/visualizer/SpectrumAnalyzer.kt`.

**Playback**
- Background playback with a media notification, lock-screen and Bluetooth controls, and audio focus. Playback pauses when headphones are unplugged.
- Shuffle, repeat all and repeat one, a queue you can view, jump around in and edit, plus *play next* and *add to queue*.
- Sleep timer, either after N minutes or at the end of the current track.
- The queue persists across restarts, and the system's "resume playback" works.
- Scrobbling: *now playing* is sent when a track starts, and a play is submitted at 50% or 4 minutes.
- Streaming quality can be the original file or transcoded at 320 to 96 kbps. Downloads are always the original.
- When a track can't be played (for example offline and not cached), the player skips it.

**Editing**
- Create, rename and delete playlists. Add songs, or a whole playlist, to another playlist. Remove tracks.
- Star and unstar songs.
- Search covers your cached playlists plus songs on the whole server. When the server is unreachable it falls back to searching the songs in your cached playlists.

**Design**
- Dark-first design with a coral accent. Accent colours are pulled from the artwork, and there's an optional pure-black OLED mode.
- Full-screen player over a blurred artwork backdrop. The artwork scales with the play state. Swipe the artwork to skip, pull down to close.
- A floating mini player you can swipe to skip.
- Animated equalizer marks the track and playlist that are playing.

## Build

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # minified; signed with the debug key — swap in a real signing config before distributing
```

`gradle.properties` points Gradle at JDK 21 (`/usr/lib/jvm/java-21-openjdk`) because AGP 8.7 doesn't run on newer JDKs. Change that path if your JDK lives somewhere else.

## Signing in

Enter the server address (`music.example.com`, `192.168.1.10:4533`, or a full URL with a path prefix), your username and your password.
If you leave out the scheme, musikbox tries HTTPS first and then HTTP. Plain HTTP is allowed because many servers live on a LAN.

The password is never stored. musikbox saves only the Subsonic salted token (`md5(password + salt)`).

## Linux desktop app

The desktop app has the same features and design as the Android app, laid out for a big screen:

- **Sidebar**: Home, Search, Downloads and Settings, a *Search playlists* box that filters by playlist name as you type, your favorites, and every playlist. Right-click any playlist for Play, Shuffle, Download, Favorite or Open.
- **Home**: *Liked songs*, *Jump back in*, *Saved playlists*, and *Playlists* as a grid with a name filter, the All / Mine / Offline filters and sorting.
- **Playlist view**: a track table. Double-click a track or press its ▶ to play it, and right-click for Play next, Add to queue, Add to playlist, Star or Remove. The header has Play, Shuffle, Favorite and the **Download** button with progress.
- **Player bar**: transport controls, seek bar, star, add to playlist, sleep timer, a queue drawer, volume and an expanded **Now Playing** view.
- **Downloads**: shows per-playlist progress and size on disk, plus the *Download automatically* rules (liked / saved / all).
- **System integration**: musikbox registers with **MPRIS**, so media keys, the Plasma/GNOME media controls, the lock screen, KDE Connect and `playerctl` all control it.
- **Keyboard**: Space plays or pauses, Ctrl+→/← skips, Ctrl+↑/↓ changes the volume, Ctrl+F searches, Alt+← goes back and Ctrl+Q quits.
- **Persistence**: the queue, position, shuffle and repeat come back exactly as you left them. A sync runs on launch.

Playback runs in a headless **mpv** started with `--no-config`, so your own mpv setup can't interfere. mpv therefore has to be installed (`sudo pacman -S mpv` or `sudo apt install mpv`).
To pick a specific audio output, pass extra options: `MUSIKBOX_MPV_OPTS="--audio-device=pipewire/…" musikbox`.
mpv shuts down with the app. A built-in watchdog stops it within about 2 seconds even if the app crashes or is killed.

Data follows the XDG base directories:

| Path | Contents |
|---|---|
| `~/.config/musikbox/settings.json` | settings and the token (mode 0600) |
| `~/.local/share/musikbox/library.json` | cached playlists and tracks |
| `~/.local/share/musikbox/queue.json` | the saved queue |
| `~/.local/share/musikbox/downloads/` | downloaded songs (original files) |
| `~/.cache/musikbox/audio/` | LRU cache of streamed songs |
| `~/.cache/musikbox/artwork/` | cover art |

```bash
./gradlew :desktop:run                          # run from source
desktop/install.sh                              # build + install to ~/.local (app menu entry, `musikbox` command)
desktop/install.sh --uninstall                  # remove (keeps your data)
./gradlew :desktop:createReleaseDistributable   # self-contained app (bundled JRE) in desktop/build/compose/binaries/main-release/
./gradlew :desktop:packageDeb                   # .deb for Debian/Ubuntu (needs dpkg-deb); packageRpm for Fedora
```

## Architecture

```
core/      shared by both apps: Subsonic client + DTOs, sign-in (HTTPS→HTTP fallback), token credentials, formatting
app/       Android app
desktop/   Linux desktop app
```

The desktop module:

```
desktop/src/main/kotlin/app/musikbox/desktop/
├── DesktopContainer.kt      manual DI (mirrors the Android AppContainer)
├── data/                    Settings (JSON), Library (sync engine, same incremental logic), Downloads + StreamCache
├── player/
│   ├── Mpv.kt               headless mpv over its JSON IPC socket, plus the crash watchdog
│   ├── Player.kt            queue/shuffle/repeat, caching, scrobbling, sleep timer, queue persistence
│   └── Mpris.kt             org.mpris.MediaPlayer2 on the session bus (dbus-java)
└── ui/                      sidebar, player bar, home, playlist, now playing + queue, search, downloads, settings, login
```

The Android module:

```
app/src/main/java/app/musikbox/
├── AppContainer.kt          manual DI: OkHttp, Room, caches, repositories, player connection
├── MusikboxApp.kt           Application + Coil image loader + periodic SyncWorker
├── data/
│   ├── local/               Room: playlists, playlist_prefs (local favourites/recents/offline), songs, playlist_tracks
│   ├── settings/            DataStore settings (the Subsonic client and Credentials live in core/)
│   └── LibraryRepository.kt sync engine, playlist editing, search, offline orchestration
├── playback/
│   ├── PlaybackService.kt   MediaSessionService + ExoPlayer; resolves stream URLs server-side
│   ├── MediaResolver.kt     mediaId → stream URL + cache key (prefers downloaded copy)
│   ├── OfflineManager.kt    Media3 DownloadManager over a never-evicted download cache
│   ├── MusikDownloadService.kt
│   ├── PlayerConnection.kt  MediaController bridge exposing StateFlows to the UI, sleep timer
│   ├── QueueStore.kt        queue persistence
│   └── Scrobbler.kt
└── ui/                      Compose screens: login, home, playlist, now playing, search, settings
```

Playback reads through two caches before it reaches the network:
**download cache** (read-only, never evicted) → **stream cache** (LRU) → **HTTP**.
Cache keys are `song:<id>:<bitrate>`. The auth token in the URL is therefore never part of the key, and transcoded bytes never mix with original ones.
