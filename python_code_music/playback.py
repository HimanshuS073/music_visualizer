"""Playback functions. Pass the state from create_player() to each function."""

import json
import os
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time


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


def exit_on_signal(signum, _frame):
    # Let the caller run its cleanup when the terminal or Lua process closes.
    raise SystemExit(128 + signum)


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
    safe_lines = []
    for line in detail.splitlines():
        if "https://" in line or "http://" in line:
            line = line.split("http", 1)[0] + "[media URL]"
        safe_lines.append(line)
    detail = "\n".join(safe_lines)
    return detail or "No diagnostic output."


def create_player(on_message=None):
    """Create state for one player. A message callback is optional."""
    return {"player": None, "worker": None, "directory": None,
            "socket_path": None, "song": None, "error": None,
            "cancelled": threading.Event(), "on_message": on_message}


def stop_song(player):
    """Stop the current track and remove its temporary control files."""
    player["cancelled"].set()
    stop_process(player["player"])
    if player["worker"] is not None:
        player["worker"].join()
    if player["directory"] is not None:
        player["directory"].cleanup()
    player["player"] = None
    player["worker"] = None
    player["directory"] = None
    player["socket_path"] = None
    player["song"] = None
    return "Playback stopped."


def play_song(player, song):
    """Start a stream in mpv. This does not draw a cover or read keyboard input."""
    if not isinstance(song, dict) or not isinstance(song.get("id"), str) or not song["id"]:
        raise ValueError("Provide a song with a video id.")
    stop_song(player)
    player["cancelled"] = threading.Event()
    player["error"] = None
    log = tempfile.TemporaryFile()
    try:
        directory = tempfile.TemporaryDirectory(prefix="music-mpv-")
        player["directory"] = directory
        player["socket_path"] = os.path.join(directory.name, "ipc")
        log_path = os.path.join(directory.name, "mpv.log")
        extractor = os.path.join(directory.name, "yt-dlp")
        with open(extractor, "w", encoding="utf-8") as script:
            script.write(f"#!{sys.executable}\nfrom yt_dlp import main\nmain()\n")
        os.chmod(extractor, 0o700)
        process = subprocess.Popen(
            ["mpv", "--no-config", "--no-video", "--no-terminal",
             "--input-terminal=no", "--ytdl=yes", "--ytdl-format=bestaudio/best",
             f"--script-opts=ytdl_hook-ytdl_path={extractor}",
             f"--input-ipc-server={player['socket_path']}", f"--log-file={log_path}",
             "--ytdl-raw-options=socket-timeout=15,retries=2,force-ipv4=,check-formats=",
             f"https://www.youtube.com/watch?v={song['id']}"],
            stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True,
        )
        player["player"] = process
        player["song"] = dict(song)
        worker = threading.Thread(target=watch_song,
                                  args=(player, process, player["cancelled"], log, log_path),
                                  daemon=True)
        worker.start()
        player["worker"] = worker
    except BaseException:
        stop_song(player)
        log.close()
        raise
    message = f"Starting: {song.get('title') or 'Unknown title'}"
    if player["on_message"]:
        player["on_message"](message)
    return message


def watch_song(player, process, cancelled, log, log_path):
    """Collect playback errors in the background without printing them."""
    try:
        process.wait()
        if cancelled.is_set():
            return
        message = "Track finished."
        if process.returncode:
            player["error"] = read_playback_error(log, log_path)
            message = f"Playback failed (exit {process.returncode}). {player['error']}"
        if player["on_message"]:
            player["on_message"](message, error=bool(process.returncode))
    finally:
        stop_process(process)
        log.close()


def send_command(player, *command):
    """Send a playback command to mpv and return its reply data."""
    process = player["player"]
    if process is None or process.poll() is not None:
        raise OSError("Select a song first.")
    deadline = time.monotonic() + 1
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
        connection.settimeout(1)
        while True:
            try:
                connection.connect(player["socket_path"])
                break
            except (FileNotFoundError, ConnectionRefusedError):
                if time.monotonic() >= deadline:
                    raise OSError("Player is starting; try again.")
                time.sleep(.03)
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
