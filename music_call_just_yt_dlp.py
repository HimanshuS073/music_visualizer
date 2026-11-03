# SPDX-FileCopyrightText: 2026 Himanshu_Sharma (HimanshuS073)
# SPDX-License-Identifier: GPL-3.0-or-later

"""Run the terminal player, or use --api to call music functions from another app."""

import base64
import codecs
import json
import os
import select
import signal
import sys
import subprocess
import queue
if os.name != "nt":
    import termios
else:
    # The portable Windows package keeps its executable tools beside Python.
    from pathlib import Path
    tools = Path(__file__).resolve().parent.parent / "runtime" / "bin"
    os.environ["PATH"] = str(tools) + os.pathsep + os.environ.get("PATH", "")
    os.environ["PYTHONIOENCODING"] = "utf-8"
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
import threading

from python_code_music import (
    create_player, search_songs, play_song, stop_song, pause_song, resume_song,
    play_pause, forward_song, backward_song, get_status, send_command,
    load_image, show_image, thumbnail_url, fetch_thumbnail,
    start_spectrum, get_spectrum, stop_spectrum,
    get_video_palette,
)
from python_code_music.playback import exit_on_signal



INPUT_PROMPT = "Song name or result number: "
INPUT_ACTIVE = threading.Event()
INPUT_BUFFER = ""
DISPLAY_LOCK = threading.RLock()
MACHINE_MODE = False


def show_message(message, error=False):
    """Print a message, then put back any search text the user was typing."""
    output = sys.stdout
    if error or MACHINE_MODE:
        output = sys.stderr
    with DISPLAY_LOCK:
        print(f"\n{message}", file=output, flush=True)
        if not MACHINE_MODE and INPUT_ACTIVE.is_set() and sys.stdout.isatty():
            print(INPUT_PROMPT + INPUT_BUFFER, end="", flush=True)


def draw_cover(song, cancelled=None, display_lock=None):
    """Load the selected song's thumbnail and draw it with Chafa."""
    if cancelled is None:
        cancelled = threading.Event()
    if display_lock is None:
        display_lock = threading.Lock()
    try:
        image_data = fetch_thumbnail(song)
        if cancelled.is_set():
            return
        # Probing asks the terminal questions and can put its replies in our input.
        rendered = subprocess.run(
            ["chafa", "--format", "symbols", "--colors", "full",
             "--probe", "off", "--animate", "off", "--threads", "2",
             "--size", "50x16", "-"],
            input=image_data, capture_output=True, check=True, timeout=5,
            start_new_session=True,
        )
        with display_lock:
            if cancelled.is_set():
                return
            print(f"\nCover: {song.get('title') or 'Unknown title'}")
            print(rendered.stdout.decode("utf-8", "replace"), end="", flush=True)
            if INPUT_ACTIVE.is_set() and sys.stdout.isatty():
                print(INPUT_PROMPT + INPUT_BUFFER, end="", flush=True)
    except FileNotFoundError:
        if not cancelled.is_set():
            show_message("To display covers: sudo pacman -S chafa", error=True)
    except (subprocess.SubprocessError, OSError, ValueError) as error:
        with display_lock:
            if not cancelled.is_set():
                show_message(f"Could not display thumbnail: {error}", error=True)


class ThumbnailDisplay:
    def __init__(self):
        self.cancelled = threading.Event()
        self.lock = DISPLAY_LOCK

    def clear(self):
        with self.lock:
            self.cancelled.set()

    def show(self, song):
        with self.lock:
            self.cancelled.set()
            self.cancelled = threading.Event()
            cancelled = self.cancelled
        # Ignore the old cover if the user picks another song before it loads.
        threading.Thread(
            target=draw_cover, args=(dict(song), cancelled, self.lock), daemon=True,
        ).start()


def load_song_image(player):
    if player["song"] is None:
        raise ValueError("Select a song first.")
    player["cover"].show(player["song"])
    return "Loading cover..."



