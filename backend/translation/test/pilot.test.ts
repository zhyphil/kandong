import { env } from "cloudflare:workers";
import { abortAllDurableObjects, reset, runInDurableObject, runDurableObjectAlarm } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from "vitest";
import worker from "../src/index";
import { AUTHORITY_NAME, DISCLOSURE } from "../src/protocol";
import { CAPS } from "../src/authority";

const token = "a".repeat(64); // Synthetic; never issued by any service.
const origin = "https://kandong-translation-pilot.test.workers.dev";
let bindings: { -readonly [K in keyof Env]: Env[K] };
let fetcher: MockInstance<typeof fetch>;
const authority = () => env.AUTHORITY.getByName(AUTHORITY_NAME);
const page = (overrides: Record<string, unknown> = {}) => ({
  requestId: crypto.randomUUID(), language: "EN", disclosure: DISCLOSURE,
  publicPageConfirmed: true, remainingMillis: 60000,
  blocks: [{ id: "inside", text: "Open the museum" }, { id: "outside", text: "Tickets are free" }], ...overrides,
});
function post(payload: unknown, path = "/translate", auth = token) {
  return worker.fetch(new Request(origin + path, {
    method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${auth}` },
    body: typeof payload === "string" ? payload : JSON.stringify(payload),
  }), bindings);
}
async function error(payload: unknown, path = "/translate", auth = token) {
  const r = await post(payload, path, auth);
  expect(r.status).not.toBe(200);
  return (await r.json() as { error: string }).error;
}
function metadata(id = crypto.randomUUID(), device = "nova9") {
  return { device, id, expiresAt: Date.now() + 86400000 };
}
async function counters() {
  return runInDurableObject(authority(), (_obj, state) => state.storage.sql.exec("SELECT * FROM counters ORDER BY scope,period").toArray());
}
async function stored() {
  return runInDurableObject(authority(), (_obj, state) => state.storage.sql.exec("SELECT * FROM requests").toArray());
}
function fakeProvider(callback?: (path: string, init: RequestInit) => Promise<Response | undefined>) {
  fetcher.mockImplementation(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = String(input);
    expect(url.startsWith("https://api-free.deepl.com/v2/")).toBe(true);
    expect(init.redirect).toBe("manual");
    expect(new Headers(init.headers).get("authorization")).toBe("DeepL-Auth-Key synthetic-private-key:fx");
    const result = await callback?.(url, init);
    if (result) return result;
    if (url.endsWith("/usage")) return Response.json({ character_count: 0, character_limit: 500000 });
    const body = JSON.parse(init.body as string);
    return Response.json({ translations: body.text.map(() => ({ text: "公开译文", detected_source_language: body.source_lang })) });
  });
}
beforeEach(async () => {
  const hash = Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token))), b => b.toString(16).padStart(2,"0")).join("");
  bindings = { ...env, DEEPL_API_KEY: "synthetic-private-key:fx", DEVICE_CREDENTIALS: JSON.stringify([{ id: "nova9", sha256: hash, expiresAt: Date.now() + 86400000 }]) };
  fetcher = vi.spyOn(globalThis, "fetch").mockRejectedValue(new Error("Offline test: unconfigured fetch"));
});
afterEach(async () => { vi.restoreAllMocks(); await reset(); });

describe("authenticated single-page contract", () => {
  it("constructs upstream requests supported by the actual Workers runtime", async () => {
    fakeProvider();
    expect((await post(page())).status).toBe(200);
    expect(fetcher).toHaveBeenCalledTimes(2);
    for (const [input, init] of fetcher.mock.calls) {
      expect(() => new Request(input, init)).not.toThrow();
    }
  });
  it.each(["EN", "FR"])("joins every block as context and preserves IDs/order for %s", async language => {
    const p = page({ language });
    fakeProvider(async (url, init) => {
      if (url.endsWith("/translate")) {
        expect(JSON.parse(init.body as string)).toEqual({ text: p.blocks.map(b => b.text), context: "Open the museum\nTickets are free", source_lang: language, target_lang: "ZH-HANS", split_sentences: "nonewlines" });
      }
      return undefined;
    });
    const r = await post(p);
    expect(r.status).toBe(200);
    expect(await r.json()).toEqual({ requestId: p.requestId, translations: p.blocks.map(b => ({ id: b.id, text: "公开译文" })) });
    expect(r.headers.get("cache-control")).toBe("no-store");
    expect(fetcher).toHaveBeenCalledTimes(2);
    const serialized = JSON.stringify(await stored()) + JSON.stringify(await counters());
    for (const secret of [token, "Open the museum", "Tickets are free", "公开译文", "synthetic-private-key"]) expect(serialized).not.toContain(secret);
    const rows = await stored();
    expect(Object.keys(rows[0]).sort()).toEqual(["deadline","device","expires","id","state"]);
  });
  it("rejects missing, wrong, expired and revoked bearers without provider traffic", async () => {
    expect(await error(page(), "/translate", "")).toBe("UNAUTHORIZED");
    expect(await error(page(), "/translate", "b".repeat(64))).toBe("UNAUTHORIZED");
    const d = JSON.parse(bindings.DEVICE_CREDENTIALS);
    d[0].expiresAt = Date.now()-1; bindings.DEVICE_CREDENTIALS=JSON.stringify(d);
    expect(await error(page())).toBe("CREDENTIAL_EXPIRED");
    d[0].expiresAt=Date.now()+86400000; d[0].sha256="0".repeat(64); bindings.DEVICE_CREDENTIALS=JSON.stringify(d);
    expect(await error(page())).toBe("UNAUTHORIZED");
    expect(fetcher).not.toHaveBeenCalled();
  });
  it.each(["", "[]", "{}", "not-json", JSON.stringify(Array(17).fill({}))])("malformed/missing secret disables service (%s)", async secret => {
    bindings.DEVICE_CREDENTIALS=secret;
    expect(await error(page())).toBe("SERVICE_DISABLED");
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("rejects non-Free provider keys and unsupported routes/query strings", async () => {
    bindings.DEEPL_API_KEY="synthetic-private-key";
    expect(await error(page())).toBe("SERVICE_DISABLED");
    const r=await worker.fetch(new Request(origin+"/health"),bindings);
    expect(await r.json()).toEqual({status:"ok"});
    expect(await error(page(),"/translate?text=not-allowed")).toBe("INVALID_REQUEST");
    expect(await error(page(),"/anything")).toBe("NOT_FOUND");
    expect(fetcher).not.toHaveBeenCalled();
  });
  it.each([{ language: "ZH-HANS" }, { disclosure: "deepl-free-direct-v2" }, { publicPageConfirmed: false }])("rejects old/wrong consent %j", async change => {
    expect(await error(page(change))).toBe("CONSENT_REQUIRED"); expect(fetcher).not.toHaveBeenCalled();
  });
  it("rejects duplicate IDs, invalid characters and oversized block/page/body", async () => {
    expect(await error(page({ blocks: [{id:"same",text:"a"},{id:"same",text:"b"}] }))).toBe("INVALID_REQUEST");
    expect(await error(page({ blocks: [{id:"ok",text:"a\u0000b"}] }))).toBe("INVALID_REQUEST");
    expect(await error(page({ blocks: [{id:"ok",text:"a".repeat(1501)}] }))).toBe("INVALID_REQUEST");
    expect(await error(page({ blocks: Array.from({length:51},(_,i)=>({id:String(i),text:"a"})) }))).toBe("PAGE_TOO_LARGE");
    expect(await error(page({ blocks: Array.from({length:5},(_,i)=>({id:String(i),text:"a".repeat(1500)})) }))).toBe("PAGE_TOO_LARGE");
    expect(await error(" ".repeat(32769))).toBe("PAGE_TOO_LARGE");
    expect(fetcher).not.toHaveBeenCalled();
  });
  it.each(["password", "mot de passe", "person@example.test", "1234 5678 9012 3456", "验证码"])("filters sensitive text outside selection: %s", async text => {
    expect(await error(page({blocks:[{id:"inside",text:"Public museum"},{id:"outside",text}]}))).toBe("SENSITIVE_PAGE");
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("rejects expired/invalid relative leases before external I/O", async () => {
    for (const remainingMillis of [0,5000,60001,1.2]) expect(await error(page({remainingMillis}))).toBe("PAGE_EXPIRED");
    expect(fetcher).not.toHaveBeenCalled();
  });
});

describe("persistent quota and cancellation", () => {
  it("admits only one parallel reservation and charges once", async () => {
    const a=metadata(), b=metadata();
    const results=await Promise.all([authority().reserve(a,6000,Date.now()+55000),authority().reserve(b,6000,Date.now()+55000)]);
    expect(results.filter(r=>r.ok)).toHaveLength(1);
    expect(results.filter(r=>!r.ok)).toEqual([{ok:false,code:"BUSY"}]);
    expect((await counters()).every(r=>r.chars===6000 && r.requests===1)).toBe(true);
  });
  it.each([
    ["global","day",CAPS.globalDay], ["global","month",CAPS.globalMonth],
    ["nova9","day",CAPS.deviceDay], ["nova9","month",CAPS.deviceMonth],
  ] as const)("enforces both character/request caps at %s %s", async (scope, periodKind, cap) => {
    for (const field of ["chars","requests"]) {
      await runInDurableObject(authority(), (_obj,state)=> {
        const date=new Date().toISOString(); const period=date.slice(0,periodKind==="day"?10:7);
        state.storage.sql.exec("DELETE FROM counters");
        state.storage.sql.exec("INSERT INTO counters VALUES (?,?,?,?)",scope,period,field==="chars"?cap.chars:0,field==="requests"?cap.requests:0);
      });
      expect(await authority().reserve(metadata(),1,Date.now()+55000)).toEqual({ok:false,code:"PILOT_QUOTA_EXCEEDED"});
    }
  });
  it("keeps counters and replay guard across object eviction and token rotation", async () => {
    const m=metadata(); expect(await authority().reserve(m,5000,Date.now()+55000)).toEqual({ok:true});
    await authority().finish(m);
    const before=await counters(); await abortAllDurableObjects();
    expect(await counters()).toEqual(before);
    expect(await authority().reserve({...m,expiresAt:m.expiresAt+1000},1,Date.now()+55000)).toEqual({ok:false,code:"DUPLICATE_NO_RETRY"});
    expect(await authority().reserve(metadata(),5001,Date.now()+55000)).toEqual({ok:false,code:"PILOT_QUOTA_EXCEEDED"});
  });
  it("remembers cancellation before arrival, including across eviction", async () => {
    const p=page(); expect((await post({requestId:p.requestId},"/cancel")).status).toBe(200);
    await abortAllDurableObjects(); expect(await error(p)).toBe("CANCELLED");
    expect(fetcher).not.toHaveBeenCalled(); expect(await counters()).toEqual([]);
  });
  it("cancels after reserve/usage before dispatch and retains the charge", async () => {
    const p=page(); fakeProvider(async url=> {
      if(url.endsWith("/usage")) expect((await post({requestId:p.requestId},"/cancel")).status).toBe(200);
      return undefined;
    });
    expect(await error(p)).toBe("CANCELLED"); expect(fetcher).toHaveBeenCalledTimes(1);
    expect((await counters()).every(r=>r.requests===1)).toBe(true);
  });
  it("cancels during provider without releasing concurrency early or publishing", async () => {
    const p=page(); fakeProvider(async url=> {
      if(url.endsWith("/translate")) {
        expect((await post({requestId:p.requestId},"/cancel")).status).toBe(200);
        expect(await error(page())).toBe("BUSY");
      }
      return undefined;
    });
    expect(await error(p)).toBe("CANCELLED"); expect(fetcher).toHaveBeenCalledTimes(2);
    expect(await error(p)).toBe("CANCELLED");
  });
  it("active/completed duplicate never dispatches twice", async () => {
    const p=page(); fakeProvider(async url=> {
      if(url.endsWith("/usage")) expect(await error(p)).toBe("DUPLICATE_NO_RETRY");
      return undefined;
    });
    expect((await post(p)).status).toBe(200);
    expect(await error(p)).toBe("DUPLICATE_NO_RETRY"); expect(fetcher).toHaveBeenCalledTimes(2);
  });
  it("ambiguous provider failure remains charged and cannot be replayed", async () => {
    const p=page(); fakeProvider(async url=> { if(url.endsWith("/translate")) throw new Error("synthetic timeout"); return undefined; });
    expect(await error(p)).toBe("PROVIDER_FAILED_NO_RETRY");
    expect(await error(p)).toBe("DUPLICATE_NO_RETRY");
    expect((await counters()).every(r=>r.requests===1)).toBe(true);
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
  it("expiry between usage and dispatch or between response and publication fails closed", async () => {
    for (const path of ["/usage","/translate"]) {
      await reset();
      fakeProvider(async url=> {
        if(url.endsWith(path)) await runInDurableObject(authority(),(_obj,state)=> { state.storage.sql.exec("UPDATE requests SET deadline=0"); });
        return undefined;
      });
      expect(await error(page())).toBe("PAGE_EXPIRED");
      expect(fetcher).toHaveBeenCalledTimes(path==="/usage"?1:2); fetcher.mockClear();
    }
  });
  it("authority storage failure cannot dispatch", async () => {
    await runInDurableObject(authority(),(_obj,state)=> { state.storage.sql.exec("DROP TABLE counters"); });
    expect(await error(page())).toBe("AUTHORITY_UNAVAILABLE"); expect(fetcher).not.toHaveBeenCalled();
  });
  it("cleans expired tombstones only after expiry plus max lease", async () => {
    const m=metadata(); await authority().cancel(m);
    await runInDurableObject(authority(),(_obj,state)=> { state.storage.sql.exec("UPDATE requests SET expires=?",Date.now()-59000); });
    await authority().cancel(metadata()); expect(await stored()).toHaveLength(2);
    await runInDurableObject(authority(),(_obj,state)=> { state.storage.sql.exec("UPDATE requests SET expires=? WHERE id=?",Date.now()-61000,m.id); });
    await authority().cancel(metadata()); expect((await stored()).some(r=>r.id===m.id)).toBe(false);
  });
  it("cleans metadata while idle instead of relying on another phone request", async () => {
    await authority().cancel(metadata());
    const alarm = await runInDurableObject(authority(), (_obj,state) => state.storage.getAlarm());
    expect(alarm).not.toBeNull();
    await runInDurableObject(authority(), (_obj,state) => {
      state.storage.sql.exec("UPDATE requests SET expires=?",Date.now()-61000);
    });
    expect(await runDurableObjectAlarm(authority())).toBe(true);
    expect(await stored()).toHaveLength(0);
    expect(await runInDurableObject(authority(), (_obj,state) => state.storage.getAlarm())).toBeNull();
  });
  it("keeps scheduling cleanup for quota counters after the final request expires", async () => {
    const m=metadata(); await authority().reserve(m,10,Date.now()+50000); await authority().finish(m);
    await runInDurableObject(authority(), (_obj,state) => {
      state.storage.sql.exec("UPDATE requests SET expires=?",Date.now()-61000);
    });
    await runDurableObjectAlarm(authority());
    expect(await stored()).toHaveLength(0); expect(await counters()).toHaveLength(4);
    expect(await runInDurableObject(authority(), (_obj,state) => state.storage.getAlarm())).not.toBeNull();
    await runInDurableObject(authority(), (_obj,state) => {
      state.storage.sql.exec("UPDATE counters SET period='2000-' || period");
    });
    await runDurableObjectAlarm(authority());
    expect(await counters()).toHaveLength(0);
    expect(await runInDurableObject(authority(), (_obj,state) => state.storage.getAlarm())).toBeNull();
  });
});

describe("provider refusal and bounds", () => {
  it("exposes only a fixed upstream stage/status, never the error body", async () => {
    fakeProvider(async () => new Response("private provider response",{status:403}));
    const r=await post(page());
    expect(r.headers.get("x-kandong-upstream")).toBe("usage/403");
    expect(await r.json()).toEqual({error:"PROVIDER_FAILED_NO_RETRY"});
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it("checks API Free usage before translation", async () => {
    fakeProvider(async () => Response.json({character_count:500000,character_limit:500000}));
    expect(await error(page())).toBe("FREE_QUOTA_EXCEEDED"); expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it("never follows redirects or retries", async () => {
    fakeProvider(async () => new Response(null,{status:302,headers:{location:"https://not-a-provider.invalid"}}));
    expect(await error(page())).toBe("PROVIDER_FAILED_NO_RETRY"); expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it("rejects oversized streamed provider responses without trusting Content-Length", async () => {
    fakeProvider(async ()=>new Response(new ReadableStream({start(c){c.enqueue(new TextEncoder().encode(" ".repeat(32769)));c.close();}})));
    expect(await error(page())).toBe("INVALID_RESPONSE"); expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it.each([{rows:[]},{rows:[{text:"",detected_source_language:"EN"}]},{rows:[{text:"译文",detected_source_language:"FR"}]}])("rejects incomplete or mismatched provider output $rows",async ({rows})=> {
    fakeProvider(async url=>url.endsWith("/translate")?Response.json({translations:rows}):undefined);
    const p=page({blocks:[{id:"only",text:"Public museum"}]});
    expect(["INVALID_RESPONSE","LANGUAGE_MISMATCH"]).toContain(await error(p));
  });
});
