#!/usr/bin/env python3
"""archive - turn Spotify playlist exports into a deduplicated music library.

A playlist is either a Spotify csv export or a screenshot json - the listing
read off a screenshot, {"source": "screenshot", "tracks": [{"position",
"title", "artists", "album", "duration", "duration_seconds", "explicit",
"music_video", "saved"}, ...]}. Both live in csv/ and are treated alike;
screenshot rows carry no album image, so the YouTube thumbnail becomes the
cover.

Every csv/*.csv and csv/*.json is folded into one set of unique tracks, each
downloaded once into tracks_spotify/ (audio + embedded cover art), and every
playlist then gets a tracks_spotify/<playlist>.m3u listing its tracks in order.
A song on twenty playlists is stored once and listed twenty times.

    pip install yt-dlp        # ffmpeg must be on PATH

    archive.py                       # every csv, automatic pick
    archive.py --manual              # choose each track yourself
    archive.py -c 2pac -c 50_cent    # only these playlist files
    archive.py -j csv/gtavi.json     # a screenshot playlist anywhere on disk
    archive.py --playlist            # pick from a searchable menu
    archive.py --playlists-only      # rebuild every .m3u, download nothing
    archive.py --reset               # ignore iterator.txt, start from track 1
    archive.py --start 5000          # jump to track 5000 and carry on
    archive.py --max-fails 0         # never stop on a run of failures
    archive.py --cookies-from-browser firefox        # for gated videos only
    archive.py --cookies ~/cookies.txt
    archive.py --proxy socks5://127.0.0.1:1080
    archive.py --from-json tracks_spotify/archive.json        # replay, no search

Progress is a single number in iterator.txt: the position in the deduplicated
track list, bumped after every track. A re-run picks up there instead of
walking the whole list again. --reset starts over, --start N jumps.

Because the position advances whether or not a track arrived, a run that has
broken - blocked address, stale cookies, no JS runtime - would otherwise walk
thousands of tracks it never really tried. So --max-fails tracks in a row
failing stops the run and winds the position back to the first of them, leaving
those tracks to be retried rather than skipped.

Cookies, when given, lead: YouTube refuses the media request with a 403 for
unauthenticated clients here, however healthy the formats looked, so the
signed-in attempt goes first and the unauthenticated one is the fallback for
whatever the account itself is blocked from, followed by the tv and
web_embedded players for sign-in and age gates.

Downloads also need a JavaScript runtime (deno, or node) to solve YouTube's
signature challenge - yt-dlp reads no config file when driven as a library, so
archive.py names the runtime itself. Without one every track fails with
"Requested format is not available".

Export cookies the way YouTube tolerates (yt-dlp wiki, Extractors#exporting-
youtube-cookies): log in from a *private* window, open a second tab and close
the first so the session stops being refreshed, export cookies.txt from that
window, then close it without logging out - logging out invalidates the cookies
you just saved.
"""

from __future__ import annotations

import argparse
import csv as csvlib
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

import ytsearch

ROOT = Path(__file__).resolve().parent
CSV_DIR = ROOT / "csv"
OUT_DIR = ROOT / "tracks_spotify"
POOL = OUT_DIR  # audio and covers/ live here
#PLAYLIST_DIR = Path("/mnt/extern/navidrome/music/playlists")  # where the .m3u files go

# --------------------------------------------------------------------------- #
# helpers
# --------------------------------------------------------------------------- #


def slug(name: str, maxlen: int = 120) -> str:
    """Filesystem-safe name that still reads like the original."""
    name = re.sub(r'[/\\:*?"<>|\x00-\x1f]', "-", name).strip(" .")
    return (name or "untitled")[:maxlen]


def mmss(text: str) -> float | None:
    """"3:16" or "1:02:30" -> seconds. None when it isn't a duration."""
    parts = (text or "").strip().split(":")
    if not parts or not all(p.strip().isdigit() for p in parts):
        return None
    secs = 0.0
    for p in parts:
        secs = secs * 60 + int(p)
    return secs


def is_screenshot_json(path: Path) -> bool:
    """A playlist scraped off a screenshot: {"source": ..., "tracks": [...]}."""
    if path.suffix.lower() != ".json":
        return False
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return False
    return isinstance(data, dict) and isinstance(data.get("tracks"), list)


