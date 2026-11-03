# Music Player test report

Tested on November 3, 2026.

## Result

The tested Linux backend and desktop UI passed in Ubuntu 24.04 and Debian 13
Distrobox containers after two small fixes. Each container passed 36 backend
checks and 27 GUI/numerical checks. Five abrupt socket-disconnect regressions
also passed in each container. Seven injected-failure checks passed separately.

The Windows package passed seven smoke checks under Wine. That is not a native
Windows playback or audio-capture certification. Android's later APK update is
covered separately below; no Android device testing was available.

## Environment and method

- Host: CachyOS, x86-64, kernel `6.18.55-2-cachyos-lts`.
- Distrobox 1.8.2.5 with rootless Podman 6.1.4.
- Containers: Ubuntu 24.04 and Debian 13, with separate home directories.
- Containers used Distrobox's display/audio integration packages. Python,
  LÖVE, mpv, yt-dlp, Deno, pactl and parec came from the Linux release bundle.
- Backend checks used the real newline-delimited JSON protocol and live YouTube
  searches/streams. Every received stdout line had to parse as a JSON object.
- GUI checks rendered the real LÖVE application using its Python backend. A
  temporary harness injected keyboard/mouse callbacks and controlled hover
  coordinates/focus. It did not automate physical keyboard or compositor input.
- Screenshots were inspected during testing. They and the harness were temporary;
  no test hooks were added to the application.
- The initial suites used the extracted release. Tests affected by the fixes
  below were rerun with the corrected source in the extracted app.

## Linux backend: 36 checks per container

Both containers passed the following groups:

| Area | What was checked |
| --- | --- |
| Startup | JSON API starts and returns results without debug text in stdout |
| Validation | Unknown command, blank query, invalid video ID, index zero, invalid seek type, NaN seek, invalid FFT size, invalid video sampling rate, spectrum before playback |
| Recovery | Malformed JSON returns an error and the next request still works |
| Search | Live search returns ten results with title, ID, URL, channel, duration and thumbnail fields |
| Images | Small preview is valid encoded image data; a preview and full cover can load independently |
| Playback | Stream becomes ready with both FFT capture and video colors enabled |
| FFT capture | Non-silent 1,024-sample PCM windows at 44,100 Hz; sequence numbers advance |
| Video colors | RGB values are within 0–1 and decoded frame sequences advance |
| Controls | Pause freezes position; resume advances position; both seek directions move exactly ten seconds |
| Responsiveness | Status requests return while music plays and while another search runs |
| Search replacement | Latest results match the latest query; a newer search cancels the older one |
| Track switching | Three rapid play requests settle on the final selection with playback ready |
| Stop | Playback stops and the spectrum becomes unavailable |
| Closing | Closing API stdin ends the backend, mpv and capture processes; temporary mpv files are removed |

These were short interactive scenarios, not an endurance test. The tests verified
non-silent decoded/captured audio and advancing position, not listening quality
through every physical output device.

## Linux GUI and FFT math: 27 checks per container

- OpenGL window starts and connects to Python.
- A synthetic 1 kHz sine peaks at the expected FFT bin: about 990.53 Hz at the
  tested frequency resolution. Silence produces zero targets.
- Smoothed bar heights remain finite and within 0–1.
- Invalid palette colors are filtered; oversized count and FFT settings clamp safely.
- S opens search; UTF-8 backspace works; Enter starts the background search.
- Search results and multiple result thumbnails reach the window.
- Clicking a result selects it and closes search.
- Real audio samples and decoded-video palettes reach the visualizer.
- The blurred cover background loads.
- Hover transitions use intermediate positions, reserve 84 pixels for controls,
  keep bar height constant, and return smoothly when the mouse leaves.
- A configured zero bottom gap now produces an actual zero gap.
- Layout follows the actual window bounds after a resize request.
- Escape closes search; F5 reloads configuration.
- Space pauses and resumes while controls are hidden.
- Frame timing was sampled and normal window closing completed.

The compositor chose a 959×1042 window after the resize request. Bounds were
checked at that actual size. An exact 720×480 physical window was not verified.

## Failures injected without network access

Seven checks used controlled responses rather than live YouTube failures:

- Three malformed search payload shapes are rejected.
- Malformed individual result entries are skipped.
- A query containing shell syntax stays a literal subprocess argument; no shell
  is requested.
- A simulated search network failure becomes an error.
- A simulated thumbnail timeout becomes an error.

These checks do not cover every possible outage or YouTube response change.

## Windows smoke checks under Wine

Passed: bundled module imports, JSON API startup, empty-search rejection, live
search with ten results, result preview loading, clean API exit, and GUI startup
with a working Lua-to-Windows-Python connection.

The Windows extractor also unpacked successfully. Native Windows playback,
WASAPI loopback/FFT, drivers, signing and installer behavior were not verified.

## Measurements

These are observations from brief runs, not minimum hardware requirements.

