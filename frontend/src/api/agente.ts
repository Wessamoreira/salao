import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { api } from './http';

/**
 * RT-CNV-007 — a trilha do agente, como o painel de revisão a lê.
 *
 * Só leitura. Os nomes dos desfechos e dos recortes são do servidor; a tela os traduz para
 * frases, mas não decide nada com eles — "gravou" e "bloqueada" já chegam calculados.
 */

export type Recorte = 'TODAS' | 'GRAVARAM' | 'BLOQUEADAS';

/** O que o sistema fez com cada pedido de ferramenta do modelo (RN-CNV-010). */
export type DesfechoDaChamada =
  | 'EXECUTADA' | 'PROPOSTA' | 'INEXISTENTE' | 'FORA_DA_LISTA' | 'SEM_PERMISSAO'
  | 'ERRO_DE_DOMINIO' | 'FALHA';

export interface Chamada {
  nome: string;
  desfecho: DesfechoDaChamada;
  /** Nulo depois dos 90 dias: é conteúdo, e sai com a anonimização. */
  argumentos: Record<string, unknown> | null;
}

export type StatusDaIntencao =
  | 'PENDENTE' | 'AGUARDANDO_ACEITE' | 'CONFIRMADA' | 'RECUSADA' | 'SUBSTITUIDA' | 'EXPIRADA';

export interface IntencaoDaAcao {
  ferramenta: string;
  resumo: string | null;
  status: StatusDaIntencao;
  motivo: string | null;
  /** O que ESTA conversa fez com a intenção: propôs, decidiu com o código, ou foi o aceite. */
  papel: 'PROPOSTA' | 'DECISAO' | 'ACEITE';
}

export interface Acao {
  id: string;
  ocorridaEm: string;
  tipo: 'CONVERSA' | 'BOTAO';
  desfecho: 'CONCLUIDA' | 'RECUSADA' | 'FALHOU';
  motivoRecusa: string | null;
  usuarioEfetivoId: string | null;
  quemPediu: string | null;
  /** Só quando não há usuário: o número desconhecido, ou a profissional sem conta. */
  telefoneFinal: string | null;
  veioDeAudio: boolean;
  pedido: string | null;
  resposta: string | null;
  ferramentas: Chamada[];
  intencoes: IntencaoDaAcao[];
  gravou: boolean;
  bloqueada: boolean;
  modelo: string | null;
  tokensEntrada: number;
  tokensSaida: number;
  custoUsd: number;
  anonimizadaEm: string | null;
}

export interface PaginaDeAcoes {
  itens: Acao[];
  proximoCursor: string | null;
  temMais: boolean;
}

export interface Resumo {
  de: string;
  ate: string;
  conversas: number;
  gravaram: number;
  bloqueadas: number;
  custoUsd: number;
  tokensEntrada: number;
  tokensSaida: number;
}

export interface Pessoa {
  usuarioId: string;
  nome: string;
  conversas: number;
}

/** Datas como dias civis do salão (`AAAA-MM-DD`), as duas inclusivas. */
export interface FiltroDaRevisao {
  recorte: Recorte;
  pessoa: string | null;
  de: string;
  ate: string;
}

function parametros(valores: Record<string, string | null | undefined>): string {
  const busca = new URLSearchParams();
  for (const [chave, valor] of Object.entries(valores)) {
    if (valor) busca.set(chave, valor);
  }
  return busca.toString();
}

/**
 * A lista, página a página pelo cursor.
 *
 * Nenhum parâmetro aqui é dado pessoal: pessoa é id, período é data, e o cursor é instante e id
 * (docs/05 — nunca dado sensível em URL).
 */
export function useAcoesDoAgente(filtro: FiltroDaRevisao) {
  return useInfiniteQuery({
    queryKey: ['agente', 'acoes', filtro],
    queryFn: ({ pageParam }) => api<PaginaDeAcoes>(`/v1/agente/acoes?${parametros({
      recorte: filtro.recorte, pessoa: filtro.pessoa, de: filtro.de, ate: filtro.ate,
      cursor: pageParam,
    })}`),
    initialPageParam: null as string | null,
    getNextPageParam: (ultima) => (ultima.temMais ? ultima.proximoCursor : null),
  });
}

/** Os totais do período e da pessoa. Sem recorte: é o que os recortes decompõem. */
export function useResumoDoAgente(filtro: FiltroDaRevisao) {
  return useQuery({
    queryKey: ['agente', 'resumo', filtro.pessoa, filtro.de, filtro.ate],
    queryFn: () => api<Resumo>(`/v1/agente/acoes/resumo?${parametros({
      pessoa: filtro.pessoa, de: filtro.de, ate: filtro.ate,
    })}`),
  });
}

/** Quem conversou com o agente no período, para o filtro de pessoa. */
export function usePessoasDoAgente(de: string, ate: string) {
  return useQuery({
    queryKey: ['agente', 'pessoas', de, ate],
    queryFn: () => api<Pessoa[]>(`/v1/agente/acoes/pessoas?${parametros({ de, ate })}`),
  });
}