def read_screenshot_tracks(path: Path) -> list[dict]:
    """A screenshot-derived playlist: one entry per row, in the listed order.

    Rows carry no cover URL, so the YouTube thumbnail becomes the artwork. Every
    credited artist is kept in `artists`; the first one drives the search, the
    way the csv reader takes the first of the comma-separated names.
    """
    data = json.loads(path.read_text(encoding="utf-8"))
    rows = data.get("tracks", []) if isinstance(data, dict) else data
    if not isinstance(rows, list):
        raise ValueError("expected a 'tracks' list")

    numbered = [r for r in rows if isinstance(r, dict)]
    if all(isinstance(r.get("position"), int) for r in numbered):
        numbered.sort(key=lambda r: r["position"])

    tracks = []
    for r in numbered:
        title = (r.get("title") or "").strip()
        if not title:
            continue
        artists = [str(a).strip() for a in (r.get("artists") or []) if str(a).strip()]
        secs = r.get("duration_seconds")
        if not isinstance(secs, (int, float)):
            secs = mmss(r.get("duration", ""))
        tracks.append(
            {
                "title": title,
                "artist": artists[0] if artists else "",
                "artists": artists,
                "album": (r.get("album") or "").strip(),
                "cover": "",
                "duration": float(secs) if secs else None,
                "explicit": bool(r.get("explicit")),
                "music_video": bool(r.get("music_video")),
                "saved": bool(r.get("saved")),
            }
        )
    return tracks


def read_tracks(path: Path) -> list[dict]:
    """A playlist file - a Spotify csv export or a screenshot json."""
    if path.suffix.lower() == ".json":
        return read_screenshot_tracks(path)
    with path.open(newline="", encoding="utf-8-sig") as fh:
        rows = list(csvlib.DictReader(fh))
    tracks = []
    for r in rows:
        title = (r.get("Track Name") or "").strip()
        artist = (r.get("Artist Name(s)") or "").split(",")[0].strip()
        if not title:
            continue
        ms = r.get("Track Duration (ms)") or ""
        tracks.append(
            {
                "title": title,
                "artist": artist,
                "album": (r.get("Album Name") or "").strip(),
                "cover": (r.get("Album Image URL") or "").strip(),
                "duration": int(ms) / 1000 if ms.isdigit() else None,
            }
        )
    return tracks


def pick(results: list[ytsearch.Result], target: float | None) -> ytsearch.Result:
    """Closest duration wins; ties and unknowns fall back to search order."""
    live = [r for r in results if not r.is_live]
    results = live or results
    if target:
        return min(results, key=lambda r: abs((r.duration or 1e6) - target))
    return results[0]


def choose(results: list[ytsearch.Result]) -> ytsearch.Result | None:
    ytsearch._print_table(results)
    while True:
        ans = input(f"  pick 1-{len(results)} [1, s=skip, q=quit]: ").strip().lower()
        if ans == "q":
            raise KeyboardInterrupt
        if ans == "s":
            return None
        if not ans:
            return results[0]
        if ans.isdigit() and 1 <= int(ans) <= len(results):
            return results[int(ans) - 1]


def fetch(url: str, dest: Path, net: dict) -> bool:
    """Grab a URL to disk. Routed through yt-dlp so socks5 works with no extra dep."""
    try:
        opts = {"quiet": True, "no_warnings": True, **net}
        with ytsearch.YoutubeDL(opts) as ydl:
            dest.write_bytes(ydl.urlopen(url).read())
        return dest.stat().st_size > 0
    except Exception:
        dest.unlink(missing_ok=True)
        return False


class Hush:
    """Swallow yt-dlp's own logging; failures are reported by the caller."""

    def debug(self, msg): pass
    def info(self, msg): pass
    def warning(self, msg): pass
    def error(self, msg): pass


def download(url: str, stem: Path, codec: str, net: dict, client: str | None = None) -> Path | None:
    """Audio only, transcoded to `codec`. Returns the resulting file."""
    opts = {
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "logger": Hush(),
        "format": "bestaudio/best",
        "outtmpl": f"{stem}.%(ext)s",  # stem is hex, so no %-escaping needed
        "writethumbnail": True,
        "postprocessors": [
            {"key": "FFmpegExtractAudio", "preferredcodec": codec, "preferredquality": "0"},
        ],
    }
    opts.update(net)
    if client:
        opts["extractor_args"] = {"youtube": {"player_client": [client]}}
    with ytsearch.YoutubeDL(opts) as ydl:
        ydl.download([url])
    out = Path(f"{stem}.{codec}")  # not with_suffix(): track names contain dots
    return out if out.exists() else None


def embed(audio: Path, cover: Path, tags: dict) -> None:
    """Remux cover + tags into the audio file in place (stream copy, no re-encode)."""
    tmp = Path(f"{audio}.tmp{audio.suffix}")
    cmd = [
        "ffmpeg", "-y", "-loglevel", "error",
        "-i", str(audio), "-i", str(cover),
        "-map", "0:a", "-map", "1:v", "-c", "copy",
        "-id3v2_version", "3", "-disposition:v", "attached_pic",
        "-metadata:s:v", "title=Album cover",
        "-metadata:s:v", "comment=Cover (front)",
        "-metadata", f"title={tags.get('title', '')}",
        "-metadata", f"artist={tags.get('artist', '')}",
        "-metadata", f"album={tags.get('album', '')}",
        str(tmp),
    ]
    if subprocess.run(cmd).returncode == 0 and tmp.exists():
        tmp.replace(audio)
    else:
        tmp.unlink(missing_ok=True)


