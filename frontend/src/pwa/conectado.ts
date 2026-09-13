import { useSyncExternalStore } from 'react';

const EVENTO = 'salao:conectividade';
let apiDisponivel = true;

function assinar(avisar: () => void): () => void {
  window.addEventListener('online', avisar);
  window.addEventListener('offline', avisar);
  window.addEventListener(EVENTO, avisar);
  return () => {
    window.removeEventListener('online', avisar);
    window.removeEventListener('offline', avisar);
    window.removeEventListener(EVENTO, avisar);
  };
}

function conectado(): boolean {
  return navigator.onLine && apiDisponivel;
}

function conectadoNoServidor(): boolean {
  return true;
}

/** Fonte única para tela e bloqueio imediato das escritas offline. */
export function useConectado(): boolean {
  return useSyncExternalStore(assinar, conectado, conectadoNoServidor);
}

/** `navigator.onLine` não percebe Wi-Fi conectado com o servidor fora do ar. */
export function registrarEstadoDaApi(disponivel: boolean): void {
  if (apiDisponivel === disponivel) return;
  apiDisponivel = disponivel;
  window.dispatchEvent(new Event(EVENTO));
}
