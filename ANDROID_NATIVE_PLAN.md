# Candela — Android Native Implementation Plan

**Date:** 2026-09-04
**Branch:** `android-native` (created; no files under `/src` touched)
**Sources of truth:** `optical-link-audit.md` (physics = spec), `PROJECT_STATUS_REPORT.md` (frozen protocol)
**Status:** planning + environment only. **No project code written yet.**

---

## 1. Environment setup — confirmation

### 1.1 What is installed and verified

| Tool | Version | Location | Verified by |
|---|---|---|---|
| JDK (Java 17) | OpenJDK **17.0.9** (Temurin 17.0.9+9), 64-Bit Server VM | `/home/user/toolchain/jdk17` | `java -version` OK |
| Kotlin compiler | **kotlinc-jvm 2.4.10** (running on JRE 17.0.9+9) | `/home/user/toolchain/kotlinc` | compiled + ran a Kotlin `main` to `h.jar` OK |
| Gradle | **not installed** | — | `services.gradle.org` unreachable |
| Android SDK / cmdline-tools / platform-tools | **not installed** | `/home/user/toolchain/android-sdk` (empty placeholder) | `dl.google.com` unreachable |

Env file written at `/home/user/toolchain/env.sh` (source before any build):

```sh
export JAVA_HOME=/home/user/toolchain/jdk17
export KOTLIN_HOME=/home/user/toolchain/kotlinc
export ANDROID_SDK_ROOT=/home/user/toolchain/android-sdk
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$JAVA_HOME/bin:$KOTLIN_HOME/bin:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"
```

### 1.2 Honest limitation of this sandbox (must be read before planning code)

Egress is **allow-listed**, not open. Measured reachability:

| Host | Result |
|---|---|
| `registry.npmjs.org`, `pypi.org`, `files.pythonhosted.org`, `github.com`, `codeload.github.com` | **200** |
| `dl.google.com` (Android SDK repo), `developer.android.com` | blocked |
| `services.gradle.org`, `gradle.org` | blocked |
| `repo1.maven.org`, `repo.maven.apache.org`, `maven.google.com`, `plugins.gradle.org`, `jitpack.io` | blocked |
| `api.adoptium.net`, `corretto.aws`, `cdn.azul.com`, `download.java.net` | blocked |
| `objects.githubusercontent.com` (GitHub **release assets**) | blocked — so GitHub releases cannot be downloaded even though `github.com` resolves |
| Debian/Ubuntu/Aliyun/Tsinghua apt mirrors, conda, crates.io, jsdelivr/unpkg | blocked |

Consequences, stated plainly:

1. `apt-get install openjdk-17-jdk` is impossible. The JDK above was obtained from **PyPI** (`jdk4py`, a Temurin 17.0.9 jlink runtime); Kotlin from **npm** (`kotlin-compiler@2.4.10`).
2. That JDK is a **jlink'd runtime**: it has `java`, `keytool`, `jcmd`, but **no `javac`, no `jar`, no `jdk.compiler` module**. Kotlin/JVM compiles fine (kotlinc carries its own compiler and bundles `kotlin-stdlib`), but Java sources and Gradle's own bootstrap cannot compile here.
3. **Gradle cannot be installed** (distribution host blocked) and, more fundamentally, **cannot be run usefully**: an Android build resolves AGP, Kotlin plugin, AndroidX, Compose and ZXing from `maven.google.com` + `repo1.maven.org`, both blocked. `sdkmanager` cannot fetch `platforms;android-35`, `build-tools`, `platform-tools` from `dl.google.com`.
4. Therefore **an APK cannot be assembled or run in this sandbox**, and no emulator/device is attached.

**What this means for the work:** exactly the situation `PROJECT_STATUS_REPORT.md` §1.2 already documented ("this execution environment has no JDK, no Android SDK, no Gradle") — now improved to **JDK 17 + Kotlin 2.4.10 present and working**. The strategy below is built around that: the whole protocol layer is **pure Kotlin/JVM with zero Android imports**, so it can be compiled and unit-tested *here today* with `kotlinc`, against golden vectors emitted by the existing TypeScript POC. Everything that genuinely needs the Android SDK (Camera2, SurfaceView, Compose, PowerManager) is authored against the frozen API surface and compiled on a machine with SDK access.

### 1.3 To fully unblock (one of these is required from you)

