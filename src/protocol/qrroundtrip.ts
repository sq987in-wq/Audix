import QRCode from "qrcode";
import jsQR from "jsqr";
import { PNG } from "pngjs";
import { generateKeyPair } from "./crypto";
import { encodeCal, encodeData, encodeHeader, decodeFrame } from "./frames";
import { FountainEncoder } from "./fountain";
import { fileHash } from "./crypto";
import { randomBytes } from "./bytes";

async function qrPixels(payload: Uint8Array): Promise<Uint8Array> {
  const buf = await QRCode.toBuffer([{ data: payload, mode: "byte" }], {
    errorCorrectionLevel: "M",
    margin: 4,
    width: 400,
    color: { dark: "#000000", light: "#FFFFFF" },
  });
  const png = PNG.sync.read(buf);
  const result = jsQR(
    new Uint8ClampedArray(png.data.buffer, png.data.byteOffset, png.data.byteLength),
    png.width,
    png.height,
    { inversionAttempts: "attemptBoth" },
  );
  if (!result) throw new Error("jsQR missed QR");
  return Uint8Array.from(result.binaryData);
}

async function main(): Promise<void> {
  const keys = await generateKeyPair();
  const sessionId = randomBytes(8);
  const data = new TextEncoder().encode("candela-qr-roundtrip");
  const enc = new FountainEncoder(data, 32);
  const header = await encodeHeader(
    {
      sessionId,
      fileName: "t.txt",
      fileSize: data.length,
      k: enc.k,
      blockSize: 32,
      fileHash: fileHash(data),
      publicKey: keys.publicKey,
      mime: "text/plain",
    },
    keys.secretKey,
  );
  const cal = encodeCal(sessionId);
  const { symbolId, payload } = enc.encode(0);
  const dat = await encodeData(sessionId, symbolId, payload, keys.secretKey);

  for (const [name, raw] of [
    ["cal", cal],
    ["header", header],
    ["data", dat],
  ] as const) {
    const recovered = await qrPixels(raw);
    const frame = await decodeFrame(recovered, keys.publicKey);
    if (!frame) throw new Error(`${name} decodeFrame failed after QR`);
    console.log("PASS", name, "kind", frame.kind, "bytes", recovered.length);
  }
}

void main();
