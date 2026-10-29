#!/usr/bin/env python3
"""ytsearch - search YouTube from the shell or from Python, via yt-dlp.

No API key, no quota. Uses yt-dlp's flat extraction, so N results cost one
request instead of N metadata lookups.

    pip install yt-dlp

    ytsearch.py "aphex twin rhubarb" -n 5
    ytsearch.py "petar danov beseda" -n 30 --json | jq -r '.[].url'
    ytsearch.py "cachyos install" --sort date --urls | xargs -r yt-dlp -x
    ytsearch.py "boards of canada" --proxy socks5://127.0.0.1:1080
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from dataclasses import asdict, dataclass
from urllib.parse import quote_plus

try:
    from yt_dlp import YoutubeDL
    from yt_dlp.cookies import SUPPORTED_BROWSERS
except ImportError:  # pragma: no cover
    sys.exit("missing dependency: pip install yt-dlp")


# --------------------------------------------------------------------------- #
# model
# --------------------------------------------------------------------------- #


@dataclass
class Result:
    id: str
    title: str
    url: str
    channel: str | None = None
    channel_url: str | None = None
    duration: float | None = None
    view_count: int | None = None
    is_live: bool = False

    @property
    def duration_hms(self) -> str:
        if self.is_live:
            return "LIVE"
        if not self.duration:
            return "--:--"
        total = int(self.duration)
        hours, rem = divmod(total, 3600)
        minutes, seconds = divmod(rem, 60)
        if hours:
            return f"{hours}:{minutes:02d}:{seconds:02d}"
        return f"{minutes}:{seconds:02d}"

    @property
    def views_short(self) -> str:
        n = self.view_count
        if n is None:
            return "-"
        for limit, suffix in ((1e9, "B"), (1e6, "M"), (1e3, "K")):
            if n >= limit:
                return f"{n / limit:.1f}".rstrip("0").rstrip(".") + suffix
        return str(n)


# --------------------------------------------------------------------------- #
# core
# --------------------------------------------------------------------------- #

_BASE_OPTS = {
    "quiet": True,
    "no_warnings": True,
    "ignoreerrors": True,
    "skip_download": True,
    "noprogress": True,
    # flat extraction: don't resolve formats for every hit
    "extract_flat": "in_playlist",
}


def _flatten(entries):
    """Search pages nest videos inside shelves; walk down to the leaves."""
    for entry in entries or []:
        if not entry:
            continue
        if entry.get("_type") == "playlist" and "entries" in entry:
            yield from _flatten(entry["entries"])
        else:
            yield entry


def _to_result(entry) -> Result | None:
    vid = entry.get("id")
    # channel and playlist hits leak through; keep 11-char video ids only
    if not vid or len(vid) != 11:
        return None
    return Result(
        id=vid,
        title=entry.get("title") or "(no title)",
        url=entry.get("url") or f"https://www.youtube.com/watch?v={vid}",
        channel=entry.get("channel") or entry.get("uploader"),
        channel_url=entry.get("channel_url") or entry.get("uploader_url"),
        duration=entry.get("duration"),
        view_count=entry.get("view_count"),
        is_live=bool(entry.get("is_live") or entry.get("live_status") == "is_live"),
    )


def browser_spec(spec: str) -> tuple:
    """Parse yt-dlp's BROWSER[+KEYRING][:PROFILE][::CONTAINER] into its tuple form."""
    container = profile = keyring = None
    if "::" in spec:
        spec, container = spec.split("::", 1)
    if ":" in spec:
        spec, profile = spec.split(":", 1)
    if "+" in spec:
        spec, keyring = spec.split("+", 1)
    return (spec.strip().lower(), profile, keyring and keyring.strip().upper(), container)


