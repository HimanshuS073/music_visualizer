# Third-party software

Music Player uses the projects below. Their authors keep their copyrights, and
their software keeps its original license. The project's GPL notice applies to
our own code; it does not replace any dependency's license.

## Android

| Component | License | Notice or source |
| --- | --- | --- |
| NewPipe Extractor v0.26.5 | GPL-3.0-or-later | [License](https://github.com/HimanshuS073/music_visualizer/blob/cb7316e2670567420ca92c17f6e000cb819468cd/android/src/main/assets/licenses/NewPipe-GPL-3.0.txt), [source](https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.26.5) |
| AndroidX Media3 1.8.0 | Apache-2.0 | [License](https://github.com/HimanshuS073/music_visualizer/blob/cb7316e2670567420ca92c17f6e000cb819468cd/android/src/main/assets/licenses/Media3-Apache-2.0.txt), [source](https://github.com/androidx/media/tree/1.8.0) |
| OkHttp 4.12.0 | Apache-2.0 | [License](https://github.com/HimanshuS073/music_visualizer/blob/cb7316e2670567420ca92c17f6e000cb819468cd/android/src/main/assets/licenses/OkHttp-Apache-2.0.txt), [source](https://github.com/square/okhttp/tree/parent-4.12.0) |
| Rhino 1.8.1 | MPL-2.0 and additional notices in its license file | [Notice](https://github.com/HimanshuS073/music_visualizer/blob/cb7316e2670567420ca92c17f6e000cb819468cd/android/src/main/assets/licenses/Rhino-MPL-2.0.txt), [source](https://github.com/mozilla/rhino) |
| jsoup 1.22.2 | MIT | [License](https://github.com/HimanshuS073/music_visualizer/blob/cb7316e2670567420ca92c17f6e000cb819468cd/android/src/main/assets/licenses/Jsoup-MIT.txt), [source](https://github.com/jhy/jsoup) |

The Android build also includes transitive dependencies such as nanojson,
protobuf-javalite, Okio, Kotlin's standard library, AndroidX libraries and
annotations, and Java library desugaring support. Before publishing a rebuilt
APK or AAB, generate its complete resolved dependency inventory and preserve
each dependency's license and required notices. The table above is not a
complete license audit of all transitive dependencies.

## Desktop

The following notices were copied from the existing packaged downloads, rather
than substituted with notices from newer upstream versions:

| Component | License information | Local notice |
| --- | --- | --- |
| LÖVE 11.5 and its included libraries | zlib for LÖVE; additional licenses for its libraries | [Windows distribution notice](licenses/desktop/windows/love-11.5-license.txt) |
| Python 3.12.10 | PSF license and included third-party terms | [Windows Python notice](licenses/desktop/windows/python-3.12.10-LICENSE.txt) |
| Python 3.14.8 | PSF license and included third-party terms | [Common PSF text](licenses/common/PSF-2.0.txt); check the matching Python source distribution as well |
| yt-dlp 2026.10.26 | Unlicense for project code; executable distributions may include differently licensed dependencies | [Notice](licenses/desktop/windows/yt_dlp-2026.10.26.dist-info/LICENSE) |
| yt-dlp-ejs 0.8.0 | Unlicense | [Notice](licenses/desktop/windows/yt_dlp_ejs-0.8.0.dist-info/LICENSE) |
| PyAudioWPatch 0.2.12.9 | Apache-2.0 for its additions; included PortAudio/PyAudio terms also apply | [Notice](licenses/desktop/windows/pyaudiowpatch-0.2.12.9.dist-info/LICENSE.txt) |
| certifi 2026.10.23 | Certificate bundle licensing described in its notice | [Notice](licenses/desktop/windows/certifi-2026.10.23.dist-info/LICENSE) |
| Deno 2.9.8 | MIT for Deno; bundled dependency notices also apply | [Bundled notice](licenses/desktop/linux/deno/LICENSE.md), [upstream](https://github.com/denoland/deno/tree/v2.9.8) |
| mpv | GPL-2.0-or-later by default, with LGPL builds possible; check the actual build and linked libraries | [Licensing guidance](https://github.com/mpv-player/mpv/blob/master/Copyright) |
| FFmpeg libraries and codecs | Depends on build options and enabled components | [Upstream licensing guidance](https://ffmpeg.org/legal.html) |
| PulseAudio client tools and libraries | LGPL-2.1-or-later for the main project, with component-specific notices | [Upstream license](https://gitlab.freedesktop.org/pulseaudio/pulseaudio/-/blob/master/LICENSE) |

The desktop packages contain many more shared libraries than this table lists.
Their available package notices are preserved in [licenses/desktop/linux/](licenses/desktop/linux/).
Common license texts included in that package are in [licenses/common/](licenses/common/).
These are inventories of available notices, not a certification that every
redistribution obligation has already been fulfilled.

## Exact package inventories

- [Linux package versions](licenses/inventories/linux-components.txt)
- [Windows download versions, URLs, and checksums](licenses/inventories/windows-components.json)

The Linux inventory includes CUDA-owned components. Their [NVIDIA license](licenses/desktop/linux/cuda/EULA.txt)
is separate from the project's GPL. Review the exact included libraries and
NVIDIA's redistribution terms before distributing that bundle publicly.

The inventories are included for attribution and version tracing. They are not
substitutes for the matching source, build instructions, or any source delivery
required by the applicable licenses. See [DISTRIBUTION.md](DISTRIBUTION.md).
