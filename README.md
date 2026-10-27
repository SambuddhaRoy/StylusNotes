# Stylus Notes

A lightweight Android app for handwriting and drawing with a stylus, built for older tablets such as the
Galaxy Note 10.1 (Android 8 and up; tuned on Android 14).

- **Notes:** pressure-sensitive pens, highlighter, lasso, text, pictures from the gallery or a web image
  search, LaTeX equations, layers, and AI help from Claude (bring your own Anthropic API key).
- **Drawings:** a Procreate-style raster canvas with a brush library, smudge, layers and blend modes,
  selections, transform, adjustments, ColorDrop fill, QuickShape, StreamLine and PSD export.
- Palm rejection, two-finger undo and three-finger redo taps, Material 3 Expressive design.

## Install

Download the latest `StylusNotes-x.y.z.apk` from [Releases](https://github.com/SambuddhaRoy/StylusNotes/releases)
and open it on the tablet. The app then keeps itself up to date: it checks Releases every few hours (or on
demand via **Library ⋮ > Check for updates**) and installs new versions from the Library screen. Android asks
once for permission to let the app install updates.

## Build

Use Android Studio's bundled JDK:

```
gradlew assembleRelease
```

Release builds are signed with the build machine's `~/.android/debug.keystore` (not in the repository).
Every release must use that same key, or installed copies can't update, so keep a backup of it.

## Release

```
python tools/release.py 3.2.0 "What changed"
```

Bumps the version, builds, commits, tags, pushes and publishes the GitHub release that installed apps update from.

## Tests

- `gradlew connectedDebugAndroidTest`: palm-rejection and gesture tests on a device or emulator.
- `python tools/ui_flow.py <apk>`: walks every screen on a device or emulator and screenshots each step.
