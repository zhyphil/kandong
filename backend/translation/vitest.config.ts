import { cloudflareTest } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [cloudflareTest({
    main: "./src/index.ts",
    remoteBindings: false,
    miniflare: {
      compatibilityDate: "2026-10-01",
      compatibilityFlags: ["nodejs_compat"],
      durableObjects: { AUTHORITY: { className: "QuotaAuthority", useSQLite: true } },
      bindings: { DEEPL_API_KEY: "disabled-test-binding", DEVICE_CREDENTIALS: "[]" },
      // Absolute network backstop. All provider behavior is explicitly mocked inside the test isolate.
      outboundService: () => new Response("Network disabled in offline tests", { status: 503 }),
    },
  })],
  test: { include: ["test/**/*.test.ts"], fileParallelism: false },
});