# --------------------------------------------------------------------------- #
# fetching one track
# --------------------------------------------------------------------------- #

IMG = (".jpg", ".jpeg", ".png", ".webp")


def grab(url: str, name: str, folder: Path, meta: dict, args) -> dict | None:
    """Download one track, always embedding cover art and tags."""
    covers = folder / "covers"
    covers.mkdir(parents=True, exist_ok=True)

    # yt-dlp writes to a hex stem: track names hold dots, %, brackets and worse,
    # all of which break output templates, suffix handling or globbing.
    tmp = folder / hashlib.md5(url.encode()).hexdigest()[:12]
    done = False
    try:
        try:
            got = download_track(url, tmp, args)
        except Exception as exc:
            print(f"  ! download failed: {short(exc)}")
            if any(p in str(exc).lower() for p in FORMAT_ERRORS):
                list_formats(url, args)
            warn_signin(exc, args)
            return None
        if not got:
            print("  ! download produced nothing")
            return None

        # cover art: Spotify's album image first, YouTube's thumbnail as backup
        cover = covers / f"{name}.jpg"
        if not cover.exists() and meta.get("cover"):
            fetch(meta["cover"], cover, args.net)
        for thumb in folder.glob(f"{tmp.name}.*"):
            if thumb.suffix.lower() in IMG:
                if cover.exists():
                    thumb.unlink(missing_ok=True)
                else:
                    cover = covers / f"{name}{thumb.suffix}"
                    thumb.replace(cover)

        if cover.exists():
            embed(got, cover, meta)
        else:
            print("  ~ no cover art found")

        got.replace(folder / f"{name}.{args.codec}")
        done = True
        return {
            "url": url,
            "yt_title": meta.get("yt_title", ""),
            "title": meta.get("title", ""),
            "artist": meta.get("artist", ""),
            "album": meta.get("album", ""),
            "cover": meta.get("cover", ""),
            "duration": meta.get("duration"),
        }
    finally:
        # tidy up after a failure or a ctrl-c; .part files stay so yt-dlp resumes
        if not done:
            for leftover in folder.glob(f"{tmp.name}.*"):
                if leftover.suffix != ".part":
                    leftover.unlink(missing_ok=True)


# The "there is nothing here to download" family: worth a retry on another
# client, and worth showing what yt-dlp did see when every client fails.
FORMAT_ERRORS = (
    "requested format is not available",
    "no video formats found",
)

# Unauthenticated extraction can sail through and still hand back stream URLs
# the CDN refuses, so the refusal only surfaces as a 403 on the media request,
# long after the formats looked healthy. It means "come back signed in", not
# "this video is broken", so it has to escalate like any other gate.
REFUSED = (
    "http error 403",
    "unable to download video data",
)

RETRYABLE = ("sign in to confirm", "age-restricted", "confirm your age") + FORMAT_ERRORS + REFUSED


def short(exc: object) -> str:
    """yt-dlp errors trail three wiki URLs; keep the part that says what happened."""
    msg = str(exc).replace("ERROR: ", "")
    for cut in (" Use --cookies", " Use --list-formats", ". See ", " See "):
        msg = msg.split(cut)[0]
    return msg.strip()


def list_formats(url: str, args) -> None:
    """Print what yt-dlp did see, for tracks where none of it was downloadable."""
    info, last = None, ""
    for net, client in args.plan:
        opts = {"quiet": True, "no_warnings": True, "logger": Hush(), **net}
        if client:
            opts["extractor_args"] = {"youtube": {"player_client": [client]}}
        try:
            with ytsearch.YoutubeDL(opts) as ydl:
                # process=False: format *selection* is what just failed, and it
                # runs even with download=False. The raw dict keeps the formats.
                info = ydl.extract_info(url, download=False, process=False)
            if info:
                break
        except Exception as exc:
            last = short(exc)
            continue
    if not info:
        print(f"  ~ could not even list formats: {last or 'no answer from any client'}")
        return

    rows = [f for f in info.get("formats") or [] if f.get("format_id")]
    audio = [f for f in rows if f.get("acodec") not in (None, "none")]
    shown = audio or rows
    if not shown:
        print("  ~ yt-dlp lists no formats at all for this video")
        return

    print(f"  ~ formats available for {info.get('id') or url}:")
    print(f"      {'ID':<10} {'EXT':<5} {'ABR':>5}  NOTE")
    for f in shown:
        abr = f.get("abr")
        print(f"      {str(f['format_id']):<10} {str(f.get('ext') or '?'):<5} "
              f"{(f'{abr:.0f}' if abr else '-'):>5}  {f.get('format_note') or ''}".rstrip())
    if audio and len(rows) > len(audio):
        print(f"      (+{len(rows) - len(audio)} video-only formats)")


