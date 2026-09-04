export type SessionState =
  | "idle"
  | "calibrating"
  | "pairing"
  | "sending"
  | "receiving"
  | "verifying"
  | "complete"
  | "aborted"
  | "paused";

export type CoachMetrics = {
  blur: number;
  contrast: number;
  motion: number;
  fps: number;
  decodeMs: number;
  unique: number;
  recovered: number;
  k: number;
  gatePass: boolean;
  reason: string;
};

export const EMPTY_COACH: CoachMetrics = {
  blur: 0,
  contrast: 0,
  motion: 0,
  fps: 0,
  decodeMs: 0,
  unique: 0,
  recovered: 0,
  k: 0,
  gatePass: false,
  reason: "awaiting",
};

export function stateLabel(s: SessionState): string {
  switch (s) {
    case "idle":
      return "Idle";
    case "calibrating":
      return "Calibrating";
    case "pairing":
      return "Compare SAS";
    case "sending":
      return "Transmitting";
    case "receiving":
      return "Receiving";
    case "verifying":
      return "Verifying";
    case "complete":
      return "Complete";
    case "aborted":
      return "Aborted";
    case "paused":
      return "Paused";
  }
}
