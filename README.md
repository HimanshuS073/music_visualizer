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

All imports in this script come with Python. The only extra Python package it
needs is `yt-dlp`; mpv and optional Chafa are command-line programs.

```bash
sudo pacman -S mpv python-yt-dlp chafa
python music_call_just_yt_dlp.py
```

Type the first song name and press Enter. Once results appear, enter a result
number and press Enter to play it. Playback keys below act immediately:

| Key | Action |
| --- | --- |
| Space at an empty prompt | Pause or resume |
| Right arrow | Forward 10 seconds |
| Left arrow | Backward 10 seconds |
| Ctrl+L | Reload the current song's cover |
| Ctrl+X | Stop playback |
| Ctrl+Q | Quit |

Type another song name directly and press Enter to search again; no search key
or mode change is needed. Letters such as `a`, `d`, and `s` are normal input.
Spaces within a name type normally; Space at an empty prompt pauses or resumes.
Arrow keys work even while typing or waiting for search results. You can also
type `q` and Enter to quit, or press Ctrl+C to stop and clear the current input.
For a numeric song title, use `search 505` to distinguish it from a result number.
Keys are read while this terminal has focus.
Audio starts and covers load in the background when selecting a result.

## Modular Python music code

`music_call_just_yt_dlp.py` starts the existing terminal player. Its reusable
Python functions are in `python_code_music/`:

- `search.py`: `search_songs(query)` returns songs or raises an error
- `playback.py`: `create_player()`, `play_song(player, song)`, `stop_song(player)`
- `controls.py`: `pause_song`, `resume_song`, `play_pause`, `forward_song`,
  `backward_song`, and `get_status`
- `thumbnails.py`: `thumbnail_url(song)` and `fetch_thumbnail(song)` return a URL
  or image bytes without drawing or saving anything
- `__init__.py`: exports the functions

Create one player state with `create_player()` and pass it to the playback and
control functions. Call `stop_song(player)` when finished. The modules contain
functions only: no keyboard loop, prompts, terminal display, or application setup.
Those parts remain in the existing entry script, outside this folder.

Run it as before:

```bash
python music_call_just_yt_dlp.py
```

### Call the functions from Lua or another language

For a simple input/output example, run:

```bash
lua lua_input.lua
```

Type a song name in Lua and press Enter. Lua sends it to Python, Python searches
YouTube, and Lua prints the JSON reply. Type `q` to stop. This example uses only
standard Lua functions; it prints JSON without needing a Lua JSON package.
To use a virtual environment, pass its Python executable:

```bash
lua lua_input.lua /absolute/path/to/.venv/bin/python
```

The same search command works from any language that can start a subprocess:

```bash
python music_call_just_yt_dlp.py --search "toca toca"
```

It returns one JSON object and exits. Successful searches exit with code 0,
search failures with 1, and empty search input with 2. The Lua example waits for
each search to finish. For ongoing playback and controls, use the persistent
interface below.

Start the existing entry point as one persistent subprocess:

```bash
python /home/himanshus/Projects/music_visualizer_ai/music_call_just_yt_dlp.py --api
```

Your app writes one JSON object followed by a newline to stdin and reads JSON
replies from stdout. Keep both pipes open while playing music. This interface
is in the existing Python file and needs no additional Python packages.

Example requests (wait for the search results before selecting a result):

```json
{"id":1,"command":"search","query":"toca toca"}
{"id":2,"command":"play","index":1}
{"id":3,"command":"pause"}
{"id":4,"command":"resume"}
{"id":5,"command":"forward","seconds":10}
{"id":6,"command":"backward","seconds":10}
{"id":7,"command":"status"}
{"id":8,"command":"thumbnail","index":1}
{"id":9,"command":"stop"}
{"id":10,"command":"quit"}
```

Also supported: `toggle` (or `play_pause`), `results`, and `seek` with signed
relative `seconds`. `play` and `thumbnail` accept a `video_id` instead of an index.
Result indexes start at 1. Each song has `title`, `video_id`, `url`, `channel`,
`duration` in seconds, and `thumbnail`; unavailable metadata is `null`.

Replies echo `id` and contain `ok: true` plus the result, or `ok: false` plus an
`error`. Searches run in the background, so replies can arrive out of order;
match them by `id`. A newer search cancels the previous search and clears old
results. Playback controls remain available while a search is running.

`play` replies with `state: "loading"`; poll `status` for `ready`, `paused`,
`position`, and `duration`. A seek can fail while a track is still loading.
`thumbnail` returns a URL for your app to display, without saving an image.
The Python helpers `thumbnail_url(song)` and `fetch_thumbnail(song)` also expose
the URL and in-memory image bytes without terminal rendering.

Player messages go to stderr, leaving stdout for JSON. Invalid requests return
an error and keep the service running. Closing stdin, sending `quit`, or sending
SIGTERM stops playback and active searches. Normal shutdown exits with code 0;
fatal errors exit with 1 and invalid launch arguments exit with 2. Your language
needs a subprocess facility with separate read/write pipes (plain Lua's
`io.popen` provides only one direction).

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