# Cookies used to be a liability on the happy path: the clients that take them
# needed a PO token for audio formats, so downloading unauthenticated came
# first. With the JS challenge actually being solved (see js_opts) that is no
# longer true, and this address now gets a 403 on every unauthenticated media
# request - a whole extraction plus a refused transfer per track. So lead with
# cookies where there are any, and keep the unauthenticated rung as the
# fallback for whatever the signed-in account is itself blocked from.
def attempt_plan(args) -> list[tuple[dict, str | None]]:
    """(network options, player client) to try in order, widest access first."""
    auth = args.net
    plain = {k: v for k, v in auth.items() if k not in ("cookiefile", "cookiesfrombrowser")}
    if plain == auth:  # no cookies configured, nothing to fall back to
        return [(plain, None)]
    return [(auth, None), (plain, None), (auth, "tv"), (auth, "web_embedded")]


def plan_label(step: tuple[dict, str | None]) -> str:
    net, client = step
    who = "cookies" if "cookiefile" in net or "cookiesfrombrowser" in net else "no cookies"
    return f"{who} as {client}" if client else who


def download_track(url: str, stem: Path, args) -> Path | None:
    """Download, escalating to authenticated clients only for gated videos."""
    plan = args.plan
    for i, (net, client) in enumerate(plan):
        try:
            return download(url, stem, args.codec, net, client)
        except Exception as exc:
            nxt = plan[i + 1] if i + 1 < len(plan) else None
            if not nxt or not any(p in str(exc).lower() for p in RETRYABLE):
                raise
            print(f"  ~ blocked ({plan_label(plan[i])}); retrying with {plan_label(nxt)}")
    return None


_warned = False


def warn_signin(exc: object, args) -> None:
    """YouTube's gates are unmissable but their fix is not; say it once."""
    global _warned
    msg = str(exc).lower()
    gated = "sign in to confirm" in msg or "age-restricted" in msg
    if _warned or not gated:
        return
    _warned = True
    age = "age" in msg
    if args.cookies or args.cookies_from_browser:
        print("  ~ cookies are set but YouTube still wants a sign-in: they may be stale,"
              "\n    or the account is not age-verified. Re-export, or use another account.")
    else:
        what = ("These videos are age-restricted and need an age-verified account."
                if age else "YouTube is rate-limiting this run.")
        print(f"  ~ {what} Authenticate with either:"
              "\n      --cookies-from-browser firefox     (close the browser first)"
              "\n      --cookies cookies.txt              (Netscape export)"
              "\n    Finished tracks are kept, so re-running resumes where this stopped.")


def track_name(meta: dict) -> str:
    both = f"{meta.get('artist', '')} - {meta.get('title', '')}".strip(" -")
    return slug(both or meta.get("yt_title") or "untitled")


# YouTube signs its stream URLs with a JavaScript challenge. With no runtime to
# solve it, every video extracts as storyboards only and every download dies
# with "Requested format is not available" - which reads like a format problem
# and is not one. Deno is the runtime yt-dlp looks for by default; the solver
# scripts are not vendored, so they are fetched from the yt-dlp-ejs repo.
DENO_FALLBACKS = (Path.home() / ".deno" / "bin" / "deno",)


def find_runtime() -> tuple[str, str] | None:
    """(name, path) of a JS runtime yt-dlp can drive, in its own order of
    preference. Only deno is enabled by default and yt-dlp reads no config file
    when driven as a library, so whichever one exists has to be named here."""
    deno = shutil.which("deno") or next((str(p) for p in DENO_FALLBACKS if p.exists()), None)
    if deno:
        return "deno", deno
    node = shutil.which("node")  # its solver script comes from ejs:github too
    if node:
        return "node", node
    return None


def js_opts() -> dict:
    """The signature solver: a JS runtime plus permission to fetch its scripts."""
    opts: dict = {"remote_components": ["ejs:github"]}
    found = find_runtime()
    if found:
        name, path = found
        opts["js_runtimes"] = {name: {"path": path}}
    return opts


def check_js_runtime() -> None:
    """Say it up front: without this every single track fails the same way."""
    if find_runtime():
        return
    print("  ! no JavaScript runtime found - YouTube's signature challenge cannot be"
          "\n    solved, so every download will fail with \"Requested format is not"
          "\n    available\". Install deno (yt-dlp's default):"
          "\n      curl -fsSL https://deno.land/install.sh | sh")


def net_opts(args) -> dict:
    """Proxy and YouTube auth, shared by search, download and cover fetch."""
    opts: dict = js_opts()
    if args.proxy:
        opts["proxy"] = args.proxy
    if args.cookies:
        opts["cookiefile"] = str(Path(args.cookies).expanduser())
    if args.cookies_from_browser:
        opts["cookiesfrombrowser"] = ytsearch.browser_spec(args.cookies_from_browser)
    return opts


