import "./styles.css";
import { OpticalSender } from "./transfer/sender";
import { OpticalReceiver } from "./transfer/receiver";
import { runLoopbackBench } from "./transfer/loopback";
import { runOpticalCanvasLoopback } from "./transfer/opticalLoopback";
import { formatBytes, formatDuration } from "./protocol/bytes";
import { sasPretty } from "./protocol/crypto";
import { MAX_FILE_BYTES, type Density } from "./protocol/constants";
import type { CoachMetrics } from "./protocol/session";

const app = document.querySelector("#app");
if (!app) throw new Error("missing #app");

app.innerHTML = `
  <div class="shell">
    <header class="topbar">
      <div class="brand">
        <div class="mark" aria-hidden="true"></div>
        <h1>Candela</h1>
        <span>Optical air-gap</span>
      </div>
      <div class="chip">Zero radio · Light only</div>
    </header>

    <section class="view" id="view-home">
      <div class="hero">
        <div>
          <h2>Move a file as <em>light</em>. Nothing else.</h2>
          <p class="lede">
            Two cooperating humans. One screen, one camera. Fountain-coded QR symbols,
            per-block Ed25519, SHA-256 at reassembly. No network, no Bluetooth, no server.
          </p>
          <div class="cta-row">
            <button class="btn btn-primary" data-go="send">Send a file</button>
            <button class="btn btn-ghost" data-go="recv">Receive</button>
            <button class="btn btn-ghost" data-go="bench">Self-test</button>
          </div>
        </div>
        <aside class="envelope">
          <h3>Operating envelope</h3>
          <dl class="kv">
            <dt>Distance / angle</dt><dd>15–40 cm · &lt; 20°</dd>
            <dt>Hold still</dt><dd>desk, stand, or planted elbows</dd>
            <dt>Light</dt><dd>indoor / indirect — no direct sun</dd>
            <dt>Screen</dt><dd>clean glass · no privacy film</dd>
            <dt>Payload</dt><dd>≤ 1 MB · 100–500 KB recommended</dd>
            <dt>Channel</dt><dd>~20–40 KB/s · 8–12 symbols/s</dd>
            <dt>Integrity</dt><dd>CRC32 · Ed25519 · SHA-256</dd>
          </dl>
        </aside>
      </div>
      <div class="grid-3">
        <div class="stat"><b>C1–C4</b><span>Freeze · gate · ROI — never decode garbage</span></div>
        <div class="stat"><b>100 ms+</b><span>Hold-time · phase-independent of rolling shutter</span></div>
        <div class="stat"><b>SAS</b><span>Eight digits compared aloud. That is the PKI.</span></div>
      </div>
      <div class="modes">
        <article class="card">
          <h3>Send</h3>
          <p>Max-brightness QR plane, fixed pacing, calibration pose, then a fountain stream with interleaved trust-anchor headers.</p>
          <button class="btn btn-primary" data-go="send">Open sender</button>
        </article>
        <article class="card">
          <h3>Receive</h3>
          <p>Motion, blur and contrast gates before decode. Alignment coach HUD. Fountain peel. Abort on hash mismatch — no partial write.</p>
          <button class="btn btn-ghost" data-go="recv">Open receiver</button>
        </article>
        <article class="card">
          <h3>Prove it</h3>
          <p>Same-device loopback: protocol math, then a live optical decode of the QR pixels on this canvas.</p>
          <button class="btn btn-ghost" data-go="bench">Run bench</button>
        </article>
      </div>
    </section>

    <section class="view hidden" id="view-send">
      <div class="panel">
        <div class="panel-head">
          <h3>Sender</h3>
          <span class="status" id="send-state">Idle</span>
        </div>
        <div class="field">
          <label for="file">File (≤ 1 MB) — or send the built-in sample</label>
          <input id="file" type="file" />
        </div>
        <div class="field">
          <label for="density">Density / hold</label>
          <select id="density">
            <option value="robust">Robust · 32 B · 220 ms hold</option>
            <option value="standard" selected>Standard · 48 B · 160 ms hold</option>
            <option value="fast">Fast · 64 B · 120 ms hold</option>
          </select>
        </div>
        <div class="cta-row">
          <button class="btn btn-primary" id="send-sample">Send sample note</button>
          <button class="btn btn-ghost" id="send-start">Send chosen file</button>
          <button class="btn btn-danger" id="send-stop">Abort</button>
          <button class="btn btn-ghost" data-go="home">Home</button>
        </div>
        <div class="sas-box hidden" id="send-sas">
          <small>Short authentication string — read aloud</small>
          <strong id="send-sas-val">———— ————</strong>
        </div>
        <p class="progress-line" id="send-progress">Pick a file or tap Send sample note. The QR plane will start immediately.</p>
        <div class="qr-wrap" style="margin-top:16px">
          <canvas id="qr" width="480" height="480"></canvas>
        </div>
        <div class="notice warn" style="margin-top:16px">
          Point the other phone’s camera at this QR. Hold still, 15–40 cm, indoor light.
        </div>
      </div>
    </section>

    <section class="view hidden" id="view-recv">
      <div class="panel">
        <div class="panel-head">
          <h3>Receiver</h3>
          <span class="status" id="recv-state">Idle</span>
        </div>
        <div class="cta-row">
          <button class="btn btn-primary" id="recv-start">Open camera</button>
          <button class="btn btn-ghost" id="recv-demo">No camera? Run demo</button>
          <button class="btn btn-danger" id="recv-stop">Abort</button>
          <button class="btn btn-ghost" data-go="home">Home</button>
        </div>
        <div class="sas-box hidden" id="recv-sas">
          <small>Compare with sender — ZRTP-style, no server</small>
          <strong id="recv-sas-val">———— ————</strong>
        </div>
        <p class="progress-line" id="recv-meta">Camera needs permission. Use HTTPS (this preview) and allow access.</p>
        <div class="recv-stage" style="margin-top:14px">
          <video id="cam" playsinline muted autoplay></video>
          <canvas id="overlay"></canvas>
        </div>
        <div class="coach">
          <div class="meter"><div class="label">Blur</div><div class="value" id="m-blur">—</div><div class="bar"><i id="b-blur"></i></div></div>
          <div class="meter"><div class="label">Contrast</div><div class="value" id="m-cr">—</div><div class="bar"><i id="b-cr"></i></div></div>
          <div class="meter"><div class="label">Motion</div><div class="value" id="m-mo">—</div><div class="bar"><i id="b-mo"></i></div></div>
          <div class="meter"><div class="label">Recovered</div><div class="value" id="m-rec">0 / 0</div><div class="bar"><i id="b-rec"></i></div></div>
        </div>
        <p class="progress-line" id="recv-hint">Waiting for camera.</p>
        <div id="recv-notice"></div>
        <div class="cta-row hidden" id="download-row" style="margin-top:14px">
          <a class="btn btn-primary" id="download" download>Download file</a>
        </div>
      </div>
    </section>

    <section class="view hidden" id="view-bench">
      <div class="panel">
        <div class="panel-head">
          <h3>Self-test</h3>
          <span class="status" id="bench-state">Ready</span>
        </div>
        <p class="lede" style="margin:0 0 16px;font-size:15px">
          Two proofs on this device: protocol math with 22% simulated drop, then a live
          optical decode of QR pixels drawn on the canvas below.
        </p>
        <div class="field">
          <label for="bench-size">Payload size</label>
          <select id="bench-size">
            <option value="2048" selected>2 KB (quick)</option>
            <option value="8192">8 KB</option>
            <option value="32768">32 KB</option>
          </select>
        </div>
        <div class="cta-row">
          <button class="btn btn-primary" id="bench-run">Run self-test</button>
          <button class="btn btn-ghost" data-go="home">Home</button>
        </div>
        <div class="sas-box hidden" id="bench-sas">
          <small>Session SAS</small>
          <strong id="bench-sas-val">———— ————</strong>
        </div>
        <div class="qr-wrap" style="margin-top:16px">
          <canvas id="bench-qr" width="400" height="400"></canvas>
        </div>
        <div class="log" id="bench-log">Tap Run self-test.</div>
      </div>
    </section>

    <footer class="footer">
      <span>Candela · session protocol + alignment coach</span>
      <span>Outside the envelope: pause / refuse — never silent corruption</span>
    </footer>
  </div>
`;

