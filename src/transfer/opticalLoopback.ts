import { HEADER_INTERLEAVE, HOLD_MS, PAYLOAD_BYTES, type Density } from "../protocol/constants";
import { bytesEq, randomBytes } from "../protocol/bytes";
import { fileHash, generateKeyPair, sasFromPublicKey } from "../protocol/crypto";
import { FountainDecoder, FountainEncoder } from "../protocol/fountain";
import { decodeFrame, encodeCal, encodeData, encodeHeader } from "../protocol/frames";
import { locateAndDecode, renderQrCanvas } from "../protocol/qr";

export type OpticalStep = {
  index: number;
  total: number;
  kind: "cal" | "header" | "data";
  unique: number;
  recovered: number;
  k: number;
};

export async function runOpticalCanvasLoopback(
  canvas: HTMLCanvasElement,
  data: Uint8Array,
  fileName: string,
  mime: string,
  density: Density,
  onStep?: (s: OpticalStep) => void,
): Promise<{
  ok: boolean;
  sas: string;
  hashMatch: boolean;
  unique: number;
  recovered: number;
  k: number;
  decodedFrames: number;
  missedFrames: number;
  fileName: string;
  fileSize: number;
  elapsedMs: number;
  message: string;
}> {
  const t0 = performance.now();
  const keys = await generateKeyPair();
  const sessionId = randomBytes(8);
  const sas = sasFromPublicKey(keys.publicKey);
  const blockSize = PAYLOAD_BYTES[density];
  const encoder = new FountainEncoder(data, blockSize);
  const headerBytes = await encodeHeader(
    {
      sessionId,
      fileName,
      fileSize: data.length,
      k: encoder.k,
      blockSize,
      fileHash: fileHash(data),
      publicKey: keys.publicKey,
      mime,
    },
    keys.secretKey,
  );
  const decoder = new FountainDecoder(encoder.k, blockSize, data.length);
  let decodedFrames = 0;
  let missedFrames = 0;
  let publicKey: Uint8Array | undefined;

  const size = Math.min(480, Math.max(280, canvas.clientWidth || 400));

  const capture = async (payload: Uint8Array, kind: OpticalStep["kind"], index: number, total: number) => {
    await renderQrCanvas(canvas, payload, density, size);
    await new Promise((r) => requestAnimationFrame(() => r(null)));
    const ctx = canvas.getContext("2d");
    if (!ctx) throw new Error("canvas 2d unavailable");
    const image = ctx.getImageData(0, 0, canvas.width, canvas.height);
    const located = locateAndDecode(image);
    if (!located?.binary) {
      missedFrames++;
      onStep?.({ index, total, kind, unique: decoder.uniqueCount, recovered: decoder.doneCount, k: decoder.k });
      return;
    }
    const frame = await decodeFrame(located.binary, publicKey);
    if (!frame) {
      missedFrames++;
      onStep?.({ index, total, kind, unique: decoder.uniqueCount, recovered: decoder.doneCount, k: decoder.k });
      return;
    }
    decodedFrames++;
    if (frame.kind === 1) publicKey = frame.publicKey;
    if (frame.kind === 2) decoder.ingest(frame.symbolId, frame.payload);
    onStep?.({ index, total, kind, unique: decoder.uniqueCount, recovered: decoder.doneCount, k: decoder.k });
  };

  const cal = encodeCal(sessionId);
  const total = encoder.recommendedSymbols() + 4;
  await capture(cal, "cal", 0, total);
  await capture(headerBytes, "header", 1, total);

  let i = 0;
  while (!decoder.isComplete() && i < encoder.recommendedSymbols() + encoder.k) {
    if (i > 0 && i % HEADER_INTERLEAVE === 0) {
      await capture(headerBytes, "header", i + 2, total);
    }
    const { symbolId, payload } = encoder.encode(i);
    const frame = await encodeData(sessionId, symbolId, payload, keys.secretKey);
    await capture(frame, "data", i + 2, total);
    i++;
    await new Promise((r) => setTimeout(r, Math.min(24, HOLD_MS[density] / 8)));
  }

  const assembled = decoder.assemble();
  const hashMatch = assembled ? bytesEq(fileHash(assembled), fileHash(data)) : false;
  const ok = Boolean(assembled && hashMatch);

  return {
    ok,
    sas,
    hashMatch,
    unique: decoder.uniqueCount,
    recovered: decoder.doneCount,
    k: decoder.k,
    decodedFrames,
    missedFrames,
    fileName,
    fileSize: data.length,
    elapsedMs: performance.now() - t0,
    message: ok
      ? "Screen pixels decoded, signatures verified, SHA-256 matched."
      : `Optical decode incomplete (${decoder.doneCount}/${decoder.k}). missed=${missedFrames}`,
  };
}
