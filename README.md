# Music Visualizer AI

A small music player with a LÖVE window and Python music functions. It uses
yt-dlp to search YouTube and mpv to play songs.

Music, thumbnails, and sampled video frames are not saved to disk.

## Install

On CachyOS or Arch Linux:

```bash
sudo pacman -S python python-yt-dlp mpv love libpulse
```

For cover images in the terminal, install Chafa too:

```bash
sudo pacman -S chafa
```

## Run the window player

Open a terminal in the project folder:

```bash
love .
```

Press **S**, type a song name, and press **Enter**. Results appear in the same
panel on the right. Click a result, or use **Up/Down** and **Enter**, to play it.
Use the mouse wheel to see more results.

The selected song's cover becomes a blurred, low-contrast background. The player
tries the highest available YouTube thumbnail and falls back if it is missing.
It loads images in memory and caches the blur until the song or window size changes.

**Space** pauses or resumes, **Left/Right** seek 10 seconds, and **S** opens another
search while the current song keeps playing. **Escape** closes the search panel.
You can also click the playback buttons at the bottom. Close the window to stop.

`music_backend.lua` handles the connection using LuaJIT, which comes with LÖVE.
If your Python dependencies are in a virtual environment, run
`MUSIC_PYTHON=/path/to/.venv/bin/python love .`. This window connection is for Linux.

## FFT bars and settings

The bars show a real FFT of mpv's own audio output. They use PipeWire/PulseAudio
monitoring through `pactl` and `parec` (provided by `libpulse`). Audio is decoded
once; the FFT runs in LuaJIT without NumPy or extra Lua packages.

Edit **`config.lua`** and press **F5** to reload the bars and background settings.
You can change the bar count, width, height, spacing, rounding, opacity, smoothing,
gain, frequency range, and update rate. The visualizer spans left to right just
above the controls, with space reserved while the search panel is open.
`fill_width` spreads the bars across that strip; `gap` is then the minimum gap.
Use `bottom_gap` to change the distance from the controls.
By default, bar colors come from **actual video frames at the current playback
position**. mpv plays the audio and decodes a low-resolution video stream in the
same process. It samples a 64×36 frame in memory and sends only RGB colors to Lua.
Seeking changes the sampled scene too; pausing stops color changes.

- `color_source = "video"` uses changing video frames. `"thumbnail"` uses the cover
  and keeps playback audio-only.
- `video_color_fps = 8` samples eight frames per second (allowed: 1–30). Lower
  values reduce work. This setting and `color_source` apply when selecting a song.
- `color_mode = "random"` assigns each bar a random color from the latest frame's
  palette on a timer. `"per_song"` keeps
  assignments until the sampled palette changes.
- `color_change_speed = 10` changes bar colors ten times per second (allowed:
  0.1–144, limited by rendering FPS). Use `2` for slower changes or `30` for faster
  changes. Press **F5** after editing it. Video sampling and FFT motion keep their
  own speeds; new video palettes wait for the next color change.
- `palette_size` limits the number of sampled colors used by the bars.

The background still uses the blurred cover. No frame images are written to disk
or sent to the window. Video sampling adds some bandwidth and decoding work;
choose `"thumbnail"` if you want audio-only playback. Audio-only videos have no
video palette, so bars retain their neutral default colors.

Defaults are 48 bars and a 1024-sample FFT at 44.1 kHz, targeting 60 spectrum
updates per second. Damped motion smooths the bars between readings. Requests
are sent immediately and old audio frames are discarded.
The frame cap is applied after presentation so it does not add another wait
before vsync. Visible playback targets 60 FPS, or 30 FPS when unfocused. A
minimized window updates at 5 FPS and skips FFT requests.
The background blur is cached and limited to 1280 pixels wide by default.
It is rebuilt less often during a resize to keep the window responsive.
Startup window dimensions in the config apply next time you launch the app.
`conf.lua` disables unused LÖVE audio, physics, and other modules; mpv handles sound.

If audio monitoring is unavailable, music still plays and an FFT message appears.
After fixing the audio connection, press F5 to reconnect the visualizer.

## Run the terminal player

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
- `playback.py` — play/stop music and sample colors from mpv video frames
- `controls.py` — pause, resume, seek, and get playback status
- `thumbnails.py` — get a cover URL or image bytes in memory
- `spectrum.py` — monitor mpv's audio and return the newest in-memory PCM samples
- `__init__.py` — make the functions available to import

Use `create_player()` once, pass that player to the control functions, and
call `stop_song(player)` when finished. The terminal interface is in
`music_call_just_yt_dlp.py`, outside this folder.

## Search from another app

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


Other commands are `resume`, `toggle`, `backward`, `seek`, `status`, `results`,
`thumbnail`, `image`, and `stop`. `image` returns base64 image bytes in the background;
`thumbnail` returns a URL. Both accept a result `index` or `video_id`.
Result numbers start at 1. Replies include the request
`id` and `ok: true` or `ok: false`. Match replies by `id`, since searches run
in the background.

For an FFT client, set `spectrum: true` on `play`, with optional `fft_size` and
`sample_rate`. Request `spectrum` to read base64 mono `s16le` PCM, or use
`spectrum_start` / `spectrum_stop` to control monitoring. Send the last `sequence`
to avoid getting the same samples again. Samples and images are never saved.

For video colors, send `"video_colors": true` and optionally `"video_color_fps": 8`
on `play`. Poll `video_palette` with your last `sequence`. It returns `available`,
`sequence`, `video_id`, playback `position`, and `colors` as RGB triples in 0–1.
Unchanged palettes omit `colors`; frames that are still loading return
`available: false`. For example, after searching:

```json
{"id": 2, "command": "play", "index": 1, "video_colors": true, "video_color_fps": 8}
{"id": 3, "command": "video_palette", "sequence": -1}
```

Playback takes a moment to load. Use `status` to check when `ready` is true.
Closing the input pipe or sending `quit` stops playback and exits Python.
