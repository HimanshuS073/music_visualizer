# SPDX-FileCopyrightText: 2026 Himanshu_Sharma (HimanshuS073)
# SPDX-License-Identifier: GPL-3.0-or-later

"""Playback functions. Pass the state from create_player() to each function."""

import json
import os
import signal
import shutil
import uuid
import socket
import subprocess
import sys
import tempfile
import threading
import time


def process_options():
    if os.name == "nt":
        return {"creationflags": subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP}
    return {"start_new_session": True}


def stop_process(process):
    """Stop a program and the helper programs it started."""
    if process is None:
        return
    if os.name == "nt":
        if process.poll() is None:
            subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                           creationflags=subprocess.CREATE_NO_WINDOW, timeout=10)
            process.wait(timeout=5)
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
    return {
        "player": None,
        "worker": None,
        "directory": None,
        "socket_path": None,
        "song": None,
        "error": None,
        "cancelled": threading.Event(),
        "on_message": on_message,
        "spectrum": None,
        "video_colors": False,
    }


def stop_song(player):
    """Stop the current track and remove its temporary control files."""
    player["cancelled"].set()
    stop_process(player["player"])
    if player.get("spectrum") is not None:
        from .spectrum import stop_spectrum
        stop_spectrum(player)
    if player["worker"] is not None:
        player["worker"].join()
    if player["directory"] is not None:
        player["directory"].cleanup()
    player["player"] = None
    player["worker"] = None
    player["directory"] = None
    player["socket_path"] = None
    player["song"] = None
    player["video_colors"] = False
    return "Playback stopped."


def write_video_sampler(directory, frame_rate):
    """Have mpv sample its own decoded frames and publish only RGB colors."""
    path = os.path.join(directory, "video-colors.lua")
    script = r'''
local mp = require('mp')
local sequence = 0
local function sample_frame()
    if mp.get_property_bool('pause', false) then return end
    local frame = mp.command_native({'screenshot-raw', 'video', 'rgba'})
    if not frame or not frame.data or frame.stride <= 0 then return end
    local colors, seen = {}, {}
    for row = 0, 3 do
        for column = 0, 7 do
            local x = math.min(frame.w - 1, math.floor((column + .5) * frame.w / 8))
            local y = math.min(frame.h - 1, math.floor((row + .5) * frame.h / 4))
            local offset = y * frame.stride + x * 4 + 1
            local r, g, b = frame.data:byte(offset, offset + 2)
            local key = math.floor(r / 16) .. ':' .. math.floor(g / 16) .. ':' .. math.floor(b / 16)
            if not seen[key] then
                seen[key] = true
                colors[#colors + 1] = {r / 255, g / 255, b / 255}
            end
        end
    end
    sequence = sequence + 1
    mp.set_property_native('user-data/fft-video-colors', {
        sequence = sequence, colors = colors,
        position = mp.get_property_number('time-pos', 0),
        width = frame.w, height = frame.h,
    })
end
mp.add_periodic_timer(1 / __FRAME_RATE__, sample_frame)
'''
    with open(path, "w", encoding="utf-8") as file:
        file.write(script.replace("__FRAME_RATE__", str(frame_rate)))
    return path


def get_video_palette(player, sequence=None):
    if not player.get("video_colors"):
        return {"available": False, "reason": "Video color sampling is disabled."}
    data = send_command(player, "get_property", "user-data/fft-video-colors")
    if not isinstance(data, dict):
        return {"available": False}
    result = {"available": True, "sequence": data.get("sequence"),
              "position": data.get("position"), "video_id": player["song"]["id"]}
    if sequence != data.get("sequence"):
        result["colors"] = data.get("colors", [])
    return result


def play_song(player, song, audio_output=None, video_colors=False, video_color_fps=8):
    """Start a stream in mpv. This does not draw a cover or read keyboard input."""
    if not isinstance(song, dict):
        raise ValueError("Provide a song with a video id.")
    if not isinstance(song.get("id"), str) or not song["id"]:
        raise ValueError("Provide a song with a video id.")
    if type(video_color_fps) is not int or not 1 <= video_color_fps <= 30:
        raise ValueError("video_color_fps must be an integer from 1 to 30.")
    stop_song(player)
    player["cancelled"] = threading.Event()
    player["error"] = None
    log = tempfile.TemporaryFile()
    try:
        directory = tempfile.TemporaryDirectory(prefix="music-mpv-")
        player["directory"] = directory
        player["socket_path"] = ('\\\\.\\pipe\\music-' + uuid.uuid4().hex) if os.name == "nt" else os.path.join(directory.name, "ipc")
        log_path = os.path.join(directory.name, "mpv.log")
        extractor = os.path.join(directory.name, "yt-dlp")
        if os.name == "nt":
            extractor = shutil.which("yt-dlp.exe")
            if not extractor:
                raise OSError("The Windows bundle is missing yt-dlp.exe.")
        else:
            with open(extractor, "w", encoding="utf-8") as script:
                script.write(f"#!{sys.executable}\nfrom yt_dlp import main\nmain()\n")
            os.chmod(extractor, 0o700)
        command = ["mpv", "--no-config", "--no-video", "--no-terminal",
             "--input-terminal=no", "--ytdl=yes", "--ytdl-format=bestaudio/best",
             f"--script-opts=ytdl_hook-ytdl_path={extractor}",
             f"--input-ipc-server={player['socket_path']}", f"--log-file={log_path}",
             "--ytdl-raw-options=socket-timeout=15,retries=2,force-ipv4=,check-formats=",
             f"https://www.youtube.com/watch?v={song['id']}"]
        if audio_output:
            command.insert(1, f"--ao={audio_output}")
        if video_colors:
            sampler = write_video_sampler(directory.name, video_color_fps)
            command.remove("--no-video")
            command.remove("--ytdl-format=bestaudio/best")
            command[1:1] = [
                "--vid=auto", "--vo=null", "--vd-lavc-threads=1",
                "--ytdl-format=bestaudio+worstvideo[height<=360]/worst[height<=360]/bestaudio/best",
                f"--vf=lavfi=[fps={video_color_fps},scale=64:36:flags=fast_bilinear,format=rgba]",
                f"--script={sampler}",
            ]
        process = subprocess.Popen(
            command,
            stdin=subprocess.DEVNULL, stdout=log, stderr=log, **process_options(),
        )
        player["player"] = process
        player["song"] = dict(song)
        player["video_colors"] = bool(video_colors)
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
        if player.get("spectrum") is not None:
            from .spectrum import stop_spectrum
            stop_spectrum(player)
        stop_process(process)
        log.close()


def send_command(player, *command):
    """Send a playback command to mpv and return its reply data."""
    process = player["player"]
    if process is None or process.poll() is not None:
        raise OSError("Select a song first.")
    if os.name == "nt":
        from .windows import pipe_command
        return pipe_command(player["socket_path"], command)
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
