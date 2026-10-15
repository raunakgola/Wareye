# Wareye: changes

Backup of the original app: git commit `9c0d02d` in this folder (`git checkout 9c0d02d` to look at it, `git reset --hard 9c0d02d` to undo everything).

## APK size

| Build | Before | After |
|---|---|---|
| Release APK | 56,747,102 bytes (54.1 MiB, unsigned, no minify) | **7,074,671 bytes (6.7 MiB)**, R8 + resource shrinking, debug-signed |
| Debug APK | not measured | 11,353,846 bytes |

What is left in the release APK (compressed size inside the APK):

| Part | Size |
|---|---|
| `assets/model.tflite` (stored uncompressed, `noCompress "tflite"`) | 2.85 MB |
| `libLiteRt.so` (arm64) | 2.22 MB |
| `libLiteRtClGlAccelerator.so` (GPU, arm64) | 1.25 MB |
| `classes.dex` (CameraX, LiteRT Kotlin API, app) | 0.41 MB |
| resources, icons, signature | about 0.3 MB |

Size choices:
- `arm64-v8a` only. 32-bit phones and x86 emulators cannot install this build.
- `jniLibs.useLegacyPackaging = true`: native libraries are compressed inside the APK (about 3.5 MB smaller). Android extracts them at install, so the installed size is larger than the APK. Set it to `false` if you prefer a smaller install over a smaller download.
- No AppCompat, Material, ConstraintLayout or Compose in our code: plain framework views with ViewBinding, `android:Theme.Material.Light`. (CameraX `camera-view` still pulls AppCompat in transitively; R8 removes most of it.)
- CameraX **1.4.2**, not 1.6.x: 1.6 adds `camera2-pipe` and grew the dex from 0.89 MB to 1.41 MB uncompressed.
- LiteRT's AI-pack download dependencies (`ai-delivery`, Play services, Guava, coroutines-guava) are excluded. They are only used by `ModelProvider`/`ModelSelector`, which the app does not call. The two `FOREGROUND_SERVICE` permissions LiteRT declares for that path are removed in the manifest.
- System fonts (`sans-serif-black` for 800 weights, `monospace` for the HUD). Plus Jakarta Sans / DM Mono were not added.

## Model and inference

- One model: `yolo26n_w8a32.tflite` (2,853,647 bytes, md5 `4f755f57...`) as `assets/model.tflite`. The old 44.5 MB model, `labels.txt` and the `tensorflow-lite` / `tensorflow-lite-support` dependencies are gone.
- LiteRT `com.google.ai.edge.litert:litert:2.1.5`, `CompiledModel`, loaded straight from assets (`CompiledModel.create(AssetManager, "model.tflite", options, env)`), so no copy to `filesDir` is needed.
- Accelerator ladder in `Engine`: GPU first (with serialized program cache in `codeCacheDir`, key `yolo26n_w8a32_v1`). If GPU compile **or** the zero-input warm-up run throws, it falls back to CPU (`availableProcessors` clamped to 1..4 threads). The UI shows what actually runs (`YOLO26n · GPU` or `· CPU`).
- Input: the camera frame is rotated upright, letterboxed into 640x640 (aspect kept, centered, pad 114 gray), RGB / 255, written NCHW or NHWC depending on the real input shape. Boxes are mapped back through the letterbox and the rotation; front camera boxes are mirrored to match the mirrored preview.
- Output: per anchor, the best class **among the selected classes only** (filter before NMS), confidence filter, class-aware greedy NMS at the IoU slider value, max 100 boxes.
- Reused buffers: frame Bitmap, 640x640 Bitmap + Canvas, `IntArray` pixels, `FloatArray` input, candidate arrays. One unavoidable allocation per frame: LiteRT 2.1.5's `TensorBuffer.readFloat()` returns a new 84,000-float array; the API has no read-into-existing-array call.
- Model stays loaded process-wide (`Engine`), so going back and forth between screens does not reload it. Home starts loading it in the background, so the loading screen usually never shows.