class SearchTask:
    """Search in a thread so playback keys keep working while results load."""
    def __init__(self, raise_errors=False):
        self.raise_errors = raise_errors
        self.error = None
        self.result = None
        self.lock = threading.Lock()
        self.cancelled = threading.Event()
        self.worker = None

    def stop(self):
        self.cancelled.set()
        if self.worker is not None:
            self.worker.join()
        self.worker = None
        with self.lock:
            self.result = None
            self.error = None

    def start(self, query):
        self.stop()
        self.cancelled = threading.Event()
        self.worker = threading.Thread(
            target=self._search, args=(query, self.cancelled), daemon=True,
        )
        self.worker.start()

    def _search(self, query, cancelled):
        error = None
        try:
            results = search_songs(query, cancelled)
        except (OSError, ValueError) as problem:
            error = str(problem)
            results = []
            if not self.raise_errors and not cancelled.is_set():
                show_message(f"Search failed: {problem}", error=True)
        with self.lock:
            if not cancelled.is_set():
                self.error = error
                self.result = (query, results)

    def get_results(self):
        with self.lock:
            result = self.result
            self.result = None
            return result


class Keyboard:
    """Read keys without Enter, then restore the terminal when we finish."""
    def __init__(self):
        self.fd = sys.stdin.fileno()
        self.previous = None
        self.pending = ""
        # A letter can arrive in more than one byte, especially in song names.
        self.decoder = codecs.getincrementaldecoder("utf-8")("replace")
        self.escape = None

    def start(self):
        if sys.stdin.isatty():
            self.previous = termios.tcgetattr(self.fd)
            settings = termios.tcgetattr(self.fd)
            # Read each key without Enter or automatic terminal echo.
            settings[3] &= ~(termios.ICANON | termios.ECHO)
            settings[6][termios.VMIN] = 1
            settings[6][termios.VTIME] = 0
            # Let Ctrl+Q reach our loop instead of being used for flow control.
            settings[0] &= ~(termios.IXON | termios.IXOFF)
            termios.tcsetattr(self.fd, termios.TCSANOW, settings)

    def stop(self):
        if self.previous is not None:
            termios.tcsetattr(self.fd, termios.TCSADRAIN, self.previous)

    def read_key(self, timeout=.1):
        if not self.pending:
            if not select.select([self.fd], [], [], timeout)[0]:
                return None
            data = os.read(self.fd, 4096)
            if not data:
                return ""
            self.pending += self.decoder.decode(data)
        while self.pending:
            key = self.pending[0]
            self.pending = self.pending[1:]
            # Terminal color replies and escape sequences are not user input.
            if self.escape == "start":
                if key == "[" or key == "O":
                    self.escape = "csi"
                elif key == "]":
                    self.escape = "osc"
                elif key in ("P", "_", "^"):
                    self.escape = "string"
                else:
                    self.escape = None
            elif self.escape == "csi":
                if "@" <= key <= "~":
                    self.escape = None
                    if key == "C":
                        return "RIGHT"
                    if key == "D":
                        return "LEFT"
            elif self.escape in ("osc", "string"):
                if key == "\x07":
                    self.escape = None
                elif key == "\x1b":
                    self.escape = "end-string"
            elif self.escape == "end-string":
                if key == "\\":
                    self.escape = None
                else:
                    self.escape = "string"
            elif key == "\x1b":
                self.escape = "start"
            else:
                return key
        return None


def control_key(player, key):
    """Check the key and call the matching player function."""
    try:
        if key == " ":
            message = play_pause(player)
        elif key == "RIGHT":
            message = forward_song(player)
        elif key == "LEFT":
            message = backward_song(player)
        elif key == "\x18":
            player["cover"].clear()
            message = stop_song(player)
        elif key == "\x0c":
            message = load_song_image(player)
        else:
            return False
    except (OSError, ValueError) as error:
        message = str(error)
    show_message(message)
    return True


