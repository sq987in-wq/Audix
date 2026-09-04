/// <reference types="vite/client" />

declare module "pngjs";

declare module "jsqr" {
  export type Point = { x: number; y: number };
  export type QRLocation = {
    topLeftCorner: Point;
    topRightCorner: Point;
    bottomLeftCorner: Point;
    bottomRightCorner: Point;
    topLeftFinderPattern: Point;
    topRightFinderPattern: Point;
    bottomLeftFinderPattern: Point;
  };
  export type QRResult = {
    data: string;
    binaryData: number[];
    location: QRLocation;
  };
  export default function jsQR(
    data: Uint8ClampedArray,
    width: number,
    height: number,
    options?: { inversionAttempts?: "dontInvert" | "onlyInvert" | "attemptBoth" | "invertFirst" },
  ): QRResult | null;
}
