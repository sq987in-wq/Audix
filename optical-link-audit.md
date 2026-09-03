# CRITICAL ENGINEERING AUDIT — Offline Screen→Camera Optical Data Transfer
**System:** Android (Kotlin / Jetpack Compose / Clean Architecture) · Animated QR stream · Fully offline (no network, BT, or local server)
**Audit scope:** Post-baseline deep-dive. Baselines (2.9 KB/frame QR, fountain codes, per-frame metadata indexing, HashSet dedup, off-main-thread ZXing/ML Kit, manual file export) are **assumed** and not re-justified.
**Date:** 2026-09-01

---

## 0. Executive Verdict (read this first)

**Feasible as a commercial-grade *utility* inside a defined physical envelope. Not feasible as a general-purpose transport.**

This distinction is the entire report in one sentence. The physics of the link are unforgiving, but they are *knowable* — every failure mode below has a measurable threshold and a software countermeasure. A transfer protocol that assumes a static phone, a clean lens, and an indoor room will work at 99%+ reliability. The same code, pointed at a hand-held phone under a privacy screen protector in sunlight, will fail 100% of the time regardless of how clever the software is. The commercial product is a **session protocol + alignment coach + thermal governor**, not a decoder.

Precedent exists: qrfiletransfer.app ships fountain-coded animated-QR transfer with BLAKE3 integrity verification; Decimen reports ~130 KB/s phone-to-phone with Luby Transform + SHA-256. So the concept is proven in the field — but both are web experiments, and neither solves the Camera2-level problems below, which is where your Android native advantage lives.

**The five audit findings, compressed:**
| # | Failure domain | Severity | Verdict on the proposed mitigation |
|---|---|---|---|
| 1 | Motion blur / focus hunting / checksum storms | **Critical** | "Pixel-level deblurring" is the *wrong* tool. Prevent, don't repair: freeze AE/AF, motion-gate, blur-gate, ROI-decode. 10–50× CPU reduction. |
| 2 | Rolling shutter vs refresh sync | **Critical** | Software sync is impossible; *phase-independence* via hold-time >> exposure is the answer. The tear problem disappears if content is static during exposure. |
| 3 | Thermal throttling / battery | **High** | Bounded and controllable: ~3–5 W sustained, throttle in 5–15 min only if you feed the decoder garbage frames. Gate-first architecture makes 2-minute transfers thermal-safe. |
| 4 | Contrast / ambient / screen guards | **High** | Partially fixable in software (ROI metering, adaptive threshold, calibration pose). Privacy protectors and direct sun are **hard physical floors** — detect and refuse, don't compensate. |
| 5 | Security / integrity | **High (design)** | Per-block Ed25519 + CRC32 is mandatory, costs ~3% throughput and ~15 ms total CPU. Do it. Crypto cannot stop physical interception — design for that honestly. |

---

## 1. MOTION BLUR, HAND SHAKE & FOCUS HUNTING

### 1.1 The physics (why this is the #1 killer)

A QR Version 40-L binary payload (2,953 bytes) is a 177×177 module grid. On a 1080 px-wide display that's **~6 px per module ≈ 1.5–2 mm**. At a 30 cm camera distance on a typical 12 MP main sensor (≈ 500 px/cm at that distance), one module spans **≈ 7–10 sensor pixels** — comfortable *if the frame is frozen and in focus*.