def check_proxy(args) -> bool:
    """One probe request, so a bad proxy fails loudly instead of as 'no results'."""
    print(f"proxy {args.proxy} ...", end=" ", flush=True)
    ok = bool(ytsearch.search("music", limit=1, extra=args.net))
    print("ok" if ok else "unreachable")
    return ok


class Store:
    """A json dict on disk, flushed in batches - 38k tracks means 38k writes."""

    def __init__(self, folder: Path, filename: str, every: int = 100):
        self.path = folder / filename
        self.every = every
        self.dirty = 0
        try:
            self.data = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            self.data = {}

    def add(self, name: str, entry: dict | None) -> None:
        self.data[name] = entry
        self.dirty += 1
        if self.dirty >= self.every:
            self.flush()

    def flush(self) -> None:
        if not self.dirty:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = Path(f"{self.path}.tmp")
        tmp.write_text(json.dumps(self.data, ensure_ascii=False, indent=1), encoding="utf-8")
        tmp.replace(self.path)  # atomic, so a ctrl-c cannot truncate the real file
        self.dirty = 0


AUDIO = (".mp3", ".opus", ".m4a", ".flac", ".ogg", ".wav", ".webm")


def audio_file(folder: Path, name: str, codec: str) -> Path | None:
    """The track's file, tolerating a folder built with a different --codec."""
    for ext in (f".{codec}", *AUDIO):
        f = folder / f"{name}{ext}"
        if f.exists():
            return f
    return None


def write_m3u(path: Path, order: list[tuple[str, dict]], pool: Path, codec: str) -> int:
    """#EXTM3U in playlist order, listing whichever tracks actually landed."""
    lines, found = ["#EXTM3U"], 0
    for name, meta in order:
        f = audio_file(pool, name, codec)
        if not f:
            continue
        found += 1
        secs = meta.get("duration")
        label = f"{meta.get('artist', '')} - {meta.get('title', '')}".strip(" -")
        lines.append(f"#EXTINF:{int(secs) if secs else -1},{label or name}")
        rel = os.path.relpath(f, path.parent)
        lines.append(str(f) if rel.startswith("..") else rel)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return found


# --------------------------------------------------------------------------- #
# one pool of tracks, many playlists
# --------------------------------------------------------------------------- #


def collect(files: list[Path], args) -> tuple[dict[str, dict], list[tuple[Path, list[str]]]]:
    """Every csv folded into one deduplicated set, keeping each csv's own order."""
    unique: dict[str, dict] = {}
    listing: list[tuple[Path, list[str]]] = []
    rows = 0

    for f in files:
        try:
            tracks = read_tracks(f)
        except Exception as exc:
            print(f"! {f.name}: {exc}", file=sys.stderr)
            continue
        if args.limit:
            tracks = tracks[: args.limit]
        rows += len(tracks)
        names = []
        for t in tracks:
            name = track_name(t)
            names.append(name)
            unique.setdefault(name, t)  # first csv to mention it wins
        listing.append((f, names))

    dupes = rows - len(unique)
    print(f"   {len(listing)} playlists \u00b7 {rows} rows \u00b7 {len(unique)} unique"
          f" ({dupes} duplicate{'' if dupes == 1 else 's'} skipped)")
    return unique, listing


ITERATOR = ROOT / "iterator.txt"


def read_iterator() -> int:
    """How many tracks previous runs got through. Absent or junk means start over."""
    try:
        return max(0, int(ITERATOR.read_text().strip() or 0))
    except (OSError, ValueError):
        return 0


def write_iterator(n: int) -> None:
    ITERATOR.write_text(f"{n}\n")


