import { BLUR_MIN, CONTRAST_MIN, CR_REFUSE } from "../protocol/constants";

export type GateResult = {
  pass: boolean;
  blur: number;
  contrast: number;
  lumaMean: number;
  lumaVar: number;
  refuse: boolean;
  reason: string;
};

function downsampleGray(
  src: Uint8ClampedArray,
  w: number,
  h: number,
  tw: number,
  th: number,
): Uint8Array {
  const out = new Uint8Array(tw * th);
  const xRatio = w / tw;
  const yRatio = h / th;
  for (let y = 0; y < th; y++) {
    const sy = Math.min(h - 1, Math.floor(y * yRatio));
    for (let x = 0; x < tw; x++) {
      const sx = Math.min(w - 1, Math.floor(x * xRatio));
      const i = (sy * w + sx) * 4;
      out[y * tw + x] = (src[i]! * 0.299 + src[i + 1]! * 0.587 + src[i + 2]! * 0.114) | 0;
    }
  }
  return out;
}

function percentile(sorted: Uint8Array, p: number): number {
  const i = Math.min(sorted.length - 1, Math.max(0, Math.floor((p / 100) * (sorted.length - 1))));
  return sorted[i]!;
}

export function gateFrame(image: ImageData, lastRect?: DOMRect | null): GateResult {
  const { data, width, height } = image;
  let x0 = 0;
  let y0 = 0;
  let x1 = width;
  let y1 = height;
  if (lastRect && lastRect.width > 8 && lastRect.height > 8) {
    const pad = 12;
    x0 = Math.max(0, Math.floor(lastRect.x - pad));
    y0 = Math.max(0, Math.floor(lastRect.y - pad));
    x1 = Math.min(width, Math.ceil(lastRect.x + lastRect.width + pad));
    y1 = Math.min(height, Math.ceil(lastRect.y + lastRect.height + pad));
  } else {
    const m = 0.18;
    x0 = Math.floor(width * m);
    y0 = Math.floor(height * m);
    x1 = Math.ceil(width * (1 - m));
    y1 = Math.ceil(height * (1 - m));
  }

  const rw = Math.max(8, x1 - x0);
  const rh = Math.max(8, y1 - y0);
  const roi = new Uint8ClampedArray(rw * rh * 4);
  for (let y = 0; y < rh; y++) {
    const srcOff = ((y0 + y) * width + x0) * 4;
    roi.set(data.subarray(srcOff, srcOff + rw * 4), y * rw * 4);
  }

  const g = downsampleGray(roi, rw, rh, 96, 96);
  const n = g.length;
  let sum = 0;
  let sum2 = 0;
  const sorted = g.slice();
  sorted.sort();
  for (let i = 0; i < n; i++) {
    const v = g[i]!;
    sum += v;
    sum2 += v * v;
  }
  const mean = sum / n;
  const lumaVar = sum2 / n - mean * mean;

  let blur = 0;
  const W = 96;
  for (let y = 1; y < 95; y++) {
    for (let x = 1; x < 95; x++) {
      const i = y * W + x;
      const lap =
        -4 * g[i]! + g[i - 1]! + g[i + 1]! + g[i - W]! + g[i + W]!;
      blur += lap * lap;
    }
  }
  blur = Math.sqrt(blur / ((94 * 94)));

  const p1 = percentile(sorted, 1);
  const p99 = percentile(sorted, 99);
  const contrast = (p99 - p1) / 255;

  const refuse = contrast < CR_REFUSE;
  const pass = blur > BLUR_MIN && contrast > CONTRAST_MIN && !refuse;
  let reason = "locked";
  if (refuse) reason = "contrast floor";
  else if (contrast <= CONTRAST_MIN) reason = "low contrast";
  else if (blur <= BLUR_MIN) reason = "motion / blur";

  return { pass, blur, contrast, lumaMean: mean, lumaVar, refuse, reason };
}

export class MotionGate {
  private last: number | null = null;
  private lastT = 0;
  magnitude = 0;
  stable = true;

  push(ax: number, ay: number, az: number, t = performance.now()): boolean {
    const a = Math.sqrt(ax * ax + ay * ay + az * az);
    if (this.last === null) {
      this.last = a;
      this.lastT = t;
      this.magnitude = 0;
      this.stable = true;
      return true;
    }
    const dt = Math.max(1, t - this.lastT) / 1000;
    const jerk = Math.abs(a - this.last) / dt;
    this.last = a;
    this.lastT = t;
    this.magnitude = 0.7 * this.magnitude + 0.3 * jerk;
    this.stable = this.magnitude < 8.5;
    return this.stable;
  }
}
