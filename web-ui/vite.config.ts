import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Subpath when fronted by hub Caddy (e.g. '/luabridge/'). Empty when standalone.
// Caddy preserves the prefix (handle, not handle_path), so Vite serves and
// receives requests including the prefix.
const VITE_BASE = process.env.VITE_BASE || '/'
const HAS_BASE = VITE_BASE !== '/'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  base: VITE_BASE,
  server: {
    host: '127.0.0.1',
    port: 5173,
    // Disable Vite's Host-header allowlist so requests via the hub
    // (hub.localhost) aren't rejected as DNS-rebinding. Dev only.
    allowedHosts: true,
    // When behind hub at port 80, the page loads via Caddy on :80 but Vite's
    // HMR WebSocket runs on :5173. Tell the client to connect via :80 so
    // Caddy can proxy the WebSocket to us.
    ...(HAS_BASE ? { hmr: { clientPort: 80 } } : {}),
  },
})
