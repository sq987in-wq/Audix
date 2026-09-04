import { KIND_CAL, KIND_DATA, KIND_HEADER } from "../protocol/constants";
import { bytesEq } from "../protocol/bytes";
import { fileHash, sasFromPublicKey } from "../protocol/crypto";
import { FountainDecoder } from "../protocol/fountain";
import { decodeFrame, type HeaderFrame } from "../protocol/frames";
import { locateAndDecode } from "../protocol/qr";
import { gateFrame, MotionGate, type GateResult } from "../vision/gates";
import type { CoachMetrics } from "../protocol/session";

export type ReceiverEvent =
  | { type: "state"; state: ReceiverState }
  | { type: "coach"; metrics: CoachMetrics }
  | { type: "header"; header: HeaderFrame; sas: string }
  | { type: "progress"; unique: number; recovered: number; k: number }
  | { type: "complete"; blob: Blob; fileName: string; fileHash: string }
  | { type: "error"; message: string }
  | { type: "refuse"; message: string };

export type ReceiverState =
  | "idle"
  | "calibrating"
  | "pairing"
  | "receiving"
  | "verifying"
  | "complete"
  | "aborted"
  | "paused";

export class OpticalReceiver {
  private video: HTMLVideoElement;
  private overlay: HTMLCanvasElement;
  private stream: MediaStream | null = null;
  private running = false;
  private raf = 0;
  private decoder: FountainDecoder | null = null;
  private header: HeaderFrame | null = null;
  private lastRect: DOMRect | null = null;
  private motion = new MotionGate();
  private lastDecode = 0;
  private fpsWindow: number[] = [];
  private decodeBusy = false;
  private workCanvas = document.createElement("canvas");
  private workCtx: CanvasRenderingContext2D;
  private listeners = new Set<(e: ReceiverEvent) => void>();
  private accelHandler: ((e: DeviceMotionEvent) => void) | null = null;
  state: ReceiverState = "idle";
  sas = "";

  constructor(video: HTMLVideoElement, overlay: HTMLCanvasElement) {
    this.video = video;
    this.overlay = overlay;
    const ctx = this.workCanvas.getContext("2d", { willReadFrequently: true });
    if (!ctx) throw new Error("2d context unavailable");
    this.workCtx = ctx;
  }

  on(fn: (e: ReceiverEvent) => void): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  private emit(e: ReceiverEvent): void {
    for (const fn of this.listeners) fn(e);
  }

  private setState(s: ReceiverState): void {
    this.state = s;
    this.emit({ type: "state", state: s });
  }

