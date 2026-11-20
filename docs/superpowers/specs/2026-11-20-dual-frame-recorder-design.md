# DualFrame: portrait + landscape recorder (design)

## Intent
Hold an Android phone upright and record one take. The app saves two videos at once: a portrait 9:16 file and a landscape 16:9 file. Both have the same quality, and a subject is the same size in both. The goal is to shoot once and publish to both vertical and horizontal platforms.

## Goals
- One record button. It produces `portrait.mp4` (1080×1920) and `landscape.mp4` (1920×1080) from the same frames, in sync.
- Same zoom: both crops are cut from one shared centered square of the sensor (Option A), at the same scale.
- Same quality settings for both: H.264, 30 fps, the same bitrate, and the same audio track.
- Ships as an installable APK.

## Non-goals (skipped for v1)
Zoom control or the ultra-wide lens, front camera, settings screen, 4K, 60 fps, HEVC, stabilization, pause/resume, gallery or playback screen.

## Anti-goals
- The two files drift out of sync, or one is missing frames the other has.
- Upscaled (soft) output passed off as 1080p.
- Losing the recording when the app is backgrounded mid-take. The files must be finalized and playable.

## Constraints
- One 4:3 sensor. With the phone upright, the usable square side equals the stream's short edge.
- No upscaling needs a source short edge ≥ 1920 px. Target a 2880×2160 or similar 4:3 stream at 30 fps.
- Two simultaneous 1080p30 H.264 encodes fit every modern Android encoder. Two 4K encodes do not.

## Approaches considered
| Approach | Trade-off | Verdict |
|---|---|---|
| **Live: Camera2 → OpenGL crop → 2× MediaCodec** | More code, but two files written live and in sync, with no wait after recording | **Picked** |
| Record one 4:3 file, then crop it twice (Media3 Transformer) | Less code, but waits after every take, needs double storage, and isn't "at a time" | Rejected |
| Two CameraX VideoCapture use cases | CameraX can't bind two VideoCapture outputs at once | Not possible |

Framing: the user picked **Option A, shared square, with 1080p output**, using the normal 1× lens.

## Design
- **Stack** (builder's call): Kotlin, a single Activity, plain Views, and platform APIs only (Camera2, GLES 2.0, MediaCodec, MediaMuxer, AudioRecord). minSdk 26, back camera.
- **Source:** the smallest 4:3 SurfaceTexture size whose short edge is ≥ 1920 and that sustains 30 fps. If none exists, use the largest 4:3 size at 30 fps, and show a "lower detail on this phone" notice.
- **Crop math** (in an upright frame of side S = the stream's short edge): portrait = centered `0.5625S × S`, landscape = centered `S × 0.5625S`. One pure function computes these crops and has a unit test.
- **Render loop:** each camera frame is drawn three times from the same external OES texture: to the screen preview (the portrait crop, with the landscape crop outlined), to the portrait encoder surface, and to the landscape encoder surface. The texture matrix and crop are applied in the shader, and rotation is baked in so the files play upright with no orientation flag.
- **Encoding:** two AVC encoders at 1080×1920 and 1920×1080, 30 fps, 16 Mbps, with a 1 s I-frame interval. One AAC encoder (48 kHz mono, 128 kbps) is fed by AudioRecord, and every audio sample is written to both muxers. Each muxer starts once its video and audio formats are known.
- **Timestamps:** both video files use the SurfaceTexture frame timestamp, so their frames line up.
- **Output:** both files are saved to `Movies/DualFrame/` via MediaStore as `DualFrame_<yyyyMMdd_HHmmss>_portrait.mp4` and `…_landscape.mp4`.
- **Lifecycle:** going to the background (onPause) stops recording and finalizes both files.
- **Permissions:** CAMERA and RECORD_AUDIO, requested on first launch.
- **UI:** a full-screen preview, a record/stop button, and an elapsed-time counter.

## Delivery
Source is committed to branch `ccr-8aff4383-pimgqs`. A debug-signed APK is built in this container and sent to Haris.

## Left to the builder
Package name (`com.dualframe.recorder`), Gradle/AGP versions, shader details, and threading layout.

## Known risk
There is no physical device here, so the APK compiles and unit tests run, but camera behaviour is only proven on a real phone.
