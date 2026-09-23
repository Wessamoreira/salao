import { useSyncExternalStore } from 'react';

const EVENTO = 'salao:conectividade';
let apiDisponivel = true;

/**
 * As duas quedas que a tela precisa distinguir.
 *
 * Dizer "sem conexão" para quem está com o WhatsApp funcionando é a forma mais rápida de
 * ensinar a recepção a não acreditar no aviso. E as saídas são diferentes: internet caída
 * é problema de quem está ali (o roteador, o 4G); servidor fora do ar não tem nada a
 * fazer senão esperar — e tentar de novo.
 */
export type EstadoDaConexao = 'conectado' | 'sem-internet' | 'servidor-fora';

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

function estado(): EstadoDaConexao {
  // Sem rede, não se sabe nada do servidor: o diagnóstico mais perto da pessoa ganha.
  if (!navigator.onLine) return 'sem-internet';
  return apiDisponivel ? 'conectado' : 'servidor-fora';
}

function estadoNoServidor(): EstadoDaConexao {
  return 'conectado';
}

/** Fonte única para tela e bloqueio imediato das escritas offline. */
export function useConectado(): boolean {
  return useEstadoDaConexao() === 'conectado';
}

/** Para quem mostra o aviso: o motivo muda a frase e muda o que há para fazer. */
export function useEstadoDaConexao(): EstadoDaConexao {
  return useSyncExternalStore(assinar, estado, estadoNoServidor);
}

/** `navigator.onLine` não percebe Wi-Fi conectado com o servidor fora do ar. */
export function registrarEstadoDaApi(disponivel: boolean): void {
  if (apiDisponivel === disponivel) return;
  apiDisponivel = disponivel;
  window.dispatchEvent(new Event(EVENTO));
}

/*
 * Voltar a ter rede zera o que sabíamos do servidor.
 *
 * Toda requisição que falha marca a API como fora do ar — inclusive a que falhou porque a
 * internet caiu. Sem isto, o Wi-Fi voltar trocaria "sem internet" por "sistema fora do
 * ar": um diagnóstico que é só o eco da queda anterior, e que ficaria na tela até alguém
 * pedir alguma coisa ao servidor. A próxima requisição diz a verdade.
 *
 * Registrado no import, e não em `assinar`: precisa correr ANTES do aviso que o React
 * dispara para redesenhar, e os ouvintes correm na ordem em que foram registrados.
 */
if (typeof window !== 'undefined') {
  window.addEventListener('online', () => { apiDisponivel = true; });
}