### Real tensor names, shapes and value ranges

Checked on the desktop with `ai-edge-litert` (Python) on the exact file, using `test_images/b.jpg`:

| | |
|---|---|
| Input | `serving_default_args_0`, shape `[1, 3, 640, 640]`, float32, **NCHW** |
| Output | `serving_default_output_0_output`, shape `[1, 10, 8400]`, float32, **channels-first** (4 box + 6 class rows) |
| Signature | `serving_default`: input `args_0` -> output `output_0` |
| Box channels | min 0.003, max 1.079: **normalized** (cx, cy, w, h as a fraction of 640). The app multiplies by 640. |
| Class channels | min 6.8e-35, max 0.957: **already sigmoid**. The app does not apply a sigmoid. |
| Test image | 43 anchors over 0.25, top scores spoon 0.957 / 0.955, i.e. two spoons before NMS |

The app does not hard-code these. It reads the real shape at startup, and on the first camera frame it decides box scaling (all box values <= 2 means normalized) and sigmoid (any class value outside 0..1). It logs both decisions. The desktop run used the CPU path of a different LiteRT build, so **confirm on the phone with the Logcat lines below**.

## Class registry

- `ClassRegistry.kt` is the single source: `DetClass(id, label, category, color)`. Ids are the model's class indices from `kitchenware.yaml` (`plate, spoon, fork, knife, cup, glass`), **not** the design's order.
- Colors: the design's six colors, kept with their class names (fork red `#FF6B5E`, knife blue `#4DB8FF`, plate yellow `#FFC93C`, spoon green `#7BE07F`, cup purple `#C58CFF`, glass teal `#2EE6D0`), stored on the class id. The design's own id numbers (fork = 0, ...) did not match the model, so they were not used. New ids get an OKLCH color: hue `(id * 137.5) mod 360`, C 0.14, L 0.78 / 0.70 / 0.86 per block of 12 ids.
- Categories: Cutlery (fork, knife, spoon), Tableware (plate, cup, glass). Adding a class = one line in the list.
- Selection: `Set<Int>` in SharedPreferences (`Settings.kt`), unknown ids dropped silently. Recent = the last selection you started a scan with.

## Screens