def run_pool(unique: dict[str, dict], manifest: Store, args) -> None:
    """Walk the track list from where the last run stopped, one step at a time.

    The position indexes the full deduplicated list, which `collect` builds in a
    stable order, so it stays meaningful between runs. Every track advances it,
    downloaded or not - that is what stops a re-run repeating the same work.
    """
    todo = list(unique.items())
    # a one-off playlist named on the command line is not the full deduplicated
    # list, so it neither reads nor writes the shared position
    shared = not getattr(args, "no_iterator", False)
    if args.start is not None:
        start = args.start - 1
    else:
        start = read_iterator() if shared and not args.reset else 0
    start = min(max(start, 0), len(todo))

    if start:
        print(f"\n== {len(todo)} tracks, resuming at #{start + 1}")
    else:
        print(f"\n== {len(todo)} tracks, starting from the top")
    if start >= len(todo):
        print("   iterator is at the end; --reset to go again")
        return

    def advance(i: int) -> None:
        if shared and not args.dry_run:  # a dry run must not move the real position
            write_iterator(i + 1)

    # A run that has stopped working fails every track the same way - a blocked
    # address, stale cookies, no JS runtime - while the position keeps advancing
    # through tracks nothing ever really tried. So count failures in a row, and
    # on a long enough streak stop and hand the position back to where the
    # streak began: the next run retries those tracks instead of skipping them.
    streak: list[int] = []

    def failed(i: int) -> bool:
        """Note a failure. True when the streak says the run itself is broken."""
        streak.append(i)
        if args.max_fails and len(streak) >= args.max_fails:
            return True  # leave the position where it is; the caller rewinds it
        advance(i)
        return False

    def worked(i: int) -> None:
        streak.clear()
        advance(i)

    for i in range(start, len(todo)):
        name, t = todo[i]
        head = f"[{i + 1}/{len(todo)}] {name}"

        if not args.force and audio_file(POOL, name, args.codec):
            print(f"{head}\n  \u00b7 have it")
            worked(i)
            continue

        query = f"{t['artist']} {t['title']}".strip()
        try:
            results = ytsearch.search(query, limit=args.results, extra=args.net)
        except Exception as exc:
            print(f"{head}\n  ! search failed: {exc}")
            if failed(i):
                break
            continue
        if not results:
            print(f"{head}\n  ! no results")
            if failed(i):
                break
            continue

        print(head)
        if args.manual:
            hit = choose(results)
            if hit is None:
                print("  \u00b7 skipped")
                advance(i)  # a choice, not a failure: leaves the streak alone
                continue
        else:
            hit = pick(results, t["duration"])
        print(f"  -> {hit.title}  [{hit.duration_hms}]  {hit.url}")

        if args.dry_run:
            continue

        t["yt_title"] = hit.title
        entry = grab(hit.url, name, POOL, t, args)
        if not entry:
            if failed(i):
                break
            continue
        manifest.add(name, entry)
        worked(i)

    if args.max_fails and len(streak) >= args.max_fails:
        first, last = streak[0] + 1, streak[-1] + 1
        if shared and not args.dry_run:
            write_iterator(streak[0])
        print(f"\n   ! stopped: {len(streak)} tracks in a row failed (#{first}-#{last}),"
              "\n     which usually means the run itself is broken rather than the tracks."
              f"\n     The position is back at #{first}, so a re-run retries them; finished"
              "\n     tracks are kept either way."
              f"\n     Genuinely dead tracks? Step over them with --start {last + 1},"
              "\n     or stop checking with --max-fails 0.")


def write_playlists(unique: dict[str, dict], listing: list, args) -> None:
    """One .m3u per csv, pointing into the pool."""
    #print(f"\n== playlists -> {args.playlist_dir}")
    print(f"\n== {len(listing)} playlists inline (in the same folder as tracks)")
    total = 0
    for f, names in listing:
        path = POOL / f"{slug(f.stem)}.m3u"
        found = write_m3u(path, [(n, unique.get(n, {})) for n in names], POOL, args.codec)
        total += found
        print(f"   {path.name}  ({found}/{len(names)})")
    print(f"   {len(listing)} playlists, {total} entries")


def do_csvs(files: list[Path], args) -> None:
    unique, listing = collect(files, args)
    if not unique:
        print("nothing to do")
        return
    manifest = Store(OUT_DIR, "archive.json")
    try:
        if not args.playlists_only:
            run_pool(unique, manifest, args)
    finally:
        manifest.flush()
        if not args.dry_run:
            write_playlists(unique, listing, args)


# --------------------------------------------------------------------------- #
# from a json manifest
# --------------------------------------------------------------------------- #


def load_json(path: Path) -> list[tuple[str, dict]]:
    """Accept an archive.json ({name: entry}) or a plain list of entries."""
    data = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(data, dict):
        # nested one level ({playlist: {name: entry}}) or flat ({name: entry})
        items = list(data.items())
        if items and all(isinstance(v, dict) and "url" not in v for _, v in items):
            items = [kv for _, sub in items for kv in sub.items()]
    elif isinstance(data, list):
        items = [(track_name(e), e) for e in data if isinstance(e, dict)]
    else:
        raise ValueError("expected a JSON object or list")
    return [(n, e) for n, e in items if isinstance(e, dict) and e.get("url")]


def do_json(path: Path, args) -> None:
    entries = load_json(path)
    if args.limit:
        entries = entries[: args.limit]

    folder = Path(args.out) if args.out else POOL
    if not args.dry_run:
        folder.mkdir(parents=True, exist_ok=True)
    manifest = Store(folder, "archive.json")

    print(f"\n== {path.name}  ({len(entries)} tracks) -> {folder}")

    order: list[tuple[str, dict]] = []
    try:
        run_json(entries, folder, manifest, order, args)
    finally:
        manifest.flush()
        if not args.dry_run:
            # inline beside the tracks, unless --playlist-dir is re-enabled
            m3u = Path(getattr(args, "playlist_dir", None) or folder) / f"{slug(path.stem)}.m3u"
            print(f"   {m3u.name}  ({write_m3u(m3u, order, folder, args.codec)} tracks)")


