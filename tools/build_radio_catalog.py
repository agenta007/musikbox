#!/usr/bin/env python3
"""Build the radio station catalog that musikbox ships with.

Reads stations.json (the scraped Radio Browser list, one row per station with its country)
and writes core/src/main/resources/app/musikbox/radio/stations.tsv.gz, which both apps read
through app.musikbox.radio.RadioCatalog.

    python3 tools/build_radio_catalog.py [path/to/stations.json]

File format (UTF-8, gzip, tab-separated, one record per line):
    #musikbox-radio <version> <generated>      header
    C <code> <name>                             a country
    G <index> <name>                            a genre
    S <id> <name> <url> <logo> <country> <codec> <kbps> <hls 0/1> <genre indexes, comma-separated> <votes>
Stations of a country keep the source's order (most listened first).
"""

import datetime
import gzip
import json
import re
import sys
from pathlib import Path

SOURCE = Path(sys.argv[1] if len(sys.argv) > 1 else "/mnt/storage/mysrc/claude/radio/stations.json")
OUT = Path(__file__).resolve().parent.parent / "core/src/main/resources/app/musikbox/radio/stations.tsv.gz"

# Genre -> keywords. A station joins a genre when one of its tags is, or contains as whole
# words, one of the keywords. The NAME list is the subset safe to look for in station names.
GENRES = [
    ("Pop", ["pop", "top 40", "top40", "hits", "charts", "chart", "adult contemporary", "chr", "k-pop", "kpop", "j-pop"]),
    ("Rock", ["rock", "alternative", "indie", "punk", "grunge", "hard rock", "classic rock"]),
    ("Metal", ["metal", "heavy metal", "metalcore", "death metal", "black metal"]),
    ("Dance & Electronic", ["dance", "electronic", "electronica", "edm", "house", "techno", "trance", "electro",
                            "drum and bass", "dnb", "dubstep", "club", "eurodance", "hardstyle", "deep house"]),
    ("Chill & Lounge", ["chill", "chillout", "chill out", "lounge", "ambient", "relax", "relaxing", "downtempo",
                        "easy listening", "smooth", "meditation"]),
    ("Hip-hop & R&B", ["hip hop", "hiphop", "hip-hop", "rap", "r&b", "rnb", "urban", "soul", "funk", "trap"]),
    ("Jazz & Blues", ["jazz", "blues", "swing", "smooth jazz", "bebop"]),
    ("Classical", ["classical", "klassik", "clasica", "clásica", "classique", "classica", "opera", "baroque",
                   "symphonic", "orchestra", "orchestral", "chamber music"]),
    ("Oldies & Retro", ["oldies", "50s", "60s", "70s", "80s", "90s", "retro", "classic hits", "golden oldies",
                        "nostalgia", "evergreen", "evergreens"]),
    ("Country & Folk", ["country", "folk", "bluegrass", "americana", "celtic", "folklore", "folclore", "traditional",
                        "chalga", "pop folk", "turbo folk", "narodna", "народна", "чалга", "фолк"]),
    ("Latin", ["latin", "latino", "latina", "salsa", "reggaeton", "bachata", "cumbia", "merengue", "tropical",
               "regional mexican", "regional mexicana", "ranchera", "banda", "norteño", "grupera", "mariachi",
               "música regional", "música popular mexicana", "tango", "bossa nova", "samba", "mpb", "sertanejo"]),
    ("World & Reggae", ["reggae", "world", "world music", "afrobeat", "afrobeats", "afro", "african", "bollywood",
                        "bhangra", "arabic", "ska", "dancehall", "caribbean", "zouk", "kompa", "soca", "highlife",
                        "oriental", "ethnic", "balkan"]),
    ("News", ["news", "noticias", "nachrichten", "information", "informacion", "información", "actualités",
              "notícias", "current affairs", "новини"]),
    ("Talk", ["talk", "talk radio", "news talk", "culture", "comedy", "podcast", "spoken word", "interviews",
              "debate", "education"]),
    ("Sports", ["sport", "sports", "deportes", "football", "soccer", "futbol", "fútbol", "cricket"]),
    ("Religious", ["christian", "religious", "religion", "gospel", "catholic", "worship", "church", "islamic",
                   "quran", "islam", "bible", "praise", "spiritual", "devotional", "sermon", "orthodox", "jewish"]),
    ("Kids", ["children", "kids", "kinder", "enfants", "children's music", "family"]),
    ("Community", ["community", "community radio", "public radio", "college", "college radio", "university",
                   "student", "campus"]),
]
NAME = {"jazz", "rock", "metal", "blues", "reggae", "classical", "klassik", "gospel", "christian", "news",
        "chill", "lounge", "techno", "trance", "oldies", "country", "folk", "latino", "salsa", "hip hop",
        "hiphop", "sport", "sports", "kids", "chalga", "чалга", "фолк", "новини", "rap", "dance", "80s", "90s"}