- **A — open egress:** allow-list `dl.google.com`, `maven.google.com`, `repo1.maven.org`, `services.gradle.org`, `plugins.gradle.org`, `objects.githubusercontent.com`. Then I install cmdline-tools + platform-tools + build-tools + `platforms;android-35` + Gradle 8.x and build a real APK here.
- **B — CI build:** keep the sandbox as-is; author the project here and let **GitHub Actions** (which has JDK, SDK, and network) run `./gradlew assembleDebug` and publish the APK artifact. This works today and is my recommendation if egress can't change.
- **C — local build:** you build in Android Studio; I keep the sandbox as protocol-layer + test authority.

---

## 2. Architectural rules I am bound by (extracted from the two documents)

These are non-negotiable constraints, not preferences. Every step in §3 is traceable to one.

| # | Rule | Source |
|---|---|---|
| R1 | **Never decode a frame that cannot decode.** Gate first (motion → blur → contrast), then ROI-decode. Ungated full-frame decode = 2–4 W, throttle in 5–10 min = the #1 product killer. | audit §1.2 C2–C4, §8 |
| R2 | **Prevent, don't repair.** No deconvolution, no OpenCV, no ML. Freeze AE/AF/AWB at a calibration pose (C1). | audit §1.2 C1 |
| R3 | **Phase-independence, not clock sync.** Hold ≥ 6 × max(screen period, exposure+readout) ≈ 100 ms @ 60 Hz. Force 60 Hz, disable LTPO. | audit §2, PSR §5.3 |
| R4 | **ZXing-core only. No ML Kit, no Play Services.** "Offline" dies the moment GMS is required. | audit §1.2 C4, §6.2 |
| R5 | **Per-block Ed25519 + CRC32 + whole-file SHA-256 + SAS.** Mandatory, ~2.4% overhead. Verify order: CRC32 → Ed25519 → id bounds → fountain insert. | audit §5, PSR §2.3 |
| R6 | **Wire format and fountain RNG are frozen and must be byte-identical to the TS POC** (`mulberry32((symbolId+1)*0x9E3779B9)`, robust soliton c=0.12 δ=0.05, systematic LT). | PSR §2.3–2.4 |
| R7 | **Zero allocation on the render path.** QR plane = `SurfaceView`/`View.onDraw` blitting pre-rendered bitmaps. Compose is the UI shell only, never the QR surface. | audit §8 kill #5, PSR §5.3 |
| R8 | **Graceful refusal, never corruption.** Outside the envelope → pause/refuse. Hard refuse below CR ~5:1. No partial file write, ever. | audit §7, §4 |
| R9 | **Thermal governor is a product feature.** `PowerManager` thermal listener drives fps, ROI downsample, duty-cycle, pause, abort+resume. | audit §3, PSR §5.4 |
| R10 | **Clean Architecture layering** `app → domain → optical-render / optical-camera → platform`; domain is pure Kotlin, no Android imports. | PSR §2.9 |
| R11 | **Do not touch `/src`.** The TS tree stays as protocol reference + golden-vector generator. | task constraint |

---

## 3. Step-by-step implementation plan

Ordered so that **everything provable in this sandbox happens first**, and each stage has a stated exit test.

### Stage 0 — Repo scaffolding and golden vectors (sandbox-provable)

**0.1 Module layout** (new top-level `android/`, `/src` untouched):

```
android/
├── settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml
├── core-protocol/      ← pure Kotlin/JVM, ZERO android imports   (R10)
├── core-vision/        ← pure Kotlin gates on ByteArray luma, no android imports
├── optical-camera/     ← Android lib: Camera2 + ImageReader + ZXing ROI
├── optical-render/     ← Android lib: SurfaceView sender, Choreographer pacing
├── platform/           ← Android lib: PowerManager, wakelock, MediaStore export
└── app/                ← Compose shell, navigation, coach HUD, SAS compare
```

`core-protocol` and `core-vision` are `kotlin("jvm")` modules precisely so they compile with the sandbox `kotlinc` and run as plain JVM tests with no SDK.

**0.2 Golden vectors from the TS POC.** Add `tools/gen-golden.ts` (new file, **outside `/src`** — it imports from `/src` read-only) that emits `android/core-protocol/src/test/resources/golden/*.json`: CAL/HEADER/DATA frame hex for fixed inputs, fountain neighbour lists for symbol ids 0..2000 at several `k`, a full 4 KB encode/decode transcript, and the SAS digits for a fixed pubkey. This is how R6 becomes testable rather than aspirational.