const $ = <T extends HTMLElement>(id: string) => {
  const el = document.getElementById(id);
  if (!el) throw new Error(`missing #${id}`);
  return el as T;
};

type View = "home" | "send" | "recv" | "bench";

function show(view: View): void {
  for (const el of document.querySelectorAll(".view")) el.classList.add("hidden");
  $(`view-${view}`).classList.remove("hidden");
  window.scrollTo(0, 0);
}

document.addEventListener("click", (ev) => {
  const t = ev.target;
  if (!(t instanceof HTMLElement)) return;
  const go = t.closest("[data-go]") as HTMLElement | null;
  if (!go) return;
  const view = go.getAttribute("data-go") as View | null;
  if (!view) return;
  if (view === "home") {
    sender?.stop();
    receiver?.stop();
  }
  show(view);
});

let sender: OpticalSender | null = null;
let receiver: OpticalReceiver | null = null;
let sending = false;

function sampleFile(): File {
  const text =
    "Candela sample note\n" +
    "Air-gapped optical transfer. Fountain + Ed25519 + SHA-256.\n" +
    `Generated ${new Date().toISOString()}\n`;
  return new File([text], "candela-sample.txt", { type: "text/plain" });
}

async function startSend(file: File): Promise<void> {
  if (sending) {
    sender?.stop();
    sending = false;
  }
  if (file.size > MAX_FILE_BYTES) {
    $("send-progress").textContent = "File exceeds 1 MB soft ceiling.";
    return;
  }
  const density = $<HTMLSelectElement>("density").value as Density;
  $("send-sas").classList.add("hidden");
  $("send-progress").textContent = `Preparing ${file.name} · ${formatBytes(file.size)}…`;
  sender = new OpticalSender($<HTMLCanvasElement>("qr"), density);
  sender.on((e) => {
    if (e.type === "state") $("send-state").textContent = e.state;
    if (e.type === "sas") {
      $("send-sas").classList.remove("hidden");
      $("send-sas-val").textContent = sasPretty(e.sas);
    }
    if (e.type === "symbol") {
      $("send-progress").textContent =
        `${file.name} · ${formatBytes(file.size)} · frame ${e.index}/${e.total}`;
    }
    if (e.type === "error") $("send-progress").textContent = e.message;
  });
  sending = true;
  try {
    await sender.prepare(file);
    await sender.run();
  } catch (err) {
    $("send-progress").textContent = err instanceof Error ? err.message : String(err);
  } finally {
    sending = false;
  }
}

