"""Search for music, play a result, and control it with the keyboard.

Start reading at main() at the bottom. Each player action has its own function.
Searching and cover loading use threads so they don't hold up the keyboard.
"""

import codecs
import json
import os
import queue
import re
import select
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import termios
import threading
import time
import tty
import urllib.request

SEARCH_PROMPT = "Search song: "
CONTROL_PROMPT = "Space pause | a/> +10s | d/< -10s | s search | c cover | x stop | q quit | result #: "
INPUT_PROMPT = SEARCH_PROMPT
INPUT_ACTIVE = threading.Event()
INPUT_BUFFER = ""
DISPLAY_LOCK = threading.RLock()


# Small helpers for writing to the terminal.
def show_message(message, error=False):
    """Print a message, then put back any search text the user was typing."""
    output = sys.stdout
    if error:
        output = sys.stderr
    with DISPLAY_LOCK:
        print(f"\n{message}", file=output, flush=True)
        if INPUT_ACTIVE.is_set() and sys.stdout.isatty():
            print(INPUT_PROMPT + INPUT_BUFFER, end="", flush=True)


# Search for songs. The optional event lets us stop an old search.
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
            text=True, start_new_session=True,
        )
        deadline = time.monotonic() + 45
        while True:
            if cancelled.is_set():
                return []
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("Search timed out. Please try again.")
            try:
                output, errors = process.communicate(timeout=min(.1, remaining))
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
    except (OSError, ValueError) as error:
        if not cancelled.is_set():
            show_message(f"Search failed: {error}", error=True)
        return []
    finally:
        if process is not None:
            stop_process(process)
            for stream in (process.stdout, process.stderr):
                stream.close()


# Download and draw the cover without making playback wait.
def load_image(song, cancelled=None, display_lock=None):
    """Load the selected song's thumbnail and draw it with Chafa."""
    if cancelled is None:
        cancelled = threading.Event()
    if display_lock is None:
        display_lock = threading.Lock()
    if not shutil.which("chafa"):
        if not cancelled.is_set():
            show_message("To display covers: sudo pacman -S chafa", error=True)
        return
    image_url = song.get("thumbnail") or (
        f"https://i.ytimg.com/vi/{song['id']}/hqdefault.jpg"
    )
    try:
        request = urllib.request.Request(
            image_url, headers={"User-Agent": "Mozilla/5.0"},
        )
        with urllib.request.urlopen(request, timeout=5) as response:
            image_data = response.read(10 * 1024 * 1024)
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
    except (subprocess.SubprocessError, OSError, ValueError) as error:
        with display_lock:
            if not cancelled.is_set():
                show_message(f"Could not display thumbnail: {error}", error=True)


def show_image(song, cancelled=None, display_lock=None):
    """The old name still works if another script uses it."""
    return load_image(song, cancelled, display_lock)


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
            target=show_image, args=(dict(song), cancelled, self.lock), daemon=True,
        ).start()


def stop_process(process):
    """Stop a program and the helper programs it started."""
    if process is None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=1.5)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait()


def read_playback_error(player_log, log_path):
    """Read the useful error lines and leave long stream URLs out of the message."""
    player_log.seek(0)
    detail = player_log.read(8192).decode("utf-8", "replace").strip()
    if os.path.exists(log_path):
        errors = []
        with open(log_path, encoding="utf-8", errors="replace") as log:
            for line in log:
                if "[e]" in line or "[f]" in line or "[w]" in line:
                    errors.append(line.strip())
        if errors:
            detail = "\n".join(errors[-16:])
    detail = re.sub(r"https?://[^\s'\"]+", "[media URL]", detail)
    return detail or "No diagnostic output."


