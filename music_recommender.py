# /// script
# requires-python = ">=3.10"
# dependencies = ["ytmusicapi==1.12.4"]
# ///
"""Search YouTube Music, rank neighbors, or learn from interactive choices."""
import argparse
import csv
import hashlib
import json
import math
import os
import random
import re
import sys
import tempfile
import time
import unicodedata
from pathlib import Path

VIDEO_TYPES = {"MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_UGC"}

LIVE_MARKER = re.compile(
    r"\b(?:live\s+(?:at|from|in\s+concert|session|performance|version|recording|on\s+stage)"
    r"|(?:recorded|performed|filmed)\s+live|on[\s-]+stage|stage\s+performance"
    r"|concert\s+(?:footage|video|version)|in\s+concert|fancam|fan[\s-]+cam"
    r"|tiny\s+desk|unplugged|en\s+vivo|ao\s+vivo)\b"
    r"|(?:[\[(|]|[-–—]\s+)\s*live\b|\blive\s*[\])]",
    re.IGNORECASE,
)
STUDIO_MARKER = re.compile(r"\b(?:official\s+(?:music\s+)?video|studio\s+(?:version|recording))\b", re.I)

REMIX_MARKER = re.compile(r"\b(?:re[\s-]?mix(?:ed|es)?|mash[\s-]?up|sped[\s-]?up|slowed|nightcore|bootleg|reverb)\b", re.I)
LANGUAGE_LABEL = re.compile(
    r"\b(english|hindi|urdu|punjabi|bengali|tamil|telugu|malayalam|kannada|marathi|"
    r"gujarati|bhojpuri|nepali|spanish|portuguese|french|german|arabic|russian|korean|"
    r"japanese|chinese|thai|turkish|indonesian)\s+(?:songs?|music|version|audio|video|lyrics)\b",
    re.I,
)
LANGUAGE_SCRIPTS = {
    "DEVANAGARI": "devanagari", "BENGALI": "bengali-script", "GURMUKHI": "punjabi",
    "GUJARATI": "gujarati", "TAMIL": "tamil", "TELUGU": "telugu", "MALAYALAM": "malayalam",
    "KANNADA": "kannada", "HANGUL": "korean", "HIRAGANA": "japanese", "KATAKANA": "japanese",
    "ARABIC": "arabic-script", "CYRILLIC": "cyrillic", "THAI": "thai",
}


def language_hint(title):
    hints = {match.group(1).casefold() for match in LANGUAGE_LABEL.finditer(title)}
    if not hints:
        for char in title:
            if char.isalpha():
                script = unicodedata.name(char, "").split(" ", 1)[0]
                if script in LANGUAGE_SCRIPTS:
                    hints.add(LANGUAGE_SCRIPTS[script])
    return next(iter(hints)) if len(hints) == 1 else None


def artist_keys(track):
    keys = set()
    for artist in track["artists"]:
        name = re.sub(r"(?:\s*-\s*topic|vevo)$", "", artist.strip().casefold())
        key = "".join(char for char in name if char.isalnum())
        if key and key not in {"unknownartist", "unknownchannel"}:
            keys.add(key)
    return keys


def language_compatible(seed_language, seed_artists, song):
    language = language_hint(song["title"])
    if seed_language and language:
        return seed_language == language
    return bool(seed_artists & artist_keys(song))


def studio_candidate(title, kind):
    return bool(title and kind in VIDEO_TYPES and not LIVE_MARKER.search(title) and not REMIX_MARKER.search(title)
                and (kind == "MUSIC_VIDEO_TYPE_OMV" or STUDIO_MARKER.search(title)))


def live_recording(row):
    album = row.get("album")
    name = album.get("name") if isinstance(album, dict) else ""
    return (row.get("isLive") is True or LIVE_MARKER.search(row.get("title") or "")
            or LIVE_MARKER.search(name or ""))


def track_info(row):
    kind = row.get("videoType") or row.get("musicVideoType")
    if kind not in VIDEO_TYPES or row.get("resultType") not in (None, "video"):
        return None
    video_id = row.get("videoId")
    title = row.get("title")
    if (not video_id or not studio_candidate(title, kind) or live_recording(row)
            or row.get("isAvailable") is False):
        return None
    artists = [a["name"] for a in row.get("artists") or [] if a.get("name")]
    return {"title": title, "artists": artists, "video_id": video_id,
            "studio_filter_version": 1, "video_type": kind, "url": f"https://www.youtube.com/watch?v={video_id}"}


