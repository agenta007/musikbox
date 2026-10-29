#!/usr/bin/env python3
"""gen_playlists - an inline .m3u in every folder that holds music.

Every directory under the roots that directly holds audio files gets
<dir>/<dirname>.m3u listing those files by bare name. Navidrome resolves
entries against the playlist's own folder, so bare names never break. Each run
rebuilds every playlist from what is on disk: new tracks in, removed ones out.

    gen_playlists.py                  # the music folder and tracks_spotify
    gen_playlists.py DIR [DIR ...]    # just these trees
    gen_playlists.py -j 8             # tag-reading threads

Only <dirname>.m3u is ever written; other playlists in a folder (archive.py's
per-csv ones in tracks_spotify) are left alone.

Durations and tags come from mutagen, ffprobe being the fallback for whatever
mutagen cannot parse (mka). Reading them is the whole cost - a cold read off
the external disk runs ~0.5 s a file - so results are cached by size and mtime
in ~/.cache/gen_playlists.json. The playlists are still rewritten in full every
run; only new or changed files are actually read. The first run reads them all
and can be interrupted: what was read so far is kept.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import mutagen

ROOT = Path(__file__).resolve().parent
MUSIC = Path("/mnt/extern/navidrome/music")  # Navidrome's MusicFolder
ROOTS = (MUSIC, ROOT / "tracks_spotify")     # today the second sits inside the first

CACHE = Path(os.environ.get("XDG_CACHE_HOME") or Path.home() / ".cache") / "gen_playlists.json"

AUDIO = {".mp3", ".flac", ".m4a", ".ogg", ".opus", ".wav", ".aac", ".wma", ".ape", ".mka"}


# --------------------------------------------------------------------------- #
# reading one track
# --------------------------------------------------------------------------- #

def tag(tags, *keys: str) -> str:
    """First non-empty value among `keys`, matched case-insensitively.

    The formats disagree on case (ARTIST in flac, artist in mp3) and on names
    (asf calls the artist Author, a wav's bare ID3 says TPE1)."""
    if not tags:
        return ""
    have = {str(k).lower(): k for k in tags.keys()}
    for key in keys:
        if key in have:
            val = tags[have[key]]
            if isinstance(val, list):
                val = val[0] if val else ""
            text = str(val).strip()
            if text:
                return text
    return ""


def ffprobe(path: Path) -> tuple[int, str, str]:
    """The slow path. Ogg and opus keep tags on the stream, so ask for both;
    -select_streams keeps an embedded cover's "title=Album cover" out of it."""
    cmd = [
        "ffprobe", "-v", "error", "-select_streams", "a:0",
        "-show_entries", "format=duration:format_tags=artist,title:stream_tags=artist,title",
        "-of", "default=nw=1", str(path),
    ]
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, errors="replace", timeout=60).stdout
    except (OSError, subprocess.SubprocessError):
        return -1, "", ""
    dur, artist, title = -1, "", ""
    for line in out.splitlines():
        key, _, val = line.partition("=")
        key = key.lower()
        if key == "duration":
            try:
                dur = round(float(val))
            except ValueError:
                pass
        elif key == "tag:artist" and not artist:
            artist = val.strip()
        elif key == "tag:title" and not title:
            title = val.strip()
    return dur, artist, title


def probe(path: Path) -> tuple[int, str, str]:
    """(seconds, artist, title), with -1 and '' for whatever cannot be read."""
    try:
        f = mutagen.File(path, easy=True)
    except Exception:
        f = None
    if f is None or f.info is None:
        return ffprobe(path)
    length = getattr(f.info, "length", 0)
    return (
        round(length) if length else -1,
        tag(f.tags, "artist", "author", "tpe1"),
        tag(f.tags, "title", "tit2"),
    )


def one_line(text: str) -> str:
    return text.replace("\r", " ").replace("\n", " ").strip()


