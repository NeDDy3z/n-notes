<h1 align="center">n-notes</h1>

<p align="center">
  A handwriting-first notebook for Android, built for pen and stylus
</p>

<p align="center">
  <a href="https://github.com/NeDDy3z/n-notes/releases/latest"><img src="https://img.shields.io/github/v/release/NeDDy3z/n-notes?style=flat-square&label=release&color=blue" alt="Release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-orange?style=flat-square" alt="License" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />
</p>

---

## Additions to the original xnotes

n-notes is a fork of [xnotes](https://github.com/shardulvs/xnotes-android). On top of the original app it adds:

- **Stylus side-button mapping**: bind a tool (such as the eraser or pan) to the pen's side button and use it while the button is held. The button is read across every route pens use to report it: the touch stream, the hover stream, and Bluetooth/USI key events, including a vendor keycode with no standard mapping and the proprietary action codes some Samsung builds emit.
- **Optional hover activation**: with the side button held, the mapped eraser or pan can run off the hover stream, so you can erase or pan without touching the screen.
- **Smoother, more reliable saving**: notes save and autosave off the main thread, and the `.xnote` read/write path was reworked for speed, so large notes save without stalling the pen.
- **Steadier canvas**: stroke handover between the live pad and the page was hardened so a settling stroke no longer flickers or gets wiped by a new one, and the undo history is bounded to keep memory in check.

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
