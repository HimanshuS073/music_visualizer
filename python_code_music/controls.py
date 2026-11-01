"""Simple music controls. Each function returns a message or playback data."""

from .playback import send_command


def pause_song(player):
    send_command(player, "set_property", "pause", True)
    return "Paused."


def resume_song(player):
    send_command(player, "set_property", "pause", False)
    return "Playing."


def play_pause(player):
    send_command(player, "cycle", "pause")
    if send_command(player, "get_property", "pause"):
        return "Paused."
    return "Playing."


def forward_song(player, seconds=10):
    send_command(player, "seek", abs(seconds), "relative+exact")
    return f"Forward {abs(seconds)} seconds."


def backward_song(player, seconds=10):
    send_command(player, "seek", -abs(seconds), "relative+exact")
    return f"Backward {abs(seconds)} seconds."


def get_status(player):
    process = player["player"]
    running = process is not None and process.poll() is None
    status = {
        "running": running,
        "ready": False,
        "song": player["song"],
        "error": player["error"],
        "paused": None,
        "position": None,
        "duration": None,
    }
    if running:
        try:
            status["paused"] = send_command(player, "get_property", "pause")
            status["position"] = send_command(player, "get_property", "time-pos")
            status["duration"] = send_command(player, "get_property", "duration")
            status["ready"] = status["position"] is not None
        except OSError:
            pass
    return status