**Exit test:** `npx tsx tools/gen-golden.ts` produces vectors; TS POC still typechecks (`npx tsc --noEmit`).

### Stage 1 — Kotlin protocol port (1:1, sandbox-provable)

Port `/src/protocol/*` to `android/core-protocol` with **identical byte semantics**:

| TS file | Kotlin target | Port notes |
|---|---|---|
| `constants.ts` | `Constants.kt` | magic `CL`, version 1, kinds, density profiles, fountain params as `const val` |
| `bytes.ts` | `Bytes.kt` | big-endian u16/u32 helpers over `ByteArray`; use `ByteBuffer.BIG_ENDIAN`, no `String` intermediates |
| `crc32.ts` | `Crc32.kt` | delegate to `java.util.zip.CRC32` and assert equality against golden vectors |
| `crypto.ts` | `Crypto.kt` | **Ed25519**: JDK 17 has `EdDSA` in `java.security` (`Ed25519` KeyPairGenerator/Signature) — no third-party dep, no GMS. SHA-256 via `MessageDigest`. SAS = first 8–10 decimal digits of SHA-256(pubkey), same derivation as TS |
| `frames.ts` | `Frames.kt` | `sealed interface Frame { Cal, Header, Data }`; `encode(): ByteArray`, `decode(ByteArray): Result<Frame>`. Sign over body-before-signature; CRC over body+signature (PSR §2.3) |
| `fountain.ts` | `Fountain.kt` | systematic LT; `Mulberry32` PRNG must be reproduced **exactly** — 32-bit unsigned arithmetic in Kotlin needs `Int`/`UInt` care and `ushr`; robust soliton c=0.12 δ=0.05; encoder count `ceil(k*1.55)+16`; decoder = peel + bounded Gaussian elimination (≤80 unknowns, pool ≤96) |
| `session.ts` | `SessionState.kt` | `IDLE → CALIBRATING → PAIRING → SENDING/RECEIVING → VERIFYING → COMPLETE/ABORTED/PAUSED` as a sealed class + explicit legal-transition table. This is the domain model (R10) |
| — | `Dedup.kt` | `BitSet(65536)`, allocation-free after init (audit §6.6) |

Ed25519 note: JDK 17's built-in `Ed25519` is the reason `core-protocol` stays dependency-free; on Android, `java.security` Ed25519 is only guaranteed API 33+, so the module exposes a `SignatureProvider` interface with a JDK impl (desktop/tests) and a **BouncyCastle/Tink-free fallback** implementation selected at runtime for API 26–32. Decided before code, because it changes the module's API shape.

**Exit test (runs in this sandbox, today):** `kotlinc` compiles `core-protocol` and a `main()` test harness; harness loads the Stage-0 golden JSON and asserts byte equality on every frame, every fountain neighbour list, the SAS digits, and a full 4 KB + 32 KB fountain round-trip at 22% simulated drop with SHA-256 match — mirroring the TS selftest numbers (k=86/sent 138 and k=683/sent 1154). **If a single byte differs, Stage 2 does not start.**

### Stage 2 — Vision gates as pure Kotlin (sandbox-provable)

Port `/src/vision/gates.ts` to `core-vision`, operating on a raw **luma `ByteArray` + stride + ROI rect** (never `Bitmap`, so it is testable off-device and allocation-free on-device):

