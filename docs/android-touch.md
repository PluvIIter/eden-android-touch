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
- `runtime`: Android-independent pointer capture, source aggregation, immediate turbo, stick arbitration, gestures and time-based relative swipe conversion.
- `ui`: one full-size workspace for offline editing, in-game editing and testing. Floating tools never reserve or resize canvas space.
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

The current suite contains 96 tests covering persistence/revision conflicts, import dependency identity, shared references, input source ownership, pointer cancellation, turbo, relative swipe sampling, gestures, native port cleanup, geometry restoration and serializable editor/tool state.

## Verification status

- Pure suite: 96 tests passed on the implementation host.
- New Kotlin sources: checked with the upstream ktlint 0.47.1 rules and disabled-rule settings.
- All new touch sources and the existing NativeInput declarations were directly type-checked against Android/AndroidX/Material class jars. This check used external compile-only symbols for generated resources and unrelated application dependencies; it is not a resource-link or whole-application build.
- Existing host integration changes in EmulationFragment, EmulationActivity, InputOverlay and settings were reviewed, but not whole-app compiled.
- Full Gradle configuration was blocked by the host sandbox denying creation of the Android plugin's user configuration directory. No APK was produced, and no Android SDK/NDK or attached device was available for validation.

## Device acceptance still required

1. Launch both offline and in-game editors; verify identical control coordinates, system-bar/cutout changes, portrait/landscape round trips and foldable windows.
2. Drag/resize while holding additional pointers. Confirm every floating tool disappears from hit testing and the final old UP cannot click restored tools.
3. Verify mapping chip grids, accessibility, large fonts, keyboard-overlay behavior and Back ordering.
4. Exercise simultaneous movement, camera and buttons; overlapping-source releases; per-child turbo; group sliding; native touchscreen passthrough and physical-controller auto-hide.
5. Background, lose focus, open drawers, pause/resume, enter/leave PiP, stop/relaunch and change templates. Confirm zero stuck input or queued motion.
6. In isolated live debug, hold a camera finger while moving a slider with another. Confirm next-update changes, unchanged pointer ownership, no unrelated touchscreen output and correct debug rollback/save semantics.
7. Verify document-provider import/export, process recreation, corrupt/oversized files, dependency conflicts and storage failure handling.
8. Calibrate swipe deadzone, response curve and maximum speed for each game/device. The generic stick interface is an open-loop approximation, not a universal angle-accurate 1:1 camera interface.

New UI labels currently use English; translation into the application's locale resources remains a separate localization pass. Automated tests do not establish visual conformance or actual game-camera feel.
