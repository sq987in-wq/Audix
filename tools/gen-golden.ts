/**
 * Stage 0 — golden vector generator.
 *
 * Reads the FROZEN TypeScript protocol in /src (read-only, never modified) and
 * emits deterministic vectors that the Kotlin port must reproduce byte-for-byte.
 *
 * Determinism rules:
 *  - No crypto.getRandomValues anywhere. All "random" inputs come from a fixed
 *    xorshift PRNG seeded with a constant, so re-running produces identical files.
 *  - Ed25519 secret keys are fixed constants, not generated.
 *
 * Run:  npx tsx tools/gen-golden.ts
 */
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import * as ed from "@noble/ed25519";
import { sha512 } from "@noble/hashes/sha512";

import { toHex } from "../src/protocol/bytes";
import { crc32 } from "../src/protocol/crc32";
import { fileHash, sasFromPublicKey, sessionFingerprint } from "../src/protocol/crypto";
import { encodeCal, encodeData, encodeHeader } from "../src/protocol/frames";
import { FountainDecoder, FountainEncoder, neighborsFor } from "../src/protocol/fountain";
import {
  FOUNTAIN_C,
  FOUNTAIN_DELTA,
  FOUNTAIN_OVERHEAD,
  PAYLOAD_BYTES,
} from "../src/protocol/constants";

ed.etc.sha512Sync = (...m) => sha512(ed.etc.concatBytes(...m));

const OUT_DIR = resolve(
  dirname(fileURLToPath(import.meta.url)),
  "../android/core-protocol/src/test/resources/golden",
);

/** Deterministic PRNG — NOT the fountain RNG, just for reproducible test inputs. */
function xorshift32(seed: number): () => number {
  let x = seed >>> 0;
  return () => {
    x ^= x << 13;
    x >>>= 0;
    x ^= x >>> 17;
    x ^= x << 5;
    x >>>= 0;
    return x >>> 0;
  };
}

function detBytes(n: number, seed: number): Uint8Array {
  const rnd = xorshift32(seed);
  const out = new Uint8Array(n);
  for (let i = 0; i < n; i++) out[i] = rnd() & 0xff;
  return out;
}

function write(name: string, obj: unknown): void {
  const p = resolve(OUT_DIR, name);
  writeFileSync(p, JSON.stringify(obj, null, 2) + "\n");
  console.log(`  wrote ${name}`);
}