Now the failure arithmetic:
- **Hand shake:** typical handheld micro-tremor is 2–5 mm/s lateral. At 1/30 s exposure (auto-exposure in moderate light), that's 70–170 µm of motion ≈ **0.5–1 module width of smear** — a guaranteed decode failure, every frame. At 1/250 s, smear drops to ~0.1 module — survivable.
- **Focus hunting:** continuous AF (CAF) on a screen that changes content every ~100 ms sees a "new scene" every frame. Contrast-detect AF re-triggers continuously; the lens oscillates through the focus range. ZXing/ML Kit need a sharp edge structure — a defocused QR (even 1–2 diopters off) fails checksum. And because your fountain code tolerates drops, the receiver *never reports failure back* — CAF keeps hunting forever with no corrective feedback. This is a silent throughput killer, not a crash.
- **The checksum-storm CPU question:** this is the one place the baselines are dangerous. A full-1080p ZXing decode is ~40–120 ms of CPU; ML Kit's BarcodeScanner is 50–300 ms on midrange SoCs. At 30 fps preview, feeding every frame to the decoder = **1.5–9 CPU cores pegged continuously**. On a midrange phone (no vapor chamber) that's 2–4 W of CPU heat → **thermal throttle within 5–10 minutes**, and battery drain that makes the app unusable. If the decode is on the main thread (even via coroutine dispatch on `Dispatchers.Main.immediate` at any point), you get ANRs. The baseline says "decode off main thread" — correct but *insufficient*: the real fix is to **never attempt a decode on a frame that cannot be decoded**.

### 1.2 Countermeasures (in order of cost/benefit)

**C1 — Freeze the camera state (Camera2). The single highest-ROI change in the entire system.**
```
1. Calibration pose: sender shows a static calibration QR for 2–3 s at session start.
2. Receiver converges AF/AE/AWB on it, then LOCKS everything:
   - CONTROL_AF_MODE = CONTINUOUS_PICTURE → CONTROL_AF_TRIGGER_START
     → wait for AF_STATE_FOCUSED_LOCKED → CONTROL_AF_TRIGGER_CANCEL
     → CONTROL_AF_MODE = CONTROL_AF_MODE_OFF + LENS_FOCUS_DISTANCE = fixed
   - CONTROL_AE_LOCK = true (or fully manual: SENSOR_EXPOSURE_TIME + SENSOR_SENSITIVITY with CONTROL_MODE = OFF)
   - CONTROL_AWB_LOCK = true
   - NOISE_REDUCTION_MODE = OFF, EDGE_MODE = OFF, TONEMAP = FAST
   - CONTROL_VIDEO_STABILIZATION_MODE = OFF (EIS warps the grid and adds latency; OIS is fine)
   - CONTROL_ENABLE_ZSL = false (ZSL re-runs AE)
3. Re-trigger the lock only when a quality metric drops (below), never on a timer.
```
Target exposure: **1/125–1/250 s with ISO clamped to 200–800**. This single change converts the "30 fps stream of garbage" into "30 fps stream of sharp frames."

**C2 — Motion-gated capture (free, using hardware you already have).**
Subscribe to `Sensor.TYPE_LINEAR_ACCELERATION` (or gyro magnitude). Only feed camera frames to the pipeline while |accel| < threshold (e.g., 0.3 m/s²). Hand shake is *low-frequency* — the gate opens/closes at ~2–5 Hz, and frames captured during motion are worthless anyway. This is cheaper and more reliable than any image-space deblur.

**C3 — Blur/contrast gate before decode (~1–3 ms, this is your "pixel-level" answer).**
Do **not** run full deconvolution (Wiener/Richardson-Lucy) per frame: unknown space-variant PSF, 30–100 ms FFT cost per 1080p frame, and it doesn't recover information the shutter already destroyed. Instead:
```
gate(frame):
  roi = crop(frame, lastKnownQrRect)          # 4-point tracked rect, fallback = center 40%
  g   = toGrayscaleDownsampled(roi, 128×128)  # integer-stride downsample, ~0.5 ms
  blurScore = variance(gauss3(g) - g)         # Laplacian proxy, ~0.5 ms
  contrast  = (p99(g) - p1(g)) / 255          # dynamic range proxy
  return blurScore  > T_blur  AND  contrast > T_contrast   # thresholds learned at calibration pose
```
Only frames passing the gate reach the decoder — **this alone cuts decode attempts by ~90–95%** and collapses the checksum storm to a trickle (idle scanning cost ≈ 0.1–0.2 W instead of 2–4 W). The gate also gives you a live "signal quality" meter for the UI — the human-in-the-loop alignment coach (section 4) is a feature, not a nicety.