  async start(): Promise<void> {
    this.running = true;
    this.setState("calibrating");
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: {
          facingMode: { ideal: "environment" },
          width: { ideal: 1280 },
          height: { ideal: 720 },
          frameRate: { ideal: 30 },
        },
      });
    } catch {
      this.stream = await navigator.mediaDevices.getUserMedia({ video: true, audio: false });
    }
    this.video.srcObject = this.stream;
    this.video.setAttribute("playsinline", "true");
    this.video.muted = true;
    await this.video.play();
    this.attachMotion();
    this.loop();
  }

  stop(): void {
    this.running = false;
    cancelAnimationFrame(this.raf);
    this.stream?.getTracks().forEach((t) => t.stop());
    this.stream = null;
    if (this.accelHandler) {
      window.removeEventListener("devicemotion", this.accelHandler);
      this.accelHandler = null;
    }
    if (this.state !== "complete") this.setState("aborted");
  }

  private attachMotion(): void {
    this.accelHandler = (e: DeviceMotionEvent) => {
      const a = e.accelerationIncludingGravity;
      if (!a) return;
      this.motion.push(a.x ?? 0, a.y ?? 0, a.z ?? 0);
    };
    window.addEventListener("devicemotion", this.accelHandler);
  }

  private loop = (): void => {
    if (!this.running) return;
    this.raf = requestAnimationFrame(this.loop);
    this.tick();
  };

  private tick(): void {
    const vw = this.video.videoWidth;
    const vh = this.video.videoHeight;
    if (!vw || !vh) return;

    const maxW = 640;
    const scale = Math.min(1, maxW / vw);
    const w = Math.max(32, Math.floor(vw * scale));
    const h = Math.max(32, Math.floor(vh * scale));
    if (this.workCanvas.width !== w || this.workCanvas.height !== h) {
      this.workCanvas.width = w;
      this.workCanvas.height = h;
    }
    this.workCtx.drawImage(this.video, 0, 0, w, h);
    const image = this.workCtx.getImageData(0, 0, w, h);
    const gate = gateFrame(image, this.lastRect);
    this.drawOverlay(w, h, gate);

    const now = performance.now();
    this.fpsWindow.push(now);
    while (this.fpsWindow.length && now - this.fpsWindow[0]! > 1000) this.fpsWindow.shift();

    const metrics: CoachMetrics = {
      blur: gate.blur,
      contrast: gate.contrast,
      motion: this.motion.magnitude,
      fps: this.fpsWindow.length,
      decodeMs: this.lastDecode,
      unique: this.decoder?.uniqueCount ?? 0,
      recovered: this.decoder?.doneCount ?? 0,
      k: this.decoder?.k ?? this.header?.k ?? 0,
      gatePass: gate.pass && this.motion.stable,
      reason: !this.motion.stable ? "hold still" : gate.reason,
    };
    this.emit({ type: "coach", metrics });

    if (gate.refuse && this.state === "calibrating") {
      this.emit({
        type: "refuse",
        message: "Contrast below physical floor. Move out of direct sun, remove privacy film, go head-on.",
      });
    }

    if (this.decodeBusy) return;
    if (now - this.lastDecode < 40) return;
    if (this.state === "complete" || this.state === "verifying") return;

    this.decodeBusy = true;
    const started = performance.now();
    void this.decodeImage(image)
      .catch(() => undefined)
      .finally(() => {
        this.lastDecode = performance.now() - started;
        this.decodeBusy = false;
      });
  }

  private async decodeImage(image: ImageData): Promise<void> {
    const located = locateAndDecode(image);
    if (!located) return;

    const loc = located.location;
    const xs = [loc.topLeftCorner.x, loc.topRightCorner.x, loc.bottomLeftCorner.x, loc.bottomRightCorner.x];
    const ys = [loc.topLeftCorner.y, loc.topRightCorner.y, loc.bottomLeftCorner.y, loc.bottomRightCorner.y];
    const minX = Math.min(...xs);
    const minY = Math.min(...ys);
    this.lastRect = new DOMRect(minX, minY, Math.max(...xs) - minX, Math.max(...ys) - minY);

    if (!located.binary) return;
    const frame = await decodeFrame(located.binary, this.header?.publicKey);
    if (!frame) return;

    if (frame.kind === KIND_CAL) {
      if (this.state === "calibrating") this.setState("pairing");
      return;
    }

    if (frame.kind === KIND_HEADER) {
      if (this.header && !bytesEq(this.header.sessionId, frame.sessionId)) return;
      if (!this.header) {
        this.header = frame;
        this.sas = sasFromPublicKey(frame.publicKey);
        this.decoder = new FountainDecoder(frame.k, frame.blockSize, frame.fileSize);
        this.emit({ type: "header", header: frame, sas: this.sas });
        this.setState("pairing");
      }
      return;
    }

    if (frame.kind === KIND_DATA) {
      if (!this.header || !this.decoder) return;
      if (!bytesEq(frame.sessionId, this.header.sessionId)) return;
      if (this.state === "pairing") this.setState("receiving");
      const wasNew = this.decoder.ingest(frame.symbolId, frame.payload);
      if (wasNew) {
        this.emit({
          type: "progress",
          unique: this.decoder.uniqueCount,
          recovered: this.decoder.doneCount,
          k: this.decoder.k,
        });
      }
      if (this.decoder.isComplete()) await this.finish();
    }
  }

  private async finish(): Promise<void> {
    if (!this.decoder || !this.header || this.state === "verifying" || this.state === "complete") return;
    this.setState("verifying");
    const data = this.decoder.assemble();
    if (!data) {
      this.emit({ type: "error", message: "Fountain reassembly failed" });
      this.setState("aborted");
      return;
    }
    const hash = fileHash(data);
    if (!bytesEq(hash, this.header.fileHash)) {
      this.emit({ type: "error", message: "SHA-256 mismatch — file discarded, no partial write" });
      this.setState("aborted");
      return;
    }
    const copy = new Uint8Array(data.byteLength);
    copy.set(data);
    const blob = new Blob([copy], { type: this.header.mime || "application/octet-stream" });
    this.emit({
      type: "complete",
      blob,
      fileName: this.header.fileName,
      fileHash: Array.from(hash)
        .map((x) => x.toString(16).padStart(2, "0"))
        .join(""),
    });
    this.setState("complete");
    this.running = false;
    this.stream?.getTracks().forEach((t) => t.stop());
  }

  private drawOverlay(w: number, h: number, gate: GateResult): void {
    const ctx = this.overlay.getContext("2d");
    if (!ctx) return;
    const ow = this.overlay.clientWidth || this.overlay.width;
    const oh = this.overlay.clientHeight || this.overlay.height;
    if (this.overlay.width !== ow || this.overlay.height !== oh) {
      this.overlay.width = ow;
      this.overlay.height = oh;
    }
    ctx.clearRect(0, 0, this.overlay.width, this.overlay.height);
    const sx = this.overlay.width / w;
    const sy = this.overlay.height / h;
    const color = gate.pass && this.motion.stable ? "rgba(196, 154, 74, 0.95)" : "rgba(196, 92, 62, 0.9)";
    ctx.strokeStyle = color;
    ctx.lineWidth = 2;
    if (this.lastRect) {
      ctx.strokeRect(
        this.lastRect.x * sx,
        this.lastRect.y * sy,
        this.lastRect.width * sx,
        this.lastRect.height * sy,
      );
    } else {
      const m = 0.18;
      ctx.setLineDash([8, 8]);
      ctx.strokeRect(this.overlay.width * m, this.overlay.height * m, this.overlay.width * (1 - 2 * m), this.overlay.height * (1 - 2 * m));
      ctx.setLineDash([]);
    }
  }
}
