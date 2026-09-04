<h1 align="center">n-notes</h1>

<p align="center">
  A handwriting-first notebook for Android, built for pen and stylus. A customized fork of xnotes.
</p>

<p align="center">
  <a href="https://github.com/NeDDy3z/n-notes/releases/latest"><img src="https://img.shields.io/github/v/release/NeDDy3z/n-notes?style=flat-square&label=release&color=blue" alt="Release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-orange?style=flat-square" alt="License" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />
</p>

---

## Additions to the original xnotes

n-notes is a fork of [xnotes](https://github.com/shardulvs/xnotes-android). On top of it:

- **Stylus side-button mapping**: bind a tool (eraser, pan, etc.) to the pen's side button, read across the touch, hover, and Bluetooth/USI event routes.
- **Optional hover activation**: run the mapped eraser or pan off the hover stream, without touching the screen.
- **Smoother saving**: notes save and autosave off the main thread, so large notes don't stall the pen.
- **Steadier canvas**: hardened stroke handover (no flicker or wipe on settling strokes) and a bounded undo history.

## Install

| Channel | |
|---|---|
| [GitHub Releases](https://github.com/NeDDy3z/n-notes/releases/latest) | Signed APK |

## Build from source

Requires **JDK 17** (the project pins Java 17):

```bash
git clone https://github.com/NeDDy3z/n-notes.git
cd n-notes
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## License

Released under the MIT License, the same as upstream. See [LICENSE](LICENSE).

n-notes is a fork of [xnotes](https://github.com/shardulvs/xnotes-android) by Shardul Vikram Singh.
