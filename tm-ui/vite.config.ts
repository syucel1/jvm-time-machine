import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// `npm run dev` serves the UI on :5173 and proxies the API to a running `tm-cli serve` (default :7070).
const api = process.env.TM_API ?? "http://127.0.0.1:7070";

export default defineConfig({
  plugins: [react()],
  base: "/",
  build: {
    outDir: "dist",
    emptyOutDir: true,
    sourcemap: false,
    // tm-cli serves files from its classpath; keep the bundle self-contained.
    assetsInlineLimit: 0,
  },
  server: {
    proxy: { "/api": { target: api, changeOrigin: true } },
  },
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
  },
});
