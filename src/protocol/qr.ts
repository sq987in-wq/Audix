import QRCode from "qrcode";
import jsQR from "jsqr";
import { QR_ECC, type Density } from "./constants";
import { b64urlDecode } from "./frames";

const ECC_MAP: Record<string, "L" | "M" | "Q" | "H"> = {
  L: "L",
  M: "M",
  Q: "Q",
  H: "H",
};

function qrOptions(density: Density, size: number) {
  return {
    errorCorrectionLevel: ECC_MAP[QR_ECC[density]] ?? "M",
    margin: 4,
    width: Math.max(240, size),
    color: { dark: "#0a0908", light: "#f4efe6" },
  } as const;
}

export async function renderQrCanvas(
  canvas: HTMLCanvasElement,
  payload: Uint8Array,
  density: Density,
  size: number,
): Promise<void> {
  await QRCode.toCanvas(canvas, [{ data: payload, mode: "byte" }], qrOptions(density, size));
}

export type QrLocate = {
  location: {
    topLeftCorner: { x: number; y: number };
    topRightCorner: { x: number; y: number };
    bottomLeftCorner: { x: number; y: number };
    bottomRightCorner: { x: number; y: number };
  };
  binary: Uint8Array | null;
};

export function locateAndDecode(image: ImageData): QrLocate | null {
  const result = jsQR(image.data, image.width, image.height, {
    inversionAttempts: "attemptBoth",
  });
  if (!result) return null;
  const fromBinary = Uint8Array.from(result.binaryData);
  let binary: Uint8Array | null = null;
  if (fromBinary.length >= 2 && fromBinary[0] === 0x43 && fromBinary[1] === 0x4c) {
    binary = fromBinary;
  } else if (result.data.startsWith("CL1:")) {
    binary = b64urlDecode(result.data.slice(4));
  }
  return { location: result.location, binary };
}
