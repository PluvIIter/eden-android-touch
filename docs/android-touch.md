# Android touch workspace

The new workspace is implemented in `src/android/app/src/main/java/org/yuzu/yuzu_emu/overlay/touch`.
It uses independent configuration; existing native overlay positions are not migrated.

## Entry points

- Android settings → input overlay → edit layout opens the template manager without starting a game.
- Per-game settings opens the same manager with the base Title ID for association, independent copies and follow-default selection.
- The in-game overlay menu opens the shared editor or the template manager.
- Select a swipe area in the editor and choose single-control live debug to run the game with only that area enabled. Parameter edits remain a draft until the outer save-and-synchronize action.

## Modules

- `model`: serializable layouts, controls, screen configurations and independently referenced swipe presets; geometry is derived from saved screens without mutating them.
- `store`: validated, revision-checked atomic persistence; shared-reference operations; immutable edit transactions; dependency-aware JSON import/export with explicit collision resolution.
- `runtime`: Android-independent pointer capture, source aggregation, immediate turbo, stick arbitration, gestures and time-based relative swipe conversion. Gesture directions are recognized only among the directions that have a mapping; a swipe toward an unmapped direction resolves to the nearest mapped one.
- `ui`: one full-size workspace for offline editing, in-game editing and testing. Floating tools never reserve or resize canvas space. The main toolbar exposes every action; the editor shows one auxiliary tool window at a time, and opening another tool commits and closes the previous one. Tool windows open beside the selected control and step aside after a drag. Mapping uses a controller-shaped picker, controls use Eden button/card styles, shape switches with a single tap, and 16dp grid snapping is available from the toolbar.
- `TouchNativeSink` / `TouchNativePort`: injectable sink and production JNI adapter. Hardware and gyroscope routing are unchanged.
- `TouchDraftState`: file-backed recovery token in Android saved instance state, avoiding putting an entire library into a Binder bundle. Restoring a stale library revision is rejected.

The library is stored privately at `filesDir/touch/templates.json`. Draft recovery files and pending export recovery files are separate from the published library. Layout exports include screen configurations and referenced parameter dependencies, not game associations or editor window preferences.

## Automated tests

With an Android SDK/NDK and the upstream build dependencies installed:

```sh
cd src/android
./gradlew :app:testMainlineDebugUnitTest
./gradlew :app:assembleMainlineDebug
```

For the pure Kotlin regression suite without an Android SDK:

```sh
bash .ci/android/test-touch.sh
```

This script requires Java 17+ and curl. It downloads pinned Kotlin 1.9.20, serialization 1.6.3 and JUnit 4.13.2 dependencies from Maven Central into the ignored `.cache/touch-tests` directory. `TOUCH_TEST_CACHE` and `MAVEN_CENTRAL_URL` may select another writable cache or mirror. It does not invoke Android Gradle, load JNI or verify APK resources.

The current pure suite contains 125 tests covering persistence/revision conflicts, import dependency identity and complexity, recovery-file lifecycle, shared references, input source ownership, pointer cancellation, effective turbo scheduling, relative swipe sampling and nonlinear smoothing, gestures, native port cleanup, geometry restoration and default-layout coverage. The Robolectric suites add real-view interaction and render checks for single-window tools, controller-shaped mapping, placement/duplicate hand-off, grid snapping, control avoidance and save validation.

## GitHub Actions APK

Run **Android APK** manually from the repository's Actions tab on `master`, or use:

```sh
gh workflow run android-apk.yml --ref master
```

The workflow builds only the ARM64 `mainlineDebug` APK (Android 13+), signed with the checked-in development key. It does not build an AAB, run a variant matrix, or publish a release. Download the `eden-touch-arm64-<run number>` artifact from the run; artifacts expire after seven days. A newer run on the same branch cancels the older run.

Gradle dependencies/build outputs, CPM dependencies and a 2 GB ccache are reused between runs. Completed native build outputs are reused only for an exact native-source/toolchain fingerprint, so Kotlin-only changes can avoid most native compilation. No cache is required for correctness; a miss performs a cold build. SDK packages are pinned to the versions required by the app. Initial compilation can be slow; parallel compilation is limited to two jobs to fit a standard private-repository runner.

## Verification status

- Pure suite: 125 tests passed on the implementation host (the Windows port still reports the known `File.renameTo` replacement failures).
- New Kotlin sources: checked with the upstream ktlint 0.47.1 rules and disabled-rule settings.
- All new touch sources and the existing NativeInput declarations were directly type-checked against Android/AndroidX/Material class jars. This check used external compile-only symbols for generated resources and unrelated application dependencies; it is not a resource-link or whole-application build.
- The complete `mainlineDebug` application, including C++ libraries and Android resources, now builds successfully in GitHub Actions. Local SDK setup remains unavailable on the implementation host.
- `Android Touch UI` renders the actual Android `TouchWorkspace` with Robolectric native graphics and the real Eden theme/resources. Eight locale/theme/orientation cases produce toolbar/property/single-tool PNGs and check Chinese text, direct toolbar actions, accessible control semantics, complete property-card bounds, visible tabs and uncut Cancel/Done actions. Additional interaction cases drive real MotionEvents, form inputs, controls-list changes, single-window switching, grid snapping, properties avoidance, mapping-derived names and scoped undo/redo. These are Android View renders, not HTML mockups or physical-device screenshots.
- Android device input, keyboard/window behavior and actual game-camera feel still require the acceptance pass below.

## Device acceptance still required

1. Launch both offline and in-game editors; verify identical control coordinates, system-bar/cutout changes, portrait/landscape round trips and foldable windows.
2. Drag/resize while holding additional pointers. Confirm the single tool window keeps avoiding the selected control and an existing canvas pointer remains captured when crossing a tool boundary.
3. Verify mapping chip grids, accessibility, large fonts, keyboard-overlay behavior and Back ordering.
4. Exercise simultaneous movement, camera and buttons; overlapping-source releases; per-child turbo; group sliding; native touchscreen passthrough and physical-controller auto-hide.
5. Background, lose focus, open drawers, pause/resume, enter/leave PiP, stop/relaunch and change templates. Confirm zero stuck input or queued motion.
6. In isolated live debug, hold a camera finger while moving a slider with another. Confirm next-update changes, unchanged pointer ownership, no unrelated touchscreen output and correct debug rollback/save semantics.
7. Verify document-provider import/export, process recreation, corrupt/oversized files, dependency conflicts and storage failure handling.
8. Calibrate swipe deadzone, response curve and maximum speed for each game/device. The generic stick interface is an open-loop approximation, not a universal angle-accurate 1:1 camera interface.

Touch interface labels use Android English and Simplified Chinese resources, and the manager applies Eden's application-language context. Newly initialized built-in names are localized; existing user template/control names are intentionally preserved. Automated rendering checks do not replace physical-device interaction testing or actual game-camera calibration.
