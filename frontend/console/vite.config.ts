import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  // LOCAL DEVELOPMENT ONLY (development sign-in, ADR-007 amendment): the dev server forwards the same-origin
  // /realms/... calls to Keycloak, so the browser makes no cross-origin request. A production build has no
  // dev server and therefore no such route.
  const keycloakTarget = env.VITE_KEYCLOAK_PROXY_TARGET || 'http://localhost:8081';
  // Keycloak must see the request exactly as if the browser had called its public URL: the Host of the
  // configured authority (KC_HOSTNAME), not the internal proxy target and not localhost:5173. Then every URL it
  // derives, the token issuer included, is the public one the backend validates.
  const publicKeycloakHost = new URL(env.VITE_OIDC_AUTHORITY || 'http://localhost:8081/realms/demo-nbfc').host;
  return {
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/realms': {
        target: keycloakTarget,
        changeOrigin: false, // keep the Host set below instead of the target's
        headers: { host: publicKeycloakHost },
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => {
            // Server-to-server call from Keycloak's point of view: no browser Origin/Referer/cookies.
            proxyReq.removeHeader('origin');
            proxyReq.removeHeader('referer');
            proxyReq.removeHeader('cookie');
          });
        },
      },
    },
  },
  build: {
    sourcemap: true,
    rolldownOptions: {
      output: {
        codeSplitting: {
          groups: [
            { name: 'react', test: /node_modules[\\/](react|react-dom|react-router|scheduler|cookie-es|@remix-run)[\\/]/ },
            { name: 'vendor', test: /node_modules[\\/]/ },
          ],
        },
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['src/test/setup.ts'],
    env: { VITE_MOCK: '1' },
    css: false,
    testTimeout: 15000,
  },
  };
});