**C4 — Decode the ROI, not the frame.**
Crop the tracked QR rect (fallback: ZXing's detector on a 1/4-scale copy once to establish it), scale to ~1.5–2 px/module, run a 3×3 median (kills sensor noise without touching edges), then ZXing's `HybridBinarizer` (or Otsu) on the ROI only. ROI-decode is 5–15 ms vs 40–120 ms full-frame — a 4–10× CPU cut on top of the gate. **Use ZXing core, not ML Kit, for the hot path** — no Google Play Services dependency (your "completely offline" claim breaks the moment you require GMS), deterministic behavior, and zxing-cpp via JNI is 2–4× faster still if you need headroom.

**C5 — Burst + best-of-N (the only "predictive correction" that pays).**
If the blur gate is marginal, capture 2–3 frames per displayed symbol and keep the sharpest by blur score. Costs nothing with an ImageReader holding 3 buffers.

> **Answer to the audit question:** a constant invalid-checksum storm is *catastrophic* if unmitigated (2–4 W CPU, throttle in 5–10 min, battery halved, ANR risk). With C1–C4 it becomes a non-event: the decoder sees only gated, ROI-cropped, sharp frames. No, you do not need OpenCV deconvolution — you need **prevention (C1), gating (C2/C3), and ROI economics (C4)**. "Predictive correction" should be reinterpreted as *motion prediction via the accelerometer + bounding-box tracking*, not pixel deconvolution.

---

## 2. SCREEN REFRESH vs CAMERA SENSOR — THE ROLLING SHUTTER TRAP

### 2.1 What actually happens (with numbers)

- Sender panel: 60/90/120 Hz (LTPO), scan-out top→bottom, frame period 16.7/11.1/8.3 ms; OLED pixel transition ≈ 0.5–1 ms.
- Receiver: rolling-shutter CMOS, row readout for 1080p ≈ 6–12 ms per frame; exposure T_e set by AE.
- **The beat:** the camera's row clock and the screen's scan clock are independent. With short T_e (bright scene → AE picks 1/1000 s), each camera row integrates only a **sliver of the screen's refresh cycle** → a dark band sweeps across the image; no single camera frame ever contains a uniformly lit QR. A June 2026 field report of exactly this failure (OLED pairing QR unreadable by ML Kit because of banding, *worse in brighter ambient light*) confirms it's a real, current, hardware-level problem — and that the fix had to be firmware-side (static content). Note the counterintuitive bit: **brighter light makes banding worse**, because AE shortens exposure.
- **Tearing** (two different QR frames in one camera image) happens only if the screen content *changes* while the sensor readout is in flight.

### 2.2 The software answer: phase-independence, not synchronization

You cannot lock two free-running clocks over an optical channel with no back-channel — any "sync" is chasing noise. Instead, engineer the protocol so **phase is irrelevant**:

1. **Hold-time rule (the core invariant):** every symbol stays on screen for `HOLD ≥ 6 × max(screenPeriod, exposure + readout)`. At 60 Hz that's ~100 ms, i.e., **6–7 screen refreshes and 3 camera frames per symbol**. During that window the panel content is byte-identical, so *every* camera row, at *every* readout phase, integrates the *same* QR state. Banding may still modulate per-row *brightness*, but it cannot tear or slice content — and brightness banding is absorbed by adaptive thresholding (HybridBinarizer). This converts the rolling-shutter problem from "unsolvable" into "cosmetic."
2. **Exposure placement:** choose T_e either **≥ one screen period** (≥ 17 ms at 60 Hz — integrates full refreshes, kills banding, but reintroduces shake blur: pair with C1/C2) **or** ≤ ~1/250 s **and** accept banding + let the threshold absorb it (the recommended combo with C1's short-exposure freeze). Do **not** sit in the middle (5–15 ms) where banding is maximal and blur is real.
3. **Force the sender to a fixed 60 Hz.** On many OLEDs, 120 Hz drives the panel in **two phase-offset halves** ("dual-scan") — a horizontal seam that no exposure fixes. Use `Display.setFrameRate(60f, FRAME_RATE_FIXED)` (API 30+) or `MODE_LIMITED` on a fixed mode; disable LTPO variability (`setVariableRefreshRate(false)` where exposed). Sender should also render at **max brightness** (kills PWM dimming entirely — at 100% brightness there is no PWM).
4. **Measure and adapt:** during the calibration pose, capture a 30-frame burst and compute luma variance per frame. High variance = beat present → lengthen T_e or shorten it per rule 2. This is a one-time, per-session, per-lighting calibration — not a runtime loop.
5. **Advanced (optional):** the flashlight back-channel. The receiver can blink its LED (e.g., 4 Hz pattern) as a 1-bit return channel the sender's camera sees: "slow down," "resume," "done." This converts the open-loop pacing problem into a closed loop *without* any radio. Cheap, robust, and completely within the "no network/BT" constraint. Use it to pick the sender rate adaptively instead of worst-casing it.

**Sender pacing math (honest):** with ZXing-ROI decode at 5–15 ms and a gated stream, expect **8–12 good decodes/s** on a midrange phone. At ~2.8 KB useful payload per frame (2,953 − header/signature), that's **~22–34 KB/s of channel capacity** — a 500 KB file needs ~190 fountain symbols → **~25–35 s of clean capture** plus calibration/alignment overhead → **1–3 min end-to-end in real conditions**. Decimen's reported ~130 KB/s comes from shorter hold times and a static web-cam-like setup — the mobile handheld reality is 3–5× slower. Budget for it.

---

## 3. THERMAL THROTTLING & BATTERY DRAIN

### 3.1 The honest power budget (steady-state, midrange phone)

| Subsystem | Power | Notes |
|---|---|---|
| Screen at max brightness (sender) | 1.2–2.0 W | The biggest single line item; it's the *medium*, you can't avoid it |
| Camera + ISP (receiver, 1080p@30) | 0.6–1.0 W | Trim: 720p preview, NOISE_REDUCTION OFF |
| CPU decode — gated ROI pipeline | 0.2–0.6 W | vs 2–4 W if decoding every full frame (section 1.1) |
| Total | **~2–4 W** | 4000 mAh → 1.5–2.5 h *continuous* session; but transfers are minutes, so ~20–60 mAh per transfer — negligible battery story |

**Thermal timeline:** sustained 3–4 W CPU+GPU on a midrange phone (no vapor chamber) → junction hits the throttle ceiling in **~5–15 min**; flagships 20–40 min; a 2-minute transfer **never throttles** *if* the decode pipeline is gated. The only way to thermal-crash this app is the checksum storm of section 1.1 — which is now structurally impossible.

### 3.2 Countermeasures

**T1 — Thermal state machine (use the OS, don't guess).**
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
Plus the reverse on the sender: `setFrameRate` down, hold-time up (slower stream = fewer camera-side decodes needed).

**T2 — Zero-allocation render path (this is where Compose will betray you).**
Per-frame `Bitmap.createBitmap` / `Image.toBitmap()` churn = GC pauses, jank, and hidden CPU. The hot path must be:
- **Sender:** pre-render each symbol's QR into a `Bitmap` once (ZXing `QRCodeWriter` renders a 177×177 matrix in <1 ms); blit with `Canvas.drawBitmap` inside a **plain `View.onDraw`/`SurfaceView`**, paced by `Choreographer`/`Handler` — **not** Compose recomposition (no per-frame `setContent`-level invalidation, no per-frame allocation). Compose stays for the UI shell; the QR plane is a dedicated composited layer. Lock `FLAG_KEEP_SCREEN_ON`, force max brightness, immersive sticky mode (gesture bar/notch must never overlap the quiet zone).
- **Receiver:** `ImageReader` with `maxImages = 3`, YUV→luma on the ROI only (never full-frame `toBitmap()`), reusable buffers, `@Volatile` swap of the "current frame" reference instead of queue copies.

**T3 — Pipeline backpressure (drop-oldest, always).**
The receiver pipeline is `camera → gate → decode → fountain → verify`. Use a `Channel<Frame>(capacity = 1, onBufferOverflow = DROP_OLDEST)`: a frame older than ~150 ms is *useless* (the symbol it shows is gone); decoding stale frames is pure waste. Decode on `Dispatchers.Default.limitedParallelism(2)`; never more. The fountain layer consumes unordered symbols, so dropping is free.

**T4 — Battery governance:** partial wakelock with timeout only during an active session; release on pause/lock; disable battery-optimization only via the proper system prompt (don't be that app). Reject "background scanning" — this is a foreground, user-facing session protocol by design.

---

## 4. CONTRAST, AMBIENT LIGHT & SCREEN GUARDS

### 4.1 The physics — and the two hard floors

Contrast ratio (CR) of the displayed QR, as seen by the camera:
- **Indoors, clean glass, max brightness:** OLED white ≈ 700–1500 nits, black ≈ 0.5 nit + ~2% reflected ambient → CR ≈ **300–800:1**. Fine.
- **Direct sun (~100 klx):** reflected ambient adds ~200–500 nits to the *black* modules (the panel is a mirror) → CR collapses to **~2–5:1**. Even Otsu thresholding fails around 2:1; around 3–4:1 it's marginal. **Sunlight is a hard floor** — software cannot manufacture photons the panel doesn't emit. Mitigation is physical (shade, angle, cupped hands) + UI coaching.
- **Matte / privacy screen protector:** a privacy film also collapses the **viewing cone to ±30° or less** and diffuses module edges. Head-on alignment becomes mandatory, and effective CR indoors drops to ~10–30:1. **This is the second hard floor** — no algorithm recovers scattered light.

### 4.2 Countermeasures (do these; they're cheap and real)

**E1 — Meter off the QR, not the scene.** Set `AE_REGIONS`/`AF_REGIONS` to the QR bounding box during calibration, lock AE (section 1). The scene behind the phone must not move the exposure.
**E2 — Calibration pose detects and *refuses* bad links.** The 2–3 s calibration frame measures: achieved CR (from the p99/p1 gate), blur margin, banding variance (section 2.2). If CR < ~5:1 → tell the user "move out of direct sun / remove screen protector / go head-on" *before* wasting a transfer. This converts the two hard floors from silent failures into guided UX.
**E3 — Adaptive threshold, not gamma, is the fix.** On the ROI: 3×3 median → Otsu or ZXing `HybridBinarizer`. Global "gamma correction" is the wrong tool (the problem is *spatially varying* illumination across the screen — glare gradients). CLAHE on the ROI is justified only if a glare hotspot is detected by the gate; it costs ~2–3 ms on a 128×128 ROI. Don't run it unconditionally.
**E4 — The alignment coach (the actual product feature).** Live HUD: blur score, CR, and a "frame-now" hint driven by the gate metrics, plus a corner-guide overlay that tracks the QR rect. Humans fix angle/glare/shake in ~3 seconds when shown a number; they flail for minutes when shown nothing.

---

## 5. SECURITY & DATA INTEGRITY (MALICIOUS INJECTION)

### 5.1 Threat model (be honest about it)

No server, no radio, no back-channel. The camera is an *unauthenticated optical input*. Attackers can: **splice** their own screen into the optical path (show their own QR stream to the receiver), **replay** previously captured frames, or **bit-flip** frames via a display overlay. What the receiver must provide: (a) **integrity** — the reassembled file is byte-identical to what the sender encoded; (b) **authenticity** — the stream came from the intended sender; (c) **binding** — frame i belongs to this session's symbol set, not an old one.

### 5.2 The answer: per-block Ed25519 + CRC32, with SAS bootstrap

- **Header frame (sent repeatedly, interleaved with data — it's the trust anchor):** session ID, manifest (file name, total size, symbol count, block size), sender's ephemeral Ed25519 public key, and a **SAS** (short authentication string, 8–10 digits = hash of the public key) displayed on *both* screens for the humans to compare — ZRTP-style, no server, no prior key distribution. Pairing: two humans standing next to each other can read 8 digits aloud. That's the whole PKI.
- **Every data frame:** `[magic(2) | version(1) | symbolId(2) | total(2) | flags(1) | payload(~2.7 KB) | CRC32(4) | Ed25519-sig(64)]`. Verify order per frame: CRC32 → signature → symbol id bounds → fountain insert.
- **Why per-block signature and not a Merkle/hash-chain:** fountain codes deliver symbols **out of order** — a hash chain *forces* ordering and destroys the entire point of fountain coding. A Merkle tree works unordered but costs a ~8×32 B proof per symbol and complicates the header. Per-block Ed25519 is simpler, unordered, and cheap. (BLAKE3/SHA-256 **of the whole file** runs once at reassembly as the final gate — mandatory, microseconds on 500 KB.)
- **Replay of old frames is harmless** by construction: the header carries the session ID; old-session symbols fail the signature check (ephemeral key) and are dropped by the BitSet. An attacker replaying *this* session's frames is just... resending valid data — fountain codes don't care.
- **What crypto cannot do (say it in the docs):** a spliced stream where the attacker *is* the intended sender (compromised device) is undetectable at this layer; and physical interception (attacker photographs the screen) is a confidentiality leak no receiver-side crypto fixes. If confidentiality matters, encrypt the payload with the pre-shared SAS-derived key — 100 bytes of code, ~0% throughput cost.

**Cost numbers (the audit question):** Ed25519 verify ≈ 40–80 µs/symbol; 190 symbols ≈ **~15 ms total**. Signature overhead 64 B / ~2.7 KB ≈ **2.4% throughput**. SHA-256 of the file: ~1–2 ms. There is **no meaningful decode-speed impact — make it mandatory**. Skip it and the system is trivially injectable; keep it and the cost is noise. Note also: per-symbol CRC32 catches *false decodes* (QR ECC can decode "successfully" with residual errors at high module-error rates) — without it, a corrupted-but-valid-checksum symbol could poison the fountain reassembly.

---

## 6. HIDDEN EDGE CASES (the ones nobody lists in the README)

1. **The "GIF" trap.** If "animated GIF" means *literal GIF89a files*: GIF's 256-color palette + dithering **destroys QR module edges**. A GIF works only with a forced 2-color (pure B/W) palette and dithering disabled — then it's lossless. And GIF's 10–15 fps timing is unrelated to camera sync (section 2). **Architectural ruling: render symbols live from pre-rasterized bitmaps; if pre-rendered media is required, use lossless PNG/WebP sequences or a B/W no-dither GIF.** Never pass a photo-style GIF through a sender.
2. **"Completely offline" vs ML Kit.** ML Kit's on-device barcode API technically works offline but the dependency graph (Play Services) is a deployment risk on AOSP/enterprise/air-gapped devices — the exact audience for this tool. ZXing core is a single jar, no GMS, deterministic. (Already covered in C4 — repeat here because it's a *product-definition* decision, not an implementation detail.)
3. **Safe-area / cutouts / gesture bar.** The QR needs a full 4-module quiet zone; notches, rounded corners, and the gesture pill crop it. Sender must render inside `WindowInsets`-safe bounds, immersive sticky, and account for camera cutouts on punch-hole displays.
4. **Auto-brightness mid-transfer.** The ambient-light sensor will ramp screen brightness (or the user's hand covers the sensor) *during* a transfer, changing exposure conditions the receiver locked at calibration. Sender: hold brightness constant (WRITE_SETTINGS is optional; a full-screen bright QR already defeats most auto-brightness — verify per device). Receiver: re-trigger AE-lock only on a gate-threshold breach.
5. **Doze / battery saver / screen timeout.** Partial wakelock with timeout, session-scoped; on timeout → pause state machine, never crash. Battery saver dimming must be pre-empted on the sender.
6. **Symbol ID space & dedup memory.** 500 KB / 2.7 KB ≈ 190 symbols → 16-bit ID is ample. Use a `BitSet(65536)` — a HashMap of 190 keys is fine too, but BitSet is allocation-free after init.
7. **Multi-file / multi-session / resume.** The session ID + manifest make resume trivial (receiver keeps the fountain buffer, skips known symbols). Design the session state machine (`IDLE → CALIBRATING → RECEIVING → VERIFYING → COMPLETE/ABORTED`) as the domain model — it's the only state that matters, and it makes the Clean Architecture boundaries obvious.
8. **The sender's own camera.** If the sender also runs the *receiver* role later (bidirectional), note that the sender's preview feed must not interfere with rendering; on most devices it won't, but test per-OEM (some OEMs throttle the display pipeline when camera is active — this is real, e.g., thermal-coupled panel behavior).

---

## 7. THE OPERATING ENVELOPE (the contract that makes it "stable")

| Parameter | Guaranteed operating range | Outside → behavior |
|---|---|---|
| Device positioning | Both phones stationary (desk/stand or elbows planted), distance 15–40 cm, angle < 20° | Quality coach + motion gate; auto-pause |
| Lighting | Indoor/indirect; no direct sun on sender screen | Calibration refuses below CR ~5:1 |
| Screen protector | Clean glass (≤ mild matte); no privacy film | Calibration warns/refuses |
| Payload | ≤ 1 MB (soft ceiling; 100–500 KB recommended) | Session protocol + resume; no hard failure |
| Sender rate | 8–12 fps, hold ≥ 100 ms, fixed 60 Hz | Receiver gate drops frames; fountain absorbs |
| Duration | Sessions ≤ ~5 min continuous | Thermal governor duty-cycles; resume supported |
| Security | SAS compared by humans; per-block Ed25519 verified | Failure to verify → session abort, no partial write |

Outside this envelope the system degrades *gracefully to a pause/refuse*, never to corruption — that property, not throughput, is what makes it commercial-grade.

---

## 8. ULTIMATE FEASIBILITY VERDICT

**Verdict: YES — as a commercial-grade, niche utility. NO — as a general-purpose transport.**

**Why it can be stable:** every failure mode is physical, measurable, and bounded. The stack — frozen AE/AF, motion + blur + contrast gating, ROI decoding, hold-time phase independence, fixed-60 Hz max-brightness rendering, per-block Ed25519 + whole-file hash, thermal governor, and an alignment coach UI — is implementable in Kotlin/Compose with stock Android APIs (Camera2, ImageReader, PowerManager, Display.setFrameRate, SensorManager). No exotic hardware, no ML, no proprietary math. The concept is field-proven by qrfiletransfer.app and Decimen; your native Camera2 advantage lets you *beat* their web-browser limitations (they cannot lock exposure, force refresh rates, or gate decodes). A well-executed implementation delivers 20–40 KB/s, 100 KB in ~10–20 s, 500 KB in ~1–3 min, at ~99% integrity with cryptographic authenticity — a genuinely useful, demonstrably better alternative to "hold the phone and read the QR one screen at a time" for air-gapped document/voice-note transfer, insider-threat-conscious environments, and demos that need to *show* data moving as light.

**Why it can never be more than that:** the link is line-of-sight, human-aligned, low-bandwidth, and physically capped by the panel's luminance and the camera's optics. It will never compete with Bluetooth/WiFi Direct for general transfer (those hit 1–50 MB/s with zero user effort), and it cannot survive direct sun, privacy protectors, or hand-held motion. Any roadmap that frames this as "Bluetooth replacement" fails; any roadmap that frames it as "secure, air-gapped, zero-radio transfer of small payloads between two cooperating humans" wins.

**What would kill it (ranked):** (1) decoding ungated full frames — thermal death; (2) short symbol hold times — rolling-shutter tears; (3) shipping without per-block signatures — the "secure" claim becomes marketing fiction; (4) no calibration/refusal logic — sunlight and privacy films turn into silent 100% failure rates; (5) per-frame Compose recomposition/bitmap allocation — jank and GC stalls on the render path.

**Suggested staging:** POC (2 weeks): ZXing-ROI pipeline + LT fountain + hold-time pacing on a test bench — validate 8–12 decodes/s and 20–40 KB/s. Hardening (6 weeks): Camera2 freeze, gates, thermal governor, calibration pose, SAS + Ed25519. Commercial polish (12 weeks): alignment coach UX, session/resume, multi-file, per-OEM device matrix (Samsung/OnePlus/Pixel/xiaomi all differ in AF behavior and panel scan modes — budget real device-lab time; this is where "works on my Pixel" products die).

*— Audit complete. The physics is the spec.*
