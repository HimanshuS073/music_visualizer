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
Each result has a small thumbnail that loads in the background. These previews
stay in memory and are cleared when you search again; no image files are saved.

| Key | Action |
| --- | --- |
| S | Open search |
| Space | Pause or resume |
| Left / Right | Back or forward 10 seconds |
| Escape | Close the search panel |
| F5 | Reload visualizer and background settings |
| Q | Quit |

Move the mouse to the bottom of the window to show the playback buttons and song
name. They hide when you move away. You can also hover over the song's title area
near the top to see its details. Keyboard shortcuts keep working while controls
are hidden.
The bars slide smoothly to the bottom while the controls are hidden and move
above them when they appear, keeping the same height. The `bottom_gap` setting
adds space below the bars in either position.

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
| Linux | `music-player-linux-x86_64.run` | x86-64 Linux with graphics and PipeWire/PulseAudio |
| Windows | `music-player-windows-x64.exe` | Windows 10/11, 64-bit |
| Android (Music Room) | `music-room-v5.apk` in the Android release | Android 8.0 or newer |

These builds include their app dependencies. You still need internet access,
working graphics drivers, and an audio output. Linux also uses your existing
PipeWire/PulseAudio service.

The Linux package was tested on CachyOS and in Ubuntu 24.04 and Debian 13
Distrobox containers. Both containers passed bundled runtime checks, window
startup, the JSON API, live search, thumbnails, playback, pause/resume, seeking,
searching while a song plays, switching tracks, live FFT capture, and video colors.
The package uses its bundled `libmujs` library instead of requiring a copy in
`/usr/lib`.

Distrobox shares the host's kernel, display, graphics devices, and audio service.
These results don't replace testing on separate installations. Read
[TEST_REPORT.md](TEST_REPORT.md) for the test coverage, measurements, and limits.

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

### Android — Music Room

Download the APK from [Music Room 1.4](https://github.com/HimanshuS073/music_visualizer/releases/tag/android-v1.4).
It supports Android 8 and newer. Install it once over the earlier **Kotlin** app
to get the updater. The older Java Android app is a separate application.

Search for a song and tap a result. The list hides until you search again, leaving
the listening area clear. Music keeps playing when you switch apps or
lock the screen. Pause/resume and seek controls are available in the window;
Android's media notification also controls playback. Swiping Music Room out of
Recents stops it. Controls hide smoothly while playing; tap the visualizer to
show them. Settings change the bar count, color speed and video sampling rate.

Music Room checks GitHub for new Android releases at startup. Accept the update
prompt to download the APK, then approve Android's installation prompt. Android
may ask you to allow installs from Music Room once. Updates keep the same app
and settings. **Settings → Check updates** checks manually.

The Kotlin source and build instructions are in
[kotlin_music_player/](kotlin_music_player/README.md). Signing keys and generated
files remain private. The app uses native Android views, Media3 and NewPipe;
it needs no Python, mpv or Termux. Automated checks are documented in
[the Android test report](kotlin_music_player/TEST_REPORT.md). Real phone playback
and installer testing are still needed. The APK is a personal debug-signed build.

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
- `kotlin_music_player/` contains the public Music Room Android project.
- `android/src/main/` is the older local Java Android app; Git ignores it.

Build scripts and download caches were removed after making the packaged builds.
The source is still here, but rebuilding them needs a new build setup. Bundled
components keep their own licenses; notices are included with the packages.

## Support the project

If you enjoy this player, please [give the repository a star](https://github.com/HimanshuS073/music_visualizer)
and [follow me on GitHub](https://github.com/HimanshuS073) for updates. Thanks for
trying it out!

## Contributing

Contributions are welcome. This is a personal project, and there's still room to
improve it. Bug fixes, ideas, documentation, and testing on Linux, Windows, or
Android all help.

Found a problem? [Open an issue](https://github.com/HimanshuS073/music_visualizer/issues)
with your operating system, what happened, and the steps to reproduce it. Include
any useful error messages, but leave out tokens and other private information.

To make a change, fork the repository, create a branch, and open a pull request.
Explain what you changed and how you tested it. For bigger changes, open an issue
first so we can talk about the idea. Please keep the app lightweight and avoid
adding dependencies unless they're needed.

Contributions to the app's code and documentation use the project's
GPL-3.0-or-later license. Keep existing copyright and third-party notices.

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
