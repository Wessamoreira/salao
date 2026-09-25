import { lazy } from 'react';

/**
 * Cada tela num pedaço próprio do pacote.
 *
 * Até 22/09/2026 era um arquivo só, de 411 KB: abrir a agenda no celular da recepção
 * baixava conciliação, preços, o agente e a revisão de IA junto — código que aquela pessoa
 * talvez não abra no dia inteiro.
 *
 * <h2>O mesmo carregador serve para a rota e para o pré-carregamento</h2>
 *
 * Dividir sem pré-carregar troca um problema por outro: a primeira tela fica leve e **cada
 * troca de menu passa a ter uma espera**. Por isso o mesmo `import()` está no `lazy` e no
 * mapa por rota: a casca chama o segundo quando o ponteiro encosta no item do menu, e o
 * pedaço quase sempre já chegou quando o clique acontece.
 *
 * O navegador cacheia o módulo; chamar duas vezes não baixa duas vezes.
 */
const carregar = {
  agenda: () => import('./telas/agenda/Agenda'),
  fila: () => import('./telas/fila/Fila'),
  comandas: () => import('./telas/comandas/Comandas'),
  clientes: () => import('./telas/clientes/Clientes'),
  catalogo: () => import('./telas/catalogo/Catalogo'),
  agente: () => import('./telas/agente/RevisaoDoAgente'),
  conciliacao: () => import('./telas/conciliacao/Conciliacao'),
  precos: () => import('./telas/precos/Precos'),
  config: () => import('./telas/config/Configuracao'),
  painel: () => import('./telas/agenda/Painel'),
};

export const Agenda = lazy(() => carregar.agenda().then((m) => ({ default: m.Agenda })));
export const Fila = lazy(() => carregar.fila().then((m) => ({ default: m.Fila })));
export const Comandas = lazy(() => carregar.comandas().then((m) => ({ default: m.Comandas })));
export const Clientes = lazy(() => carregar.clientes().then((m) => ({ default: m.Clientes })));
export const Catalogo = lazy(() => carregar.catalogo().then((m) => ({ default: m.Catalogo })));
export const Conciliacao = lazy(() =>
  carregar.conciliacao().then((m) => ({ default: m.Conciliacao })));
export const Precos = lazy(() => carregar.precos().then((m) => ({ default: m.Precos })));
export const Painel = lazy(() => carregar.painel().then((m) => ({ default: m.Painel })));
export const Configuracao = lazy(() =>
  carregar.config().then((m) => ({ default: m.Configuracao })));
export const RevisaoDoAgente = lazy(() =>
  carregar.agente().then((m) => ({ default: m.RevisaoDoAgente })));

/** A rota do menu → o pedaço que ela precisa. O que não está aqui não tem o que precarregar. */
const POR_ROTA: Record<string, () => Promise<unknown>> = {
  '/agenda': carregar.agenda,
  '/fila': carregar.fila,
  '/comandas': carregar.comandas,
  '/clientes': carregar.clientes,
  '/catalogo': carregar.catalogo,
  '/agente': carregar.agente,
  '/conciliacao': carregar.conciliacao,
  '/precos': carregar.precos,
  '/config': carregar.config,
};

/**
 * Puxa o pedaço de uma rota antes do clique.
 *
 * Falha em silêncio de propósito: se a rede cair no meio do pré-carregamento, quem vai
 * mostrar o erro é a navegação de verdade, com a tela de falha que já existe — e não um
 * aviso sobre algo que a pessoa nem pediu.
 */
export function precarregar(rota: string): void {
  void POR_ROTA[rota]?.().catch(() => {});
}
