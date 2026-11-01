"""Reusable music functions. No terminal UI or application loop runs here."""

from .search import search_songs
from .playback import create_player, play_song, stop_song, send_command, get_video_palette
from .controls import (
    pause_song, resume_song, play_pause,
    forward_song, backward_song, get_status,
)
from .thumbnails import thumbnail_url, fetch_thumbnail, load_image, show_image
from .spectrum import start_spectrum, get_spectrum, stop_spectrum