$("send-sample").onclick = () => void startSend(sampleFile());
$("send-start").onclick = () => {
  const file = $<HTMLInputElement>("file").files?.[0];
  if (!file) {
    $("send-progress").textContent = "No file chosen — sending the sample note instead.";
    void startSend(sampleFile());
    return;
  }
  void startSend(file);
};
$("send-stop").onclick = () => {
  sender?.stop();
  $("send-state").textContent = "aborted";
};

function paintCoach(m: CoachMetrics): void {
  $("m-blur").textContent = m.blur.toFixed(1);
  $("m-cr").textContent = `${Math.round(m.contrast * 100)}%`;
  $("m-mo").textContent = m.motion.toFixed(1);
  $("m-rec").textContent = `${m.recovered} / ${m.k}`;
  $("b-blur").style.width = `${Math.min(100, (m.blur / 20) * 100)}%`;
  $("b-cr").style.width = `${Math.min(100, m.contrast * 100)}%`;
  $("b-mo").style.width = `${Math.min(100, (m.motion / 20) * 100)}%`;
  $("b-rec").style.width = `${m.k ? (m.recovered / m.k) * 100 : 0}%`;
  $("recv-hint").textContent = m.gatePass
    ? `Scanning · ${m.fps} fps · decode ${m.decodeMs.toFixed(0)} ms · ${m.unique} unique symbols`
    : `Scanning (${m.reason}) · ${m.fps} fps`;
}

$("recv-start").onclick = async () => {
  receiver?.stop();
  $("recv-notice").innerHTML = "";
  $("download-row").classList.add("hidden");
  $("recv-state").textContent = "requesting camera";
  if (!navigator.mediaDevices?.getUserMedia) {
    $("recv-notice").innerHTML =
      `<div class="notice err">This browser has no camera API. Open the preview on a phone over HTTPS.</div>`;
    return;
  }
  receiver = new OpticalReceiver($<HTMLVideoElement>("cam"), $<HTMLCanvasElement>("overlay"));
  receiver.on((e) => {
    if (e.type === "state") $("recv-state").textContent = e.state;
    if (e.type === "coach") paintCoach(e.metrics);
    if (e.type === "header") {
      $("recv-sas").classList.remove("hidden");
      $("recv-sas-val").textContent = sasPretty(e.sas);
      $("recv-meta").textContent =
        `${e.header.fileName} · ${formatBytes(e.header.fileSize)} · k=${e.header.k} · block ${e.header.blockSize} B`;
    }
    if (e.type === "progress") {
      $("recv-meta").textContent = `symbols ${e.unique} · recovered ${e.recovered}/${e.k}`;
    }
    if (e.type === "refuse") {
      $("recv-notice").innerHTML = `<div class="notice warn">${e.message}</div>`;
    }
    if (e.type === "error") {
      $("recv-notice").innerHTML = `<div class="notice err">${e.message}</div>`;
    }
    if (e.type === "complete") {
      $("recv-notice").innerHTML =
        `<div class="notice ok">Verified SHA-256 ${e.fileHash.slice(0, 16)}…</div>`;
      const a = $<HTMLAnchorElement>("download");
      a.href = URL.createObjectURL(e.blob);
      a.download = e.fileName;
      $("download-row").classList.remove("hidden");
    }
  });
  try {
    await receiver.start();
    $("recv-meta").textContent = "Camera live. Point it at the sender QR.";
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    $("recv-state").textContent = "camera denied";
    $("recv-notice").innerHTML = `<div class="notice err">${msg}. Allow camera permission and retry. Desktop browsers without a camera cannot receive.</div>`;
  }
};