def word_pattern(keyword):
    return re.compile(r"(?<![\w])" + re.escape(keyword) + r"(?![\w])")


PATTERNS = [[word_pattern(k) for k in keywords] for _, keywords in GENRES]
NAME_PATTERNS = [[word_pattern(k) for k in keywords if k in NAME] for _, keywords in GENRES]


def genres_of(tags, name):
    tags = [t.strip().lower() for t in (tags or "").split(",") if t.strip()]
    name = name.lower()
    found = []
    for i, (patterns, name_patterns) in enumerate(zip(PATTERNS, NAME_PATTERNS)):
        if any(p.search(t) for t in tags for p in patterns) or any(p.search(name) for p in name_patterns):
            found.append(i)
    return found


def clean(text):
    return re.sub(r"\s+", " ", str(text or "")).strip()


def main():
    rows = json.load(open(SOURCE, encoding="utf-8"))
    countries, lines, seen = {}, [], set()
    per_genre = [0] * len(GENRES)
    for r in rows:
        cc = clean(r["country_code"]).upper()
        name = clean(r["name"])
        url = clean(r.get("url_resolved") or r.get("stream_url"))
        if not cc or not name or not url.startswith(("http://", "https://")):
            continue
        if (cc, url) in seen:  # the same stream twice in one country
            continue
        seen.add((cc, url))
        countries.setdefault(cc, clean(r["country"]) or cc)
        logo = clean(r.get("logo"))
        if not logo.startswith(("http://", "https://")):
            logo = ""
        genres = genres_of(r.get("tags"), name)
        for g in genres:
            per_genre[g] += 1
        codec = clean(r.get("codec")).upper()
        if codec == "UNKNOWN":
            codec = ""
        lines.append("\t".join([
            "S", clean(r["id"]), name, url, logo, cc, codec, str(int(r.get("bitrate") or 0)),
            "1" if r.get("hls") else "0", ",".join(map(str, genres)), str(int(r.get("votes") or 0)),
        ]))

    header = [f"#musikbox-radio\t1\t{datetime.date.today().isoformat()}"]
    header += [f"C\t{cc}\t{name}" for cc, name in sorted(countries.items(), key=lambda kv: kv[1])]
    header += [f"G\t{i}\t{name}" for i, (name, _) in enumerate(GENRES)]
    data = ("\n".join(header + lines) + "\n").encode("utf-8")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(gzip.compress(data, 9, mtime=0))

    print(f"{len(lines)} stations, {len(countries)} countries -> {OUT} ({OUT.stat().st_size // 1024} KiB)")
    untagged = sum(1 for line in lines if not line.split("\t")[9])
    print(f"{len(lines) - untagged} stations have a genre, {untagged} have none")
    for (name, _), n in sorted(zip(GENRES, per_genre), key=lambda x: -x[1]):
        print(f"  {name}: {n}")


if __name__ == "__main__":
    main()