- `RoiTracker` — last known QR rect, fallback centre 40–64%.
- `downsampleLuma(...)` — integer-stride to 128×128 into a **reused** buffer (~0.5 ms).
- `blurScore` — Laplacian/`variance(gauss3(g) − g)` proxy (~0.5 ms).
- `contrastRatio` — `(p99 − p1)/255` from a 256-bin histogram.
- `MotionGate` — magnitude of `TYPE_LINEAR_ACCELERATION`, admit while `|a| < 0.3 m/s²`; the sensor subscription itself lives in `optical-camera`, the maths lives here.
- `GateThresholds` — **learned at the calibration pose**, not hard-coded (the web's relaxed `BLUR_MIN=1.2 / CONTRAST_MIN=0.08` are demo values and are explicitly not carried over).
- `CalibrationVerdict` — `OK | WARN(reason) | REFUSE(reason)`; **hard refuse below CR ~5:1** (R8).

**Exit test:** synthetic luma fixtures (sharp QR, box-blurred QR, low-contrast QR, banded QR) generated in-sandbox; assert the gate admits sharp, rejects the other three, and that `gate+downsample` runs under ~3 ms per frame on the JVM.

### Stage 3 — Gradle + Android skeleton (needs egress or CI)

- Gradle wrapper 8.x, AGP 8.x, Kotlin 2.x, `compileSdk 35`, `minSdk 26`, JVM target 17, version catalog.
- Dependencies: `com.google.zxing:core` **only** for decoding (R4); Compose BOM; `androidx.lifecycle`; **no** `play-services-*`, **no** ML Kit, **no** CameraX for the hot path (CameraX cannot express AE/AF freeze the way C1 requires — Camera2 direct).
- Manifest: `CAMERA` permission, **no** `INTERNET` permission at all — the strongest possible proof of the offline claim.
- CI workflow: JDK 17 + `android-actions/setup-android`, `gradle verify` then `:app:assembleDebug`, upload APK. **Written and parked at `android/ci/android.yml`** — the pushing GitHub App lacks the `workflows` permission, so it needs one manual move into `.github/workflows/`; see `android/ci/README.md`.

**Exit test:** CI green; `:core-protocol:test` passes in CI with the same golden vectors.

### Stage 4 — Camera2 receiver, the C1 freeze (the highest-ROI stage)

`optical-camera/Camera2Session.kt`, exactly the audit's C1 sequence:

1. Open camera, `SessionConfiguration` with an `ImageReader(YUV_420_888, maxImages = 3)` (R1/C5 burst-of-3 comes free).
2. Calibration pose: sender holds a static CAL QR 2–3 s.
3. `CONTROL_AF_MODE = CONTINUOUS_PICTURE` → `CONTROL_AF_TRIGGER_START` → await `AF_STATE_FOCUSED_LOCKED` → `TRIGGER_CANCEL` → `CONTROL_AF_MODE_OFF` + pinned `LENS_FOCUS_DISTANCE`.
4. `CONTROL_AE_LOCK = true`, or full manual `SENSOR_EXPOSURE_TIME` + `SENSOR_SENSITIVITY` with `CONTROL_MODE = OFF`; target **1/125–1/250 s, ISO 200–800**.
5. `CONTROL_AWB_LOCK = true`; `NOISE_REDUCTION_MODE = OFF`; `EDGE_MODE = OFF`; `TONEMAP_MODE = FAST`; `CONTROL_VIDEO_STABILIZATION_MODE = OFF` (EIS warps the module grid; OIS stays on); `CONTROL_ENABLE_ZSL = false`.
6. `AE_REGIONS` / `AF_REGIONS` set to the tracked QR bounding box.
7. Re-lock **only** on a gate-metric breach — never on a timer (R2).
8. Deliberately avoid exposure in the 5–15 ms band (banding maximal, blur real): either ≥ one screen period with motion gating, or ≤ 1/250 s and let `HybridBinarizer` absorb banding.

Every capability is read from `CameraCharacteristics` first, with a documented degradation path per device (`INFO_SUPPORTED_HARDWARE_LEVEL = LEGACY` → manual exposure unavailable → fall back to AE-lock + coach).

**Exit test (device):** on a real phone, log the resulting `CaptureResult` shows AF/AE/AWB locked, exposure inside band, and that no lens hunting occurs across 60 s of changing screen content.

### Stage 5 — Gated ROI decode pipeline

`camera → motion gate → blur/contrast gate → ROI crop → ZXing → protocol verify → fountain`:

- `ImageReader.OnImageAvailableListener` on a dedicated `HandlerThread`; read **plane 0 (luma) only**, never `toBitmap()`.
- `Channel<FrameRef>(capacity = 1, onBufferOverflow = DROP_OLDEST)` — frames older than ~150 ms are worthless.
- Decode on `Dispatchers.Default.limitedParallelism(2)`. Never more (thermal budget, R9).
- ROI → scale to ~1.5–2 px/module → 3×3 median → `HybridBinarizer` → ZXing `QRCodeReader` in **byte mode**, `PURE_BARCODE`/`TRY_HARDER` tuned per measurement.
- Verify order strictly CRC32 → Ed25519 → id bounds → `BitSet` dedup → fountain insert (R5).
- Instrumentation from day one: gate pass rate, decode attempts/s, successful decodes/s, W estimate. The audit's claim is *~90–95% of frames never reach the decoder*; that number is a **test assertion**, not a hope.

**Exit test (device):** ≥ 8–12 good decodes/s, ~22–34 KB/s at ~2.8 KB/frame, decode attempts ≤ 10% of camera frames, sustained 2 min with no thermal throttle.

### Stage 6 — Sender render path

- Pre-rasterize every symbol's QR once into reusable `Bitmap`s (Version 40-L class, 177×177) — never per frame (R7).
- `SurfaceView` + `Choreographer`-paced blit; `Canvas.drawBitmap` with `FilterBitmap = false`, integer-scaled to avoid resampling module edges.
- `Display.setFrameRate(60f, FRAME_RATE_FIXED)` (API 30+), LTPO pinned off; hold ≥ 100 ms per symbol (R3); HEADER interleaved every 8 DATA symbols.
- Max brightness (defeats PWM and auto-brightness ramping), `FLAG_KEEP_SCREEN_ON`, immersive-sticky, QR laid out inside `WindowInsets` safe bounds so notch/cutout/gesture pill never crop the 4-module quiet zone (audit §6.3).
- Bitmaps are pure 2-colour B/W; no GIF, no palette, no dithering anywhere in the path (audit §6.1 "GIF trap").

### Stage 7 — Thermal governor + power

`PowerManager.addThermalStatusListener`: `LIGHT` → sender 12→8 fps, receiver ROI downsample ×2; `MODERATE` → duty-cycle 8 s work / 2 s sleep + raise gate thresholds; `SEVERE` → pause with a user-visible notice; `CRITICAL` → abort and persist resume state. Session-scoped partial wakelock with timeout; no background scanning, ever.

### Stage 8 — Compose shell, alignment coach, SAS gate

- Coach HUD: live blur score, contrast ratio, motion state, "hold still / frame now" hint, corner-guide overlay tracking the QR rect — the audit calls the human-in-the-loop coach *the product*, so it is a first-class screen, not a debug overlay.
- Calibration screen that **refuses** below CR ~5:1 with an actionable reason (direct sun / privacy film / off-axis) before a transfer is wasted (R8).
- **SAS compare must block the data plane** until both humans confirm — this is the one place the web POC knowingly regressed (it displays and continues) and it must not be carried over.
- Export via MediaStore only after whole-file SHA-256 passes: write to a temp file, verify, then publish. **No partial write on any failure path** (R8).

**Stage 8 as built.** The SAS gate is a domain object (`SasGate`), not a screen,
and it is enforced inside `ReceiveSession.ingestFrame` rather than in the UI:
every DATA frame arriving before *both* parties confirm is dropped and counted
in `framesBlockedBySas`. The test suite proves this by firing a full, correctly
signed, CRC-valid symbol stream at an unconfirmed session and asserting the
decoder ingests zero of it and the session never leaves `PAIRING`. One-sided
confirmation is explicitly not enough — that collapses the ZRTP-style comparison
to a single endpoint, which is precisely the attack it exists to stop. A
mismatch is terminal and aborts the session; confirming while the digits differ
is caught by comparing rather than trusting the tap.

Export is a pure decision (`ExportGate.evaluate`) — completeness, then size
against the signed header, then whole-file SHA-256 — followed by a staging write
(`MediaStoreExporter`) that hashes the read-back before publishing and uses
`IS_PENDING=1` so a partial file is never observable. File names arrive over the
optical channel and are attacker-influenced even with a valid signature, so they
are sanitised for path escapes and control characters.

In the UI, "They match" is deliberately *not* the visually dominant control and
is not pre-focused: a user tapping the biggest green thing without reading the
digits is the entire attack. Screen routing derives solely from `SessionState`,
so no UI-only navigation state can reach `RECEIVING` without passing `PAIRING`.

### Stage 9 — Hardening and device matrix

Resume/multi-file via session id + manifest; OEM matrix (Pixel / Samsung / OnePlus / Xiaomi differ in AF behaviour and panel scan modes); optional SAS-derived XChaCha20-Poly1305 payload encryption; documented, explicit non-goals (direct sun, privacy film, handheld motion, "Bluetooth replacement").

---

## 4. Sequencing summary and what I need from you

| Stage | Status | Verified where |
|---|---|---|
| 0 golden vectors | **DONE** | sandbox — byte-reproducible, 832 KB of vectors |
| 1 Kotlin protocol port | **DONE** | sandbox — 114 assertions, 0 failures |
| 2 vision gates (pure Kotlin) | **DONE** | sandbox — 24 assertions, 0 failures |
| 3 Gradle/Android skeleton | **DONE** | modules + version catalog written; resolves in CI |
| 4 Camera2 C1 freeze | **DONE** | decision logic: sandbox (122 assertions). Camera2 call layer: CI compile + on-device exit test |
| 5 gated ROI decode | **DONE** (pipeline) | `DecodePipeline` wires gate→ROI→ZXing; throughput numbers need a device |
| 6 sender render path | **DONE** | pacing logic: sandbox. SurfaceView layer: CI compile + on-device |
| 7 thermal governor | partial | `HoldTimePlan.derate` ladder done in sandbox; `PowerManager` listener pending |
| 8 Compose shell / coach / SAS gate | **DONE** (logic) | gate logic: sandbox (65 assertions). Compose/MediaStore layer: CI compile + on-device |
| 9 hardening, device matrix | not started | |

**The pure/main source-set split.** `:optical-camera` and `:optical-render` each
carry two source roots. `src/pure/kotlin` holds the decision logic — which
exposure, when to re-lock, how to crop, how long to hold a symbol — with zero
`android.*` imports, so it compiles and runs on a bare JVM. `src/main/kotlin`
holds the Camera2/SurfaceView call layer that applies those decisions. This is
not cosmetic: the bugs in a camera pipeline live in the decisions, not the API
calls, and this split is what made them testable here. It already paid for
itself — see the hold-time default bug in the Stage 6 notes below.

**Nothing under `/src` has been or will be modified.** Branch `android-native` exists; new code lands under `android/` and `tools/`.

### Stage 4 / Stage 6 implementation notes

**The forbidden exposure band.** The audit gives two constraints that read as
contradictory: "target 1/125–1/250 s" (section 1.2) and "do not sit exposure in
5–15 ms" (section 5.1) — but 1/125 s *is* 8 ms, inside the forbidden band. The
resolution is that 5–15 ms is the worst of both worlds: long enough for tremor to
smear a module, short enough that the rolling shutter catches only part of a
refresh, so you get blur **and** banding. `ExposurePlan` therefore emits only two
strategies — SHORT_FREEZE (≤ 4 ms; banding absorbed by `HybridBinarizer`) or
LONG_INTEGRATE (≥ one full refresh; banding eliminated, motion gate mandatory) —
and a 63-case sweep asserts no metering outcome can ever land in the band.

**A real bug the pure/main split caught.** `HoldTimePlan.compute` originally
defaulted the receiver exposure to a full 16.7 ms refresh period. That inflated
the hold to 183 ms and dropped the sender to 5.5 symbols/s — outside the audit's
8–12 fps envelope, a 45% throughput tax, and it would have been invisible until
someone timed a real transfer. Defaulting to the SHORT_FREEZE ceiling (4 ms,
which is what the receiver actually runs in any adequately lit room) gives
exactly the audit's 100 ms hold at 10 symbols/s. A second bug in the same area:
thermal derating could *raise* fps above the base plan on a slow-readout device;
it is now clamped to never speed up.

**Re-lock discipline.** `LockPolicy` re-triggers AF only after 45 consecutive
gate failures (~1.5 s at 30 fps), never on a timer, and a single good frame
resets the counter. This is the audit's most easily-missed rule: fountain codes
hide decode failure from the receiver, so a timer-driven AF would hunt forever
with no corrective signal and silently kill throughput.

**Zero-allocation render path.** `SymbolScheduler.needsRedraw` makes 50 of every
60 vsync callbacks return without touching the canvas. Bitmaps are pre-rasterized
once by `SymbolBitmapCache`; the blit path holds `Rect`/`Paint` as fields with
`isFilterBitmap = false` and integer-multiple scaling, so module edges stay hard.

### What still needs a device

Stages 4–6 are code-complete but their *performance* claims are not yet
evidence. The on-device exit tests remain: AF/AE/AWB actually locked in
`CaptureResult`, no lens hunting over 60 s, ≥ 8–12 decodes/s, decode attempts
≤ 10% of camera frames, and 2 minutes sustained with no thermal throttle. I can
write those as instrumented tests, but no number should be quoted as fact until
they run on real hardware — per-OEM AF and panel-scan behaviour is exactly where
"works on my Pixel" products die.

My recommendation for what's next: **Stage 8** (Compose shell + coach HUD +
blocking SAS gate) makes the whole thing demonstrable end-to-end, and the
blocking SAS confirm is the one security regression carried in the web POC that
must not survive into the product.