def run_json(entries, folder: Path, manifest, order: list, args) -> None:
    streak = 0
    for i, (name, e) in enumerate(entries, 1):
        name = slug(name)
        order.append((name, e))
        head = f"[{i}/{len(entries)}] {name}"

        if audio_file(folder, name, args.codec) and not args.force:
            print(f"{head}\n  \u00b7 have it")
            continue

        label = f"{e['yt_title']}  " if e.get("yt_title") else ""
        print(f"{head}\n  -> {label}{e['url']}")
        if args.dry_run:
            continue

        entry = grab(e["url"], name, folder, e, args)
        if entry:
            manifest.add(name, entry)
            streak = 0
            continue
        streak += 1
        if args.max_fails and streak >= args.max_fails:
            # no shared position here, so just stop before burning the whole list
            print(f"\n   ! stopped: {streak} tracks in a row failed, which usually means"
                  "\n     the run itself is broken. Re-run to try them again (finished"
                  "\n     tracks are skipped), or stop checking with --max-fails 0.")
            return


# --------------------------------------------------------------------------- #
# interactive playlist picker
# --------------------------------------------------------------------------- #

PAGE = 20
_counts: dict[Path, int] = {}


def norm(text: str) -> str:
    """Fold punctuation and underscores to spaces so 'best of' finds best_of_bach."""
    return re.sub(r"[\W_]+", " ", text.lower()).strip()


def n_tracks(path: Path) -> int:
    """Row count, near enough for a menu; computed once per file, on demand."""
    if path not in _counts:
        try:
            if path.suffix.lower() == ".json":
                _counts[path] = len(read_screenshot_tracks(path))
            else:
                with path.open("rb") as fh:
                    _counts[path] = max(0, fh.read().count(b"\n") - 1)
        except (OSError, ValueError):
            _counts[path] = 0
    return _counts[path]


