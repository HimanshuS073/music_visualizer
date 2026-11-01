# Music Visualizer AI

A small music player built with Python. It uses yt-dlp to search YouTube and
mpv to play songs. Lua or another language can send requests to Python and
read the results as JSON.

Music and thumbnails are not saved to disk.

## Install

On CachyOS or Arch Linux:

```bash
sudo pacman -S python python-yt-dlp mpv
```

For cover images in the terminal, install Chafa too:

```bash
sudo pacman -S chafa
```

## Run the player

Open a terminal in the project folder and run:

```bash
python music_call_just_yt_dlp.py
```

Type a song name and press Enter. Choose a result by entering its number.
You can type another song name while music is playing.

| Key | What it does |
| --- | --- |
| Space at an empty prompt | Pause or resume |
| Right arrow | Forward 10 seconds |
| Left arrow | Backward 10 seconds |
| Ctrl+L | Show the cover again |
| Ctrl+X | Stop the song |
| Ctrl+Q | Quit |

## Python functions

The `python_code_music` folder only contains reusable functions:

- `search.py` — search for songs
- `playback.py` — create a player, play a song, and stop it
- `controls.py` — pause, resume, seek, and get playback status
- `thumbnails.py` — get a cover URL or image bytes in memory
- `__init__.py` — make the functions available to import

Use `create_player()` once, pass that player to the control functions, and
call `stop_song(player)` when finished. The terminal interface is in
`music_call_just_yt_dlp.py`, outside this folder.

## Input from Lua

```bash
lua lua_input.lua
```

Enter a song name in Lua. Python searches for it and returns JSON.
Type `q` to quit. This example searches only; it does not start playback.

You can run the same search from another language:

```bash
python music_call_just_yt_dlp.py --search "toca toca"
```

## Control music from another app

Keep this Python process open:

```bash
python music_call_just_yt_dlp.py --api
```

Send one JSON request per line through stdin and read replies from stdout.
For example, search first, wait for the results, then play a result:

```json
{"id":1,"command":"search","query":"toca toca"}
{"id":2,"command":"play","index":1}
{"id":3,"command":"pause"}
{"id":4,"command":"forward","seconds":10}
{"id":5,"command":"quit"}
```

Other commands are `resume`, `toggle`, `backward`, `seek`, `status`, `results`,
`thumbnail`, and `stop`. Result numbers start at 1. Replies include the request
`id` and `ok: true` or `ok: false`. Match replies by `id`, since searches run
in the background.

Playback takes a moment to load. Use `status` to check when `ready` is true.
Closing the input pipe or sending `quit` stops playback and exits Python.
