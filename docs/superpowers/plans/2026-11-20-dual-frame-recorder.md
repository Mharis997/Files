# DualFrame Recorder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an Android APK that records one take into a portrait 1080×1920 MP4 and a landscape 1920×1080 MP4 at the same time, both cropped from one shared centered square.

**Architecture:** Camera2 streams one 4:3 SurfaceTexture. A GL thread draws each frame three times: to the preview (the square, with outlines drawn by a View overlay), to the portrait encoder surface, and to the landscape encoder surface. Crop and sensor rotation are baked into the texture matrix. Two AVC encoders and one AAC encoder run in async mode. A synchronized `MuxerPair` writes video to its own file and audio to both files.

**Tech Stack:** Kotlin 2.0.21, AGP 8.7.3, Gradle 8.14.3, compileSdk/targetSdk 35, minSdk 29, JUnit 4.13.2 (tests only). Platform APIs only.

**Spec:** `docs/superpowers/specs/2026-11-20-dual-frame-recorder-design.md`

## Global Constraints
- Package `com.dualframe.recorder`, app name "DualFrame".
- Output: H.264, 30 fps, 16 Mbps, I-frame interval 1 s, 1080×1920 and 1920×1080. AAC-LC, 48 kHz mono, 128 kbps.
- Files: `Movies/DualFrame/DualFrame_<yyyyMMdd_HHmmss>_portrait.mp4` and `…_landscape.mp4`, written via MediaStore with `IS_PENDING` set during the write.
- No third-party runtime dependencies.
- minSdk raised from the spec's 26 to 29: MediaStore `RELATIVE_PATH` is API 29+, and this avoids storage permissions. The S23 runs Android 13+.

## Review Focus
1. **Sensor orientation 90 vs 270.** Wrong rotation means sideways or upside-down video. Covered by `texMatrix` corner tests (Task 1).
2. **Phone without a ≥1920 4:3 stream.** Expect the fallback size plus a visible notice, not a crash. Covered by `chooseSourceSize` tests (Task 1).
3. **A/V clock mismatch.** Camera timestamps may be BOOTTIME (`SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`) or MONOTONIC. Audio must use the same clock, or the audio drifts by the device's uptime gap. Task 3 picks the clock from that characteristic.
4. **Backgrounding mid-take.** Both files must still be finalized and playable. Task 3 manual check.
5. **Video frames that arrive before the muxer starts.** If they are dropped, the file starts without a keyframe. `MuxerPair` queues them (Task 3).

---

### Task 1: Project scaffold + crop math

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `app/build.gradle.kts`, Gradle wrapper (`gradle wrapper --gradle-version 8.14.3`), `.gitignore`
- Create: `app/src/main/AndroidManifest.xml` (CAMERA, RECORD_AUDIO; single portrait-locked activity)
- Create: `app/src/main/java/com/dualframe/recorder/CropMath.kt`
- Test: `app/src/test/java/com/dualframe/recorder/CropMathTest.kt`

**Interfaces (produces):**
- `data class Crop(val x: Float, val y: Float, val w: Float, val h: Float)`: normalized rect in the upright frame, with GL convention origin bottom-left.
- `fun squareCrops(uprightW: Int, uprightH: Int): Pair<Crop, Crop>` returns (portrait, landscape) centered in square S = min(W, H).
- `fun chooseSourceSize(sizes: List<Pair<Int, Int>>, minFrameNs: (Pair<Int, Int>) -> Long): Pair<Pair<Int, Int>, Boolean>?` takes sensor-orientation (landscape) sizes and returns (size, lowDetail). It keeps only 4:3 sizes with minFrameNs ≤ 33_333_334. It returns the smallest-area size with short edge ≥ 1920 and lowDetail=false; otherwise the largest kept size with lowDetail=true; otherwise null.
- `fun texMatrix(crop: Crop, sensorOrientation: Int): FloatArray` returns a column-major 4×4 matrix that maps quad UV (0..1) → upright UV within `crop` → sensor UV. The caller multiplies the SurfaceTexture matrix on the left. Upright→sensor mapping: 0:(u,v); 90:(1−v,u); 180:(1−u,1−v); 270:(v,1−u).

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun squareCrops_s23Upright() {
  val (p, l) = squareCrops(3000, 4000)
  assertCrop(Crop(0.21875f, 0.125f, 0.5625f, 0.75f), p)
  assertCrop(Crop(0f, 0.2890625f, 1f, 0.421875f), l)
  // same zoom: source px per output px equal
  assertEquals(p.w * 3000 / 1080, l.w * 3000 / 1920, 1e-4f)
}
@Test fun chooseSourceSize_picksSmallestAtLeast1920() =
  assertEquals((2560 to 1920) to false, chooseSourceSize(listOf(4000 to 3000, 2560 to 1920, 1440 to 1080, 1920 to 1080)) { 33_333_333L })
@Test fun chooseSourceSize_skipsSlowSizes() =
  assertEquals((4000 to 3000) to false, chooseSourceSize(listOf(4000 to 3000, 2560 to 1920)) { if (it.first == 2560) 50_000_000L else 33_333_333L })
@Test fun chooseSourceSize_fallsBackLowDetail() =
  assertEquals((1440 to 1080) to true, chooseSourceSize(listOf(1440 to 1080, 640 to 480, 1920 to 1080)) { 33_333_333L })
