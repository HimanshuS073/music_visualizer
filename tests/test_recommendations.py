import csv
import json
import os
import subprocess
import sys
from pathlib import Path
import tempfile
import threading
import time
import unittest

from music_recommender import Listener, Radio, personalized, recommend, resolve_seed, track_info
from python_code_music.controls import get_status
from python_code_music.recommendations import RecommendationTask

A, B, C, D = "r7qovpFAGrQ", "BBBBBBBBBBB", "YnopHCL1Jk8", "DDDDDDDDDDD"
OMV = "MUSIC_VIDEO_TYPE_OMV"


def video(video_id, title, artist, **fields):
    return dict(videoId=video_id, title=title, artists=[{"name": artist}],
                videoType=OMV, **fields)


SONGS = {
    A: video(A, "Old Town Road (English Song)", "Lil Nas X"),
    B: video(B, "Recommendation B (English Song)", "Artist B"),
    C: video(C, "Toca Toca (English Song)", "Fly Project"),
    D: video(D, "Recommendation D (English Song)", "Artist D"),
}


def seed(video_id):
    row = SONGS[video_id]
    return resolve_seed(None, video_id, row["title"], row["artists"][0]["name"])


class YouTube:
    def __init__(self):
        self.calls = []
        self.searches = []
        self.queues = {A: [SONGS[A], SONGS[B], SONGS[B], video("EEEEEEEEEEE", "Live at a venue", "X")],
                       C: [SONGS[D]]}
        self.started = threading.Event()
        self.release = None
        self.finished = threading.Event()
        self.error = False

    def search(self, query, **kwargs):
        self.searches.append(query)
        return [SONGS[A]]

    def get_watch_playlist(self, videoId, radio=False, limit=None):
        self.calls.append((videoId, radio))
        if self.error:
            raise TimeoutError("simulated network failure")
        if videoId == A and self.release is not None:
            self.started.set()
            self.release.wait(3)
            self.finished.set()
        return {"tracks": self.queues.get(videoId, []) if radio else [SONGS[videoId]]}


class RecommendationsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name)
        self.api = YouTube()

    def task(self):
        task = RecommendationTask(self.path, lambda: Radio(self.api, self.path / "cache"))
        self.addCleanup(task.close)
        return task

    def start(self, task, i, video_id, **options):
        song = seed(video_id)
        task.start(dict(id=i, video_id=video_id, title=song["title"],
                        artist=song["artists"][0], **options))

    def collect(self, task):
        deadline = time.monotonic() + 3
        replies = []
        while time.monotonic() < deadline:
            for request, data, error in task.poll():
                replies.append((request["id"], data, error))
                if error or data["done"]:
                    return replies
            time.sleep(.005)
        self.fail("Recommendation worker timed out")

    def test_language_and_remix_filters_include_remembered_candidates(self):
        source = resolve_seed(None, A, "First (Hindi Song)", "SingerVEVO")
        matching = track_info(video(B, "Second (Hindi Song)", "Another singer"))
        different = track_info(video(C, "Third (English Song)", "Singer"))
        same_artist = track_info(video(D, "Fourth", "Singer - Topic"))
        self.assertIsNone(track_info(video("EEEEEEEEEEE", "Fifth (Hindi Song Remix)", "Singer")))
        listener = Listener(self.path / "feedback.csv")
        listener.remember(source, different, 1)
        rows = personalized(source, [different, matching, same_artist], listener)
        self.assertEqual([row[1]["video_id"] for row in rows], [B, D])

    def test_autoplay_keeps_the_chosen_song_and_explicit_search_language(self):
        listener = Listener(self.path / "feedback.csv")
        listener.played(seed(A), chosen=True)
        listener.searched("English songs")
        played_next = resolve_seed(None, C, "Automatic (Hindi Song)", "Different singer")
        listener.played(played_next)
        self.api.queues[A] = [
            video(D, "Wrong language (Hindi Song)", "Different singer"),
            SONGS[B],
        ]
        radio = Radio(self.api, self.path / "cache")
        rows = recommend(radio, listener, C, played_next["title"], played_next["artists"], chosen=False)
        self.assertEqual(self.api.calls, [(A, True)])
        self.assertEqual([row[1]["video_id"] for row in rows], [B])

    def test_recent_plays_and_search_are_restored_and_used(self):
        path = self.path / "feedback.csv"
        listener = Listener(path)
        listener.played(seed(A), chosen=True)
        listener.played(seed(C))
        listener.searched("dance")
        restored = Listener(path)
        self.assertEqual(restored.context["last_played"]["video_id"], C)
        self.assertEqual(restored.context["last_chosen"]["video_id"], A)
        self.assertEqual(restored.context["search"], "dance")
        dance = track_info(video(D, "Dance (English Song)", "Artist D"))
        rock = track_info(video(B, "Rock (English Song)", "Artist B"))
        rows = personalized(seed(A), [rock, track_info(SONGS[C]), dance], restored)
        self.assertEqual([row[1]["video_id"] for row in rows], [D, B])

    def test_saved_play_profiles_rank_unlabeled_matches(self):
        listener = Listener(self.path / "feedback.csv")
        seed_track = resolve_seed(None, A, "Roots", "Singer")
        similar = track_info(video(B, "Acoustic First", "Another singer"))
        other = track_info(video(C, "Dance Second", "Different singer"))
        for _ in range(5):
            listener.observe(dict(similar, query="acoustic"))
        restored = Listener(self.path / "feedback.csv")
        rows = personalized(seed_track, [other, similar], restored)
        self.assertEqual([row[1]["video_id"] for row in rows], [B, C])
        self.assertGreater(rows[0][0], rows[1][0])

    def test_short_desktop_queue_searches_for_twenty_eligible_tracks(self):
        self.api.search = lambda query, **kwargs: [
            SONGS[B], video("EEEEEEEEEEE", "Wrong (Hindi Song)", "Lil Nas X"),
            video("FFFFFFFFFFF", "Remix (English Song)", "Lil Nas X"),
        ] + [video(f"track{n:06d}", f"Song {n} (English Song)", "Another artist")
             for n in range(25)]
        task = self.task()
        self.start(task, 1, A, fill=True)
        replies = self.collect(task)
        self.assertEqual(len(replies[0][1]["recommendations"]), 1)
        ranked = replies[-1][1]["recommendations"]
        self.assertEqual(len({song["video_id"] for song in ranked}), 20)
        self.assertFalse({A, "EEEEEEEEEEE", "FFFFFFFFFFF"} & {song["video_id"] for song in ranked})

    def test_exact_recording_skip_and_choice_survive_restart(self):
        task = self.task()
        self.start(task, 1, A)
        _, data, error = self.collect(task)[-1]
        self.assertIsNone(error)
        self.assertEqual([t["video_id"] for t in data["recommendations"]], [B])
        self.assertEqual(self.api.calls, [(A, True)])
        self.assertEqual(self.api.searches, [])
        task.feedback(seed(A), seed(B), "skip")
        task.feedback(seed(A), seed(C), "choice")
        with (self.path / "song_choices.csv").open(newline="") as f:
            events = [(json.loads(a)["video_id"], json.loads(b)["video_id"], float(r))
                      for a, b, r in csv.reader(f)]
        self.assertEqual(events, [(A, B, -.35), (A, C, 1)])
        restarted = self.task()
        self.start(restarted, 2, A)
        *_, (_, data, error) = self.collect(restarted)
        self.assertIsNone(error)
        self.assertEqual(data["recommendations"][0]["video_id"], C)
        self.assertEqual(self.api.calls.count((A, True)), 1)
        self.assertIn((C, False), self.api.calls)
        self.assertEqual(self.api.searches, [])

    def test_slow_old_response_cannot_replace_new_suggestions(self):
        self.api.release = threading.Event()
        self.addCleanup(self.api.release.set)
        task = self.task()
        self.start(task, 1, A)
        self.assertTrue(self.api.started.wait(1))
        self.start(task, 2, C)
        self.assertEqual(self.collect(task)[-1][1]["recommendations"][0]["video_id"], D)
        self.api.release.set()
        self.assertTrue(self.api.finished.wait(1))
        task.close()
        for worker in task.workers:
            worker.join(1)
            self.assertFalse(worker.is_alive())
        self.assertEqual(list(task.poll()), [])

    def test_network_failure_and_optional_knn_with_cache(self):
        task = self.task()
        self.api.error = True
        self.start(task, 1, A)
        self.assertIn("simulated network failure", self.collect(task)[-1][2])
        self.api.error = False
        self.api.queues[B] = [SONGS[D]]
        self.start(task, 2, A, knn=True)
        replies = self.collect(task)
        self.assertFalse(replies[0][1]["done"])
        self.assertTrue(replies[-1][1]["done"])
        self.assertIn((B, True), self.api.calls)
        calls = list(self.api.calls)
        self.start(task, 3, A, knn=True, excluded=[B])
        self.assertEqual(self.collect(task)[-1][1]["recommendations"], [])
        self.assertEqual(self.api.calls, calls)

    def test_callable_falls_back_to_title_only_without_id(self):
        radio = Radio(self.api, self.path / "cache")
        listener = Listener(self.path / "feedback.csv")
        rows = recommend(radio, listener, title="Old Town Road", artist="Lil Nas X")
        self.assertEqual(rows[0][1]["video_id"], B)
        self.assertEqual(self.api.searches, ["Old Town Road Lil Nas X"])

    def test_json_protocol_keeps_playback_running_on_recommendation_error(self):
        harness = """
import sys
sys.path.insert(0, "tests")
import music_call_just_yt_dlp as app
from test_recommendations import YouTube

youtube = YouTube()
youtube.error = True
app.RecommendationTask.make_radio = lambda self: app_radio(self)
def app_radio(task):
    from music_recommender import Radio
    return Radio(youtube, task.directory / "cache")

app.play_song = lambda player, song, **unused: player.update(song=song)
app.get_status = lambda player: {
    "song": player["song"], "running": True, "ready": True, "position": 32,
}
sys.exit(app.run_api())
"""
        with subprocess.Popen(
            [sys.executable, "-u", "-c", harness],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, env=dict(os.environ, MUSIC_RECOMMENDER_DATA=str(self.path)),
        ) as process:
            from queue import Queue
            lines = Queue()

            def read_replies():
                for line in process.stdout:
                    lines.put(line)

            reader = threading.Thread(target=read_replies, daemon=True)
            reader.start()
            try:
                def ask(command, i, **fields):
                    process.stdin.write(json.dumps(dict(command=command, id=i, **fields)) + "\n")
                    process.stdin.flush()
                    response = json.loads(lines.get(timeout=3))
                    self.assertEqual(response["id"], i)
                    return response

                self.assertTrue(ask("play", 1, video_id=A, title="Old Town Road", artist="Lil Nas X")["ok"])
                failed = ask("recommend", 2, video_id=A, title="Old Town Road", artist="Lil Nas X")
                self.assertFalse(failed["ok"])
                self.assertIn("simulated network failure", failed["error"])
                self.assertTrue(ask("recommend_feedback", 3, source_id=A, source_title="Old Town Road",
                                    source_artist="Lil Nas X", video_id=C, title="Toca Toca",
                                    artist="Fly Project", action="choice")["ok"])
                status = ask("status", 4)
                self.assertTrue(status["running"])
                self.assertEqual(status["song"]["video_id"], A)
                self.assertTrue(ask("quit", 5)["ok"])
                self.assertEqual(process.wait(timeout=3), 0)
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=3)
                reader.join(timeout=3)

    def test_status_only_advances_after_successful_exit(self):
        class Process:
            def poll(self):
                return self.code

        process = Process()
        player = dict(player=process, cancelled=threading.Event(), song=seed(A), error=None)
        process.code = 1
        self.assertFalse(get_status(player)["ended"])
        process.code = 0
        self.assertTrue(get_status(player)["ended"])
        player["cancelled"].set()
        self.assertFalse(get_status(player)["ended"])


if __name__ == "__main__":
    unittest.main()
