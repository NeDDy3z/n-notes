<p align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" width="80" alt="xnotes icon" />
</p>

<h1 align="center">xnotes</h1>

<p align="center">
  A handwriting-first notebook for Android, built for pen and stylus
</p>

<p align="center">
  <a href="https://github.com/shardulvs/xnotes-android/releases/latest"><img src="https://img.shields.io/github/v/release/shardulvs/xnotes-android?style=flat-square&label=release&color=blue" alt="Release" /></a>
  <a href="https://f-droid.org/en/packages/com.xnotes"><img src="https://img.shields.io/f-droid/v/com.xnotes?style=flat-square" alt="F-Droid" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-orange?style=flat-square" alt="License" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />
  <a href="https://github.com/sponsors/shardulvs"><img src="https://img.shields.io/badge/sponsor-%E2%9D%A4-ec6cb9?style=flat-square&logo=githubsponsors&logoColor=white" alt="Sponsor" /></a>
</p>

<p align="center">
  <a href="https://github.com/shardulvs/xnotes-android/releases/latest">GitHub Releases</a> &bull;
  <a href="https://f-droid.org/en/packages/com.xnotes">F-Droid</a> &bull;
  <a href="https://play.google.com/store/apps/details?id=com.xnotes">Google Play</a>
</p>

<p align="center">
  <a href="https://f-droid.org/en/packages/com.xnotes"><img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" height="70" alt="Get it on F-Droid" /></a>
  <a href="https://play.google.com/store/apps/details?id=com.xnotes"><img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" height="70" alt="Get it on Google Play" /></a>
</p>

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/sevenInchScreenshots/1.png" width="90%" alt="xnotes editor: a PDF open with the ink toolbar and page thumbnails" />
  <br />
  <em>Annotating a PDF in dark mode with pages inverted</em>
</p>

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/sevenInchScreenshots/2.png" width="90%" alt="xnotes home screen: the file browser showing imported notes" />
  <br />
  <em>Browsing your notes and imported PDFs</em>
</p>

---

## At a glance

- **Pressure-sensitive ink**: a custom stroke engine turns raw stylus samples into a smooth, variable-width ribbon that swells and tapers with pen pressure, so handwriting and sketches feel natural instead of like a flat marker.
- **Vector PDF, in and out**: drop in a PDF as a page background to annotate, then export your notes back to PDF as true vector: ink and text stay crisp at any zoom instead of being flattened to pixels.
- **Razor-sharp deep zoom**: a background renderer redraws a high-resolution viewport off the main thread, so you can zoom far in and the ink stays sharp rather than turning blocky.
- **Real highlighter blending**: highlighters are composited live every frame with a true multiply blend, so overlapping strokes deepen like real ink instead of painting over one another.
- **Neon pen**: a glowing pen with a bright white core and saturated, luminous edges for accents that pop off the page.
- **Smart PDF dark mode**: invert a PDF page for comfortable night reading while leaving embedded photos and images untouched.
- **Nothing is ever flattened**: every stroke is stored as editable vector data in the open `.xnote` format, so you can re-select, move, restyle, or erase any mark at any time.
- **Stylus-aware by design**: pen and finger are handled separately, so you can pan with a finger while you draw with the pen; on devices without a stylus, finger drawing turns on automatically.
- **Private and open**: open source, no accounts, no telemetry, no network access at all. Files go through Android's Storage Access Framework, so the app needs no broad storage permission either.

## Install

| Channel | |
|---|---|
| [GitHub Releases](https://github.com/shardulvs/xnotes-android/releases/latest) | Signed APK |
| [F-Droid](https://f-droid.org/en/packages/com.xnotes) | Built reproducibly from source |
| [Google Play](https://play.google.com/store/apps/details?id=com.xnotes) | Automatic updates |

GitHub Releases and F-Droid ship the same signed APK. F-Droid rebuilds from source and verifies it against the GitHub release, so you can switch between those two without reinstalling.

## Build from source

Requires **JDK 17** (the project pins Java 17):

```bash
git clone https://github.com/shardulvs/xnotes-android.git
cd xnotes-android
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`
