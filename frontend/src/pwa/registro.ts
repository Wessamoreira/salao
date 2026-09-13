/** Registra o app shell somente na versão de produção; no Vite dev o cache atrapalharia. */
export function registrarPwa(): void {
  if (!import.meta.env.PROD || !('serviceWorker' in navigator)) return;

  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {
      // A aplicação continua sendo web normal quando o navegador recusa o service worker.
    });
  }, { once: true });
}
