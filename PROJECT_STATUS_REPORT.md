# Candela — Project Status and Architecture Report

**Date:** 2026-09-04  
**Workspace:** `/workspace`  
**Source of truth:** `optical-link-audit.md`  
**Codename:** Candela — air-gapped optical file transfer (zero radio, light only)

---

## 0. One-page summary

| Item | Status |
|---|---|
| Audit interpretation | Physics-bounded commercial *utility*, not a general-purpose transport |
| Production runtime of record | Native Android (Kotlin / Jetpack Compose shell / Camera2 / ZXing-core) |
| What exists in this repo today | TypeScript/Vite **protocol POC** + web demo |
| Android / Gradle / Camera2 | **Not started** (no JDK, no Android SDK in this environment) |
| Protocol freeze | CAL / HEADER / DATA + fountain + Ed25519 + CRC32 + SHA-256 + SAS |
| Tests run | `tsc` clean; fountain 4 KB / 32 KB @ 22% drop PASS; binary QR roundtrip PASS |
| Web preview purpose | Prove the protocol and show session/coach UX — **not** the shipping client |
| Next real work | Port protocol 1:1 to Android, then Camera2 freeze + gated ROI decode |

**Bottom line:** Protocol is frozen enough to port. Product is not started. Do not treat the Vite app as production.

---

## 1. Core understanding

### 1.1 How the engineering audit was interpreted

`optical-link-audit.md` is a physics-bounded product spec, not a feature wishlist. Governing sentence:

> Feasible as a commercial-grade *utility* inside a defined physical envelope. Not feasible as a general-purpose transport.

The commercial product is **session protocol + alignment coach + thermal governor**, not a decoder. Every failure mode has a measurable threshold and a software countermeasure. Outside the envelope the system must **pause/refuse**, never silently corrupt.

The audit’s five findings were mapped as implementation rules:

| # | Failure domain | Severity | Taken as | Explicitly rejected |
|---|---|---|---|---|
| 1 | Motion blur / focus hunting / checksum storms | Critical | Prevent: freeze AE/AF, motion-gate, blur-gate, ROI-decode. Never decode garbage. | Pixel deconvolution / OpenCV repair |
| 2 | Rolling shutter vs screen refresh | Critical | Phase-independence: hold-time >> exposure so content is static during readout | Software clock sync over the optical channel |
| 3 | Thermal throttling / battery | High | Gate-first pipeline (~2–4 W). A 2-minute transfer stays thermal-safe | Ungated 30 fps full-frame ZXing (2–4 W, throttle in 5–10 min) |
| 4 | Contrast / ambient / privacy film | High | ROI metering, adaptive threshold, calibration refuse below CR ~5:1 | Compensating for hard physical floors (direct sun, privacy film) |
| 5 | Security / integrity | High (design) | Per-block Ed25519 + CRC32 + whole-file SHA-256 + SAS bootstrap. Mandatory. | Claiming crypto stops physical interception or a spliced intended-sender |

Baselines the audit said to assume (not re-justify):

- Fountain codes
- Per-frame metadata indexing
- BitSet / HashSet dedup
- Off-main-thread decode
- Manual file export

Operating envelope treated as the **contract** (audit section 7):

| Parameter | Guaranteed range | Outside → behavior |
|---|---|---|
| Positioning | Both phones stationary, 15–40 cm, angle &lt; 20° | Coach + motion gate; auto-pause |
| Lighting | Indoor / indirect; no direct sun on sender | Calibration refuses below CR ~5:1 |
| Screen protector | Clean glass (≤ mild matte); no privacy film | Calibration warns / refuses |
| Payload | ≤ 1 MB soft ceiling; 100–500 KB recommended | Session + resume; no hard crash |
| Sender rate | 8–12 fps, hold ≥ 100 ms, fixed 60 Hz | Receiver drops frames; fountain absorbs |
| Duration | Sessions ≤ ~5 min continuous | Thermal governor duty-cycles; resume |
| Security | SAS compared by humans; per-block Ed25519 | Verify fail → abort, no partial write |