def display_results(query, results):
    with DISPLAY_LOCK:
        print(f"\nResults for {query!r}:")
        if not results:
            print("No results found. Type another song name to search again.")
        for index, song in enumerate(results, 1):
            print(f"{index}. {song.get('title') or 'Unknown title'}")


class InterfaceState:
    """Remember the input text and the latest list of songs."""
    def __init__(self):
        self.buffer = ""
        self.results = []


def redraw_prompt(state):
    global INPUT_BUFFER
    with DISPLAY_LOCK:
        INPUT_BUFFER = state.buffer
        if sys.stdout.isatty():
            print("\r\033[2K" + INPUT_PROMPT + INPUT_BUFFER, end="", flush=True)


def select_song(player, results, number):
    """Turn a displayed result number into a song to play."""
    try:
        index = int(number) - 1
        if not 0 <= index < len(results):
            raise ValueError("Choose a number from the latest results.")
        player["cover"].clear()
        play_song(player, results[index])
        player["cover"].show(results[index])
    except (OSError, ValueError) as error:
        show_message(str(error), error=True)


def handle_key(key, state, player, search):
    """Handle one key. False means the user wants to quit."""
    if key in ("", "\x04", "\x11"):
        return False
    if key == "\x03":
        raise KeyboardInterrupt
    if key in ("LEFT", "RIGHT", "\x18", "\x0c"):
        control_key(player, key)
    elif key == " " and not state.buffer:
        control_key(player, key)
    elif key in ("\x7f", "\b"):
        state.buffer = state.buffer[:-1]
    elif key == "\x15":
        state.buffer = ""
    elif key in ("\r", "\n"):
        query = state.buffer.strip()
        state.buffer = ""
        if query.lower() in ("q", "quit", "exit"):
            return False
        if not query:
            return True
        if query.isdecimal() and state.results:
            select_song(player, state.results, query)
        else:
            # Every song name starts a fresh search, even while music is playing.
            if query.lower().startswith("search "):
                query = query[7:].strip()
            if query:
                state.results = []
                print(f"\nSearching for {query!r}...", flush=True)
                search.start(query)
    elif key.isprintable():
        state.buffer += key
    return True


def keyboard_loop(player, search):
    """Keep checking for new results and keys until the user quits."""
    state = InterfaceState()
    keyboard = Keyboard()

    try:
        keyboard.start()
        INPUT_ACTIVE.set()
        redraw_prompt(state)

        while True:
            try:
                # Show the results if the background search has finished.
                completed_search = search.get_results()
                if completed_search is not None:
                    query, results = completed_search
                    state.results = results
                    display_results(query, results)
                    redraw_prompt(state)

                # No key yet? Go back and check the search again.
                key = keyboard.read_key()
                if key is None:
                    continue

                keep_running = handle_key(key, state, player, search)
                if not keep_running:
                    break
                redraw_prompt(state)

            except KeyboardInterrupt:
                search.stop()
                player["cover"].clear()
                stop_song(player)
                state.buffer = ""
                print("\nStopped. Type another song name or press Ctrl+Q to quit.", flush=True)
                redraw_prompt(state)
    finally:
        INPUT_ACTIVE.clear()
        keyboard.stop()


