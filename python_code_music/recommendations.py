"""Background recommendations for the desktop JSON player."""

from copy import deepcopy
from functools import partial
import os
from pathlib import Path
from queue import Empty, SimpleQueue
import threading

from music_recommender import Listener, Radio, features, recommend


def history_directory():
    root = Path(__file__).resolve().parents[1]
    if (root.parent / "runtime").is_dir():
        if os.name == "nt":
            base = Path(os.environ.get("LOCALAPPDATA") or Path.home())
        else:
            base = Path(os.environ.get("XDG_DATA_HOME") or Path.home() / ".local/share")
        return base / "music-room" / "next_song_bandit"
    return root / "data/next_song_bandit"


class RecommendationTask:
    def __init__(self, directory=None, radio_factory=None):
        self.directory = Path(directory or os.environ.get(
            "MUSIC_RECOMMENDER_DATA", history_directory()))
        self.radio_factory = radio_factory or self.make_radio
        self.generation = None
        self.cancelled = threading.Event()
        self.jobs = SimpleQueue()
        self.results = SimpleQueue()
        self.workers = []
        self.listener = None

    def make_radio(self):
        from requests import Session
        from ytmusicapi import YTMusic

        session = Session()
        session.request = partial(session.request, timeout=8)
        return Radio(YTMusic(requests_session=session), self.directory / "cache")

    def history(self):
        if self.listener is None:
            self.directory.mkdir(parents=True, exist_ok=True)
            self.listener = Listener(self.directory / "feedback.csv")
        return self.listener

    def start(self, request):
        self.cancel()
        self.generation = request["id"]
        self.cancelled = threading.Event()
        # A new track can start while the previous HTTP request finishes.
        if not self.workers:
            self.workers = [threading.Thread(target=self.work, daemon=True) for _ in range(2)]
            for worker in self.workers:
                worker.start()
        self.jobs.put((request, self.cancelled, deepcopy(self.history())))

    def searched(self, query):
        self.history().searched(query)

    def played(self, track, chosen):
        self.history().played(track, chosen)

    def cancel(self):
        self.cancelled.set()
        self.generation = None

    def close(self):
        self.cancel()
        for worker in self.workers:
            self.jobs.put(None)

    def work(self):
        while True:
            job = self.jobs.get()
            if job is None:
                return
            request, cancelled, listener = job
            if cancelled.is_set():
                continue
            def publish(rows=(), *, done=True, error=None):
                if not cancelled.is_set():
                    self.results.put((request, {"recommendations": [
                        dict(song, artist=", ".join(song["artists"]), score=score)
                        for score, song, _ in rows
                    ], "done": done}, error))
            try:
                rows = recommend(
                    self.radio_factory(), listener, request.get("video_id"),
                    request["title"], request.get("artist"),
                    excluded=request.get("excluded", []), use_knn=request.get("knn", False),
                    fill=request.get("fill", False), query=request.get("query") or "",
                    on_fast=lambda rows: publish(rows, done=False), cancelled=cancelled.is_set,
                )
                publish(rows)
            except Exception as exc:
                publish(error=str(exc) or type(exc).__name__)

    def poll(self):
        while True:
            try:
                request, data, error = self.results.get_nowait()
            except Empty:
                return
            if request["id"] == self.generation:
                if error or data["done"]:
                    self.generation = None
                yield request, data, error

    def feedback(self, source, song, action, x=None):
        reward = {"skip": -0.35, "choice": 1.0}[action]
        if source["video_id"] != song["video_id"]:
            self.history().record(source, song, x or features(source, song, None), reward, announce=False)