@Test fun chooseSourceSize_noneIsNull() = assertNull(chooseSourceSize(listOf(1920 to 1080)) { 33_333_333L })
@Test fun texMatrix_rotations() {
  // full-frame crop: upright top-left (0,1) maps to sensor (0,0) at 90, to (1,1) at 270
  val full = Crop(0f, 0f, 1f, 1f)
  assertUv(0f, 0f, texMatrix(full, 90), 0f, 1f)
  assertUv(1f, 1f, texMatrix(full, 270), 0f, 1f)
  assertUv(0f, 1f, texMatrix(full, 0), 0f, 1f)
  // crop applied before rotation: quad (0,0) → upright (crop.x, crop.y)
  assertUv(1f - 0.125f, 0.21875f, texMatrix(Crop(0.21875f, 0.125f, 0.5625f, 0.75f), 90), 0f, 0f)
}
```
(`assertUv(expS, expT, m, u, v)` multiplies `m` by `(u, v, 0, 1)`.)

- [ ] **Step 2:** Install the SDK (cmdline-tools → `platforms;android-35`, `build-tools;35.0.0`). Run `./gradlew :app:testDebugUnitTest`. Expected: FAIL (unresolved references).
- [ ] **Step 3:** Implement `CropMath.kt` per the Interfaces above.
- [ ] **Step 4:** Run `./gradlew :app:testDebugUnitTest`. Expected: PASS (6 tests).
- [ ] **Step 5:** Commit `feat: scaffold project and crop math`.

### Task 2: Camera preview through GL

**Files:**
- Create: `app/src/main/java/com/dualframe/recorder/GlRenderer.kt`, `FrameOverlay.kt`, `MainActivity.kt`, `app/src/main/res/layout/activity_main.xml`

**Interfaces:**
- Consumes: `chooseSourceSize`, `squareCrops`, `texMatrix`, `Crop`.
- Produces:
  - `class GlRenderer(previewSurface: Surface)` runs on its own HandlerThread. It provides `val surfaceTexture: SurfaceTexture` (call `setDefaultBufferSize` before use), `fun setCrops(square: Crop, portrait: Crop, landscape: Crop, sensorOrientation: Int)`, `fun startEncoding(portrait: Surface, landscape: Surface)`, `fun stopEncoding(done: () -> Unit)` (runs `done` on the GL thread after the last encoder frame is drawn), and `fun release()`.
  - Each frame: `updateTexImage`, then draw the square crop to the preview. While encoding, also draw portrait/landscape to their EGL surfaces (created with `EGL_RECORDABLE_ANDROID`) with `eglPresentationTimeANDROID(surfaceTexture.timestamp)`.
  - `FrameOverlay : View` draws the portrait outline (blue) and landscape outline (orange) centered in its square bounds: 0.5625×1 and 1×0.5625.
- Layout: a FrameLayout holding a square SurfaceView with a `FrameOverlay` on top, a `Chronometer`, a record `Button`, and a `TextView` notice (shown when `lowDetail`).

- [ ] **Step 1:** Implement. The camera is the first `LENS_FACING_BACK` id, using `TEMPLATE_RECORD` with `CONTROL_AE_TARGET_FPS_RANGE` = [30,30]. Permissions are requested on launch. If they are denied, or `chooseSourceSize` returns null, show the reason in the notice and disable record.
- [ ] **Step 2:** Run `./gradlew :app:assembleDebug :app:testDebugUnitTest`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 3:** Commit `feat: camera preview with shared-square crop`.

### Task 3: Dual recording

**Files:**
- Create: `app/src/main/java/com/dualframe/recorder/Recorder.kt`
- Modify: `MainActivity.kt` (record button, Chronometer, stop in `onPause`)

**Interfaces:**
- Consumes: `GlRenderer.startEncoding/stopEncoding`.
- Produces: `class Recorder(context: Context, useBootTimeClock: Boolean)` with `fun start(): Pair<Surface, Surface>` (portrait and landscape encoder input surfaces) and `fun stop(onSaved: (ok: Boolean) -> Unit)`.
- Internal `MuxerPair` (`@Synchronized`): one MediaMuxer per file, opened on the MediaStore `"rw"` FileDescriptor. A muxer starts when its video format and the audio format are known. Until then it keeps copies of video samples in a queue, and drops early audio. Each muxer stops after its video EOS and the audio EOS, then `IS_PENDING=0`. If a write fails, the MediaStore row is deleted and `onSaved(false)` is called.
- Audio: AudioRecord(MIC, 48000, mono, PCM16) reads on the audio encoder's async `onInputBufferAvailable` (its own HandlerThread). PTS = `elapsedRealtimeNanos` when `useBootTimeClock` (from `SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME`), else `System.nanoTime()`, divided by 1000.
- Stop order: `GlRenderer.stopEncoding`, then in `done` call `signalEndOfInputStream` on both video encoders and queue audio EOS. Release when all three EOS have been written.

- [ ] **Step 1:** Implement.
- [ ] **Step 2:** Run `./gradlew :app:assembleDebug :app:testDebugUnitTest`. Expected: BUILD SUCCESSFUL, tests PASS.
- [ ] **Step 3:** Commit `feat: record portrait and landscape files at once`, then push.
- [ ] **Step 4 (manual on the S23):** Record for 10 s and check that both files exist in `Movies/DualFrame`, play upright, have the same duration ±0.1 s, and keep subjects the same size. Record, press Home mid-take, and check that both files play.
