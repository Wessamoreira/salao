import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './http';

/**
 * RT-EST-010 e RT-EST-011 — a régua do salão, o que ela acha dos preços, e o que foi decidido.
 *
 * O número vem sempre do servidor: a régua de 9,90, o chão de margem e os quatro motivos são regra
 * de negócio, e a tela só desenha. Nem o preço sugerido nem o mínimo são recalculados aqui.
 */

/** Por que o sistema acha que este preço está errado. */
export type Motivo =
  | 'MARGEM_ABAIXO_DO_MINIMO' | 'ENCALHADO' | 'MARGEM_ALTA' | 'PRECO_QUEBRADO';

export interface Sugestao {
  id: string;
  produtoId: string;
  produto: string;
  motivo: Motivo;
  /** O rótulo vem do servidor: a lista de motivos é dele. */
  rotulo: string;
  /** Nulo é produto sem preço de venda — um dos motivos de haver sugestão. */
  precoAtual: number | null;
  custoMedio: number;
  precoSugerido: number;
  /** Nula pela mesma razão que `precoAtual`. */
  margemAtual: number | null;
  margemSugerida: number;
  /** A conta por extenso, escrita pelo servidor. */
  racional: string;
  /** A redação da IA; nula enquanto não existir. */
  explicacao: string | null;
}

export interface Painel {
  /** Nula enquanto o salão não definiu a régua — e sem ela não há sugestão. */
  margemMinima: number | null;
  geradaEm: string | null;
  itens: Sugestao[];
}

export interface Decisao {
  id: string;
  produtoId: string;
  produto: string;
  decisao: 'APLICADA' | 'RECUSADA';
  motivo: Motivo;
  precoAnterior: number | null;
  custoMedio: number;
  precoSugerido: number;
  /** Pode diferir do sugerido: o dono ajusta antes de aplicar. Nulo quando recusada. */
  precoAplicado: number | null;
  racional: string;
  /** O motivo escrito da recusa. Nulo quando aplicada. */
  justificativa: string | null;
  quemDecidiu: string | null;
  decididaEm: string;
}

interface PaginaDeDecisoes {
  itens: Decisao[];
  proximoCursor: string | null;
  temMais: boolean;
}

const SUGESTOES = '/v1/sugestoes-de-preco';
const chave = ['precos'] as const;

export function usePainelDePrecos() {
  return useQuery({
    queryKey: [...chave, 'painel'],
    queryFn: () => api<Painel>(SUGESTOES),
  });
}

export function useSalvarMargemMinima() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: (margemMinima: number) =>
      api<{ margemMinima: number }>('/v1/politica-de-preco', {
        method: 'PUT', body: JSON.stringify({ margemMinima }),
      }),
    // A régua nova muda toda sugestão seguinte.
    onSuccess: () => fila.invalidateQueries({ queryKey: chave }),
  });
}

export function useGerarSugestoes() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: () => api<Painel>(SUGESTOES, { method: 'POST' }),
    onSuccess: (painel) => fila.setQueryData([...chave, 'painel'], painel),
  });
}

export function useAprovar() {
  const fila = useQueryClient();
  return useMutation({
    /** `preco` ausente aplica o sugerido. */
    mutationFn: ({ id, preco }: { id: string; preco?: number }) =>
      api<Painel>(`${SUGESTOES}/${id}/aprovacao`, {
        method: 'POST', body: JSON.stringify(preco == null ? {} : { preco }),
      }),
    onSuccess: () => fila.invalidateQueries({ queryKey: chave }),
  });
}

export function useRecusar() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ id, motivo }: { id: string; motivo: string }) =>
      api<Painel>(`${SUGESTOES}/${id}/recusa`, {
        method: 'POST', body: JSON.stringify({ motivo }),
      }),
    onSuccess: () => fila.invalidateQueries({ queryKey: chave }),
  });
}

export function useDecisoesDePreco() {
  return useInfiniteQuery({
    queryKey: [...chave, 'decisoes'],
    queryFn: ({ pageParam }) => api<PaginaDeDecisoes>('/v1/decisoes-de-preco'
      + (pageParam ? `?cursor=${encodeURIComponent(pageParam)}` : '')),
    initialPageParam: null as string | null,
    getNextPageParam: (ultima) => (ultima.temMais ? ultima.proximoCursor : null),
  });
}
