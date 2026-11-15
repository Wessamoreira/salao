import { useSyncExternalStore } from 'react';

/**
 * RT-INF-013 — o app instalado: versão nova que espera, e instalação que se oferece.
 *
 * <h2>Por que a versão nova não assume sozinha (R-UX-23)</h2>
 *
 * Até aqui o service worker chamava `skipWaiting()` na instalação. A versão nova assumia no
 * meio do uso e, ao ativar, apagava o cache da anterior — e a aba aberta continuava rodando
 * o bundle velho. A próxima tela ainda não carregada pedia um arquivo com hash que não
 * existia mais, e a barreira mostrava erro numa tela que estava certa.
 *
 * Agora ela baixa em segundo plano e espera. A barra mostra "Versão nova · Atualizar", e só
 * o clique a ativa — com a página recarregando inteira, nunca pela metade.
 *
 * <h2>Por que procurar versão nova sem ninguém pedir</h2>
 *
 * O navegador só procura em navegação, e o computador do balcão abre a agenda às 8h e não
 * navega de documento o dia inteiro (é uma SPA). Uma correção publicada às 9h chegaria no dia
 * seguinte. Procura-se ao voltar para a janela e a cada hora.
 */

export interface EstadoDoApp {
  /** Há uma versão já baixada, esperando o clique para assumir. */
  versaoNova: boolean;
  /** O navegador deixou o sistema oferecer a instalação. */
  podeInstalar: boolean;
}

/** Não está em `lib.dom` porque só o Chromium implementa. */
interface PedidoDeInstalacao extends Event {
  prompt(): Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

const UMA_HORA = 60 * 60_000;
const NADA: EstadoDoApp = { versaoNova: false, podeInstalar: false };

let estado: EstadoDoApp = NADA;
const ouvintes = new Set<() => void>();
let registro: ServiceWorkerRegistration | null = null;
let pedido: PedidoDeInstalacao | null = null;

function mudar(parcial: Partial<EstadoDoApp>): void {
  estado = { ...estado, ...parcial };
  ouvintes.forEach((avisar) => avisar());
}

function assinar(avisar: () => void): () => void {
  ouvintes.add(avisar);
  return () => ouvintes.delete(avisar);
}

export function useEstadoDoApp(): EstadoDoApp {
  return useSyncExternalStore(assinar, () => estado, () => NADA);
}

export function registrarPwa(): void {
  if (typeof window === 'undefined') return;

  // O pedido de instalação chega uma vez, cedo, e não volta: é guardado para o menu da conta.
  // `preventDefault` tira a faixa automática do Chrome no celular — quem oferece é o sistema,
  // num lugar só, e não uma faixa por cima da agenda na primeira visita.
  window.addEventListener('beforeinstallprompt', (evento) => {
    evento.preventDefault();
    pedido = evento as PedidoDeInstalacao;
    mudar({ podeInstalar: true });
  });
  window.addEventListener('appinstalled', () => {
    pedido = null;
    mudar({ podeInstalar: false });
  });

  // No Vite dev o cache atrapalharia: o service worker só existe no build.
  if (!import.meta.env.PROD || !('serviceWorker' in navigator)) return;
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js')
      .then((reg) => acompanhar(reg))
      .catch(() => {
        // A aplicação continua sendo web normal quando o navegador recusa o service worker.
      });
  }, { once: true });
}

/**
 * Liga o estado da tela ao ciclo de vida do service worker.
 *
 * Exportada com as dependências como parâmetro para o teste montar o ciclo inteiro sem
 * navegador: o jsdom não tem service worker, e `location.reload` não pode ser espionado nele.
 */
export function acompanhar(
  reg: ServiceWorkerRegistration,
  sw: ServiceWorkerContainer = navigator.serviceWorker,
  recarregar: () => void = () => window.location.reload(),
): void {
  registro = reg;

  /*
   * Sem controlador, é a PRIMEIRA instalação: não há versão velha rodando, nada a esperar e
   * nada a recarregar. O `clients.claim()` dela dispara `controllerchange`, e recarregar ali
   * seria piscar a tela de quem acabou de chegar.
   */
  const haviaVersao = Boolean(sw.controller);

  if (reg.waiting && haviaVersao) mudar({ versaoNova: true });

  reg.addEventListener('updatefound', () => {
    const chegando = reg.installing;
    chegando?.addEventListener('statechange', () => {
      if (chegando.state === 'installed' && sw.controller) mudar({ versaoNova: true });
    });
  });

  /*
   * Recarrega UMA vez, e em toda aba: a aba que não clicou também passou a ser controlada
   * pela versão nova, com o cache novo — e o bundle velho dela pediria arquivos que sumiram.
   * Duas abas, uma de cada versão, é o estado que esta função existe para impedir.
   */
  let recarregou = false;
  sw.addEventListener('controllerchange', () => {
    if (!haviaVersao || recarregou) return;
    recarregou = true;
    recarregar();
  });

  const procurar = () => { reg.update().catch(() => { /* sem rede, tenta na próxima */ }); };
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') procurar();
  });
  setInterval(procurar, UMA_HORA);
}

/** O clique em "Atualizar". A recarga vem do `controllerchange`, em todas as abas. */
export function aplicarAtualizacao(): void {
  const esperando = registro?.waiting;
  if (esperando) {
    esperando.postMessage({ tipo: 'atualizar' });
    return;
  }
  // Outra aba já ativou a versão nova entre o aviso e o clique: só falta recarregar.
  window.location.reload();
}

export async function instalar(): Promise<void> {
  if (!pedido) return;
  const atual = pedido;
  pedido = null;
  mudar({ podeInstalar: false });
  await atual.prompt();
  // O navegador não deixa pedir de novo depois de usado — aceito ou recusado, o item sai.
  await atual.userChoice.catch(() => undefined);
}