async function main(): Promise<void> {
  mkdirSync(OUT_DIR, { recursive: true });
  console.log("Generating golden vectors ->", OUT_DIR);

  // Fixed keypair. Secret key is a constant so the Kotlin side can sign identically.
  const secretKey = detBytes(32, 0xc0ffee);
  const publicKey = await ed.getPublicKeyAsync(secretKey);
  const sessionId = detBytes(8, 0x5e5510);

  // ---- 1. crypto vectors -------------------------------------------------
  const cryptoVecs = {
    secretKeyHex: toHex(secretKey),
    publicKeyHex: toHex(publicKey),
    sessionIdHex: toHex(sessionId),
    sas: sasFromPublicKey(publicKey),
    fingerprint: sessionFingerprint(sessionId, publicKey),
    sha256: [0, 1, 32, 1000].map((n) => {
      const d = detBytes(n, 0x1234 + n);
      return { len: n, dataHex: toHex(d), hashHex: toHex(fileHash(d)) };
    }),
    // Deterministic Ed25519 signatures (RFC 8032 is deterministic — no nonce).
    signatures: [0, 1, 64, 300].map((n) => {
      const msg = detBytes(n, 0x9999 + n);
      return {
        len: n,
        msgHex: toHex(msg),
        sigHex: toHex(ed.sign(msg, secretKey)),
      };
    }),
  };
  write("crypto.json", cryptoVecs);

  // ---- 2. crc32 vectors --------------------------------------------------
  const crcVecs = {
    cases: [
      { name: "empty", dataHex: "" },
      { name: "abc", dataHex: toHex(new TextEncoder().encode("abc")) },
      { name: "check123456789", dataHex: toHex(new TextEncoder().encode("123456789")) },
      { name: "zeros32", dataHex: toHex(new Uint8Array(32)) },
      { name: "rand256", dataHex: toHex(detBytes(256, 0x77)) },
      { name: "rand4095", dataHex: toHex(detBytes(4095, 0x88)) },
    ].map((c) => {
      const bytes = c.dataHex.length
        ? Uint8Array.from(c.dataHex.match(/../g)!.map((h) => parseInt(h, 16)))
        : new Uint8Array(0);
      return { ...c, crc: crc32(bytes) };
    }),
  };
  write("crc32.json", crcVecs);

  // ---- 3. frame vectors --------------------------------------------------
  const calRaw = encodeCal(sessionId);

  const payload = detBytes(48, 0xabc);
  const fh = fileHash(detBytes(4096, 0xfeed));
  const headerRaw = await encodeHeader(
    {
      sessionId,
      fileName: "candela-test.bin",
      fileSize: 4096,
      k: 86,
      blockSize: 48,
      fileHash: fh,
      publicKey,
      mime: "application/octet-stream",
    },
    secretKey,
  );

  const dataFrames = [];
  for (const symbolId of [0, 1, 85, 137, 65535]) {
    const p = detBytes(48, 0x2000 + symbolId);
    dataFrames.push({
      symbolId,
      payloadHex: toHex(p),
      rawHex: toHex(await encodeData(sessionId, symbolId, p, secretKey)),
    });
  }

  write("frames.json", {
    sessionIdHex: toHex(sessionId),
    publicKeyHex: toHex(publicKey),
    cal: { rawHex: toHex(calRaw) },
    header: {
      fileName: "candela-test.bin",
      fileSize: 4096,
      k: 86,
      blockSize: 48,
      fileHashHex: toHex(fh),
      mime: "application/octet-stream",
      rawHex: toHex(headerRaw),
    },
    data: dataFrames,
    unusedPayloadHex: toHex(payload),
  });

  // ---- 4. fountain vectors (the highest-risk port) -----------------------
  // Neighbour lists fully pin down mulberry32 + robust soliton + sampling.
  const neighbourSets: Array<{ k: number; entries: Array<{ id: number; n: number[] }> }> = [];
  for (const k of [1, 2, 7, 86, 683, 2000]) {
    const enc = new FountainEncoder(new Uint8Array(k * 48), 48);
    const entries: Array<{ id: number; n: number[] }> = [];
    // dense sweep over low ids + spot checks high, incl. the 16-bit ceiling
    const ids = new Set<number>();
    for (let i = 0; i <= Math.min(2000, k * 2 + 40); i++) ids.add(i);
    for (const i of [4096, 10000, 40000, 65535]) ids.add(i);
    for (const id of Array.from(ids).sort((a, b) => a - b)) {
      entries.push({ id, n: neighborsFor(id, enc.k, enc.mu) });
    }
    neighbourSets.push({ k: enc.k, entries });
  }
  write("fountain_neighbors.json", {
    c: FOUNTAIN_C,
    delta: FOUNTAIN_DELTA,
    overhead: FOUNTAIN_OVERHEAD,
    note: "neighborsFor(symbolId, k, robustSoliton(k)); mulberry32((id+1)*0x9E3779B9)",
    sets: neighbourSets,
  });

  // Robust soliton distribution itself, to localise a divergence fast.
  const solitonSets = [1, 2, 7, 86, 683].map((k) => {
    const enc = new FountainEncoder(new Uint8Array(k * 48), 48);
    return { k: enc.k, mu: Array.from(enc.mu).map((v) => Number(v.toFixed(12))) };
  });
  write("fountain_soliton.json", { sets: solitonSets });

  // Encoder payload vectors: XOR output must match exactly.
  const encFile = detBytes(4096, 0xdeadbeef);
  const encoder = new FountainEncoder(encFile, PAYLOAD_BYTES.standard);
  const encoded = [];
  for (let id = 0; id < encoder.k + 60; id++) {
    const e = encoder.encode(id);
    encoded.push({ id, payloadHex: toHex(e.payload), neighbors: e.neighbors });
  }
  write("fountain_encode.json", {
    fileHex: toHex(encFile),
    fileSize: encFile.length,
    blockSize: PAYLOAD_BYTES.standard,
    k: encoder.k,
    recommendedSymbols: encoder.recommendedSymbols(),
    fileHashHex: toHex(fileHash(encFile)),
    symbols: encoded,
  });

  // ---- 5. end-to-end transcript at 22% deterministic drop ----------------
  // Mirrors src/protocol/selftest.ts but with a fixed drop pattern so Kotlin
  // consumes the exact same surviving symbol set.
  for (const size of [4096, 32768]) {
    const data = detBytes(size, 0x5150 + size);
    const bs = PAYLOAD_BYTES.standard;
    const e = new FountainEncoder(data, bs);
    // Same budget the TS bench uses (src/transfer/loopback.ts): recommended + k.
    const total = e.recommendedSymbols() + e.k;
    const drop = xorshift32(0xd0d0 + size);

    const kept: Array<{ id: number; payloadHex: string }> = [];
    for (let id = 0; id < total; id++) {
      if (drop() % 100 < 22) continue; // deterministic 22% loss
      const s = e.encode(id);
      kept.push({ id, payloadHex: toHex(s.payload) });
    }

    // Prove the TS side actually recovers from this exact set.
    const dec = new FountainDecoder(e.k, bs, data.length);
    let used = 0;
    for (const s of kept) {
      used++;
      dec.ingest(s.id, Uint8Array.from(s.payloadHex.match(/../g)!.map((h) => parseInt(h, 16))));
      if (dec.isComplete()) break;
    }
    const out = dec.assemble();
    const ok = out !== null && toHex(fileHash(out)) === toHex(fileHash(data));
    if (!ok) throw new Error(`transcript ${size} did not recover in TS — refusing to emit`);

    write(`transcript_${size}.json`, {
      fileSize: size,
      blockSize: bs,
      k: e.k,
      totalGenerated: total,
      keptCount: kept.length,
      droppedCount: total - kept.length,
      symbolsConsumedToComplete: used,
      fileHashHex: toHex(fileHash(data)),
      fileHex: toHex(data),
      symbols: kept,
    });
  }

  console.log("Golden vectors complete.");
}

void main().catch((e) => {
  console.error(e);
  process.exit(1);
});
