export const MAX_BYTES = 32768;
export const DISCLOSURE = "deepl-cloudflare-direct-v3";
export const AUTHORITY_NAME = "deepl-api-free-pilot-v1"; // Never change on rotation/redeploy.
export const MAX_LEASE = 60000;
export class Refused extends Error {
  constructor(public readonly code: string, public readonly status = 400) { super(code); }
}
export function insist(ok: unknown, code = "INVALID_REQUEST", status = 400): asserts ok {
  if (!ok) throw new Refused(code, status);
}
export function record(v: unknown): v is Record<string, unknown> {
  return v !== null && typeof v === "object" && !Array.isArray(v);
}
export function exact(v: unknown, keys: string[]): v is Record<string, unknown> {
  return record(v) && Object.keys(v).sort().join(",") === keys.sort().join(",");
}
export function requestId(v: unknown): v is string {
  return typeof v === "string" && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v);
}
export function sensitive(text: string): boolean {
  return /(password|passcode|verification code|one.time code|mot de passe|code de v[eé]rification|验证码|密码|银行卡|信用卡|身份证|\bIBAN\b|\bCVV\b|\bCVC\b|[\w.+-]+@[\w.-]+\.[a-z]{2,}|(?:\d[ -]?){13,19})/i.test(text);
}
export async function boundedJson(body: Request | Response, code: string): Promise<unknown> {
  const size = body.headers.get("content-length");
  insist(size === null || (/^\d+$/.test(size) && Number(size) <= MAX_BYTES), code);
  insist(body.body, code);
  const reader = body.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      length += value.byteLength;
      insist(length <= MAX_BYTES, code);
      chunks.push(value);
    }
    const bytes = new Uint8Array(length);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
    try { return JSON.parse(new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(bytes)); }
    catch { throw new Refused(code === "INVALID_RESPONSE" ? code : "INVALID_REQUEST"); }
    finally { bytes.fill(0); }
  } finally {
    await reader.cancel().catch(() => {});
    chunks.forEach(chunk => chunk.fill(0));
  }
}
export function response(value: unknown, status = 200): Response {
  const body = JSON.stringify(value);
  insist(new TextEncoder().encode(body).length <= MAX_BYTES, "RESPONSE_TOO_LARGE", 502);
  return new Response(body, { status, headers: {
    "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
  } });
}
export function prepare(value: unknown, receivedAt: number) {
  insist(exact(value, ["requestId", "language", "disclosure", "publicPageConfirmed", "remainingMillis", "blocks"]));
  insist(requestId(value.requestId));
  insist((value.language === "EN" || value.language === "FR") && value.disclosure === DISCLOSURE && value.publicPageConfirmed === true, "CONSENT_REQUIRED");
  insist(typeof value.remainingMillis === "number" && Number.isInteger(value.remainingMillis) && value.remainingMillis > 5000 && value.remainingMillis <= MAX_LEASE, "PAGE_EXPIRED");
  insist(Array.isArray(value.blocks) && value.blocks.length >= 1 && value.blocks.length <= 50, "PAGE_TOO_LARGE");
  const ids: string[] = [], texts: string[] = [];
  for (const b of value.blocks) {
    insist(exact(b, ["id", "text"]));
    insist(typeof b.id === "string" && /^[A-Za-z0-9_/-]{1,240}$/.test(b.id) && !ids.includes(b.id));
    insist(typeof b.text === "string" && b.text.trim().length > 0 && [...b.text].length <= 1500 && !/[\x00-\x08\x0b-\x1f]/.test(b.text));
    ids.push(b.id); texts.push(b.text);
  }
  const chars = texts.reduce((n, text) => n + [...text].length, 0);
  insist(chars <= 6000, "PAGE_TOO_LARGE");
  const context = texts.join("\n");
  insist(!sensitive(context), "SENSITIVE_PAGE");
  const body = JSON.stringify({ text: texts, source_lang: value.language, target_lang: "ZH-HANS", context, split_sentences: "nonewlines" });
  insist(new TextEncoder().encode(body).length <= MAX_BYTES, "PAGE_TOO_LARGE");
  return { id: value.requestId, ids, chars, language: value.language, body, deadline: receivedAt + value.remainingMillis - 5000 };
}
