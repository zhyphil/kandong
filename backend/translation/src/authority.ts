import { DurableObject } from "cloudflare:workers";
import { MAX_LEASE } from "./protocol";

export const CAPS = {
  globalDay: { chars: 20000, requests: 100 }, globalMonth: { chars: 100000, requests: 2000 },
  deviceDay: { chars: 10000, requests: 60 }, deviceMonth: { chars: 60000, requests: 1200 },
} as const;
export interface Metadata { device: string; id: string; expiresAt: number }
type Row = { device: string; id: string; expires: number; deadline: number; state: string };
type Decision = { ok: true } | { ok: false; code: string };
const denied = (code: string): Decision => ({ ok: false, code });

/** One account, one authority. RPC receives metadata only, never page content. */
export class QuotaAuthority extends DurableObject<Env> {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS requests (
      device TEXT NOT NULL, id TEXT NOT NULL, expires INTEGER NOT NULL,
      deadline INTEGER NOT NULL, state TEXT NOT NULL, PRIMARY KEY(device,id));
      CREATE INDEX IF NOT EXISTS requests_expiry ON requests(expires);
      CREATE TABLE IF NOT EXISTS counters (
      scope TEXT NOT NULL, period TEXT NOT NULL, chars INTEGER NOT NULL,
      requests INTEGER NOT NULL, PRIMARY KEY(scope,period));`);
  }
  private row(m: Metadata): Row | undefined {
    return this.ctx.storage.sql.exec<Row>("SELECT * FROM requests WHERE device=? AND id=?", m.device, m.id).toArray()[0];
  }
  private cleanup(now: number) {
    this.ctx.storage.sql.exec("DELETE FROM requests WHERE expires < ?", now - MAX_LEASE);
    const previous = new Date(now); previous.setUTCDate(1); previous.setUTCMonth(previous.getUTCMonth() - 1);
    this.ctx.storage.sql.exec("DELETE FROM counters WHERE period < ?", previous.toISOString().slice(0, 7));
  }
  private room(device: string): boolean {
    const sql = this.ctx.storage.sql;
    return sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM requests").one().n < 40000 &&
      sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM requests WHERE device=?", device).one().n < 10000;
  }
  async reserve(m: Metadata, chars: number, deadline: number): Promise<Decision> {
    const result: Decision = this.ctx.storage.transactionSync((): Decision => {
      const now = Date.now(); this.cleanup(now);
      if (m.expiresAt <= now || deadline <= now || deadline > now + MAX_LEASE || !Number.isInteger(chars) || chars < 1 || chars > 6000) return denied("PAGE_EXPIRED");
      const old = this.row(m);
      if (old) return denied(old.state.startsWith("cancelled") ? "CANCELLED" : "DUPLICATE_NO_RETRY");
      if (!this.room(m.device)) return denied("METADATA_LIMIT");
      // No queue: at most one active provider lease for the shared account.
      if (this.ctx.storage.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM requests WHERE state IN ('reserved','dispatched','cancelled_active') AND deadline > ?", now).one().n > 0) return denied("BUSY");
      const day = new Date(now).toISOString().slice(0, 10), month = day.slice(0, 7);
      const budgets = [
        ["global", day, CAPS.globalDay], ["global", month, CAPS.globalMonth],
        [m.device, day, CAPS.deviceDay], [m.device, month, CAPS.deviceMonth],
      ] as const;
      for (const [scope, period, cap] of budgets) {
        const used = this.ctx.storage.sql.exec<{ chars: number; requests: number }>("SELECT chars,requests FROM counters WHERE scope=? AND period=?", scope, period).toArray()[0];
        if ((used?.chars ?? 0) + chars > cap.chars || (used?.requests ?? 0) + 1 > cap.requests) return denied("PILOT_QUOTA_EXCEEDED");
      }
      // Charge durably BEFORE usage lookup / sole dispatch. Never refund.
      for (const [scope, period] of budgets) this.ctx.storage.sql.exec(
        "INSERT INTO counters VALUES (?,?,?,1) ON CONFLICT(scope,period) DO UPDATE SET chars=chars+excluded.chars, requests=requests+1", scope, period, chars);
      this.ctx.storage.sql.exec("INSERT INTO requests VALUES (?,?,?,?, 'reserved')", m.device, m.id, m.expiresAt, deadline);
      return { ok: true };
    });
    if (result.ok) await this.scheduleCleanup();
    return result;
  }
  async cancel(m: Metadata): Promise<Decision> {
    const result: Decision = this.ctx.storage.transactionSync((): Decision => {
      const now = Date.now(); this.cleanup(now);
      if (m.expiresAt <= now) return denied("CREDENTIAL_EXPIRED");
      const old = this.row(m);
      if (!old) {
        if (!this.room(m.device)) return denied("METADATA_LIMIT");
        this.ctx.storage.sql.exec("INSERT INTO requests VALUES (?,?,?,0,'cancelled')", m.device, m.id, m.expiresAt);
      } else {
        // Retain active lease until the owning dispatch finishes or times out.
        const state = ["dispatched", "cancelled_active"].includes(old.state) ? "cancelled_active" : "cancelled";
        this.ctx.storage.sql.exec("UPDATE requests SET state=? WHERE device=? AND id=?", state, m.device, m.id);
      }
      return { ok: true };
    });
    if (result.ok) await this.scheduleCleanup();
    return result;
  }
  private async scheduleCleanup() {
    const expiry = this.ctx.storage.sql.exec<{ expiry: number | null }>("SELECT MIN(expires) AS expiry FROM requests").one().expiry;
    const hasCounters = this.ctx.storage.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM counters").one().n > 0;
    if (expiry === null && !hasCounters) { await this.ctx.storage.deleteAlarm(); return; }
    // Counters outlive some credentials; keep the monthly cleanup even with no requests left.
    const now = new Date();
    const monthBoundary = Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 1);
    const next = Math.min(expiry === null ? Infinity : expiry + MAX_LEASE + 1, hasCounters ? monthBoundary : Infinity);
    const existing = await this.ctx.storage.getAlarm();
    if (existing === null || next < existing) await this.ctx.storage.setAlarm(next);
  }
  async alarm(): Promise<void> {
    this.cleanup(Date.now());
    await this.scheduleCleanup();
  }
  advance(m: Metadata, stage: "dispatch" | "publish"): Decision {
    return this.ctx.storage.transactionSync(() => {
      const row = this.row(m), now = Date.now();
      if (!row || row.state.startsWith("cancelled")) return denied("CANCELLED");
      if (m.expiresAt <= now || row.expires <= now || row.deadline <= now) return denied("PAGE_EXPIRED");
      if (row.state !== (stage === "dispatch" ? "reserved" : "dispatched")) return denied("DUPLICATE_NO_RETRY");
      this.ctx.storage.sql.exec("UPDATE requests SET state=? WHERE device=? AND id=?", stage === "dispatch" ? "dispatched" : "done", m.device, m.id);
      return { ok: true };
    });
  }
  finish(m: Metadata): void {
    this.ctx.storage.sql.exec("UPDATE requests SET state=CASE WHEN state LIKE 'cancelled%' THEN 'cancelled' ELSE 'done' END WHERE device=? AND id=?", m.device, m.id);
  }
}
