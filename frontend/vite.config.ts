// defineConfig do vitest, não do vite: é o único que aceita a chave `test`.
import { defineConfig } from 'vitest/config';
import { createHash } from 'node:crypto';
import type { Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

/**
 * PWA sem dependência de runtime.
 *
 * O próprio build conhece os nomes com hash que acabou de produzir. O plugin transforma
 * essa lista no app shell do service worker, evitando uma segunda ferramenta só para
 * descobrir arquivos que o Vite já conhece.
 *
 * A API fica deliberadamente fora do Cache Storage: agenda contém nome de cliente e o
 * computador pode ser compartilhado. Durante uma queda, o React Query mantém o retrato
 * já aberto em memória; recarregar dados e qualquer escrita continuam exigindo servidor.
 */
function pwaDoSalao(): Plugin {
  return {
    name: 'pwa-do-salao',
    apply: 'build',
    generateBundle(_opcoes, bundle) {
      const gerados = Object.values(bundle).map((arquivo) => `/${arquivo.fileName}`).sort();
      const appShell = [...new Set([
        '/', '/index.html', '/manifest.webmanifest', '/favicon-48.png',
        '/pwa-icon-192.png', '/pwa-icon-512.png', '/pwa-maskable-512.png',
        '/apple-touch-icon.png', ...gerados,
      ])];
      const versao = createHash('sha256').update(appShell.join('|')).digest('hex').slice(0, 12);
      const fonte = `
const CACHE = 'salao-shell-${versao}';
const APP_SHELL = ${JSON.stringify(appShell)};

// Sem skipWaiting aqui (RT-INF-013, R-UX-23): assumir sozinha apagaria o cache da versão
// que a aba aberta ainda está usando. A versão nova espera o clique em "Atualizar".
// Na primeira instalação não há quem esperar, e ela ativa direto.
self.addEventListener('install', (evento) => {
  evento.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(APP_SHELL)));
});

self.addEventListener('message', (evento) => {
  if (evento.data && evento.data.tipo === 'atualizar') self.skipWaiting();
});

self.addEventListener('activate', (evento) => {
  evento.waitUntil(
    caches.keys()
      .then((chaves) => Promise.all(chaves
        .filter((chave) => chave.startsWith('salao-shell-') && chave !== CACHE)
        .map((chave) => caches.delete(chave))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (evento) => {
  const requisicao = evento.request;
  const url = new URL(requisicao.url);
  if (requisicao.method !== 'GET' || url.origin !== self.location.origin) return;

  // Dado autenticado nunca vai para um cache compartilhado do navegador.
  if (url.pathname.startsWith('/api/')) return;

  if (requisicao.mode === 'navigate') {
    evento.respondWith(
      Promise.race([
        fetch(requisicao),
        new Promise((_, rejeitar) => setTimeout(() => rejeitar(new Error('prazo')), 3000)),
      ])
        .then((resposta) => {
          if (resposta.ok) {
            const copia = resposta.clone();
            caches.open(CACHE).then((cache) => cache.put('/index.html', copia));
          }
          return resposta;
        })
        .catch(() => caches.match('/index.html')),
    );
    return;
  }

  evento.respondWith(
    caches.match(requisicao).then((guardado) => guardado ?? fetch(requisicao)),
  );
});
`;
      this.emitFile({ type: 'asset', fileName: 'sw.js', source: fonte.trimStart() });
    },
  };
}

export default defineConfig({
  plugins: [react(), tailwindcss(), pwaDoSalao()],
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
    /*
     * 15s por teste, não os 5s do padrão. Com o servidor da prévia e o Chrome abertos na mesma
     * máquina, 35 testes estouraram os 5s em 15/11/2026 e passaram todos com a máquina livre —
     * nenhum por asserção. É o mesmo motivo do `asyncUtilTimeout` em `teste/preparo.ts`: teste
     * instável ensina a ignorar vermelho. O prazo só decide quando desistir; quem passa, passa
     * na mesma velocidade.
     */
    testTimeout: 15_000,
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/teste/preparo.ts',
  },
});
