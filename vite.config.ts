import { defineConfig } from "vite";

export default defineConfig({
  server: {
    host: true,
    port: 5173,
    allowedHosts: [".monkeycode-ai.live"],
    strictPort: false,
  },
  preview: {
    host: true,
    port: 4173,
    allowedHosts: [".monkeycode-ai.live"],
  },
  build: {
    target: "es2022",
    sourcemap: false,
  },
});
