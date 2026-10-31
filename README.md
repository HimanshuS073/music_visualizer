# Music Visualizer AI

A small Python and LÖVE project exploring music search, streaming playback, and audio visualization. The current Python tool searches YouTube Music and streams a selected result through `yt-dlp` and FFmpeg. The LÖVE files are an early visualization scaffold.

> **Current status:** search and streaming playback are implemented in `music_call.py`. The LÖVE scaffold does not yet analyze audio or draw a live visualizer, and music taste recommendations are not implemented yet.

## Features

- Search YouTube Music for songs from the terminal.
- Choose a search result by its number.
- Resolve the audio stream with `yt-dlp` and play it with `ffplay`; the script does not save an audio file.
- Stop playback with **Ctrl+C** or quit the search loop with `q`.
- Includes a minimal LÖVE (Lua) application scaffold in `main.lua` and `conf.lua`.

## Requirements

- Python 3.12 or newer
- FFmpeg with `ffplay` available on your `PATH`
- Internet access to search YouTube Music and resolve/play streams

## Install

Clone the repository and install its Python dependencies:

```bash
git clone https://github.com/HimanshuS073/music_visualizer.git
cd music_visualizer
python -m venv .venv
```

Activate the virtual environment, then install the project:

```bash
# macOS / Linux
source .venv/bin/activate
python -m pip install -e .
```

```powershell
# Windows PowerShell
.venv\Scripts\Activate.ps1
python -m pip install -e .
```

The dependencies, including `yt-dlp` and `ytmusicapi`, are declared in `pyproject.toml`.

## Run the music search and player

```bash
python music_call.py
```

Enter a song or artist search, then enter the number of a result to play it. Press Enter at the selection prompt to search again, or enter `q` at the search prompt to quit.

## Run the player with immediate keyboard controls (Linux)

`music_call_just_yt_dlp.py` uses mpv for streaming and playback controls,
yt-dlp for searches, and optional Chafa for terminal covers:

```bash
sudo pacman -S mpv python-yt-dlp chafa
python music_call_just_yt_dlp.py
```

Type the first song name and press Enter. Once results appear, enter a result
number and press Enter to play it. Playback keys below act immediately:

| Key | Action |
| --- | --- |
| Space | Pause or resume |
| `>` or `a` | Forward 10 seconds |
| `<` or `d` | Backward 10 seconds |
| `s` | Open search input; type a name and press Enter |
| `c` | Reload the current song's cover |
| `x` | Stop playback |
| `q` | Quit |

Search input accepts ordinary text, including spaces and the shortcut letters.
After submitting a search, the playback keys work while results load. In search
input, type `q` and Enter to quit, or press Ctrl+C to leave search input and stop.
Audio starts and covers load in the background when selecting a result.

The reusable action functions in the script are `search_songs(query)`,
`play_song(player, song)`, `load_image(song)`, `load_song_image(player)`,
`pause_song(player)`, `resume_song(player)`, `play_pause(player)`,
`forward_song(player, seconds=10)`, `backward_song(player, seconds=10)`,
and `stop_song(player)`. Create a `MusicPlayer()` instance to call playback
functions; call `player.stop()` in a `finally` block when finished.
`keyboard_loop()` polls keys, `handle_key()` dispatches actions, and `SearchTask`
runs cancellable searches outside the keyboard loop. Importing the script does
not start the terminal interface.

## Run the LÖVE scaffold

Install [LÖVE](https://love2d.org/) and launch the repository directory with it:

```bash
love .
```

The scaffold currently displays a blank window; live audio input and visualization still need to be built.

## Troubleshooting

- **`ffplay` not found:** install FFmpeg and ensure its `bin` directory is on `PATH`.
- **Artist shows as “Unknown artist”:** some YouTube Music search results omit artist metadata. The title and video ID can still be available.
- **Playback or search fails:** check your internet connection and try again. YouTube stream availability and `yt-dlp` compatibility can change over time.

## Project files

| File | Purpose |
| --- | --- |
| `music_call.py` | Terminal search, result selection, and streaming playback |
| `main.lua`, `conf.lua` | Early LÖVE visualization scaffold |
| `pyproject.toml` | Python project metadata and dependencies |
| `src/music_visualizer_ai/` | Python package entry point placeholder |

## License

No license has been added yet. Until one is added, standard copyright applies and reuse is not granted by this repository.