def identity(track):
    return (track["title"].strip().casefold(),
            tuple(sorted(a.strip().casefold() for a in track["artists"])))



def video_label(track):
    return "Music video" if track["video_type"] == "MUSIC_VIDEO_TYPE_OMV" else "Video"


def label(track):
    return f"{track['title']} — {', '.join(track['artists']) or 'unknown artist'}"


class Radio:
    def __init__(self, client, directory):
        self.client = client
        self.directory = directory
        directory.mkdir(parents=True, exist_ok=True)

    def cached(self, key, fetch):
        path = self.directory / (hashlib.sha256(key.encode()).hexdigest() + ".json")
        if path.exists() and time.time() - path.stat().st_mtime < 7 * 86400:
            try:
                return json.loads(path.read_text())
            except ValueError:
                pass
        result = fetch()
        if result:
            with tempfile.NamedTemporaryFile(mode="w", dir=self.directory, delete=False) as f:
                json.dump(result, f)
            os.replace(f.name, path)
        return result

    def search(self, query):
        def fetch():
            hits = self.client.search(query, filter="videos", limit=15)
            return [t for row in hits if (t := track_info(row))]
        return self.cached("studio-video-search-v1:" + query.casefold(), fetch)

    def verify_video(self, track):
        """Check a remembered manual choice by ID against the studio filter."""
        video_id = track["video_id"]

        def fetch():
            result = self.client.get_watch_playlist(videoId=video_id, limit=1)
            for row in result.get("tracks") or []:
                if row.get("isAvailable") is False or live_recording(row):
                    continue
                for candidate in (row, row.get("counterpart")):
                    if (isinstance(candidate, dict) and candidate.get("videoId") == video_id
                            and (verified := track_info(candidate))):
                        return verified
            return None

        return self.cached("studio-video-by-id-v1:" + video_id, fetch)

    def queue(self, seed):
        def fetch():
            result = self.client.get_watch_playlist(videoId=seed['video_id'], radio=True, limit=35)
            seen = {seed["video_id"]}
            songs = {identity(seed)}
            tracks = []
            for row in result.get("tracks") or []:
                if row.get("isAvailable") is False or live_recording(row):
                    continue
                t = track_info(row)
                if not t and isinstance(row.get("counterpart"), dict):
                    t = track_info(row["counterpart"])
                if not t or t["video_id"] in seen or identity(t) in songs:
                    continue
                seen.add(t["video_id"])
                songs.add(identity(t))
                tracks.append(t)
            return tracks
        return self.cached("studio-video-queue-v2:" + seed["video_id"], fetch)


def features(seed, track, position):
    artists = {a.casefold() for a in track["artists"]}
    current = {a.casefold() for a in seed["artists"]}
    x = {"same_artist": float(bool(artists & current))}
    if position is not None:
        x["radio_rank"] = 1 / math.log2(position + 1)
    for a in artists:
        x["artist:" + a] = 1 / len(artists)
    return x


def recording_group(track):
    # Group remixes and extended edits by title and lead artist.
    title = re.sub(r"\s*[(\[].*?\b(?:remix|extended|radio edit)\b.*?[)\]]", "",
                   track["title"], flags=re.I).strip()
    title = re.sub(r"\s*\(feat\. [^)]*\)$", "", title, flags=re.I).strip()
    artist = next(iter(track["artists"]), "").casefold()
    return (title.casefold(), artist)


class Listener:
    def __init__(self, path):
        self.path = path
        self.transitions = {}
        self.events_path = path.with_name("song_choices.csv")
        if self.events_path.exists():
            with self.events_path.open(newline="") as f:
                for source, song, reward in csv.reader(f):
                    self.remember(json.loads(source), json.loads(song), float(reward))
        self.weights = {"radio_rank": 0.65}
        if path.exists():
            with path.open(newline="") as f:
                for reward, raw in csv.reader(f):
                    self.update(json.loads(raw), float(reward))

    def remember(self, source, song, reward):
        entries = self.transitions.setdefault(recording_group(source), {})
        key = song["video_id"]
        previous = entries.get(key, (None, 0.0))[1]
        entries[key] = (song, 0.8 * previous + reward)

    def record(self, source, song, x, reward, *, announce=True):
        with self.events_path.open("a", newline="") as f:
            csv.writer(f).writerow([json.dumps(source), json.dumps(song), reward])
        self.remember(source, song, reward)
        self.feedback(x, reward)
        if announce:
            print(f"Saved {'choice' if reward > 0 else 'skip'}: {source['title']} -> {song['title']}")

    def base_score(self, x):
        return sum(self.weights.get(k, 0) * v for k, v in x.items())

    def update(self, x, reward):
        error = reward - self.base_score(x)
        for k, v in x.items():
            self.weights[k] = self.weights.get(k, 0) + 0.2 * error * v

    def feedback(self, x, reward):
        with self.path.open("a", newline="") as f:
            csv.writer(f).writerow([reward, json.dumps(x)])
        self.update(x, reward)