# This object keeps the current song, mpv process, and cover together.
class MusicPlayer:
    def __init__(self):
        self.song = None
        self.player = None
        self.worker = None
        self.directory = None
        self.socket_path = None
        self.cancelled = threading.Event()
        self.cover = ThumbnailDisplay()

    def stop(self):
        self.cover.clear()
        self.cancelled.set()
        stop_process(self.player)
        if self.worker is not None:
            self.worker.join()
        if self.directory is not None:
            self.directory.cleanup()
        self.player = None
        self.worker = None
        self.directory = None
        self.socket_path = None
        self.song = None

    def play(self, song):
        # Always stop the previous track before starting the new one.
        self.stop()
        self.cancelled = threading.Event()
        player_log = tempfile.TemporaryFile()
        try:
            self.directory = tempfile.TemporaryDirectory(prefix="music-mpv-")
            self.socket_path = os.path.join(self.directory.name, "ipc")
            log_path = os.path.join(self.directory.name, "mpv.log")
            # Use the same Python environment for mpv extraction and searches.
            extractor = os.path.join(self.directory.name, "yt-dlp")
            with open(extractor, "w", encoding="utf-8") as script:
                script.write(f"#!{sys.executable}\nfrom yt_dlp import main\nmain()\n")
            os.chmod(extractor, 0o700)
            # These yt-dlp options fixed the YouTube 403 error in this player.
            self.player = subprocess.Popen(
                ["mpv", "--no-config", "--no-video", "--no-terminal",
                 "--input-terminal=no", "--ytdl=yes", "--ytdl-format=bestaudio/best",
                 f"--script-opts=ytdl_hook-ytdl_path={extractor}",
                 f"--input-ipc-server={self.socket_path}",
                 f"--log-file={log_path}",
                 "--ytdl-raw-options=socket-timeout=15,retries=2,force-ipv4=,check-formats=",
                 f"https://www.youtube.com/watch?v={song['id']}"],
                stdin=subprocess.DEVNULL, stdout=player_log, stderr=player_log,
                start_new_session=True,
            )
            self.song = song
            self.worker = threading.Thread(
                target=self._watch,
                args=(self.player, self.cancelled, player_log, log_path), daemon=True,
            )
            self.worker.start()
        except BaseException:
            self.stop()
            player_log.close()
            raise
        print(f"\nStarting: {song.get('title') or 'Unknown title'}")
        print("Space: pause/resume | a/>: +10s | d/<: -10s | s: search")
        self.cover.show(song)

    def command(self, *command):
        """Send a command to mpv through its local socket and wait for the reply."""
        if self.player is None or self.player.poll() is not None:
            raise OSError("Select a song first.")
        deadline = time.monotonic() + 1
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
            connection.settimeout(1)
            while True:
                try:
                    connection.connect(self.socket_path)
                    break
                except (FileNotFoundError, ConnectionRefusedError):
                    if time.monotonic() >= deadline:
                        raise OSError("Player is starting; try the key again.")
                    time.sleep(0.03)
            message = {"command": list(command), "request_id": 1}
            connection.sendall((json.dumps(message) + "\n").encode())
            with connection.makefile("rb") as replies:
                while time.monotonic() < deadline + 1:
                    line = replies.readline(65536)
                    if not line:
                        break
                    reply = json.loads(line)
                    if reply.get("request_id") == 1:
                        if reply.get("error") != "success":
                            raise OSError(f"Control unavailable while loading: {reply.get('error')}")
                        return reply.get("data")
        raise OSError("Player did not acknowledge the command.")

    @staticmethod
    def _watch(player, cancelled, player_log, log_path):
        try:
            player.wait()
            if cancelled.is_set():
                return
            if player.returncode:
                detail = read_playback_error(player_log, log_path)
                show_message(f"Playback failed (exit {player.returncode}). {detail}", error=True)
            else:
                show_message("Track finished.")
        finally:
            stop_process(player)
            player_log.close()


# Individual actions: these functions don't need to know which key was pressed.
def play_song(player, song):
    """Replace the current track and load its cover in the background."""
    player.play(song)
    return f"Selected: {song.get('title') or 'Unknown title'}"


def stop_song(player):
    player.stop()
    return "Playback stopped."


def pause_song(player):
    player.command("set_property", "pause", True)
    return "Paused."


def resume_song(player):
    player.command("set_property", "pause", False)
    return "Playing."


def play_pause(player):
    """Pause if music is playing, or continue if it is paused."""
    player.command("cycle", "pause")
    if player.command("get_property", "pause"):
        return "Paused."
    return "Playing."


def forward_song(player, seconds=10):
    player.command("seek", abs(seconds), "relative+exact")
    return f"Forward {abs(seconds)} seconds."


def backward_song(player, seconds=10):
    player.command("seek", -abs(seconds), "relative+exact")
    return f"Backward {abs(seconds)} seconds."


def load_song_image(player):
    if player.song is None:
        raise ValueError("Select a song first.")
    player.cover.show(player.song)
    return "Loading cover..."


def control_key(player, key):
    """Check the key and call the matching player function."""
    try:
        if key == " ":
            message = play_pause(player)
        elif key == ">" or key == "a":
            message = forward_song(player)
        elif key == "<" or key == "d":
            message = backward_song(player)
        elif key == "x":
            message = stop_song(player)
        elif key == "c":
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
            print("No results found. Press s to search again.")
        for index, song in enumerate(results, 1):
            print(f"{index}. {song.get('title') or 'Unknown title'}")


