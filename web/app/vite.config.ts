import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Dev-only: proxy API/auth/WS calls to the BFF (run separately via
// `npm run dev` in web/server) so `npm run dev` here gives hot-reload without
// rebuilding the SPA into the BFF's served dist/ on every change.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:3021',
      '/uploads': 'http://localhost:3021',
      '/auth': 'http://localhost:3021',
      '/ws': { target: 'ws://localhost:3021', ws: true },
    },
  },
})