def search_choice(radio, query, confirm=True, artist=None):
    hits = radio.search(f"{query} {artist}" if artist else query)
    if artist:
        hits = [t for t in hits if artist.casefold() in {a.casefold() for a in t["artists"]}]
    wanted = " ".join(query.casefold().split())
    def exact(t):
        title = t["title"].casefold()
        return wanted == title or any(wanted == title + " " + a.casefold() for a in t["artists"])
    hits = sorted(hits, key=exact, reverse=True)[:5]
    if not hits:
        print("No matching studio-style videos found. Try adding the artist name.", file=sys.stderr)
        return None
    if not confirm:
        print(f"Matched: {label(hits[0])}", file=sys.stderr, flush=True)
        return hits[0]
    for i, hit in enumerate(hits, 1):
        print(f"  {i}. {label(hit)} [{video_label(hit)}]")
    answer = input("Choose a match [Enter = 1, 0 = cancel]: ").strip() or "1"
    if answer.isdigit() and 1 <= int(answer) <= len(hits):
        return hits[int(answer)-1]
    return None


def personalized(seed, queue, listener, excluded=(), remembered=()):
    past = listener.transitions.get(recording_group(seed), {})
    pool = {t["video_id"]: (t, i) for i, t in enumerate(queue, 1)}
    for song in remembered:
        pool.setdefault(song["video_id"], (song, None))
    for key, (song, net) in past.items():
        if net > 0 and song.get("studio_filter_version") == 1:
            pool.setdefault(key, (song, None))
    seen = set(excluded) | {seed["video_id"]}
    songs = {identity(seed)}
    ranked = []
    seed_language = language_hint(seed["title"])
    seed_artists = artist_keys(seed)
    for song, i in pool.values():
        if (song["video_id"] in seen or not studio_candidate(song["title"], song.get("video_type"))
                or not language_compatible(seed_language, seed_artists, song)):
            continue
        x = features(seed, song, i)
        prior = 0.55 * x.get("radio_rank", 0.0)
        broad = listener.base_score({k: v for k, v in x.items() if k != "radio_rank"})
        net = past.get(song["video_id"], (None, 0.0))[1]
        score = prior + 0.12 * math.tanh(broad) + 0.85 * math.tanh(net)
        ranked.append((score, song, x))
    ranked.sort(key=lambda row: row[0], reverse=True)
    unique = []
    for row in ranked:
        key = identity(row[1])
        if key not in songs:
            songs.add(key)
            unique.append(row)
    return unique[:20]


def resolve_seed(radio, video_id, title, artist):
    if video_id:
        artists = artist if isinstance(artist, list) else ([artist] if artist else [])
        return {"video_id": video_id, "title": title or video_id, "artists": artists}
    return search_choice(radio, title, confirm=False, artist=artist)


def recommend(radio, listener, video_id=None, title="", artist=None, *,
              excluded=(), use_knn=False, fill=False, on_fast=None, cancelled=lambda: False):
    """Return personalized (score, track, features) rows for an exact recording."""
    seed = resolve_seed(radio, video_id, title, artist)
    if not seed or cancelled():
        return []
    try:
        tracks = radio.queue(seed)
    except Exception:
        if not fill:
            raise
        tracks = []
    if cancelled():
        return []
    fast = personalized(seed, tracks, listener, excluded)
    pending = unverified_choices(seed, listener, tracks, excluded)
    needs_fill = fill and len(fast) < 20
    if not pending and not use_knn and not needs_fill:
        return fast
    if on_fast:
        on_fast(fast)
    remembered = remembered_choices(radio, pending, cancelled)
    if use_knn and not cancelled():
        tracks = rank({"seed": seed, "recommendations": tracks}, radio.queue, cancelled)
    rows = personalized(seed, tracks, listener, excluded, remembered)
    if needs_fill:
        artist = " ".join(seed["artists"])
        language = language_hint(seed["title"]) or ""
        if language.endswith("-script") or language in {"devanagari", "cyrillic"}:
            language = ""
        queries = [f"{artist} {language}", f"{seed['title']} {artist} {language}",
                   f"{artist} {language} studio songs", f"{artist} {language} music album"]
        for query in dict.fromkeys(query.strip() for query in queries):
            if len(rows) >= 20 or cancelled():
                break
            if not query:
                continue
            try:
                more = radio.search(query)
            except Exception:
                continue
            merged = {song["video_id"]: song for song in tracks}
            for song in more:
                merged.setdefault(song["video_id"], song)
            tracks = list(merged.values())
            rows = personalized(seed, tracks, listener, excluded, remembered)
    return rows


