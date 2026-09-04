import { FOUNTAIN_C, FOUNTAIN_DELTA, FOUNTAIN_OVERHEAD } from "./constants";

function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function robustSoliton(k: number, c = FOUNTAIN_C, delta = FOUNTAIN_DELTA): Float64Array {
  const R = Math.max(1, c * Math.log(k / delta) * Math.sqrt(k));
  const rho = new Float64Array(k + 1);
  rho[1] = 1 / k;
  for (let d = 2; d <= k; d++) rho[d] = 1 / (d * (d - 1));

  const spike = Math.max(1, Math.min(k, Math.round(k / R)));
  const tau = new Float64Array(k + 1);
  for (let d = 1; d < spike; d++) tau[d] = R / (d * k);
  tau[spike] = (R * Math.log(R / delta)) / k;

  let z = 0;
  const mu = new Float64Array(k + 1);
  for (let d = 1; d <= k; d++) {
    mu[d] = rho[d]! + tau[d]!;
    z += mu[d]!;
  }
  for (let d = 1; d <= k; d++) mu[d]! /= z;
  return mu;
}

function sampleDegree(mu: Float64Array, rng: () => number): number {
  const u = rng();
  let acc = 0;
  for (let d = 1; d < mu.length; d++) {
    acc += mu[d]!;
    if (u <= acc) return d;
  }
  return Math.max(1, mu.length - 1);
}

function sampleNeighbors(k: number, d: number, rng: () => number): number[] {
  const set = new Set<number>();
  const want = Math.min(Math.max(1, d), k);
  let guard = 0;
  while (set.size < want && guard++ < k * 8) {
    const idx = Math.floor(rng() * k);
    if (idx >= 0 && idx < k) set.add(idx);
  }
  return Array.from(set).sort((a, b) => a - b);
}

export function neighborsFor(symbolId: number, k: number, mu: Float64Array): number[] {
  if (symbolId < k) return [symbolId];
  const rng = mulberry32((symbolId + 1) * 0x9e3779b9);
  const d = sampleDegree(mu, rng);
  return sampleNeighbors(k, d, rng);
}

function xorInto(dst: Uint8Array, src: Uint8Array): void {
  const n = Math.min(dst.length, src.length);
  for (let i = 0; i < n; i++) dst[i]! ^= src[i]!;
}

type Equation = {
  vars: number[];
  payload: Uint8Array;
};

export class FountainEncoder {
  readonly k: number;
  readonly blockSize: number;
  readonly blocks: Uint8Array[];
  readonly fileSize: number;
  readonly mu: Float64Array;
  private nextId = 0;

  constructor(data: Uint8Array, blockSize: number) {
    this.fileSize = data.length;
    this.blockSize = blockSize;
    this.k = Math.max(1, Math.ceil(data.length / blockSize));
    this.blocks = [];
    for (let i = 0; i < this.k; i++) {
      const b = new Uint8Array(blockSize);
      const start = i * blockSize;
      b.set(data.subarray(start, Math.min(start + blockSize, data.length)));
      this.blocks.push(b);
    }
    this.mu = robustSoliton(this.k);
  }

  recommendedSymbols(): number {
    return Math.ceil(this.k * FOUNTAIN_OVERHEAD) + 16;
  }

  encode(symbolId?: number): { symbolId: number; payload: Uint8Array; neighbors: number[] } {
    const id = symbolId ?? this.nextId++;
    const neighbors = neighborsFor(id, this.k, this.mu);
    const payload = new Uint8Array(this.blockSize);
    for (const n of neighbors) xorInto(payload, this.blocks[n]!);
    return { symbolId: id, payload, neighbors };
  }
}

export class FountainDecoder {
  readonly k: number;
  readonly blockSize: number;
  readonly fileSize: number;
  readonly mu: Float64Array;
  private recovered: (Uint8Array | null)[];
  private recoveredCount = 0;
  private seen = new Set<number>();
  private pool: Equation[] = [];

  constructor(k: number, blockSize: number, fileSize: number) {
    this.k = k;
    this.blockSize = blockSize;
    this.fileSize = fileSize;
    this.mu = robustSoliton(k);
    this.recovered = Array.from({ length: k }, () => null);
  }

  get uniqueCount(): number {
    return this.seen.size;
  }

  get doneCount(): number {
    return this.recoveredCount;
  }

  get progress(): number {
    return this.k === 0 ? 1 : this.recoveredCount / this.k;
  }

  has(symbolId: number): boolean {
    return this.seen.has(symbolId);
  }

