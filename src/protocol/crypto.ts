import * as ed from "@noble/ed25519";
import { sha256 } from "@noble/hashes/sha256";
import { sha512 } from "@noble/hashes/sha512";
import { concat, toHex } from "./bytes";
import { SAS_DIGITS } from "./constants";

ed.etc.sha512Sync = (...m) => sha512(ed.etc.concatBytes(...m));

export type KeyPair = {
  secretKey: Uint8Array;
  publicKey: Uint8Array;
};

export async function generateKeyPair(): Promise<KeyPair> {
  const secretKey = ed.utils.randomPrivateKey();
  const publicKey = await ed.getPublicKeyAsync(secretKey);
  return { secretKey, publicKey };
}

export async function sign(message: Uint8Array, secretKey: Uint8Array): Promise<Uint8Array> {
  return ed.signAsync(message, secretKey);
}

export async function verify(
  signature: Uint8Array,
  message: Uint8Array,
  publicKey: Uint8Array,
): Promise<boolean> {
  try {
    return await ed.verifyAsync(signature, message, publicKey);
  } catch {
    return false;
  }
}

export function fileHash(data: Uint8Array): Uint8Array {
  return sha256(data);
}

export function sasFromPublicKey(publicKey: Uint8Array): string {
  const h = sha256(concat(new Uint8Array([0x53, 0x41, 0x53]), publicKey));
  let n = 0;
  for (let i = 0; i < 4; i++) n = (n * 256 + h[i]!) >>> 0;
  return (n % 10 ** SAS_DIGITS).toString().padStart(SAS_DIGITS, "0");
}

export function sasPretty(sas: string): string {
  return `${sas.slice(0, 4)} ${sas.slice(4)}`;
}

export function sessionFingerprint(sessionId: Uint8Array, publicKey: Uint8Array): string {
  return toHex(sha256(concat(sessionId, publicKey)).slice(0, 6)).toUpperCase();
}
