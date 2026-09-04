import { runLoopbackBench } from "../transfer/loopback";

async function main(): Promise<void> {
  const sizes = [4096, 32768];
  for (const size of sizes) {
    const data = crypto.getRandomValues(new Uint8Array(size));
    const r = await runLoopbackBench(data, `t-${size}.bin`, "application/octet-stream", "standard", 0.22);
    if (!r.ok) {
      console.error("FAIL", r);
      process.exit(1);
    }
    console.log(
      `PASS size=${size} k=${r.k} sent=${r.symbolsSent} dropped=${r.dropped} recovered=${r.recovered} ms=${r.elapsedMs.toFixed(0)}`,
    );
  }
}

void main();