def search(
    query: str,
    limit: int = 10,
    sort: str = "relevance",
    sp: str | None = None,
    cookies_from_browser: str | None = None,
    cookies: str | None = None,
    proxy: str | None = None,
    extra: dict | None = None,
) -> list[Result]:
    """Return up to `limit` video results for `query`.

    sort: "relevance" or "date".
    sp:   raw YouTube filter token. Run a filtered search in your browser and
          copy the `sp=` value out of the URL (duration, upload date, HD, ...).
          Overrides `sort`.
    cookies_from_browser: e.g. "firefox" - helps when YouTube demands consent
          or throws a bot check.
    cookies: path to a Netscape cookies.txt export - the other way past a bot
          check, and the one that survives a browser being open.
    proxy: e.g. "socks5://127.0.0.1:1080". Use socks5h:// to resolve DNS through
          the proxy too; http:// and https:// work as well.
    extra: any further YoutubeDL options, merged last.
    """
    opts = dict(_BASE_OPTS)
    if cookies_from_browser:
        opts["cookiesfrombrowser"] = browser_spec(cookies_from_browser)
    if cookies:
        opts["cookiefile"] = cookies
    if proxy:
        opts["proxy"] = proxy
    opts.update(extra or {})

    if sp:
        target = f"https://www.youtube.com/results?search_query={quote_plus(query)}&sp={sp}"
        opts["playlistend"] = limit
    else:
        prefix = "ytsearchdate" if sort == "date" else "ytsearch"
        target = f"{prefix}{limit}:{query}"

    with YoutubeDL(opts) as ydl:
        info = ydl.extract_info(target, download=False)

    if not info:
        return []

    results: list[Result] = []
    seen: set[str] = set()
    for entry in _flatten(info.get("entries")):
        r = _to_result(entry)
        if r and r.id not in seen:
            seen.add(r.id)
            results.append(r)
        if len(results) >= limit:
            break
    return results


def details(video_id_or_url: str, proxy: str | None = None) -> dict:
    """Full metadata for one video (formats, description, chapters...)."""
    opts = {"quiet": True, "no_warnings": True, "skip_download": True}
    if proxy:
        opts["proxy"] = proxy
    with YoutubeDL(opts) as ydl:
        return ydl.extract_info(video_id_or_url, download=False)


# --------------------------------------------------------------------------- #
# cli
# --------------------------------------------------------------------------- #


def _print_table(results: list[Result]) -> None:
    width = shutil.get_terminal_size((100, 24)).columns
    # index(4) + duration(9) + views(8) + channel(20) + separators
    title_w = max(24, width - 4 - 9 - 8 - 20 - 8)
    for i, r in enumerate(results, 1):
        title = r.title if len(r.title) <= title_w else r.title[: title_w - 1] + "…"
        chan = (r.channel or "-")[:20]
        print(f"{i:>3}. {title:<{title_w}}  {r.duration_hms:>8}  {r.views_short:>7}  {chan}")
        print(f"     {r.url}")


def main(argv=None) -> int:
    p = argparse.ArgumentParser(
        prog="ytsearch", description="Search YouTube via yt-dlp (no API key)."
    )
    p.add_argument("query", nargs="+", help="search terms")
    p.add_argument("-n", "--limit", type=int, default=10, help="max results (default 10)")
    p.add_argument(
        "--sort", choices=("relevance", "date"), default="relevance", help="result ordering"
    )
    p.add_argument("--sp", help="raw YouTube filter token copied from a browser URL")
    p.add_argument("--cookies-from-browser", metavar="BROWSER",
                   help="e.g. firefox, chrome:Profile 1, firefox+kwallet::container")
    p.add_argument("--cookies", metavar="FILE", help="Netscape cookies.txt to authenticate with")
    p.add_argument("--proxy", metavar="URL", help="e.g. socks5://127.0.0.1:1080, socks5h://, http://")

    out = p.add_mutually_exclusive_group()
    out.add_argument("--json", action="store_true", help="dump results as JSON")
    out.add_argument("--urls", action="store_true", help="print bare URLs, one per line")

    args = p.parse_args(argv)
    query = " ".join(args.query)

    try:
        results = search(
            query,
            limit=args.limit,
            sort=args.sort,
            sp=args.sp,
            cookies_from_browser=args.cookies_from_browser,
            cookies=args.cookies,
            proxy=args.proxy,
        )
    except Exception as exc:  # yt-dlp raises a whole zoo of these
        print(f"search failed: {exc}", file=sys.stderr)
        return 1

    if not results:
        print("no results", file=sys.stderr)
        return 2

    if args.json:
        json.dump([asdict(r) for r in results], sys.stdout, ensure_ascii=False, indent=2)
        print()
    elif args.urls:
        for r in results:
            print(r.url)
    else:
        _print_table(results)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