Throughput the audit budgets for a well-executed **native** implementation: ~20–40 KB/s; 100 KB in ~10–20 s; 500 KB in ~1–3 min. Those numbers are **not** claimed for the current web POC.

Precedent cited by the audit (web experiments, not Camera2): qrfiletransfer.app, Decimen (~130 KB/s on a static webcam-like setup). Native advantage is lock exposure, force refresh, gate decodes — none of which a browser can do.

### 1.2 Target runtime — explicit

**The current tree is a protocol POC / web demonstrator. It is not the production client. TypeScript will not be the shipping runtime.**

| Layer | Audit target | What exists now |
|---|---|---|
| Production client | Android Kotlin + Jetpack Compose **shell** + Clean Architecture + Camera2 + ImageReader + ZXing-core (**not** ML Kit / GMS) | **None.** No `app/`, no Gradle, no Kotlin, no Camera2 |
| Why web was built | This execution environment has **no JDK, no Android SDK, no Gradle**. `java` is not installed. A native APK cannot be compiled or run here. | Vite + TypeScript so the protocol could be executed, typechecked, and previewed |
| What “done” means for the product | Native app inside the operating envelope | Not started |

Legitimate remaining roles for the web tree:

1. Protocol reference (byte layouts, fountain parameters, SAS)
2. Same-device bench / golden vectors for the Android port
3. Limited desktop demo where two browsers are acceptable

Web **cannot**: freeze AE/AF, force 60 Hz / disable LTPO, ImageReader YUV ROI, thermal `PowerManager`, or survive OEM AF/panel differences.

ML Kit is a product-definition reject: “completely offline” breaks the moment Play Services is required. ZXing-core (or zxing-cpp JNI) only.

---

## 2. Architecture of what was built (web POC)

### 2.1 Repository layout

Workspace started as git + `optical-link-audit.md` only. No git submodules.

```
/workspace
├── optical-link-audit.md          # source of truth (unchanged)
├── PROJECT_STATUS_REPORT.md       # this report
├── package.json                   # Vite 6, TypeScript 5.8
├── vite.config.ts                 # allowedHosts: .monkeycode-ai.live
├── index.html
├── tsconfig.json
├── src/
│   ├── main.ts                    # SPA: Home / Send / Receive / Self-test
│   ├── styles.css
│   ├── protocol/
│   │   ├── constants.ts           # magic, kinds, density, fountain params
│   │   ├── bytes.ts               # concat, u16/u32 BE, hex, format
│   │   ├── crc32.ts               # IEEE CRC32
│   │   ├── crypto.ts              # Ed25519, SHA-256, SAS
│   │   ├── frames.ts              # CAL / HEADER / DATA encode+decode
│   │   ├── fountain.ts            # systematic LT + peel + bounded GE
│   │   ├── qr.ts                  # binary byte-mode QR (qrcode + jsQR)
│   │   ├── session.ts             # session state names, coach metrics
│   │   ├── selftest.ts            # Node fountain bench
│   │   └── qrroundtrip.ts         # Node PNG → jsQR → decodeFrame
│   ├── vision/
│   │   └── gates.ts               # blur / contrast / motion (software)
│   └── transfer/
│       ├── sender.ts              # paced QR plane
│       ├── receiver.ts            # getUserMedia + overlay + fountain
│       ├── loopback.ts            # protocol math @ 22% drop
│       └── opticalLoopback.ts     # canvas pixel optical decode
└── public/                        # static copies for one-tap download
```

Dependencies: `@noble/ed25519`, `@noble/hashes`, `qrcode`, `jsqr`. No backend. No network in the transfer path.

### 2.2 Session state machine (domain)

```
IDLE
  → CALIBRATING     (static CAL QR; receiver converges)
  → PAIRING         (HEADER + SAS displayed on both sides)
  → SENDING | RECEIVING
  → VERIFYING       (assemble fountain, SHA-256)
  → COMPLETE | ABORTED | PAUSED
```

On Android this should be the Clean Architecture domain model. On web it is UI state plus sender/receiver class state.

### 2.3 Wire format

Magic `CL` (`0x43 0x4C`), protocol version `1`.

