// defineConfig do vitest, não do vite: é o único que aceita a chave `test`.
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // O front nunca conhece a URL do backend: chama /api e o proxy resolve.
    // Em produção o Caddy faz o mesmo papel, então o código é o mesmo nos dois.
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
    fs: {
      // design/tokens.css vive fora de frontend/ de propósito: é a fonte única,
      // compartilhada com o canvas de design. O symlink em src/estilo aponta para lá.
      allow: ['..'],
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/teste/preparo.ts',
  },
});
