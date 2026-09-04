import {
  CALIBRATION_MS,
  HEADER_INTERLEAVE,
  HOLD_MS,
  KIND_CAL,
  KIND_DATA,
  KIND_HEADER,
  PAYLOAD_BYTES,
  type Density,
} from "../protocol/constants";
import { generateKeyPair, fileHash, sasFromPublicKey, type KeyPair } from "../protocol/crypto";
import { FountainEncoder } from "../protocol/fountain";
import { encodeCal, encodeData, encodeHeader } from "../protocol/frames";
import { renderQrCanvas } from "../protocol/qr";
import { randomBytes } from "../protocol/bytes";

export type SenderEvent =
  | { type: "state"; state: SenderState }
  | { type: "symbol"; index: number; total: number; kind: number }
  | { type: "sas"; sas: string; publicKey: Uint8Array; sessionId: Uint8Array }
  | { type: "error"; message: string };

export type SenderState = "idle" | "calibrating" | "pairing" | "sending" | "complete" | "aborted";

export class OpticalSender {
  private canvas: HTMLCanvasElement;
  private density: Density;
  private keys: KeyPair | null = null;
  private sessionId: Uint8Array | null = null;
  private encoder: FountainEncoder | null = null;
  private headerBytes: Uint8Array | null = null;
  private calBytes: Uint8Array | null = null;
  private timer: number | null = null;
  private aborted = false;
  private listeners = new Set<(e: SenderEvent) => void>();
  private symbolCursor = 0;
  private recommended = 0;
  state: SenderState = "idle";
  sas = "";
  fileName = "";
  fileSize = 0;

  constructor(canvas: HTMLCanvasElement, density: Density = "standard") {
    this.canvas = canvas;
    this.density = density;
  }

  on(fn: (e: SenderEvent) => void): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  private emit(e: SenderEvent): void {
    for (const fn of this.listeners) fn(e);
  }

  private setState(s: SenderState): void {
    this.state = s;
    this.emit({ type: "state", state: s });
  }

  stop(): void {
    this.aborted = true;
    if (this.timer !== null) {
      window.clearTimeout(this.timer);
      this.timer = null;
    }
    if (this.state !== "complete") this.setState("aborted");
  }

  async prepare(file: File): Promise<void> {
    this.aborted = false;
    this.fileName = file.name;
    this.fileSize = file.size;
    const buf = new Uint8Array(await file.arrayBuffer());
    this.keys = await generateKeyPair();
    this.sessionId = randomBytes(8);
    this.sas = sasFromPublicKey(this.keys.publicKey);
    const blockSize = PAYLOAD_BYTES[this.density];
    this.encoder = new FountainEncoder(buf, blockSize);
    this.recommended = this.encoder.recommendedSymbols();
    this.calBytes = encodeCal(this.sessionId);
    this.headerBytes = await encodeHeader(
      {
        sessionId: this.sessionId,
        fileName: file.name,
        fileSize: file.size,
        k: this.encoder.k,
        blockSize,
        fileHash: fileHash(buf),
        publicKey: this.keys.publicKey,
        mime: file.type || "application/octet-stream",
      },
      this.keys.secretKey,
    );
    this.emit({
      type: "sas",
      sas: this.sas,
      publicKey: this.keys.publicKey,
      sessionId: this.sessionId,
    });
  }

  async run(): Promise<void> {
    if (!this.encoder || !this.keys || !this.sessionId || !this.headerBytes || !this.calBytes) {
      this.emit({ type: "error", message: "Sender not prepared" });
      return;
    }
    try {
      this.setState("calibrating");
      await this.hold(this.calBytes, KIND_CAL, CALIBRATION_MS);
      if (this.aborted) return;
      this.setState("pairing");
      await this.hold(this.headerBytes, KIND_HEADER, 2200);
      if (this.aborted) return;
      this.setState("sending");
      const hold = HOLD_MS[this.density];
      const total = this.recommended;
      this.symbolCursor = 0;
      while (!this.aborted && this.symbolCursor < total) {
        if (this.symbolCursor > 0 && this.symbolCursor % HEADER_INTERLEAVE === 0) {
          await this.hold(this.headerBytes, KIND_HEADER, hold);
          if (this.aborted) return;
        }
        const { symbolId, payload } = this.encoder.encode(this.symbolCursor);
        const frame = await encodeData(this.sessionId, symbolId, payload, this.keys.secretKey);
        await this.hold(frame, KIND_DATA, hold);
        this.emit({ type: "symbol", index: this.symbolCursor + 1, total, kind: KIND_DATA });
        this.symbolCursor++;
      }
      if (!this.aborted) {
        await this.hold(this.headerBytes, KIND_HEADER, hold * 2);
        this.setState("complete");
      }
    } catch (err) {
      this.emit({ type: "error", message: err instanceof Error ? err.message : String(err) });
      this.setState("aborted");
    }
  }

  private async hold(payload: Uint8Array, kind: number, ms: number): Promise<void> {
    const size = Math.min(720, Math.floor(Math.min(window.innerWidth, window.innerHeight) * 0.78));
    await renderQrCanvas(this.canvas, payload, this.density, size);
    this.emit({ type: "symbol", index: this.symbolCursor, total: this.recommended, kind });
    await this.sleep(ms);
  }

  private sleep(ms: number): Promise<void> {
    return new Promise((resolve) => {
      this.timer = window.setTimeout(() => {
        this.timer = null;
        resolve();
      }, ms);
    });
  }

}