  ingest(symbolId: number, payload: Uint8Array): boolean {
    if (this.seen.has(symbolId) || this.isComplete()) return false;
    this.seen.add(symbolId);
    const neighbors = neighborsFor(symbolId, this.k, this.mu);
    const eq: Equation = { vars: neighbors.slice(), payload: payload.slice() };
    this.substituteKnown(eq);
    this.pool.push(eq);
    this.peel();
    if (!this.isComplete() && this.pool.length > 0 && this.pool.length <= 96) {
      this.tryGaussian();
    }
    return true;
  }

  private substituteKnown(eq: Equation): void {
    for (let i = eq.vars.length - 1; i >= 0; i--) {
      const n = eq.vars[i]!;
      const rec = this.recovered[n];
      if (rec) {
        xorInto(eq.payload, rec);
        eq.vars.splice(i, 1);
      }
    }
  }

  private peel(): void {
    let progressed = true;
    while (progressed) {
      progressed = false;
      for (let i = this.pool.length - 1; i >= 0; i--) {
        const eq = this.pool[i]!;
        this.substituteKnown(eq);
        if (eq.vars.length === 0) {
          this.pool.splice(i, 1);
        } else if (eq.vars.length === 1) {
          const idx = eq.vars[0]!;
          if (!this.recovered[idx]) {
            this.recovered[idx] = eq.payload.slice();
            this.recoveredCount++;
            progressed = true;
          }
          this.pool.splice(i, 1);
        }
      }
    }
  }

  private tryGaussian(): void {
    const unknown: number[] = [];
    for (let i = 0; i < this.k; i++) if (!this.recovered[i]) unknown.push(i);
    if (unknown.length === 0 || unknown.length > 80) return;
    const col = new Map<number, number>();
    unknown.forEach((id, i) => col.set(id, i));
    const u = unknown.length;
    const eqs: Equation[] = [];
    for (const eq of this.pool) {
      this.substituteKnown(eq);
      if (eq.vars.length === 0) continue;
      if (eq.vars.every((v) => col.has(v))) eqs.push({ vars: eq.vars.slice(), payload: eq.payload.slice() });
    }
    if (eqs.length < u) return;

    const bits: Uint8Array[] = [];
    const rhs: Uint8Array[] = [];
    const rowW = Math.ceil(u / 8);
    for (const eq of eqs) {
      const row = new Uint8Array(rowW);
      for (const v of eq.vars) {
        const c = col.get(v);
        if (c === undefined) continue;
        row[c >> 3]! |= 1 << (c & 7);
      }
      bits.push(row);
      rhs.push(eq.payload.slice());
    }

    const m = bits.length;
    const pivotRow = new Int32Array(u).fill(-1);
    let rank = 0;
    for (let c = 0; c < u; c++) {
      let pr = -1;
      for (let r = rank; r < m; r++) {
        if (bits[r]![c >> 3]! & (1 << (c & 7))) {
          pr = r;
          break;
        }
      }
      if (pr < 0) continue;
      if (pr !== rank) {
        const tb = bits[rank]!;
        bits[rank] = bits[pr]!;
        bits[pr] = tb;
        const tp = rhs[rank]!;
        rhs[rank] = rhs[pr]!;
        rhs[pr] = tp;
      }
      pivotRow[c] = rank;
      for (let r = 0; r < m; r++) {
        if (r === rank) continue;
        if (bits[r]![c >> 3]! & (1 << (c & 7))) {
          for (let j = 0; j < rowW; j++) bits[r]![j]! ^= bits[rank]![j]!;
          xorInto(rhs[r]!, rhs[rank]!);
        }
      }
      rank++;
    }
    if (rank < u) return;

    for (let c = 0; c < u; c++) {
      const r = pivotRow[c] ?? -1;
      if (r < 0) return;
      const idx = unknown[c]!;
      const solved = rhs[r];
      if (!solved) return;
      if (!this.recovered[idx]) {
        this.recovered[idx] = solved.slice();
        this.recoveredCount++;
      }
    }
    this.pool = [];
  }

  isComplete(): boolean {
    return this.recoveredCount >= this.k;
  }

  assemble(): Uint8Array | null {
    if (!this.isComplete()) return null;
    const out = new Uint8Array(this.fileSize);
    for (let i = 0; i < this.k; i++) {
      const block = this.recovered[i];
      if (!block) return null;
      const start = i * this.blockSize;
      const len = Math.min(this.blockSize, this.fileSize - start);
      out.set(block.subarray(0, len), start);
    }
    return out;
  }
}
