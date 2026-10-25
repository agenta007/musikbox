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
- **The server reachable from the internet**, so the app works away from home: an open (forwarded) port on your router to Navidrome (4533 by default), or better a reverse proxy with HTTPS on 443, plus a domain name or dynamic DNS. At home, the server's LAN address is enough, and a VPN into your home network works too.
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

- **Android**: an F-Droid release is on its way; until then, build an APK as shown below.
- **Linux desktop**: run `desktop/install.sh` (see below). It needs `mpv` (`sudo pacman -S mpv` or `sudo apt install mpv`).

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

# Tests
./gradlew :core:test :app:testDebugUnitTest
MB_TEST_SERVER=http://127.0.0.1:4533 ./gradlew :core:test --rerun   # also run the tests that need a real Navidrome (they write test data to it)
```

`deploy.sh` is the maintainer's release script. It bumps `version.properties`, builds, tests, signs and uploads the APK, then commits the version and tags it `v<version>`, which is what F-Droid builds from. Its upload target defaults to the maintainer's server, so set `MB_DEPLOY_REMOTE`, `MB_DEPLOY_DIR` and `MB_DEPLOY_URL` to use your own. Release keys (`keystore/`, `keystore.properties`) are never committed.

### Desktop data

The desktop app follows the XDG base directories:

| Path | Contents |
|---|---|
| `~/.config/musikbox/settings.json` | settings and the token (mode 0600) |
| `~/.local/share/musikbox/library.json` | cached playlists and tracks |
| `~/.local/share/musikbox/queue.json` | the saved queue |
| `~/.local/share/musikbox/downloads/` | downloaded songs |
| `~/.cache/musikbox/` | stream and artwork caches |

mpv runs with `--no-config`, so your own mpv setup can't interfere. To pass extra options, set `MUSIKBOX_MPV_OPTS`, for example `MUSIKBOX_MPV_OPTS="--audio-device=pipewire/…" musikbox`. A watchdog stops mpv within about 2 seconds if the app dies.

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