**CAL**

```
magic(2) | version(1) | kind=0(1) | sessionId(8) | CRC32(4)
```

**HEADER** (trust anchor; interleaved every 8 data symbols)

```
magic(2) | version(1) | kind=1(1) | sessionId(8)
| nameLen(2) | name | fileSize(4) | k(2) | blockSize(2)
| fileHash SHA-256(32) | publicKey Ed25519(32)
| mimeLen(2) | mime
| Ed25519-sig(64) | CRC32(4)
```

Signature is over the body **before** the signature. CRC32 is over body+signature.

**DATA**

```
magic(2) | version(1) | kind=2(1) | sessionId(8)
| symbolId(2) | payloadLen(2) | payload
| Ed25519-sig(64) | CRC32(4)
```

Receiver verify order per frame: **CRC32 → Ed25519 → symbol-id bounds → fountain insert**.

Why per-block signatures instead of a hash chain: fountain codes deliver **out of order**. A chain would destroy that. Merkle proofs add ~256 B/symbol. Per-block Ed25519 is unordered and cheap (audit: ~40–80 µs/symbol, ~2.4% size overhead at 2.7 KB payload).

Replay of old sessions fails the ephemeral key. Replay of *this* session is just extra fountain symbols.

**What crypto cannot do (required honesty):** a spliced stream where the attacker *is* the intended sender is undetectable at this layer; photographing the screen is a confidentiality leak no receiver-side crypto fixes. Optional later: encrypt payload with a SAS-derived key.

### 2.4 Fountain coding

Systematic Luby Transform:

- Symbols `0 .. k-1` are source blocks (identity).
- Symbols `k+` XOR neighbors sampled from a robust soliton (c = 0.12, δ = 0.05).
- Neighbor RNG: `mulberry32((symbolId + 1) * 0x9E3779B9)` — **must stay identical** on Android.
- Encoder recommended count: `ceil(k * 1.55) + 16`.
- Decoder: peel decoder + bounded Gaussian elimination (≤ 80 unknowns, pool ≤ 96) so mid-size `k` actually completes.
- Dedup: `Set<symbolId>` (Android: `BitSet(65536)`).

### 2.5 QR mapping (POC vs product)

Web POC uses **binary byte-mode** QR (`qrcode` encode, `jsQR` `binaryData` decode). Not GIF, not photo-style palettes (audit “GIF trap”).

| Profile | Payload | Hold | QR ECC |
|---|---|---|---|
| robust | 32 B | 220 ms | M |
| standard | 48 B | 160 ms | M |
| fast | 64 B | 120 ms | L |

Audit production baseline: QR Version 40-L, ~2,953 B/frame, ~2.7 KB useful after header/sig. Web is **~40–80× below** that because jsQR + canvas is not ZXing-ROI on a frozen 1080p stream. **POC ceiling, not product ceiling.** On Android, raise `PAYLOAD_BYTES` toward Version-40 class after C1 freeze is proven.

### 2.6 Sender (web)

`OpticalSender` (`src/transfer/sender.ts`):

1. Generate ephemeral Ed25519 keypair + 8-byte session id
2. Fountain-encode file at selected block size
3. Hold CAL for `CALIBRATION_MS` (2800 ms)
4. Hold HEADER (~2200 ms)
5. Stream DATA with HEADER every 8 symbols
6. Pace with `setTimeout` hold 120–220 ms

Not implemented on web (Android-only): `Display.setFrameRate(60, FRAME_RATE_FIXED)`, LTPO off, max brightness / no PWM, SurfaceView zero-allocation blit, `FLAG_KEEP_SCREEN_ON`, immersive sticky quiet zone.

### 2.7 Receiver (web)

`OpticalReceiver` (`src/transfer/receiver.ts`):

- `getUserMedia` ~720p @ 30, facingMode environment
- Downsample to ~640 px work canvas
- Overlay tracked QR rect
- Coach meters: blur, contrast, motion, recovered/k
- Fountain ingest; SHA-256 gate; Blob download on success

