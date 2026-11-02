"""Search functions. They return results or raise an error; they do not draw a UI."""

import json
import subprocess
import sys
import threading
import time

from .playback import stop_process, process_options


def search_songs(query, cancelled=None):
    """Ask yt-dlp for up to ten matching songs."""
    query = query.strip()
    if not query:
        return []
    if cancelled is None:
        cancelled = threading.Event()
    process = None
    try:
        process = subprocess.Popen(
            [sys.executable, "-m", "yt_dlp", "--flat-playlist",
             "--dump-single-json", "--no-warnings", "--socket-timeout", "15",
             "--retries", "2", f"ytsearch10:{query}"],
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, encoding="utf-8", **process_options(),
        )
        deadline = time.monotonic() + 45
        while True:
            if cancelled.is_set():
                return []
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("Search timed out. Please try again.")
            try:
                output, errors = process.communicate(timeout=min(.05, remaining))
                break
            except subprocess.TimeoutExpired:
                continue
        if process.returncode:
            raise ValueError(errors.strip() or "YouTube search failed")
        payload = json.loads(output)
        if not isinstance(payload, dict):
            raise ValueError("YouTube returned an invalid result")
        entries = payload.get("entries", [])
        if not isinstance(entries, list):
            raise ValueError("YouTube returned an invalid result list")
        songs = []
        for song in entries:
            if not isinstance(song, dict):
                continue
            if isinstance(song.get("id"), str) and song["id"]:
                songs.append(song)
        return songs
    finally:
        if process is not None:
            stop_process(process)
            for stream in (process.stdout, process.stderr):
                stream.close()
