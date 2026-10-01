import { bindings, defineConfig, defineWorker, exports } from "cf/config";
import * as entrypoint from "./src/index.ts" with { type: "cf-worker" };

// A typed reference preserves the exported RPC methods in generated Env types.
const reference = defineWorker({
  name: "kandong-translation-pilot", compatibilityDate: "2026-10-01", entrypoint,
  exports: { QuotaAuthority: exports.durableObject({ storage: "sqlite" }) },
});
export default defineConfig({
  worker: {
    ...reference,
    workersDev: true,
    previewUrls: false,
    observability: {
      enabled: false, redactQueryString: true, issues: { enabled: false },
      logs: { enabled: false, invocationLogs: false, persist: false },
      traces: { enabled: false, persist: false },
    },
    env: {
      DEEPL_API_KEY: bindings.secret(),
      DEVICE_CREDENTIALS: bindings.secret(),
      AUTHORITY: bindings.durableObject({ worker: reference, exportName: "QuotaAuthority" }),
    },
  },
});