**Important regression vs audit:** software gates exist in `src/vision/gates.ts`, but the camera hot path currently **still decodes ungated** so the preview is usable on desktop/phone. That is the opposite of C1–C4 and **must be reversed on Android**.

### 2.8 Vision gates (software sketch only)

`src/vision/gates.ts`:

- ROI crop (last QR rect or center 64%)
- Downsample to 96×96 grayscale
- Blur: Laplacian energy
- Contrast: (p99 − p1) / 255
- MotionGate from `devicemotion` magnitude

Current web thresholds (relaxed for demo): `BLUR_MIN = 1.2`, `CONTRAST_MIN = 0.08`, `CR_REFUSE = 0.03`. Android must learn thresholds at calibration pose and refuse around CR ~5:1 as the audit specifies.

### 2.9 Clean Architecture mapping (intended Android, not built)

```
app (Compose UI shell, navigation, coach HUD, SAS compare)
  → domain (SessionState, FountainEncoder/Decoder, Frame types, use-cases)
  → optical-render (SurfaceView sender, Choreographer pacing, 60 Hz, brightness)
  → optical-camera (Camera2 freeze, ImageReader, gates, ZXing ROI)
  → platform (PowerManager thermal, wakelock, MediaStore export)
```

Compose **must not** recompose the QR plane per frame. Audit kill #5: per-frame `Bitmap.createBitmap` / Compose invalidation = GC + jank.

---

## 3. Current progress (completed)

### 3.1 Protocol — done and tested

| Module | File | Test evidence |
|---|---|---|
| Constants / density / fountain params | `src/protocol/constants.ts` | used by all benches |
| Byte helpers | `src/protocol/bytes.ts` | used by frames |
| CRC32 | `src/protocol/crc32.ts` | qrroundtrip + loopback |
| Ed25519 + SHA-256 + SAS | `src/protocol/crypto.ts` | loopback + qrroundtrip |
| CAL / HEADER / DATA codec | `src/protocol/frames.ts` | qrroundtrip PASS all three kinds |
| Systematic LT fountain | `src/protocol/fountain.ts` | selftest PASS |
| Binary QR | `src/protocol/qr.ts` | qrroundtrip PASS |

**Fountain selftest** (`npx tsx src/protocol/selftest.ts`):

- 4 KB → k=86, sent 138, dropped 37, recovered 86, SHA-256 match, ~0.9 s
- 32 KB → k=683, sent 1154, dropped 285, recovered 683, SHA-256 match, ~7 s
- Drop rate: 22% simulated optical loss

**QR roundtrip** (`npx tsx src/protocol/qrroundtrip.ts`):

- CAL kind 0, HEADER kind 1, DATA kind 2
- Path: encode frame → QR PNG → jsQR binaryData → `decodeFrame`
- All PASS

**Typecheck:** `npx tsc --noEmit` — clean.

### 3.2 Transfer / UX — done as web demo only

| Item | Status |
|---|---|
| Sender paced QR plane | Done (web canvas) |
| Sample-file send (no file picker required) | Done |
| Receiver getUserMedia + overlay + coach HUD | Done (web); ungated decode |
| No-camera receive demo (canvas loopback) | Done |
| Protocol math bench UI | Done |
| Optical canvas loopback UI | Done |
| SPA routing Home / Send / Receive / Self-test | Done (fixed after landing page failed to hide) |

### 3.3 Purpose of the web preview

Not a product launch. Three jobs:

1. Prove encode → QR pixels → decode → fountain → hash round-trips.
2. Make the session/coach/SAS UX visible and clickable.
3. Provide the only runnable artifact in an environment without Android toolchains.

If the Vite terminal is still running, the app is at:

- Preview: `https://5173-18329b81dd19ebee.monkeycode-ai.live`
- Local: `http://127.0.0.1:5173/`

Honest limits of that link:

- Desktop without a camera: Self-test and “No camera? Run demo” work. Live optical receive does not.
- Phone-to-phone over HTTPS getUserMedia: possible, but payload is 32–64 B/symbol and AE/AF are unlocked — audit failure modes 1 and 2 are **unmitigated**.
- Do **not** quote web KB/s as a product number.
- jsQR on a 640 px preview is not ZXing-ROI.