def menu(files: list[Path], term: str = "") -> list[Path]:
    """Type to search, numbers to toggle, Enter to start. Returns the picks."""
    chosen: set[Path] = set()
    page = 0

    while True:
        words = norm(term).split()
        shown = [f for f in files if all(w in norm(f.stem) for w in words)] if words else files
        pages = max(1, -(-len(shown) // PAGE))
        page = max(0, min(page, pages - 1))
        window = shown[page * PAGE : (page + 1) * PAGE]

        print()
        for i, f in enumerate(window, page * PAGE + 1):
            mark = "\u2022" if f in chosen else " "
            print(f" {mark} {i:>4}. {f.stem[:58]:<58} {n_tracks(f):>5}")
        if not window:
            print("   (nothing matches)")
        head = f"search {term.strip()!r} \u00b7 " if term.strip() else ""
        print(f"   {head}{len(shown)} playlists \u00b7 page {page + 1}/{pages} \u00b7 {len(chosen)} picked")

        try:
            ans = input("   text or /text=search  1,3-5=pick  a=all  c=clear  n/p=page  <enter>=go  q=quit > ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            return sorted(chosen)

        low = ans.lower()
        if low == "q":
            return []
        if not ans:
            if chosen:
                picks = sorted(chosen)
                print(f"\n   {len(picks)} playlists, {sum(n_tracks(f) for f in picks)} tracks")
                return picks
            print("   nothing picked yet")
            continue
        if low == "a":
            chosen.update(shown)
            continue
        if low == "c":
            chosen.clear()
            continue
        if low in ("n", "]"):
            page += 1
            continue
        if low in ("p", "["):
            page -= 1
            continue

        if ans.startswith("/"):
            term, page = ans[1:], 0
            continue

        picked = parse_ranges(ans, len(shown))
        if picked is None:  # not numbers -> treat it as a new search
            term, page = ans, 0
            continue
        for idx in picked:
            f = shown[idx - 1]
            chosen.symmetric_difference_update({f})


def parse_ranges(text: str, hi: int) -> list[int] | None:
    """"1,3-5 8" -> [1,3,4,5,8]. None if this isn't a number expression."""
    out: list[int] = []
    for part in text.replace(",", " ").split():
        m = re.fullmatch(r"(\d+)(?:-(\d+))?", part)
        if not m:
            return None
        a = int(m.group(1))
        b = int(m.group(2) or a)
        if a > b:
            a, b = b, a
        out.extend(i for i in range(a, b + 1) if 1 <= i <= hi)
    return out or None


# --------------------------------------------------------------------------- #
# cli
# --------------------------------------------------------------------------- #


def main(argv=None) -> int:
    global POOL, OUT_DIR

    p = argparse.ArgumentParser(prog="archive", description=__doc__.splitlines()[0])
    p.add_argument("-c", "--csv", action="append", metavar="NAME",
                   help="only playlist files whose name contains NAME (repeatable)")
    p.add_argument("-j", "--from-json", metavar="PATH", action="append",
                   help="an archive.json to download straight from, or a screenshot "
                        "playlist to search for (repeatable)")
    p.add_argument("-o", "--out", metavar="DIR", help="output folder for --from-json")
    p.add_argument("-P", "--playlist", nargs="?", const="", metavar="SEARCH",
                   help="pick playlists from an interactive menu (optional initial search)")
    #p.add_argument("--playlist-dir", metavar="DIR", default=str(PLAYLIST_DIR),
    #               help=f"where the .m3u files go (default {PLAYLIST_DIR})")
    p.add_argument("--cookies", metavar="FILE",
                   help="Netscape cookies.txt, to get past YouTube's sign-in check")
    p.add_argument("--cookies-from-browser", metavar="SPEC",
                   help="BROWSER[+KEYRING][:PROFILE][::CONTAINER], e.g. firefox, chrome:Default")
    p.add_argument("--proxy", metavar="URL",
                   help="e.g. socks5://127.0.0.1:1080 (socks5h:// to resolve DNS remotely)")
    p.add_argument("--manual", action="store_true", help="pick every result by hand")
    p.add_argument("-n", "--results", type=int, default=5, help="results per search (default 5)")
    p.add_argument("--codec", default="mp3", help="audio codec (default mp3)")
    p.add_argument("--limit", type=int, help="max tracks per playlist")
    p.add_argument("--force", action="store_true", help="re-download existing files")
    p.add_argument("--max-fails", type=int, default=10, metavar="N",
                   help="stop after N tracks in a row fail, keeping the position at the "
                        "first of them (0 = never stop; default 10)")
    p.add_argument("--reset", action="store_true",
                   help=f"ignore {ITERATOR.name} and start from the first track")
    p.add_argument("--start", type=int, metavar="N",
                   help="start at track N (1-based), and carry on from there")
    p.add_argument("--playlists-only", action="store_true",
                   help="just (re)write the .m3u files from what is already in the pool")
    p.add_argument("--dry-run", action="store_true", help="search and print, download nothing")
    args = p.parse_args(argv)

    spec = args.cookies_from_browser
    if spec and ytsearch.browser_spec(spec)[0] not in ytsearch.SUPPORTED_BROWSERS:
        print(f"unknown browser {spec!r}; pick one of "
              f"{', '.join(sorted(ytsearch.SUPPORTED_BROWSERS))}", file=sys.stderr)
        return 2
    if args.cookies and not Path(args.cookies).expanduser().is_file():
        print(f"no cookies file at {args.cookies}", file=sys.stderr)
        return 2
    check_js_runtime()
    args.net = net_opts(args)
    args.plan = attempt_plan(args)

    #if not args.dry_run:
    #    Path(args.playlist_dir).mkdir(parents=True, exist_ok=True)

    if args.proxy and not check_proxy(args):
        return 3

    if args.from_json:
        for name in args.from_json:
            path = Path(name)
            if not path.is_file():
                print(f"! no such json: {path}", file=sys.stderr)
                continue
            try:
                # a screenshot playlist has no urls in it, so it goes through
                # the search pipeline instead of the straight-to-download replay
                if is_screenshot_json(path):
                    args.no_iterator = True
                    if args.out:
                        POOL = OUT_DIR = Path(args.out)
                    if not args.dry_run:
                        POOL.mkdir(parents=True, exist_ok=True)
                    do_csvs([path], args)
                else:
                    do_json(path, args)
            except KeyboardInterrupt:
                print("\nstopped")
                return 130
            except Exception as exc:
                print(f"! {path.name}: {exc}", file=sys.stderr)
        return 0

    if not CSV_DIR.is_dir():
        return print(f"no csv folder at {CSV_DIR}", file=sys.stderr) or 1

    files = sorted([*CSV_DIR.glob("*.csv"),
                    *(f for f in CSV_DIR.glob("*.json") if is_screenshot_json(f))])
    if args.csv:
        pats = [s.lower() for s in args.csv]
        files = [f for f in files if any(s in f.name.lower() for s in pats)]
    if not files:
        return print("no matching playlist files", file=sys.stderr) or 2

    if args.playlist is not None:
        files = menu(files, args.playlist)
        if not files:
            print("nothing to do")
            return 0

    if not args.dry_run:
        POOL.mkdir(parents=True, exist_ok=True)
    try:
        do_csvs(files, args)
    except KeyboardInterrupt:
        print("\nstopped")
        return 130
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
