import { exact, insist, Refused } from "./protocol";

export interface Device { id: string; sha256: string; expiresAt: number }
export function configuration(env: Pick<Env, "DEEPL_API_KEY" | "DEVICE_CREDENTIALS">): Device[] {
  try {
    insist(typeof env.DEEPL_API_KEY === "string" && /^[A-Za-z0-9:_-]{16,256}$/.test(env.DEEPL_API_KEY) && env.DEEPL_API_KEY.endsWith(":fx"));
    insist(typeof env.DEVICE_CREDENTIALS === "string" && env.DEVICE_CREDENTIALS.length <= 8192);
    const devices: unknown = JSON.parse(env.DEVICE_CREDENTIALS);
    insist(Array.isArray(devices) && devices.length > 0 && devices.length <= 16);
    const ids = new Set(), hashes = new Set();
    for (const d of devices) {
      insist(exact(d, ["id", "sha256", "expiresAt"]));
      insist(typeof d.id === "string" && /^[a-z0-9_-]{1,32}$/.test(d.id) && d.id !== "global" && !ids.has(d.id));
      insist(typeof d.sha256 === "string" && /^[a-f0-9]{64}$/.test(d.sha256) && !hashes.has(d.sha256));
      insist(typeof d.expiresAt === "number" && Number.isSafeInteger(d.expiresAt) && d.expiresAt > 0 && d.expiresAt <= Date.now() + 90 * 86400000);
      ids.add(d.id); hashes.add(d.sha256);
    }
    return devices as Device[];
  } catch { throw new Refused("SERVICE_DISABLED", 503); }
}
export async function authenticate(request: Request, devices: Device[]): Promise<Device> {
  const authorization = request.headers.get("authorization") ?? "";
  insist(/^Bearer [a-f0-9]{64}$/.test(authorization), "UNAUTHORIZED", 401);
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(authorization.slice(7)));
  let matched: Device | undefined;
  // All configured hashes are examined using the runtime constant-time primitive.
  for (const d of devices) {
    const expected = Uint8Array.from(d.sha256.match(/../g)!, pair => parseInt(pair, 16));
    if (crypto.subtle.timingSafeEqual(digest, expected)) matched = d;
  }
  insist(matched, "UNAUTHORIZED", 401);
  insist(matched.expiresAt > Date.now(), "CREDENTIAL_EXPIRED", 401);
  return matched;
}