### 3.4 Explicitly not done

- Android Gradle project / Kotlin / Compose
- Camera2 AE/AF/AWB lock, manual exposure, AE/AF regions
- ImageReader triple-buffer YUV ROI path
- ZXing-core or zxing-cpp JNI
- `Display.setFrameRate(60, FRAME_RATE_FIXED)` / LTPO off
- SurfaceView zero-allocation sender
- `PowerManager` thermal state machine
- Session-scoped wakelock / ignore battery saver
- Hard CR&lt;5:1 calibration refuse as a state machine
- SAS **blocking** confirm before data plane
- SAS-derived payload encryption
- Resume / multi-file / MediaStore export with no partial write
- Flashlight 1-bit back-channel
- OEM device matrix (Samsung / OnePlus / Pixel / Xiaomi)

---

## 4. Gap vs the audit “kill list”

The audit ranked what would kill the product:

| Rank | Killer | Web POC | Android (required) |
|---|---|---|---|
| 1 | Decoding ungated full frames → thermal death | Still true on camera path | Gate-first; decoder never sees a failed frame |
| 2 | Short hold times → rolling-shutter tears | Holds 120–220 ms; no 60 Hz lock | HOLD ≥ 6 × max(period, exposure+readout); force 60 Hz |
| 3 | Shipping without per-block signatures | **Done in protocol** | Port; do not regress |
| 4 | No calibration/refusal → silent 100% fail in sun/privacy film | Copy only | Hard refuse below CR ~5:1 |
| 5 | Per-frame Compose / bitmap allocation | N/A | QR plane = SurfaceView / View.onDraw only |

---

## 5. Pending deliverables — Native Android roadmap

### 5.1 C1 — Camera2 freeze (highest ROI, not started)

Calibration pose: sender shows static CAL QR 2–3 s. Receiver converges then **locks**:

1. AF: `CONTROL_AF_MODE = CONTINUOUS_PICTURE` → `CONTROL_AF_TRIGGER_START` → wait `AF_STATE_FOCUSED_LOCKED` → `CONTROL_AF_TRIGGER_CANCEL` → `CONTROL_AF_MODE_OFF` + fixed `LENS_FOCUS_DISTANCE`
2. AE: `CONTROL_AE_LOCK = true` **or** fully manual `SENSOR_EXPOSURE_TIME` + `SENSOR_SENSITIVITY` with `CONTROL_MODE = OFF`
3. Target exposure: **1/125–1/250 s**, ISO clamped **200–800**
4. `CONTROL_AWB_LOCK = true`
5. `NOISE_REDUCTION_MODE = OFF`, `EDGE_MODE = OFF`, `TONEMAP = FAST`
6. `CONTROL_VIDEO_STABILIZATION_MODE = OFF` (EIS warps the grid). OIS is fine.
7. `CONTROL_ENABLE_ZSL = false`
8. `AE_REGIONS` / `AF_REGIONS` on QR bounding box (audit E1)
9. Re-trigger lock **only** when a quality metric drops — never on a timer

Do **not** sit exposure in 5–15 ms (banding maximal and blur real). Either ≥ one screen period (~17 ms @ 60 Hz) paired with motion gates, or ≤ ~1/250 s and let HybridBinarizer absorb banding.

### 5.2 C2–C4 — Gated ROI decode (sketched in TS, not Camera2)

- Subscribe `Sensor.TYPE_LINEAR_ACCELERATION` (or gyro magnitude). Feed frames only while |accel| &lt; ~0.3 m/s².
- Blur/contrast gate on 128×128 downsample (~1–3 ms): Laplacian proxy + (p99−p1)/255. Thresholds learned at calibration.
- `ImageReader` `maxImages = 3`. YUV → luma on ROI only. Never full-frame `Image.toBitmap()`.
- Crop tracked QR rect; scale ~1.5–2 px/module; 3×3 median; ZXing `HybridBinarizer` (or Otsu) on ROI. Target 5–15 ms vs 40–120 ms full-frame.
- **ZXing-core or zxing-cpp JNI. Not ML Kit.**
- Pipeline: `camera → gate → decode → fountain → verify`
- `Channel<Frame>(capacity = 1, onBufferOverflow = DROP_OLDEST)`. Frames older than ~150 ms are useless.
- Decode on `Dispatchers.Default.limitedParallelism(2)`. Never more.
- C5: if blur gate is marginal, capture 2–3 frames per symbol, keep sharpest (ImageReader already holds 3 buffers).

