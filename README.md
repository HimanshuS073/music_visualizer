# Music Player

A small music player I'm building for myself. Search YouTube, pick a song, and
keep listening while you look for the next one.

The desktop app uses Lua with LÖVE for the window, Python and yt-dlp for search,
and mpv for playback. There's also an Android version with touch controls.
Both have a cover background and audio bars that follow the music.

Songs stream without being saved as music files. Cover images and the video
frames used for bar colors stay in memory too.

## Demo video

A demo video hasn't been added yet.



https://github.com/user-attachments/assets/10e519cb-7d65-467b-b470-79dcf37dc91c





## Run it on Linux

On CachyOS or Arch, install the dependencies:

```bash
sudo pacman -S python python-yt-dlp mpv love libpulse
```

Then open the project folder and run:

```bash
./music-player
```

You can also use `love .`. Keep the launcher beside the source files; it finds
them even when you start it from another directory.

If you use a Python virtual environment:

```bash
MUSIC_PYTHON=/path/to/.venv/bin/python ./music-player
```

Press **S**, type a song name, and press **Enter**. Click a result to play it,
or choose one with **Up/Down** and **Enter**. Use the mouse wheel to scroll.
You can search again while the current song plays.

| Key | Action |
| --- | --- |
| S | Open search |
| Space | Pause or resume |
| Left / Right | Back or forward 10 seconds |
| Escape | Close the search panel |
| F5 | Reload visualizer and background settings |
| Q | Quit |

The playback buttons at the bottom work with the mouse too. Closing the window
stops the music and its background processes.

## Change the visualizer

Edit `config.lua`, save it, and press **F5** in the player. You can change the
number of bars, their size and spacing, opacity, smoothing, frequency range,
and the background's blur and brightness.

For random bar colors, use these settings inside `bars`:

```lua
color_mode = "random",
color_change_speed = 10,
video_color_fps = 8,
```

`color_change_speed` is how many times per second each bar picks a new color.
Try `2` for slower changes or `30` for faster ones. It doesn't change how fast
the bars move with the music.

`video_color_fps` controls how often the player samples a small video frame for
fresh colors. That setting applies the next time you select a song. The colors
come from the actual video at the current playback position, so seeking can
change the palette. The background uses the song's cover.

If you want to avoid decoding video, set `color_source = "thumbnail"`.
`color_mode = "per_song"` keeps each bar's assigned color until the palette
changes. Window dimensions apply after restarting the app.

On Linux, the FFT reads only mpv's audio through PipeWire/PulseAudio. If the bars
stop working, check that `pactl` and `parec` are available. Music can still play
without the visualizer; press F5 after fixing the audio connection.

## Packaged versions

The finished builds are kept locally in `dist/`. They aren't committed to this
repository, so cloning it gives you the source, not the installers.

| Platform | File | Requirements |
| --- | --- | --- |
| Linux | `music-player-linux-x86_64.run` | CachyOS-compatible x86-64 Linux |
| Windows | `music-player-windows-x64.exe` | Windows 10/11, 64-bit |
| Android | `music-player-android.apk` | Android 8.0 or newer |

These builds include their app dependencies. You still need internet access,
working graphics drivers, and an audio output. Linux also uses your existing
PipeWire/PulseAudio service.

For the Linux build:

```bash
chmod +x dist/music-player-linux-x86_64.run
./dist/music-player-linux-x86_64.run
```

Use `--check` to check its bundled runtimes. To keep an unpacked copy for editing:

```bash
./dist/music-player-linux-x86_64.run --extract "$HOME/music-player-portable"
"$HOME/music-player-portable/AppRun"
```

On Windows, open the `.exe`. To unpack it from PowerShell:

```powershell
.\music-player-windows-x64.exe --extract "$HOME\MusicPlayerPortable"
& "$HOME\MusicPlayerPortable\music-player-app.exe"
```

Edit `app/config.lua` inside either unpacked desktop version to change its
settings. Without `--extract`, the desktop builds unpack temporarily and clean
up when you close them. Allow about 1 GB of temporary space on Linux and 600 MB
on Windows. The Windows executable is unsigned.

