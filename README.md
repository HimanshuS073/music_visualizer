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