Expected after C1–C4 on midrange: **8–12 good decodes/s**, ~22–34 KB/s channel at ~2.8 KB/frame. 500 KB → ~25–35 s clean capture + alignment → **1–3 min end-to-end**.

### 5.3 T2 — Sender render path (not started)

- Pre-render each symbol’s QR into a `Bitmap` once (`QRCodeWriter`, 177×177 in &lt;1 ms at Version 40).
- Blit with `Canvas.drawBitmap` in a **plain `View.onDraw` / `SurfaceView`**, paced by `Choreographer` / `Handler`.
- **Not** Compose recomposition. Compose = UI shell only. QR plane = dedicated composited layer.
- `Display.setFrameRate(60f, FRAME_RATE_FIXED)` (API 30+) or `MODE_LIMITED`. Disable LTPO. 120 Hz dual-scan seam is unfixable in software.
- Max brightness (kills PWM). `FLAG_KEEP_SCREEN_ON`. Immersive sticky. `WindowInsets` safe bounds so notch/gesture bar never crops the 4-module quiet zone.
- Hold-time rule: every symbol on screen for `HOLD ≥ 6 × max(screenPeriod, exposure + readout)`. At 60 Hz that is ~100 ms.

### 5.4 T1 — Thermal governor (not started)

```kotlin
pm.addThermalStatusListener { status ->
    when (status) {
        THERMAL_STATUS_LIGHT    -> sender: fps 12→8; receiver: roiDownsample 2x
        THERMAL_STATUS_MODERATE -> dutyCycle(work = 8s, sleep = 2s); raise gate thresholds
        THERMAL_STATUS_SEVERE   -> pause session + user-facing notice (never silently die)
        THERMAL_STATUS_CRITICAL -> abort + persist resume state
    }
}
```

Partial wakelock with timeout **only** during an active session. Reject background scanning. This is a foreground session protocol.

Honest power budget (midrange, gated): screen max 1.2–2.0 W + camera/ISP 0.6–1.0 W + gated CPU 0.2–0.6 W ≈ **2–4 W**. A 2-minute transfer never throttles if gated. Ungated checksum storm is the only way to thermal-crash it.

### 5.5 Alignment coach and hard floors (the actual product feature)

Live HUD: blur score, contrast ratio, motion, “frame-now” hint, corner-guide overlay on QR rect.

Calibration pose measures CR, blur margin, banding variance. If CR &lt; ~5:1 → tell the user **before** wasting a transfer: move out of direct sun / remove privacy film / go head-on.

Adaptive threshold on ROI (3×3 median → Otsu / HybridBinarizer). Global gamma is the wrong tool. CLAHE only if a glare hotspot is detected (~2–3 ms on 128×128). Do not run unconditionally.

### 5.6 Security left on Android

- SAS compare UX that **blocks** the data plane until both humans confirm (web currently displays SAS and continues).
- Optional XChaCha20-Poly1305 / AES-GCM from SAS-derived key if confidentiality is in scope.
- Docs sentence the audit demanded: crypto does not stop a spliced intended-sender or a photograph of the screen.
- No partial MediaStore write on hash mismatch.

### 5.7 Domain / persistence

- `BitSet(65536)` for symbol dedup (16-bit id space; 500 KB / 2.7 KB ≈ 190 symbols).
- Resume: keep fountain buffer, skip known symbols via session id + manifest.
- Multi-file / multi-session later, not in first device POC.

### 5.8 Optional later (not first POC)

- Flashlight 1-bit back-channel (4 Hz pattern: slow down / resume / done). Stays inside “no network/BT”.
- SAS-derived payload encryption.