class Tags:
    """probe() results by path, reused for as long as size and mtime hold."""

    def __init__(self, path: Path, roots: list[Path]):
        self.path = path
        try:
            old = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            old = {}
        self.old = old
        # entries outside this run's trees are carried over untouched; inside
        # them, only files seen this run survive, so deleted tracks drop out
        under = tuple(f"{r}{os.sep}" for r in roots)
        self.new = {k: v for k, v in old.items() if not k.startswith(under)}
        self.read = 0
        self.lock = threading.Lock()

    def get(self, f: Path) -> tuple[int, str, str]:
        key = str(f)
        try:
            st = f.stat()
        except OSError:
            return probe(f)
        stamp = [st.st_size, st.st_mtime_ns]
        hit = self.old.get(key)
        if hit and hit[:2] == stamp:
            dur, artist, title = hit[2:]
        else:
            dur, artist, title = probe(f)
            with self.lock:
                self.read += 1
        self.new[key] = [*stamp, dur, artist, title]
        return dur, artist, title

    def save(self) -> None:
        snap = dict(self.new)  # workers may still be adding after a ctrl-c
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(snap, ensure_ascii=False), encoding="utf-8")
        os.replace(tmp, self.path)


def entry(path: Path, tags: Tags) -> str:
    dur, artist, title = tags.get(path)
    artist = one_line(artist) or "Unknown Artist"
    title = one_line(title) or path.stem
    return f"#EXTINF:{dur},{artist} - {title}\n{path.name}\n"


# --------------------------------------------------------------------------- #
# folders
# --------------------------------------------------------------------------- #

def music_dirs(roots: list[Path]):
    """(folder, its audio files) for every folder holding audio directly."""
    seen: set[Path] = set()
    for root in roots:
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = sorted(d for d in dirnames if not d.startswith("."))
            here = Path(dirpath)
            real = here.resolve()
            if real in seen:  # tracks_spotify comes round twice by default
                continue
            seen.add(real)
            files = sorted(
                here / n for n in filenames
                if not n.startswith(".") and Path(n).suffix.lower() in AUDIO
            )
            if files:
                yield here, files


def write(folder: Path, files: list[Path], pool: ThreadPoolExecutor, tags: Tags) -> None:
    """Rebuild <folder>/<folder>.m3u, whole or not at all: Navidrome may be scanning."""
    m3u = folder / f"{folder.name}.m3u"
    tmp = folder / f".{folder.name}.m3u.tmp"
    body = "".join(pool.map(lambda f: entry(f, tags), files))
    try:
        tmp.write_text(f"#EXTM3U\n#PLAYLIST:{folder.name}\n{body}", encoding="utf-8")
        os.replace(tmp, m3u)
    finally:
        tmp.unlink(missing_ok=True)


def main() -> int:
    p = argparse.ArgumentParser(description="Write an inline .m3u into every folder that holds music.")
    p.add_argument("roots", nargs="*", type=Path, default=list(ROOTS),
                   help=f"trees to walk (default: {' '.join(map(str, ROOTS))})")
    p.add_argument("-j", "--jobs", type=int, default=8,
                   help="tag-reading threads; the disk, not the cpu, is the limit (default 8)")
    args = p.parse_args()
    roots = [Path(os.path.abspath(r)) for r in args.roots]  # the cache is keyed by absolute path
    tags = Tags(CACHE, roots)

    made = tracks = 0
    failed: list[Path] = []
    pool = ThreadPoolExecutor(args.jobs)
    try:
        for folder, files in music_dirs(roots):
            if not os.access(folder, os.W_OK):
                print(f"  ! {folder}: not writable")
                failed.append(folder)
                continue
            try:
                write(folder, files, pool, tags)
            except OSError as exc:
                print(f"  ! {folder}: {exc.strerror or exc}")
                failed.append(folder)
                continue
            made += 1
            tracks += len(files)
            print(f"  + {folder / folder.name}.m3u  ({len(files)})")
    except KeyboardInterrupt:
        # don't sit through a 17k-track folder's queued reads on the way out
        pool.shutdown(wait=False, cancel_futures=True)
        print(f"\ninterrupted after {made} playlists; {tags.read} files read are cached")
        return 130
    finally:
        tags.save()
    pool.shutdown()

    print(f"\n{made} playlists, {tracks} tracks ({tags.read} read, the rest cached)", end="")
    print(f", {len(failed)} folders not writable (owned by root?)" if failed else "")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