The Windows FFT reads the output device's audio mix, so sounds from other apps
can affect the bars. Its startup, search, backend connection, and playback
controls were tested under Wine. Audio capture still needs testing on real
Windows hardware.

### Android

Install the APK, search for a song, and tap a result. The bottom buttons pause,
resume, and seek 10 seconds. Open **Settings** to change the bar count, color
speed, and video sampling rate. Playback pauses when the app goes into the
background.

Android uses Media3 for playback and NewPipe Extractor for search and stream
resolution. It doesn't need Python, mpv, or Termux. The FFT reads the player's
own audio, without recording the microphone. Android 12+ blurs the cover
background; older versions show a muted cover.

The APK is a test-signed personal build. Compilation, signature checks, and a
live search/stream test passed, but the UI, playback, and FFT still need testing
on a phone. Android source is in `android/src/main/`.

## Terminal player

On Linux:

```bash
python music_call_just_yt_dlp.py
```

Type a name, then enter a result number. You can type another search while a
song is playing.

| Key | Action |
| --- | --- |
| Space at an empty prompt | Pause or resume |
| Left / Right | Back or forward 10 seconds |
| Ctrl+L | Show the cover again |
| Ctrl+X | Stop |
| Ctrl+Q | Quit |

Terminal covers are optional. Install `chafa` if you want them:

```bash
sudo pacman -S chafa
```

## Use it from Lua or another app

For a single search that returns JSON:

```bash
python music_call_just_yt_dlp.py --search "toca toca"
```

For playback controls and repeated searches, keep the backend running:

```bash
python music_call_just_yt_dlp.py --api
```

Send one JSON object per line to stdin and read replies from stdout. For example,
send a search and wait for its reply before selecting a result:

```json
{"id": 1, "command": "search", "query": "toca toca"}
```

Then play the first result:

```json
{"id": 2, "command": "play", "index": 1}
```

Other commands include `results`, `pause`, `resume`, `toggle`, `forward`,
`backward`, `seek`, `status`, `thumbnail`, `image`, `stop`, and `quit`.
Result numbers start at 1; you can also select a song by `video_id`.
Use `status` to check when playback is ready.

Replies contain the request's `id` and an `ok` value. Match replies by ID because
searches and image loading run in the background. Errors go to stderr, keeping
stdout available for JSON. Closing stdin or sending `quit` stops playback.

For FFT data, add `"spectrum": true` to `play`, then request `spectrum`. It returns
base64 mono `s16le` PCM with the sample rate, FFT size, and a sequence number.
Send the last sequence number to avoid receiving the same samples again.
`spectrum_start` and `spectrum_stop` control capture separately.

For video colors, add `"video_colors": true` to `play` and request `video_palette`.
Its reply includes RGB colors in the 0–1 range, the video ID, playback position,
and sequence number. An unchanged sequence omits the colors; frames still
loading return `available: false`.

## Where the code lives

- `main.lua` draws the desktop window and handles input.
- `music_backend.lua` connects Lua to Python; `music_windows.lua` handles Windows pipes.
- `spectrum.lua` calculates and draws the desktop FFT bars.
- `config.lua` holds desktop appearance and visualizer settings.
- `music_call_just_yt_dlp.py` runs the terminal player and JSON backend.
- `python_code_music/` contains the search, playback, controls, image, and audio functions.
- `android/src/main/` contains the Android app and its license notices.

Build scripts and download caches were removed after making the packaged builds.
The source is still here, but rebuilding them needs a new build setup. Bundled
components keep their own licenses; notices are included with the packages.

## License

The app's own code and documentation use **GPL-3.0-or-later**. You can use,
change, and share them under that license. Sharing covered versions also brings
source-sharing and notice requirements. Read [LICENSE](LICENSE) and [NOTICE](NOTICE)
for the terms and copyright notice.

Dependencies keep their original licenses. Their notices and package inventories
are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Before publishing
binaries, read [DISTRIBUTION.md](DISTRIBUTION.md): the matching build setup and some
release-specific license work still need to be restored or completed.

These licenses cover software. They don't grant rights to YouTube music, video,
or artwork, and they don't guarantee Play Store approval or protection from disputes.
