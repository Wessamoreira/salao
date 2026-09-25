import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './http';

export type TipoDeEtapa = 'ATIVO' | 'PAUSA';

/**
 * Uma etapa da receita.
 *
 * `ATIVO` é trabalho: a profissional está com a cliente. `PAUSA` é espera — a química agindo —,
 * e é o que faz esta agenda diferente de um calendário: a profissional está livre ali.
 */
export interface Etapa {
  tipo: TipoDeEtapa;
  duracaoMinutos: number;
  /** Ids de recurso (lavatório, cadeira). Por etapa, nunca pelo serviço inteiro (RN-CAT-010). */
  recursosExigidos: string[];
}

export interface Servico {
  id: string;
  nome: string;
  descricao: string | null;
  ativo: boolean;
  versao: number;
  /** Tempo de cadeira. */
  duracaoTotal: number;
  /** Tempo da profissional — o número que decide se cabe um encaixe. Vem do servidor: separar o
   *  que ocupa a profissional do que não ocupa é regra de domínio (RN-CAT-001). */
  minutosDoProfissional: number;
  /**
   * Quanto custa HOJE, no fuso do salão. Nulo é serviço que ninguém precificou — e é a linha
   * mais importante da lista, porque é a que a comanda vai recusar vender (RN-CAT-007). Zero
   * seria um número errado dito com confiança.
   */
  precoVigente: number | null;
  etapas: Etapa[];
}

export interface Preco {
  id: string;
  valor: number;
  vigenteDe: string;
}

export interface Recurso {
  id: string;
  nome: string;
  quantidade: number;
  ativo: boolean;
  versao: number;
}

const CHAVE_SERVICOS = ['catalogo', 'servicos'] as const;
const CHAVE_RECURSOS = ['catalogo', 'recursos'] as const;

export function useServicos(incluirInativos = true) {
  return useQuery({
    queryKey: [...CHAVE_SERVICOS, incluirInativos],
    queryFn: () => api<Servico[]>(`/v1/servicos?incluirInativos=${incluirInativos}`),
  });
}

export function useRecursos(incluirInativos = true) {
  return useQuery({
    queryKey: [...CHAVE_RECURSOS, incluirInativos],
    queryFn: () => api<Recurso[]>(`/v1/recursos?incluirInativos=${incluirInativos}`),
  });
}

export function usePrecos(servicoId: string | null) {
  return useQuery({
    queryKey: ['catalogo', 'precos', servicoId],
    queryFn: () => api<Preco[]>(`/v1/servicos/${servicoId}/precos`),
    enabled: servicoId !== null,
  });
}

interface DadosDoServico {
  nome: string;
  descricao: string | null;
  etapas: Etapa[];
}

/**
 * Cadastrar e editar num gancho só.
 *
 * As duas rotas mandam a receita inteira e devolvem o serviço inteiro; a única diferença é o
 * verbo e a versão. Dois ganchos quase iguais divergiriam no dia em que um deles ganhasse um
 * campo.
 */
export function useSalvarServico() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ id, versao, ...dados }: DadosDoServico & { id?: string; versao?: number }) =>
      id
        ? api<Servico>(`/v1/servicos/${id}`, {
          method: 'PUT', body: JSON.stringify({ ...dados, versao }),
        })
        : api<Servico>('/v1/servicos', { method: 'POST', body: JSON.stringify(dados) }),
    onSuccess: () => { void fila.invalidateQueries({ queryKey: CHAVE_SERVICOS }); },
  });
}

export function useAtivacaoDoServico() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ativo }: { id: string; ativo: boolean }) =>
      api<Servico>(`/v1/servicos/${id}/ativacao`, {
        method: 'PATCH', body: JSON.stringify({ ativo }),
      }),
    onSuccess: () => { void fila.invalidateQueries({ queryKey: CHAVE_SERVICOS }); },
  });
}

export function useDefinirPreco(servicoId: string) {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ valor, vigenteDe }: { valor: number; vigenteDe: string | null }) =>
      api<Preco>(`/v1/servicos/${servicoId}/precos`, {
        method: 'POST', body: JSON.stringify({ valor, vigenteDe }),
      }),
    onSuccess: () => {
      void fila.invalidateQueries({ queryKey: ['catalogo', 'precos', servicoId] });
    },
  });
}

export function useRemoverPrecoFuturo(servicoId: string) {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: (precoId: string) =>
      api<void>(`/v1/servicos/${servicoId}/precos/${precoId}`, { method: 'DELETE' }),
    onSuccess: () => {
      void fila.invalidateQueries({ queryKey: ['catalogo', 'precos', servicoId] });
    },
  });
}

export function useSalvarRecurso() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ id, versao, nome, quantidade }: {
      id?: string; versao?: number; nome: string; quantidade: number;
    }) =>
      id
        ? api<Recurso>(`/v1/recursos/${id}`, {
          method: 'PUT', body: JSON.stringify({ nome, quantidade, versao }),
        })
        : api<Recurso>('/v1/recursos', {
          method: 'POST', body: JSON.stringify({ nome, quantidade }),
        }),
    onSuccess: () => { void fila.invalidateQueries({ queryKey: CHAVE_RECURSOS }); },
  });
}

export function useAtivacaoDoRecurso() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ativo }: { id: string; ativo: boolean }) =>
      api<Recurso>(`/v1/recursos/${id}/ativacao`, {
        method: 'PATCH', body: JSON.stringify({ ativo }),
      }),
    onSuccess: () => { void fila.invalidateQueries({ queryKey: CHAVE_RECURSOS }); },
  });
}

/** `1h40`, `45min` — a leitura de balcão, não `100 minutos`. */
export function emHoras(minutos: number): string {
  if (minutos < 60) return `${minutos}min`;
  const horas = Math.floor(minutos / 60);
  const resto = minutos % 60;
  return resto === 0 ? `${horas}h` : `${horas}h${String(resto).padStart(2, '0')}`;
}
