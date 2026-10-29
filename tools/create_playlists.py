#!/usr/bin/env python3
"""create_playlists - Navidrome playlists that carry their original names.

Every playlist is named the way it was exported, not the way the filesystem
mangled it: csv/ is the register of original names (one file per playlist, csv
export or screenshot json) and each generated .m3u gets a #PLAYLIST: line with
that name, which is what Navidrome shows.

Two passes, both writing into PLAYLISTS_PATH:

  folders   every directory under ROOT that holds audio becomes one playlist.
            Its name comes from the csv whose name matches the folder's -
            compared loosely, so 50_cent/ finds "50 Cent.csv" - and falls back
            to the folder's own name when nothing matches.

  pool      the deduplicated track pool (tracks_spotify/) is not one playlist
            but hundreds, so it is unpacked instead: every csv becomes its own
            playlist of the pool tracks it lists, in its own order, under its
            own original name. --no-pool turns this off.

    create_playlists.py                     # the music folder + the pool
    create_playlists.py DIR                 # walk DIR instead
    create_playlists.py --no-pool           # folders only
    create_playlists.py --out ~/playlists   # somewhere else
    create_playlists.py --dry-run           # say what it would write

PLAYLISTS_PATH must sit inside Navidrome's MusicFolder: PlaylistsPath is
resolved relative to it, so anything outside is never imported.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

# slug() and track_name() decide what a pool file is called; importing them is
# what keeps a csv row and the file archive.py wrote for it in agreement
import archive

MUSIC_EXTENSIONS = {
    ".mp3", ".flac", ".ogg", ".opus",
    ".aac", ".m4a", ".wav", ".wma",
    ".ape", ".mka",
}

ROOT = Path(__file__).resolve().parent
PLAYLISTS_PATH = Path("/mnt/extern/navidrome/music/playlists")
MUSIC = Path("/mnt/extern/navidrome/music")  # Navidrome's MusicFolder
CSV_DIR = ROOT / "csv"
POOL = ROOT / "tracks_spotify"


def norm(text: str) -> str:
    """Fold case, punctuation and underscores so 50_cent matches '50 Cent'."""
    return re.sub(r"[\W_]+", " ", text.lower()).strip()


def playlist_files(csv_dir: Path) -> list[Path]:
    """Every playlist export in csv/, csv and screenshot json alike."""
    if not csv_dir.is_dir():
        return []
    return sorted([*csv_dir.glob("*.csv"),
                   *(f for f in csv_dir.glob("*.json") if archive.is_screenshot_json(f))])


def original_names(files: list[Path]) -> dict[str, str]:
    """{normalized name: the name as exported}. First file with a name wins."""
    names: dict[str, str] = {}
    for f in files:
        names.setdefault(norm(f.stem), f.stem)
    return names


def find_music_files(directory: Path) -> list[str]:
    """Sorted paths of the music files sitting directly in `directory`."""
    entries = []
    try:
        for entry in os.scandir(directory):
            if entry.is_file() and os.path.splitext(entry.name)[1].lower() in MUSIC_EXTENSIONS:
                entries.append(entry.path)
    except PermissionError:
        print(f"  [skip] permission denied: {directory}", file=sys.stderr)
    return sorted(entries)


def write_m3u(out_dir: Path, name: str, paths: list[str], used: dict[str, str], args) -> Path | None:
    """One playlist called `name`, however its filename has to be spelled.

    The name Navidrome shows is the #PLAYLIST: line, so the filename only has
    to be unique and legal - two playlists whose names differ by a character
    the filesystem cannot keep still end up in two files.
    """
    stem = archive.slug(name)
    path = out_dir / f"{stem}.m3u"
    n = 2
    while str(path) in used and used[str(path)] != name:
        path = out_dir / f"{stem} ({n}).m3u"
        n += 1
    used[str(path)] = name

    if not args.dry_run:
        with path.open("w", encoding="utf-8") as fh:
            fh.write(f"#EXTM3U\n#PLAYLIST:{name}\n")
            for p in paths:
                fh.write(f"{p}\n")
    return path


def folder_playlists(root: Path, names: dict[str, str], out_dir: Path,
                     used: dict[str, str], args) -> tuple[int, int]:
    """One playlist per folder that holds music, named from csv/ where it can be."""
    made = matched = 0
    skip = args.pool.resolve() if args.pool else None

    for dirpath, dirnames, _ in os.walk(root):
        dirnames.sort()  # deterministic traversal
        here = Path(dirpath)
        if skip and here.resolve() == skip:
            dirnames[:] = []  # the pool is unpacked per csv instead
            continue

        music_files = find_music_files(here)
        if not music_files:
            continue

        name = names.get(norm(here.name))
        matched += name is not None
        path = write_m3u(out_dir, name or here.name, music_files, used, args)
        tail = f"  <- {name}" if name else ""
        print(f"  [+] {path.name}  ({len(music_files)} tracks){tail}")
        made += 1

    return made, matched


def pool_index(pool: Path) -> dict[str, str]:
    """{track name: its file} for the pool, read once - it holds 20k files."""
    index: dict[str, str] = {}
    try:
        for entry in os.scandir(pool):
            stem, ext = os.path.splitext(entry.name)
            if ext.lower() in MUSIC_EXTENSIONS and entry.is_file():
                index.setdefault(stem, entry.path)
    except OSError as exc:
        print(f"  ! {pool}: {exc.strerror or exc}", file=sys.stderr)
    return index


def pool_playlists(files: list[Path], pool: Path, out_dir: Path,
                   used: dict[str, str], args) -> tuple[int, int]:
    """Every csv becomes a playlist of the pool tracks it lists, in csv order."""
    index = pool_index(pool)
    if not index:
        return 0, 0
    print(f"\n{len(index)} tracks in {pool}")

    made = listed = 0
    for f in files:
        try:
            tracks = archive.read_tracks(f)
        except Exception as exc:
            print(f"  ! {f.name}: {exc}", file=sys.stderr)
            continue

        paths, seen = [], set()
        for t in tracks:
            name = archive.track_name(t)
            got = index.get(name)
            if got and got not in seen:  # a playlist may list a track twice
                seen.add(got)
                paths.append(got)
        if not paths:
            print(f"  [-] {f.stem}  (none of its {len(tracks)} tracks downloaded yet)")
            continue

        path = write_m3u(out_dir, f.stem, paths, used, args)
        print(f"  [+] {path.name}  ({len(paths)}/{len(tracks)} tracks)")
        made += 1
        listed += len(paths)

    return made, listed


def main() -> int:
    p = argparse.ArgumentParser(prog="create_playlists",
                                description=__doc__.splitlines()[0])
    p.add_argument("root", nargs="?", default=str(MUSIC), metavar="ROOT_DIR",
                   help=f"tree to walk for folders of music (default {MUSIC})")
    p.add_argument("--csv-dir", default=str(CSV_DIR), metavar="DIR",
                   help=f"where the original playlist names come from (default {CSV_DIR})")
    p.add_argument("--pool", default=str(POOL), metavar="DIR",
                   help=f"the deduplicated track pool, unpacked per csv (default {POOL})")
    p.add_argument("--no-pool", action="store_true",
                   help="skip the per-csv pool playlists, walk folders only")
    p.add_argument("--out", default=str(PLAYLISTS_PATH), metavar="DIR",
                   help=f"where the .m3u files go (default {PLAYLISTS_PATH})")
    p.add_argument("--dry-run", action="store_true", help="print, write nothing")
    args = p.parse_args()

    root = Path(os.path.abspath(args.root))
    if not root.is_dir():
        print(f"Error: '{root}' is not a directory.", file=sys.stderr)
        return 1

    out_dir = Path(os.path.abspath(args.out))
    args.pool = None if args.no_pool else Path(os.path.abspath(args.pool))

    files = playlist_files(Path(os.path.abspath(args.csv_dir)))
    names = original_names(files)
    print(f"{len(files)} playlist names in {args.csv_dir}")
    if not files:
        print("  ~ no csv exports found; folders keep their own names")

    if not args.dry_run:
        out_dir.mkdir(parents=True, exist_ok=True)
    print(f"Writing into {out_dir}{' (dry run)' if args.dry_run else ''}\n")

    used: dict[str, str] = {}
    print(f"Scanning: {root}")
    made, matched = folder_playlists(root, names, out_dir, used, args)
    print(f"  {made} folder playlist(s), {matched} named from {args.csv_dir}")

    if args.pool and args.pool.is_dir():
        pooled, listed = pool_playlists(files, args.pool, out_dir, used, args)
        print(f"  {pooled} pool playlist(s), {listed} entries")
        made += pooled

    print(f"\nDone. {made} playlist(s){' would be' if args.dry_run else ''} written.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