| Measurement | Ubuntu 24.04 | Debian 13 |
| --- | ---: | ---: |
| Live search | 3.12 s | 2.87 s |
| Stream ready, including video decoding | 13.87 s | 14.63 s |
| Median status round trip, 12 samples | 0.49 ms | 0.50 ms |
| Longest status round trip, same samples | 0.73 ms | 0.75 ms |
| Status round trip while search runs | 1.24 ms | 1.51 ms |
| Backend process-tree CPU, five-second sample | 3.79% of one core | 4.39% of one core |
| Backend process-tree proportional memory | 87.96 MiB | 121.47 MiB |
| GUI frame-time 95th percentile | 33.05 ms | 33.43 ms |

CPU and memory include the Python backend, mpv and parec, but exclude LÖVE, the
container infrastructure and the desktop. They are steady-state observations;
loading peaks were not measured. Different live search results were selected,
so the columns are not a distribution performance ranking.

GUI timing includes the app's idle/unfocused frame caps. Around 33 ms is
consistent with its 30 FPS cap; these tests do not establish sustained 60 FPS.

## Bugs found and corrected

1. **Zero bottom gap became four pixels.** `spectrum.lua` clamped `bottom_gap`
   to a minimum of 4. Its minimum is now 0. Both rendered GUI tests passed after
   the change.
2. **A closing client could produce a reset message or exit code 120.** The API
   now handles connection resets and redirects pending stdout to the null device
   after a disconnected pipe, preventing a second flush failure during Python
   shutdown. Five forced socket closures passed in each Linux container with
   exit code 0 and no reset traceback.

The earlier cross-distribution mpv fix is also present: mpv uses the bundled
`libmujs.so` instead of an absolute `/usr/lib/libmujs.so` dependency.

## Remaining limits

- Distrobox shares the host kernel, graphics devices, display server and audio
  service. Separate Ubuntu/Debian installations, other GPUs, older CPUs,
  Wayland/X11 variations, and other distributions need their own testing.
- No ARM, 32-bit, musl/Alpine or macOS support was established.
- No Android device/emulator or native Windows machine was available.
- No hours-long soak test, real offline network test, memory-leak certification,
  high-DPI scaling matrix, or full configuration-range sweep was performed.
- The API's SIGTERM path and every hardware/driver failure were not systematically
  exercised. Clean EOF, normal GUI close and abrupt socket close were checked.
- YouTube availability and extraction behavior can change independently of the app.
- Build reproducibility and dependency licensing were not audited by these runtime
  tests. See [DISTRIBUTION.md](DISTRIBUTION.md) for the existing release limitations.

## Android APK update verification

The Android app was updated later on November 3, 2026. These checks are separate
from the desktop test counts above.

Changes:

- Result thumbnails use a 2 MiB memory cache and separate image workers. Images
  are not saved to disk. Image responses are limited to 4 MiB and decoded to a
  bounded size before display.
- Controls and the song title hide after three seconds of active playback. A tap
  reveals them; the bars move over 240 ms without changing height. Controls stay
  visible while paused or when screen-reader touch exploration is enabled.
- Superseded search/stream/image jobs cancel their active HTTP calls, and stale
  replies are ignored.
- A stream resolved after the activity stops does not start playing in the
  background. Existing pause-on-background and release-on-destroy behavior remain.
- The visible video preview is smaller, leaving more room for results while still
  providing decoded frames for the FFT palette.

Build and package checks:

- APK version 1.1, version code 2; package `local.music.visualizer`.
- Minimum API 26 (Android 8); compile/target API 36 (Android 16).
- OpenJDK 17, Gradle 8.13, Android Gradle plugin 8.13.0, build tools 36.0.0.
- Media3 1.8.0, NewPipe Extractor v0.26.5 and OkHttp 4.12.0 retained.
- `assembleRelease` and `lintRelease` passed: zero lint errors and 20 warnings.
  Warnings concern existing translation/resource choices, dependency version
  notices, backup metadata, and the missing custom application icon.
- The APK retains the earlier public AOSP test certificate and has a higher
  version code. It remains a personal test build, not a production-signed build.
- APK ZIP integrity, alignment, and v2/v3 signature verification passed. The
  certificate matches the previous APK. No native-library ABI dependency or
  microphone/storage permission was added.

Thirteen host-JVM checks passed using the actual compiled `MusicSearch` code:

1. Cancelled search rejects before network access.
2. Live search returns a non-empty result list bounded to 20 entries.
3. Result metadata and preview/cover URLs are present.
4. Live preview image bytes load.
5. Live cover image bytes load.
6. Cancelled stream resolution rejects before network access.
7. A live media stream resolves.
8. The stream returns HTTP 206 media data; only 1,024 bytes were read into memory.
9. Cancelled image jobs reject.
10. Oversized image responses are rejected.
11. Image IO runs asynchronously.
12. Cancelling a job aborts an active HTTP call within the two-second test bound.
13. A fresh job still succeeds after cancellation.

No Android device was connected (`adb devices` returned an empty list). UI layout,
touch interactions, animations, Media3 decoding/audio output, FFT drawing, Android
lifecycle behavior, and installation/update on a phone still need device testing.
The host-JVM checks do not establish those behaviors. No emulator was used.