def unverified_choices(seed, listener, queue, excluded):
    seen = {t["video_id"] for t in queue} | set(excluded) | {seed["video_id"]}
    past = listener.transitions.get(recording_group(seed), {})
    entries = sorted(past.values(), key=lambda entry: entry[1], reverse=True)
    return [song for song, net in entries if net > 0 and song["video_id"] not in seen
            and song.get("studio_filter_version") != 1][:20]


def remembered_choices(radio, pending, cancelled):
    remembered = []
    for song in pending:
        if cancelled():
            break
        try:
            verified = radio.verify_video(song)
        except Exception as exc:
            print(f"Could not verify remembered recording: {exc}", file=sys.stderr)
            continue
        if verified:
            remembered.append(verified)
    return remembered


def session(radio, listener, query, epsilon, artist=None, use_knn=False):
    seed = search_choice(radio, query, artist=artist)
    if seed is None:
        return
    queue = candidates(radio, seed, use_knn)
    seen = {seed["video_id"]}
    while True:
        past = listener.transitions.get(recording_group(seed), {})
        ranked = personalized(seed, queue, listener, seen)
        if not ranked:
            print("No more eligible videos in this queue.")
            return
        exploring = len(ranked) > 1 and random.random() < epsilon
        score, pick, pick_x = random.choice(ranked[1:5]) if exploring else ranked[0]
        print(f"\nCurrent: {label(seed)}\nPick: {label(pick)}\n{pick['url']}")
        if exploring:
            print("Trying an alternative from the top five.")
        for i, (s, t, _) in enumerate(ranked, 1):
            net = past.get(t["video_id"], (None, 0.0))[1]
            reason = " | previously chosen here" if net > 0 else " | previously skipped here" if net < 0 else ""
            print(f"{i:2}. {label(t)} [{video_label(t)}]  ({s:.3f}){reason}")
        while True:
            cmd = input("\nEnter = accept | s = skip | number = choose | title = switch | ? title = select version | q = quit: ").strip()
            if cmd.casefold() == "q":
                return
            if cmd.casefold() == "s":
                listener.record(seed, pick, pick_x, -0.35)
                seen.add(pick["video_id"])
                break
            if not cmd:
                choice, chosen_x = pick, pick_x
            elif cmd.isdigit():
                n = int(cmd)
                if not 1 <= n <= len(ranked):
                    print("Choose a displayed number.")
                    continue
                _, choice, chosen_x = ranked[n-1]
            else:
                try:
                    confirm = cmd.startswith("?")
                    query = cmd[1:].strip() if confirm else cmd
                    if not query:
                        print("Type a song title after the question mark.")
                        continue
                    choice = search_choice(radio, query, confirm=confirm)
                except Exception as exc:
                    print(f"Search failed: {exc}")
                    continue
                if choice is None:
                    continue
                chosen_x = next((x for _, t, x in ranked if t["video_id"] == choice["video_id"]),
                                features(seed, choice, None))
            if choice["video_id"] == seed["video_id"]:
                print("That is already the current song.")
                continue
            try:
                new_queue = candidates(radio, choice, use_knn)
            except Exception as exc:
                print(f"Could not load the new queue: {exc}")
                continue
            if cmd.isdigit() and choice["video_id"] != pick["video_id"]:
                listener.record(seed, pick, pick_x, -0.35)
            listener.record(seed, choice, chosen_x, 1.0)
            seen.update((pick["video_id"], choice["video_id"]))
            seed, queue = choice, new_queue
            break



def vector(ids):
    return {track: 1 / math.log2(rank + 1) for rank, track in enumerate(ids, 1)}