- **Home**: wordmark, model chip (ready / loading / CPU / failed states, opens Settings), headline, 208 dp scan ring.
- **Class picker**: collapsible category headers with `n / total` and per-category Select all / Clear, `Selected: n of 6` counter, global Select all / Clear, Recent row with Use again, text-only tiles (2 columns, 64 dp, selected = tint + 2 dp border + check badge + bold). Past 12 classes it switches to dense chips, adds the search field and collapses categories without a selection. Start scanning (N); at 0 it shows the disabled look but stays focusable and says why.
- **Live scan**: edge-to-edge preview (FILL_CENTER, 4:3 analysis to match the preview's field of view), overlay, back button, HUD pill, bottom Forest panel with the count title, count chips (dashed when 0), controls.
  - HUD pill: `12.9 FPS · 69.7 ms`, tap for the expanded card: model + accelerator, detections, FPS and total ms, pre / inference / post ms, P50 and P95 of total over the last 100 frames, **App RAM** (this process's PSS). UI refresh 4 Hz; RAM sampled once a second off the UI thread.
  - FPS = 1e9 / smoothed frame interval (`0.95 old + 0.05 new`) between processed frames.
  - Low FPS: pill turns amber with a warning icon after 3 s under 8 FPS, clears after 3 s at 10+. On CPU a banner offers **Switch to GPU** (tap the text to dismiss; it returns only after a recovery).
  - Nothing detected for 2 s: hint card with **Lower to 0.15** (hidden if already at or below 0.15).
  - Controls: torch toggle (disabled without a flash), flip (disabled with one camera), pause (freezes the shown frame with a snapshot and stops analysis; boxes drop to 60%), zoom (tap cycles 1.0 / 2.0 / 5.0x clamped to the camera's range; pinch to zoom on the preview), tune (sliders for confidence and IoU, live, defaults 0.25 / 0.70; "More settings" opens the sheet).
- **Settings sheet**: CPU / GPU (GPU default; changing it reloads the model and shows the loading screen), confidence and IoU sliders, Show metrics, Reset, Done. No model picker.
- **Status screens** (inside the scan screen, so the model and camera survive): loading with steps (model file, runtime, compiling GPU delegate / preparing CPU, starting camera), camera permission off (Open settings, re-checks on return; Not now), model failed with the error details (**Run on CPU**, **Try GPU again**, **Copy error details**). If GPU fails but CPU works, the CPU model is already warm, so Run on CPU continues instantly.
- **Overlay**: 2 dp class-color stroke + 1 dp 45% black keyline, `fork 87%` label chips (20 dp, 6 dp padding, 12 sp bold, `#121210` text), placement: inside top-left 2 dp from the stroke, then 22 dp steps down, then below, then above; right-aligned at the right edge; boxes under 44 dp tall go straight to below/above; clamped to the preview; labels never overlap (a label with no free slot is hidden); only the 12 highest-confidence boxes get labels. Boxes ease to new positions with an 80 ms time constant; labels follow.
- **Rotation**: `configChanges` on all activities, so rotation never recreates the activity, reloads the model or restarts the camera. Camera target rotation follows the display. Landscape moves the panel to a 280 dp side panel on the right and the preview fills the rest.

## Build setup changes

- Gradle 8.4 -> **9.8.1**, AGP 8.3.1 -> **9.3.3** (the newest line Android Studio 2026.1.3 supports; 9.4 fails to sync) (built-in Kotlin; Kotlin compiler pinned to 2.3.21 because LiteRT 2.1.5 is built with Kotlin 2.3), compileSdk/targetSdk 36, minSdk 23 (LiteRT minimum), Java 17. Gradle 9.8 runs on JDK 17 through 25, so Android Studio's bundled JDK works.
- Release build is **signed with the debug key** so it installs over USB. Put your own `signingConfig` in before publishing.
- Removed: template unit/instrumented tests (their test dependencies were the only users), all `ic_class_*` drawables, old backgrounds, night theme, backup XML rules, unused adaptive launcher layers. The launcher icon webps are kept.
- Package / applicationId renamed to `com.raunak.wareye` (was `com.surendramaran.yolov8tflite`), version 1.0.0 (versionCode 1), APK files named `Wareye-debug.apk` / `Wareye-release.apk`. Launcher icon is a vector adaptive icon (cream eye, red pupil, forest background). Android sees this as a new app: uninstall the old one.

## Not done, or not tested

- **Nothing was run on a phone.** No device was connected over adb, and no arm64 emulator image is installed. Everything below the build step is unverified on hardware: GPU delegate, FPS, camera/overlay alignment, rotation, pinch zoom, torch.
- The GPU delegate is **not confirmed to work**. Only trust it if Logcat shows `compiled on GPU`.
- Optional items skipped to keep the app small: the separate Metrics sheet with the sparkline, the share button, swipe-to-dismiss on the low-FPS banner (tap instead), sticky category headers.
- TalkBack: tiles announce as checkboxes with state, controls have labels, the detection summary is set on the count title at most once per second, but it is not a live region (it would announce at 4 Hz).
- The preview/analysis alignment assumes both use the same 4:3 sensor crop. On a phone whose analysis stream falls back to another aspect ratio, boxes could be slightly offset.
- Two Kotlin deprecation warnings remain in the picker (`announceForAccessibility`, `AccessibilityNodeInfo.isChecked`); both still work.
- No latency numbers are quoted for the phone. The server-CPU numbers in the training repo do not apply. Read the HUD.