$("recv-stop").onclick = () => receiver?.stop();

$("recv-demo").onclick = async () => {
  receiver?.stop();
  $("recv-notice").innerHTML = "";
  $("download-row").classList.add("hidden");
  $("recv-state").textContent = "demo";
  $("recv-meta").textContent = "Decoding a sample stream from pixels on a hidden canvas…";
  const text = "Candela demo receive\nNo camera required.\n" + new Date().toISOString();
  const data = new TextEncoder().encode(text);
  const canvas = document.createElement("canvas");
  canvas.width = 400;
  canvas.height = 400;
  try {
    const opt = await runOpticalCanvasLoopback(
      canvas,
      data,
      "candela-demo.txt",
      "text/plain",
      "robust",
      (s) => {
        $("recv-state").textContent = `demo ${s.recovered}/${s.k}`;
        $("m-rec").textContent = `${s.recovered} / ${s.k}`;
        $("b-rec").style.width = `${s.k ? (s.recovered / s.k) * 100 : 0}%`;
      },
    );
    $("recv-sas").classList.remove("hidden");
    $("recv-sas-val").textContent = sasPretty(opt.sas);
    if (opt.ok) {
      $("recv-state").textContent = "complete";
      $("recv-notice").innerHTML =
        `<div class="notice ok">${opt.message} Recovered ${opt.recovered}/${opt.k}.</div>`;
      const blob = new Blob([data], { type: "text/plain" });
      const a = $<HTMLAnchorElement>("download");
      a.href = URL.createObjectURL(blob);
      a.download = "candela-demo.txt";
      $("download-row").classList.remove("hidden");
    } else {
      $("recv-state").textContent = "fail";
      $("recv-notice").innerHTML = `<div class="notice err">${opt.message}</div>`;
    }
  } catch (err) {
    $("recv-state").textContent = "error";
    $("recv-notice").innerHTML =
      `<div class="notice err">${err instanceof Error ? err.message : String(err)}</div>`;
  }
};

$("bench-run").onclick = async () => {
  const size = Number($<HTMLSelectElement>("bench-size").value);
  const data = crypto.getRandomValues(new Uint8Array(size));
  $("bench-state").textContent = "running";
  $("bench-log").textContent = "1/2 protocol math…";
  try {
    const math = await runLoopbackBench(data, `bench-${size}.bin`, "application/octet-stream", "standard", 0.22);
    $("bench-sas").classList.remove("hidden");
    $("bench-sas-val").textContent = sasPretty(math.sas);
    $("bench-log").textContent =
      `1/2 protocol ${math.ok ? "PASS" : "FAIL"}\n` +
      `k=${math.k} sent=${math.symbolsSent} dropped=${math.dropped} recovered=${math.recovered}/${math.k}\n` +
      `sha256 ${math.hashMatch ? "match" : "MISMATCH"} · ${formatDuration(math.elapsedMs)}\n\n` +
      `2/2 optical canvas decode…`;
    const opt = await runOpticalCanvasLoopback(
      $<HTMLCanvasElement>("bench-qr"),
      data.slice(0, Math.min(data.length, 2048)),
      `optical-${Math.min(data.length, 2048)}.bin`,
      "application/octet-stream",
      "robust",
      (s) => {
        $("bench-state").textContent = `optical ${s.recovered}/${s.k}`;
      },
    );
    $("bench-state").textContent = math.ok && opt.ok ? "pass" : "fail";
    $("bench-log").textContent = [
      math.ok ? "1/2 protocol PASS" : "1/2 protocol FAIL",
      `  k=${math.k} sent=${math.symbolsSent} dropped=${math.dropped} recovered=${math.recovered}/${math.k}`,
      `  sha256 ${math.hashMatch ? "match" : "MISMATCH"} · ${formatDuration(math.elapsedMs)}`,
      opt.ok ? "2/2 optical PASS" : "2/2 optical FAIL",
      `  decoded ${opt.decodedFrames} frames · missed ${opt.missedFrames}`,
      `  recovered ${opt.recovered}/${opt.k} · ${formatDuration(opt.elapsedMs)}`,
      opt.message,
    ].join("\n");
  } catch (err) {
    $("bench-state").textContent = "error";
    $("bench-log").textContent = err instanceof Error ? err.stack || err.message : String(err);
  }
};