def check_dependencies():
    try:
        subprocess.run(
            ["mpv", "--version"], check=True, timeout=10,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
    except (subprocess.SubprocessError, OSError):
        show_message("Install playback dependency: sudo pacman -S mpv", error=True)
        return False
    try:
        subprocess.run(
            [sys.executable, "-m", "yt_dlp", "--version"], check=True,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10,
        )
    except (subprocess.SubprocessError, OSError):
        print("Install yt-dlp: sudo pacman -S python-yt-dlp", file=sys.stderr)
        return False
    return True


def run_terminal():
    if not check_dependencies():
        return 1
    player = create_player(on_message=show_message)
    player["cover"] = ThumbnailDisplay()
    search = SearchTask()
    handlers = {}
    print("Type your first song name and press Enter.")
    print("After searching: enter a result number, then Enter to play.")
    print("Immediate keys: Space pause/resume, Right +10s, Left -10s.")
    print("Ctrl+X stops, Ctrl+L reloads the cover, Ctrl+Q quits.")
    print("Type another name directly to search. Spaces within names work normally.")
    try:
        for signum in dict.fromkeys((signal.SIGTERM, getattr(signal, "SIGHUP", signal.SIGTERM))):
            handlers[signum] = signal.signal(signum, exit_on_signal)
        keyboard_loop(player, search)
    finally:
        search.stop()
        player["cover"].clear()
        stop_song(player)
        for signum, handler in handlers.items():
            signal.signal(signum, handler)
    print("\nGoodbye!")
    return 0



def song_info(song):
    """Keep the fields another app needs to display a song."""
    duration = song.get("duration")
    if type(duration) not in (int, float):
        duration = None
    elif duration != duration or abs(duration) == float("inf"):
        duration = None
    return {"title": song.get("title") or "Unknown title", "video_id": song["id"],
            "url": f"https://www.youtube.com/watch?v={song['id']}",
            "channel": song.get("channel") or song.get("uploader"),
            "duration": duration, "thumbnail": thumbnail_url(song)}


def selected_song(request, results):
    if "index" in request:
        index = request["index"]
        if type(index) is not int or not 1 <= index <= len(results):
            raise ValueError("Use an index from the latest search, starting at 1.")
        return results[index - 1]
    video_id = request.get("video_id")
    allowed = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-"
    if not isinstance(video_id, str) or len(video_id) != 11:
        raise ValueError("Provide a result index or an 11-character video_id.")
    if any(char not in allowed for char in video_id):
        raise ValueError("Invalid video_id.")
    for song in results:
        if song["id"] == video_id:
            return song
    return {"id": video_id, "title": video_id}


def handle_request(request, player, results):
    """Call the same functions used by the terminal and return plain data."""
    command = request.get("command")
    seconds = request.get("seconds", 10)
    if command in ("forward", "backward", "seek"):
        if type(seconds) not in (int, float) or seconds != seconds or abs(seconds) > 86400:
            raise ValueError("seconds must be a finite number within one day.")
    if command == "play":
        song = selected_song(request, results)
        spectrum = request.get("spectrum", False)
        fft_size, sample_rate = request.get("fft_size", 1024), request.get("sample_rate", 44100)
        if spectrum:
            if type(fft_size) is not int or fft_size not in (256, 512, 1024, 2048, 4096):
                raise ValueError("Invalid FFT size.")
            if type(sample_rate) is not int or sample_rate not in (11025, 22050, 44100, 48000):
                raise ValueError("Invalid spectrum sample rate.")
        # Try PulseAudio/PipeWire's compatibility output, with normal mpv fallback.
        play_song(player, song, audio_output="pulse," if spectrum and os.name != "nt" else None,
                  video_colors=request.get("video_colors", False),
                  video_color_fps=request.get("video_color_fps", 8))
        if spectrum:
            start_spectrum(player, fft_size, sample_rate)
        return {"state": "loading", "song": song_info(song)}
    if command == "video_palette":
        try:
            return get_video_palette(player, request.get("sequence"))
        except OSError:
            return {"available": False, "reason": "Waiting for a decoded video frame."}
    if command == "spectrum_start":
        return start_spectrum(player, request.get("fft_size", 1024), request.get("sample_rate", 44100))
    if command == "spectrum_stop":
        stop_spectrum(player)
        return {}
    if command == "spectrum":
        return get_spectrum(player, request.get("sequence"))
    if command == "pause":
        return {"message": pause_song(player)}
    if command == "resume":
        return {"message": resume_song(player)}
    if command in ("toggle", "play_pause"):
        return {"message": play_pause(player)}
    if command == "forward":
        return {"message": forward_song(player, seconds)}
    if command == "backward":
        return {"message": backward_song(player, seconds)}
    if command == "seek":
        send_command(player, "seek", seconds, "relative+exact")
        return {}
    if command == "stop":
        return {"message": stop_song(player)}
    if command == "status":
        status = get_status(player)
        if status["song"]:
            status["song"] = song_info(status["song"])
        return status
    if command == "thumbnail":
        return {"thumbnail": thumbnail_url(selected_song(request, results))}
    if command == "results":
        return {"results": [song_info(song) for song in results]}
    if command == "quit":
        return {}
    raise ValueError("Unknown command.")


def reply(request, data=None, error=None):
    message = {"id": request.get("id"), "ok": error is None}
    if error is not None:
        message["error"] = str(error)
    else:
        message.update(data or {})
    print(json.dumps(message, ensure_ascii=False, allow_nan=False), flush=True)


class ImageTask:
    """Fetch a cover without blocking search or playback requests."""
    def __init__(self):
        self.lock = threading.Lock()
        self.cancelled = threading.Event()
        self.result = None

    def stop(self):
        with self.lock:
            self.cancelled.set()
            self.result = None

    def start(self, song, high_resolution=True):
        self.stop()
        with self.lock:
            self.cancelled = threading.Event()
            cancelled = self.cancelled
        threading.Thread(target=self.fetch, args=(song, cancelled, high_resolution), daemon=True).start()

    def fetch(self, song, cancelled, high_resolution=True):
        data, error = None, None
        try:
            if not high_resolution:
                song = dict(song, thumbnail=f"https://i.ytimg.com/vi/{song['id']}/mqdefault.jpg")
            image = fetch_thumbnail(song, high_resolution=high_resolution)
            if not cancelled.is_set():
                data = base64.b64encode(image).decode("ascii")
        except (OSError, ValueError) as problem:
            error = str(problem)
        with self.lock:
            if not cancelled.is_set():
                self.result = (song["id"], data, error)

    def get_result(self):
        with self.lock:
            result = self.result
            self.result = None
            return result


def run_api():
    """Read newline-separated JSON requests until the caller quits or closes stdin."""
    global MACHINE_MODE
    player = create_player(on_message=show_message)
    search = SearchTask(raise_errors=True)
    images = ImageTask()
    previews = ImageTask()
    results = []
    pending_search = None
    pending_image = None
    pending_preview = None
    buffer = b""
    handlers = {}
    previous_mode = MACHINE_MODE
    MACHINE_MODE = True
    incoming = None
    if os.name == "nt":
        incoming = queue.Queue(maxsize=32)
        def read_requests():
            while True:
                data = os.read(sys.stdin.fileno(), 65536)
                incoming.put(data)
                if not data:
                    return
        threading.Thread(target=read_requests, daemon=True).start()
    try:
        for signum in dict.fromkeys((signal.SIGTERM, getattr(signal, "SIGHUP", signal.SIGTERM))):
            handlers[signum] = signal.signal(signum, exit_on_signal)
        while True:
            preview_result = previews.get_result()
            if preview_result is not None and pending_preview is not None:
                video_id, image, error = preview_result
                reply(pending_preview, {"video_id": video_id, "image": image,
                                        "encoding": "base64"}, error=error)
                pending_preview = None
            image_result = images.get_result()
            if image_result is not None and pending_image is not None:
                video_id, image, error = image_result
                if error:
                    reply(pending_image, error=error)
                else:
                    reply(pending_image, {"video_id": video_id, "image": image,
                                          "encoding": "base64"})
                pending_image = None
            completed = search.get_results()
            if completed is not None and pending_search is not None:
                query, songs = completed
                if search.error:
                    reply(pending_search, error=search.error)
                else:
                    results = songs
                    reply(pending_search, {"query": query, "results": [song_info(song) for song in songs]})
                pending_search = None
            wait = .015 if any(item is not None for item in
                               (pending_search, pending_image, pending_preview)) else .05
            if incoming is not None:
                try:
                    data = incoming.get(timeout=wait)
                except queue.Empty:
                    continue
            else:
                if not select.select([sys.stdin.fileno()], [], [], wait)[0]:
                    continue
                data = os.read(sys.stdin.fileno(), 65536)
            if not data:
                return 0
            buffer += data
            if len(buffer) > 1024 * 1024:
                raise ValueError("Request is too large.")
            while b"\n" in buffer:
                line, buffer = buffer.split(b"\n", 1)
                if not line.strip():
                    continue
                request = {}
                try:
                    parsed = json.loads(line)
                    if not isinstance(parsed, dict):
                        raise ValueError("Send a JSON object.")
                    if type(parsed.get("id")) not in (str, int, type(None)):
                        raise ValueError("id must be a string, integer, or null.")
                    request = parsed
                    if request.get("command") == "search":
                        query = request.get("query")
                        if not isinstance(query, str) or not query.strip():
                            raise ValueError("Provide a song name in query.")
                        if pending_search is not None:
                            reply(pending_search, error="Cancelled by a newer search.")
                        results = []
                        search.start(query.strip())
                        pending_search = request
                    elif request.get("command") == "preview":
                        song = selected_song(request, results)
                        if pending_preview is not None:
                            reply(pending_preview, error="Cancelled by a newer preview.")
                        previews.start(song, high_resolution=False)
                        pending_preview = request
                    elif request.get("command") == "image":
                        song = selected_song(request, results)
                        if pending_image is not None:
                            reply(pending_image, error="Cancelled by a newer image.")
                        images.start(song)
                        pending_image = request
                    else:
                        reply(request, handle_request(request, player, results))
                        if request.get("command") == "quit":
                            return 0
                except (OSError, ValueError) as error:
                    reply(request, error=error)
    except KeyboardInterrupt:
        return 0
    except (BrokenPipeError, ConnectionResetError):
        # The window can close while a reply is still buffered. Discard it so
        # Python doesn't try to flush a dead pipe again during shutdown.
        with open(os.devnull, "wb") as sink:
            os.dup2(sink.fileno(), sys.stdout.fileno())
        return 0
    except (OSError, ValueError) as error:
        print(str(error), file=sys.stderr)
        return 1
    finally:
        images.stop()
        previews.stop()
        search.stop()
        stop_song(player)
        MACHINE_MODE = previous_mode
        for signum, handler in handlers.items():
            signal.signal(signum, handler)


def run_search(query):
    """Take one song name from any language and return one JSON result."""
    request = {"id": None}
    query = query.strip()
    if not query:
        reply(request, error="Enter a song name.")
        return 2
    try:
        songs = search_songs(query)
        reply(request, {"query": query, "results": [song_info(song) for song in songs]})
        return 0
    except (OSError, ValueError) as error:
        reply(request, error=error)
        print(str(error), file=sys.stderr)
        return 1


def main():
    if sys.argv[1:] == ["--api"]:
        return run_api()
    if len(sys.argv) == 3 and sys.argv[1] == "--search":
        return run_search(sys.argv[2])
    if sys.argv[1:] in (["-h"], ["--help"]):
        print("python music_call_just_yt_dlp.py        # terminal player")
        print("python music_call_just_yt_dlp.py --api  # JSON requests on stdin/stdout")
        print('python music_call_just_yt_dlp.py --search "song name"  # one JSON result')
        return 0
    if sys.argv[1:]:
        print("Unknown argument. Use --help.", file=sys.stderr)
        return 2
    if os.name == "nt":
        print("On Windows, open music-player.exe to use the window player.", file=sys.stderr)
        return 2
    return run_terminal()


if __name__ == "__main__":
    raise SystemExit(main())
