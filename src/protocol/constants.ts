export const MAGIC = new Uint8Array([0x43, 0x4c]); // "CL"
export const PROTOCOL_VERSION = 1;

export const KIND_CAL = 0;
export const KIND_HEADER = 1;
export const KIND_DATA = 2;

export const SESSION_ID_LEN = 8;
export const PUBKEY_LEN = 32;
export const SIG_LEN = 64;
export const HASH_LEN = 32;
export const CRC_LEN = 4;
export const SAS_DIGITS = 8;

export const HEADER_INTERLEAVE = 8;

export const HOLD_MS = {
  robust: 220,
  standard: 160,
  fast: 120,
} as const;

export const PAYLOAD_BYTES = {
  robust: 32,
  standard: 48,
  fast: 64,
} as const;

export const QR_ECC = {
  robust: "M",
  standard: "M",
  fast: "L",
} as const;

export type Density = keyof typeof PAYLOAD_BYTES;

export const CALIBRATION_MS = 2800;
export const MOTION_GATE_MS2 = 0.45;
export const BLUR_MIN = 1.2;
export const CONTRAST_MIN = 0.08;
export const CR_REFUSE = 0.03;
export const DECODE_PARALLEL = 1;
export const FRAME_STALE_MS = 160;
export const MAX_FILE_BYTES = 1_048_576;
export const RECOMMENDED_FILE_BYTES = 512_000;

export const FOUNTAIN_C = 0.12;
export const FOUNTAIN_DELTA = 0.05;
export const FOUNTAIN_OVERHEAD = 1.55;