class SearchTask:
    """Search in a thread so playback keys keep working while results load."""
    def __init__(self):
        self.events = queue.Queue()
        self.cancelled = threading.Event()
        self.worker = None

    def stop(self):
        self.cancelled.set()
        if self.worker is not None:
            self.worker.join()
        self.worker = None
        while True:
            try:
                self.events.get_nowait()
            except queue.Empty:
                break

    def start(self, query):
        self.stop()
        self.cancelled = threading.Event()
        self.worker = threading.Thread(
            target=self._search, args=(query, self.cancelled), daemon=True,
        )
        self.worker.start()

    def _search(self, query, cancelled):
        results = search_songs(query, cancelled)
        if not cancelled.is_set():
            self.events.put((query, results))

    def get_results(self):
        try:
            return self.events.get_nowait()
        except queue.Empty:
            return None


# Read one key at a time. This is why playback keys don't need Enter.
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
            tty.setcbreak(self.fd, when=termios.TCSANOW)

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
                if key == "[":
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


class InterfaceState:
    """Remember the input text and the latest list of songs."""
    def __init__(self):
        self.search_mode = True
        self.buffer = ""
        self.results = []


def redraw_prompt(state):
    global INPUT_PROMPT, INPUT_BUFFER
    with DISPLAY_LOCK:
        if state.search_mode:
            INPUT_PROMPT = SEARCH_PROMPT
        else:
            INPUT_PROMPT = CONTROL_PROMPT
        INPUT_BUFFER = state.buffer
        if sys.stdout.isatty():
            print("\r\033[2K" + INPUT_PROMPT + INPUT_BUFFER, end="", flush=True)


def select_song(player, results, number):
    """Turn a displayed result number into a song to play."""
    try:
        index = int(number) - 1
        if not 0 <= index < len(results):
            raise ValueError("Choose a number from the latest results.")
        play_song(player, results[index])
    except (OSError, ValueError) as error:
        show_message(str(error), error=True)


def handle_key(key, state, player, search):
    """Handle one key. False means the user wants to quit."""
    if key == "" or key == "\x04":
        return False
    if key == "\x03":
        raise KeyboardInterrupt
    if key in ("\x7f", "\b"):
        state.buffer = state.buffer[:-1]
    elif key == "\x15":
        state.buffer = ""
    elif state.search_mode:
        if key in ("\r", "\n"):
            query = state.buffer.strip()
            if query.lower() in {"q", "quit", "exit"}:
                return False
            if query:
                state.results = []
                state.buffer = ""
                state.search_mode = False
                print(f"\nSearching for {query!r}...", flush=True)
                search.start(query)
        elif key.isprintable():
            state.buffer += key
    elif key == "q":
        return False
    elif key == "s":
        state.search_mode = True
        state.buffer = ""
    elif key in (" ", ">", "<", "a", "d", "x", "c"):
        control_key(player, key)
    elif key in ("\r", "\n"):
        if state.buffer:
            select_song(player, state.results, state.buffer)
            state.buffer = ""
    elif key.isdecimal():
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
                stop_song(player)
                state.buffer = ""
                state.search_mode = False
                print("\nStopped. Press s to search or q to quit.", flush=True)
                redraw_prompt(state)
    finally:
        INPUT_ACTIVE.clear()
        keyboard.stop()


def exit_on_signal(signum, _frame):
    # Raising here runs main's finally block even when the terminal is closed.
    raise SystemExit(128 + signum)


def check_dependencies():
    if not shutil.which("mpv"):
        print("Install playback dependency: sudo pacman -S mpv", file=sys.stderr)
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


# Start here: check the tools, listen for keys, and clean up when finished.
def main():
    if not check_dependencies():
        return 1
    player = MusicPlayer()
    search = SearchTask()
    handlers = {}
    print("Type your first song name and press Enter.")
    print("After searching: enter a result number, then Enter to play.")
    print("Immediate keys: Space pause/resume, a/> forward, d/< backward,")
    print("s search, c cover, x stop, q quit. Search mode accepts normal text.")
    try:
        for signum in (signal.SIGTERM, signal.SIGHUP):
            handlers[signum] = signal.signal(signum, exit_on_signal)
        keyboard_loop(player, search)
    finally:
        search.stop()
        player.stop()
        for signum, handler in handlers.items():
            signal.signal(signum, handler)
    print("\nGoodbye!")
    return 0


if __name__ == "__main__":
    sys.exit(main())