### 5.9 Out of scope unless the envelope is reopened

- Framing this as a Bluetooth / Wi‑Fi Direct replacement
- &gt;1 MB as a first-class path
- Direct sun, privacy film, hand-held motion as supported modes

---

## 6. Concrete execution steps (POC → Android)

The audit’s own staging, with this repo as the already-finished “protocol math” slice.

### Step A — Toolchain (blocked in this environment)

- JDK 17, Android Gradle Plugin, compileSdk 35, minSdk 26 (Camera2; `setFrameRate` needs API 30 — gate older devices).
- Modules:
  - `:app` — Compose shell, navigation, coach HUD, SAS
  - `:core-protocol` — port of `src/protocol/*` (byte-identical)
  - `:optical-camera` — Camera2 + gates + ZXing ROI
  - `:optical-render` — SurfaceView sender
- ZXing-core only. No GMS. No ML Kit.

**Blocked here until a machine with Android SDK exists.**

### Step B — Port protocol 1:1 (1–3 days once Android exists)

- Byte-identical CAL / HEADER / DATA. Do not “improve” the layout until golden vectors pass on device.
- Same soliton seed: `mulberry32((id + 1) * 0x9E3779B9)`.
- Ed25519 via Tink or BouncyCastle; SHA-256 `MessageDigest`; CRC32 same IEEE polynomial.
- Golden tests: `qrroundtrip` vectors and the 4 KB / 32 KB benches must match web hashes.

### Step C — Device POC (~2 weeks per audit)

- SurfaceView sender, hold ≥ 100 ms, ZXing on ROI from ImageReader.
- Raise payload off the jsQR 32–64 B cap toward Version-40 class.
- Validate **8–12 good decodes/s** and **20–40 KB/s** with phones on a desk, AE locked.
- If that fails on a Pixel with AE locked, **stop and re-measure**. Do not add features.

### Step D — Hardening (~6 weeks)

- C1 freeze, C2/C3 gates, T1 thermal, calibration refuse, SAS confirm + Ed25519 on the hot path.
- Gate-first: decoder never sees a frame that failed blur/contrast/motion.

### Step E — Commercial polish (~12 weeks)

- Alignment coach HUD (the product).
- Resume, multi-file, immersive safe area, auto-brightness hold.
- OEM matrix: Samsung / OnePlus / Pixel / Xiaomi — AF behavior and panel scan modes differ. Budget device-lab time. “Works on my Pixel” is a known death mode.

Suggested Android package sketch (not created):

```
com.candela.optical
  ui/          Compose screens
  domain/      Session, Fountain, Frames, UseCases
  camera/      Camera2Session, Gates, ZxingRoiDecoder
  render/      QrSurfaceView, Pacer
  export/      VerifiedFileWriter (hash gate before MediaStore)
```

---

## 7. What will not be done unless explicitly ordered

- Expanding the web app toward “production.” Browser cameras cannot satisfy C1/C2.
- Raising web payload to 2.9 KB/frame. jsQR will not carry product numbers.
- Claiming 20–40 KB/s from the current preview.
- Installing Android SDK in this environment without a confirmed image that actually has it.

---

## 8. Decision needed

This environment cannot compile Android. To start Step A/B, one of:

1. An Android/JDK image or a repository where Gradle already exists, **or**
2. Explicit order to keep iterating the web POC only, knowing it is not the shipping client.

Until that choice, accurate status:

**Protocol: frozen enough to port.**  
**Product: not started.**  
**Runtime of record: Android Camera2, not this Vite app.**

---

## 9. File index (this workspace)

| Path | Role |
|---|---|
| `optical-link-audit.md` | Original engineering audit (do not treat as optional) |
| `PROJECT_STATUS_REPORT.md` | This report |
| `src/protocol/*` | Portable protocol (port this first) |
| `src/transfer/*` | Web sender/receiver/benches (reference only) |
| `src/vision/gates.ts` | Gate math sketch (reimplement on YUV/Camera2) |
| `src/main.ts` | Web SPA (not production UI) |

---

*End of report. The physics is still the spec.*
