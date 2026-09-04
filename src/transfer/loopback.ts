import { HEADER_INTERLEAVE, PAYLOAD_BYTES, type Density } from "../protocol/constants";
import { bytesEq, randomBytes } from "../protocol/bytes";
import { fileHash, generateKeyPair, sasFromPublicKey } from "../protocol/crypto";
import { FountainDecoder, FountainEncoder } from "../protocol/fountain";
import { decodeFrame, encodeCal, encodeData, encodeHeader } from "../protocol/frames";

export type BenchResult = {
  ok: boolean;
  fileName: string;
  fileSize: number;
  k: number;
  symbolsSent: number;
  uniqueReceived: number;
  recovered: number;
  dropped: number;
  elapsedMs: number;
  sas: string;
  hashMatch: boolean;
  message: string;
};

export async function runLoopbackBench(
  data: Uint8Array,
  fileName: string,
  mime: string,
  density: Density,
  dropRate = 0.22,
): Promise<BenchResult> {
  const t0 = performance.now();
  const keys = await generateKeyPair();
  const sessionId = randomBytes(8);
  const sas = sasFromPublicKey(keys.publicKey);
  const blockSize = PAYLOAD_BYTES[density];
  const encoder = new FountainEncoder(data, blockSize);
  const header = await encodeHeader(
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
  const cal = encodeCal(sessionId);
  const decoder = new FountainDecoder(encoder.k, blockSize, data.length);

  let symbolsSent = 0;
  let dropped = 0;

  const ingest = async (raw: Uint8Array, force = false) => {
    if (!force && Math.random() < dropRate) {
      dropped++;
      return;
    }
    const frame = await decodeFrame(raw, keys.publicKey);
    if (!frame) return;
    if (frame.kind === 2) decoder.ingest(frame.symbolId, frame.payload);
  };

  await ingest(cal, true);
  await ingest(header, true);

  const budget = encoder.recommendedSymbols() + encoder.k;
  for (let i = 0; i < budget && !decoder.isComplete(); i++) {
    if (i > 0 && i % HEADER_INTERLEAVE === 0) await ingest(header);
    const { symbolId, payload } = encoder.encode(i);
    const frame = await encodeData(sessionId, symbolId, payload, keys.secretKey);
    symbolsSent++;
    await ingest(frame);
  }

  const assembled = decoder.assemble();
  const hashMatch = assembled ? bytesEq(fileHash(assembled), fileHash(data)) : false;
  const ok = Boolean(assembled && hashMatch && decoder.isComplete());

  return {
    ok,
    fileName,
    fileSize: data.length,
    k: encoder.k,
    symbolsSent,
    uniqueReceived: decoder.uniqueCount,
    recovered: decoder.doneCount,
    dropped,
    elapsedMs: performance.now() - t0,
    sas,
    hashMatch,
    message: ok
      ? "Integrity verified. Per-block Ed25519 + SHA-256 + fountain peel all passed."
      : "Reassembly failed under simulated optical drop. Increase hold time or lower density.",
  };
}
