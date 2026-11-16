# PocketCarioca

A native Android app (Kotlin UI) on top of the Zeebx core. It's a fork of
[ZeebxTeam/zeebx-emu](https://github.com/ZeebxTeam/zeebx-emu) and, like Zeebx, licensed
GPL-2.0-or-later. The emulation is entirely Zeebx's; this directory only adds a different
Android frontend.

## How it differs from `frontends/android`

| | `frontends/android` (upstream) | `frontends/pocketcarioca` |
|---|---|---|
| UI | egui inside `NativeActivity` | Kotlin activities, Material 3 |
| Library | egui grid | cover grid, folder picker or "Import games" into app storage |
| Settings | egui page | AndroidX Preference screen |
| Save states | slots | 10 slots with thumbnails, from the pause menu |
| Extras | — | fast-forward (hold or toggle), screenshots to Pictures/PocketCarioca |
| Motion | not hooked up | phone accelerometer as a Boomerang, with sensitivity and recenter |
| Controllers | yes | yes, plus HAT d-pads, analog triggers, keyboard; touch controls auto-hide |

## Layout

```
src/lib.rs      the JNI bridge: libpocketcarioca.so, driven from the GLSurfaceView's thread
app/            the Gradle project (Kotlin)
build.sh        builds the .so with cargo-ndk, then the APKs
```

`Session` is not `Send`, so it lives in a thread-local on the GL thread; the sensor thread and the
UI thread talk to it through a small mutex-protected struct that the next frame applies.

## Building

CI (`.github/workflows/pocketcarioca.yml`) builds debug and release APKs on every push and uploads
them as the `PocketCarioca-apk` artifact. Locally it needs the same toolchain as
`frontends/android`, then:

```bash
./frontends/pocketcarioca/build.sh --apk
```
