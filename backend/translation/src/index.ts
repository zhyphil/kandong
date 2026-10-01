import { authenticate, configuration } from "./auth";
import { AUTHORITY_NAME, boundedJson, exact, insist, prepare, record, Refused, requestId, response } from "./protocol";
export { QuotaAuthority } from "./authority";

const PROVIDER = "https://api-free.deepl.com";
class UpstreamFailure extends Refused {
  constructor(code: string, status: number, readonly diagnostic: string) { super(code,status); }
}
async function provider(path: "/v2/usage" | "/v2/translate", key: string, deadline: number, body?: string): Promise<unknown> {
  const stage = path === "/v2/usage" ? "usage" : "translate";
  const wait = Math.min(30000, deadline - Date.now());
  insist(wait > 0, "PAGE_EXPIRED");
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), wait);
  try {
    const r = await fetch(PROVIDER + path, {
      // Workers supports manual/follow only. Reject 3xx below without forwarding the key.
      method: body === undefined ? "GET" : "POST", redirect: "manual", signal: controller.signal,
      headers: { Authorization: `DeepL-Auth-Key ${key}`, "Content-Type": "application/json" }, body,
    });
    if (!r.ok) {
      await r.body?.cancel();
      throw new UpstreamFailure(r.status === 456 ? "FREE_QUOTA_EXCEEDED" : "PROVIDER_FAILED_NO_RETRY", 502, `${stage}/${r.status}`);
    }
    return await boundedJson(r, "INVALID_RESPONSE");
  } catch (e) {
    if (e instanceof Refused) throw e;
    throw new UpstreamFailure(Date.now() >= deadline ? "PAGE_EXPIRED" : "PROVIDER_FAILED_NO_RETRY", 502, `${stage}/transport`);
  } finally { clearTimeout(timer); }
}
export default {
  async fetch(request, env): Promise<Response> {
    const receivedAt = Date.now();
    try {
      const url = new URL(request.url);
      insist(url.protocol === "https:" && !url.search && !url.hash, "INVALID_REQUEST");
      if (url.pathname === "/health" && request.method === "GET") return response({ status: "ok" });
      insist(request.method === "POST" && ["/translate", "/cancel"].includes(url.pathname), "NOT_FOUND", 404);
      const device = await authenticate(request, configuration(env));
      insist(request.headers.get("content-type") === "application/json");
      const payload = await boundedJson(request, "PAGE_TOO_LARGE");
      const authority = env.AUTHORITY.getByName(AUTHORITY_NAME);
      async function gate(action: Promise<{ ok: boolean; code?: string }>) {
        let result;
        try { result = await action; } catch { throw new Refused("AUTHORITY_UNAVAILABLE", 503); }
        if (!result.ok) throw new Refused(result.code ?? "AUTHORITY_UNAVAILABLE", 409);
      }
      if (url.pathname === "/cancel") {
        insist(exact(payload, ["requestId"]) && requestId(payload.requestId));
        await gate(authority.cancel({ device: device.id, id: payload.requestId, expiresAt: device.expiresAt }));
        return response({ cancelled: true });
      }
      const page = prepare(payload, receivedAt);
      const meta = { device: device.id, id: page.id, expiresAt: device.expiresAt };
      await gate(authority.reserve(meta, page.chars, page.deadline));
      try {
        const usage = await provider("/v2/usage", env.DEEPL_API_KEY, page.deadline);
        insist(record(usage) && Number.isSafeInteger(usage.character_count) && Number.isSafeInteger(usage.character_limit) && (usage.character_count as number) >= 0, "INVALID_RESPONSE", 502);
        insist((usage.character_limit as number) >= 0, "INVALID_RESPONSE", 502);
        let available = (usage.character_limit as number) - (usage.character_count as number);
        if ("api_key_character_limit" in usage) {
          insist(Number.isSafeInteger(usage.api_key_character_count) && Number.isSafeInteger(usage.api_key_character_limit) && (usage.api_key_character_count as number) >= 0 && (usage.api_key_character_limit as number) >= 0, "INVALID_RESPONSE", 502);
          available = Math.min(available, (usage.api_key_character_limit as number) - (usage.api_key_character_count as number));
        }
        insist(available >= page.chars, "FREE_QUOTA_EXCEEDED", 429);
        insist(!request.signal.aborted, "CANCELLED");
        await authenticate(request, configuration(env));
        await gate(authority.advance(meta, "dispatch"));
        insist(Date.now() < page.deadline && !request.signal.aborted, "PAGE_EXPIRED");
        const result = await provider("/v2/translate", env.DEEPL_API_KEY, page.deadline, page.body);
        insist(record(result) && Array.isArray(result.translations) && result.translations.length === page.ids.length, "INVALID_RESPONSE", 502);
        const translations = result.translations.map((row: unknown, i: number) => {
          insist(record(row) && typeof row.text === "string" && row.text.trim().length > 0 && [...row.text].length <= 6000, "INVALID_RESPONSE", 502);
          insist(row.detected_source_language === page.language, "LANGUAGE_MISMATCH", 502);
          return { id: page.ids[i], text: row.text };
        });
        const outgoing = response({ requestId: page.id, translations });
        insist(!request.signal.aborted, "CANCELLED");
        await authenticate(request, configuration(env));
        await gate(authority.advance(meta, "publish"));
        insist(Date.now() < page.deadline, "PAGE_EXPIRED");
        return outgoing;
      } finally {
        page.body = "";
        try { await authority.finish(meta); } catch { throw new Refused("AUTHORITY_UNAVAILABLE", 503); }
      }
    } catch (e) {
      // No exception/body/URL logging; only fixed contract codes leave this boundary.
      const failed = response({ error: e instanceof Refused ? e.code : "SERVICE_UNAVAILABLE" }, e instanceof Refused ? e.status : 503);
      // Authenticated callers get only fixed stage/status metadata, never provider text or exceptions.
      if (e instanceof UpstreamFailure) failed.headers.set("X-KanDong-Upstream",e.diagnostic);
      return failed;
    }
  },
} satisfies ExportedHandler<Env>;
