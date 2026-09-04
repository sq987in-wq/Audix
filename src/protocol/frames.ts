import { concat, readU16, readU32, u16be, u32be, utf8, utf8decode } from "./bytes";
import { crc32, crc32Bytes, readCrc32 } from "./crc32";
import {
  HASH_LEN,
  KIND_CAL,
  KIND_DATA,
  KIND_HEADER,
  MAGIC,
  PROTOCOL_VERSION,
  PUBKEY_LEN,
  SESSION_ID_LEN,
  SIG_LEN,
} from "./constants";
import { sign, verify } from "./crypto";

export type CalFrame = {
  kind: typeof KIND_CAL;
  sessionId: Uint8Array;
};

export type HeaderPayload = {
  sessionId: Uint8Array;
  fileName: string;
  fileSize: number;
  k: number;
  blockSize: number;
  fileHash: Uint8Array;
  publicKey: Uint8Array;
  mime: string;
};

export type HeaderFrame = HeaderPayload & {
  kind: typeof KIND_HEADER;
  signature: Uint8Array;
};

export type DataFrame = {
  kind: typeof KIND_DATA;
  sessionId: Uint8Array;
  symbolId: number;
  payload: Uint8Array;
  signature: Uint8Array;
};

export type DecodedFrame = CalFrame | HeaderFrame | DataFrame;

function magicOk(b: Uint8Array): boolean {
  return b.length >= 2 && b[0] === MAGIC[0] && b[1] === MAGIC[1];
}

export function encodeCal(sessionId: Uint8Array): Uint8Array {
  const body = concat(MAGIC, new Uint8Array([PROTOCOL_VERSION, KIND_CAL]), sessionId);
  return concat(body, crc32Bytes(body));
}

export function encodeHeaderBody(h: HeaderPayload): Uint8Array {
  const name = utf8(h.fileName.slice(0, 180));
  const mime = utf8((h.mime || "application/octet-stream").slice(0, 80));
  return concat(
    MAGIC,
    new Uint8Array([PROTOCOL_VERSION, KIND_HEADER]),
    h.sessionId,
    u16be(name.length),
    name,
    u32be(h.fileSize),
    u16be(h.k),
    u16be(h.blockSize),
    h.fileHash,
    h.publicKey,
    u16be(mime.length),
    mime,
  );
}

export async function encodeHeader(h: HeaderPayload, secretKey: Uint8Array): Promise<Uint8Array> {
  const body = encodeHeaderBody(h);
  const signature = await sign(body, secretKey);
  return concat(body, signature, crc32Bytes(concat(body, signature)));
}

export async function encodeData(
  sessionId: Uint8Array,
  symbolId: number,
  payload: Uint8Array,
  secretKey: Uint8Array,
): Promise<Uint8Array> {
  const body = concat(
    MAGIC,
    new Uint8Array([PROTOCOL_VERSION, KIND_DATA]),
    sessionId,
    u16be(symbolId),
    u16be(payload.length),
    payload,
  );
  const signature = await sign(body, secretKey);
  return concat(body, signature, crc32Bytes(concat(body, signature)));
}

export async function decodeFrame(raw: Uint8Array, expectedKey?: Uint8Array): Promise<DecodedFrame | null> {
  if (raw.length < 8 || !magicOk(raw)) return null;
  if (raw[2] !== PROTOCOL_VERSION) return null;

  const crcOff = raw.length - 4;
  const bodyWithSig = raw.subarray(0, crcOff);
  if (crc32(bodyWithSig) !== readCrc32(raw, crcOff)) return null;

  const kind = raw[3]!;
  if (kind === KIND_CAL) {
    if (raw.length < 4 + SESSION_ID_LEN + 4) return null;
    return { kind: KIND_CAL, sessionId: raw.subarray(4, 4 + SESSION_ID_LEN) };
  }

  if (kind === KIND_HEADER) {
    let o = 4;
    const sessionId = raw.subarray(o, o + SESSION_ID_LEN);
    o += SESSION_ID_LEN;
    const nameLen = readU16(raw, o);
    o += 2;
    const fileName = utf8decode(raw.subarray(o, o + nameLen));
    o += nameLen;
    const fileSize = readU32(raw, o);
    o += 4;
    const k = readU16(raw, o);
    o += 2;
    const blockSize = readU16(raw, o);
    o += 2;
    const fileHash = raw.subarray(o, o + HASH_LEN);
    o += HASH_LEN;
    const publicKey = raw.subarray(o, o + PUBKEY_LEN);
    o += PUBKEY_LEN;
    const mimeLen = readU16(raw, o);
    o += 2;
    const mime = utf8decode(raw.subarray(o, o + mimeLen));
    o += mimeLen;
    const signature = raw.subarray(o, o + SIG_LEN);
    const body = raw.subarray(0, o);
    if (!(await verify(signature, body, publicKey))) return null;
    return {
      kind: KIND_HEADER,
      sessionId,
      fileName,
      fileSize,
      k,
      blockSize,
      fileHash,
      publicKey,
      mime,
      signature,
    };
  }

  if (kind === KIND_DATA) {
    if (!expectedKey) return null;
    let o = 4;
    const sessionId = raw.subarray(o, o + SESSION_ID_LEN);
    o += SESSION_ID_LEN;
    const symbolId = readU16(raw, o);
    o += 2;
    const payloadLen = readU16(raw, o);
    o += 2;
    const payload = raw.subarray(o, o + payloadLen);
    o += payloadLen;
    const signature = raw.subarray(o, o + SIG_LEN);
    const body = raw.subarray(0, o);
    if (!(await verify(signature, body, expectedKey))) return null;
    return { kind: KIND_DATA, sessionId, symbolId, payload, signature };
  }

  return null;
}

export function b64urlEncode(data: Uint8Array): string {
  let bin = "";
  for (let i = 0; i < data.length; i++) bin += String.fromCharCode(data[i]!);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

export function b64urlDecode(s: string): Uint8Array | null {
  try {
    const pad = s.length % 4 === 0 ? "" : "=".repeat(4 - (s.length % 4));
    const b64 = s.replace(/-/g, "+").replace(/_/g, "/") + pad;
    const bin = atob(b64);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  } catch {
    return null;
  }
}