def compare(seed, candidate, seed_id, candidate_id):
    # Leave the two songs out of each other's neighbor lists.
    left = {k: v for k, v in seed.items() if k != candidate_id}
    right = {k: v for k, v in candidate.items() if k != seed_id}
    shared = left.keys() & right.keys()
    norm = math.sqrt(sum(v*v for v in left.values()) * sum(v*v for v in right.values()))
    score = sum(left[k] * right[k] for k in shared) / norm if norm else None
    return score, len(shared)


def rank(data, fetch, cancelled=lambda: False):
    seed_id = data["seed"]["video_id"]
    unique = {}
    for song in data["recommendations"]:
        if song["video_id"] != seed_id:
            unique.setdefault(song["video_id"], song)
    seed = vector(unique)
    rows = []
    for position, (key, song) in enumerate(unique.items(), 1):
        if cancelled():
            break
        try:
            score, shared = compare(seed, vector(t["video_id"] for t in fetch(song)), seed_id, key)
        except Exception as exc:
            print(f"Could not retrieve {key}: {exc}", file=sys.stderr)
            score, shared = None, None
        rows.append(dict(song, original_rank=position, similarity=score, shared_neighbors=shared))
    rows.sort(key=lambda r: -1 if r["similarity"] is None else r["similarity"], reverse=True)
    return rows


def candidates(radio, seed, use_knn):
    tracks = radio.queue(seed)[:20]
    if use_knn:
        return rank({"seed": seed, "recommendations": tracks}, radio.queue)
    return tracks


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("song", nargs="?", help="Song title, optionally including the artist")
    p.add_argument("--artist", help="Filter the starting search by credited artist")
    p.add_argument("--mode", choices=["play", "search", "rank"], default="play")
    p.add_argument("--input", type=Path, help="Rank a saved candidate JSON instead of searching")
    p.add_argument("--json", action="store_true", help="Output search or rank results as JSON")
    p.add_argument("--knn", action="store_true", help="Use neighbor ranking within interactive mode")
    p.add_argument("--data-dir", type=Path, default=Path("data/next_song_bandit"))
    p.add_argument("--epsilon", type=float, default=0.10)
    args = p.parse_args()
    if not 0 <= args.epsilon <= 1:
        p.error("--epsilon must be between 0 and 1")
    if args.input and (args.mode != "rank" or args.song or args.artist):
        p.error("--input requires --mode rank and replaces the song argument")
    if args.json and args.mode == "play" or args.knn and args.mode != "play":
        p.error("--json is for search/rank; --knn is for play")
    from ytmusicapi import YTMusic
    radio = Radio(YTMusic(), args.data_dir / "cache")
    try:
        if args.input:
            data = json.loads(args.input.read_text(encoding="utf-8"))
            # Old snapshots lack media type. Verify those IDs against the filtered queue.
            tracks = data["recommendations"]
            eligible = {}
            if any(t.get("studio_filter_version") != 1 for t in tracks):
                eligible = {t["video_id"]: t for t in radio.queue(data["seed"])}
            verified = []
            for t in tracks:
                if t.get("studio_filter_version") != 1:
                    t = eligible.get(t["video_id"])
                if t and studio_candidate(t["title"], t.get("video_type")):
                    verified.append(t)
            data["recommendations"] = rank({"seed": data["seed"], "recommendations": verified}, radio.queue)
        else:
            if not args.song and args.json:
                p.error("Provide a song title for JSON output")
            query = args.song or input("Starting song: ").strip()
            if args.mode == "play":
                session(radio, Listener(args.data_dir / "feedback.csv"), query,
                        args.epsilon, artist=args.artist, use_knn=args.knn)
                return
            seed = search_choice(radio, query, confirm=False, artist=args.artist)
            if seed is None:
                raise SystemExit(1)
            data = {"seed": seed, "recommendations": candidates(radio, seed, args.mode == "rank")}
        for i, t in enumerate(data["recommendations"], 1):
            t["rank"] = i
        if args.json:
            print(json.dumps(data, ensure_ascii=False, indent=2))
        else:
            print(f"Recommendations for {label(data['seed'])}")
            for t in data["recommendations"]:
                detail = ""
                if args.mode == "rank":
                    value = t["similarity"]
                    detail = " | unscored" if value is None else f" | {value:.3f}, {t['shared_neighbors']} shared"
                print(f"{t['rank']:2}. {label(t)}{detail}\n    {t['url']}")
            if not data["recommendations"]:
                print("No eligible studio videos found.")
    except (EOFError, KeyboardInterrupt):
        print("\nSession ended.", file=sys.stderr)
    except Exception as exc:
        sys.exit(f"Could not complete request: {exc}")


if __name__ == "__main__":
    main()
