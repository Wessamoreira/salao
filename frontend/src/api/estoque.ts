import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './http';

/**
 * RT-EST-013 — o depósito.
 *
 * Todo número de dinheiro chega como número: as rotas de saldo, lote e alerta devolviam o
 * registro de domínio cru, e `Money` serializa como `{ valor: … }`. Passaram a ter DTO de tela no
 * servidor — duas formas do mesmo dinheiro na mesma API era o que dava trabalho de esconder.
 */

export type Unidade = 'UN' | 'ML' | 'L' | 'G' | 'KG';
export type Finalidade = 'VENDA' | 'CONSUMO_INTERNO' | 'AMBOS';

export interface Produto {
  id: string;
  nome: string;
  descricao: string | null;
  finalidade: Finalidade;
  unidade: Unidade;
  /** Nulo é produto sem preço — e sem preço ele não é vendável. */
  precoVenda: number | null;
  codigoBarras: string | null;
  ativo: boolean;
  /** Quem decide é o servidor: ativo, não é consumo interno, e tem preço. */
  vendavel: boolean;
  versao: number;
}

export interface Saldo {
  produtoId: string;
  nome: string;
  unidade: Unidade;
  quantidade: number;
  /** Nulo quando não há estoque — e nulo não é zero (RT-EST-003). */
  custoMedio: number | null;
  valorEmEstoque: number;
  semEstoque: boolean;
}

export interface Lote {
  id: string;
  produtoId: string;
  codigo: string | null;
  custoUnitario: number;
  quantidade: number;
  /** Nula quando o produto não tem validade impressa. No FEFO, sai depois do que vence. */
  validade: string | null;
  recebidoEm: string;
}

export interface Alertas {
  diasDeAviso: number;
  custoEmRisco: number;
  abaixoDoMinimo: Array<{
    produtoId: string; nome: string; unidade: Unidade;
    quantidade: number; minimo: number; faltando: number; zerado: boolean;
  }>;
  vencendo: Array<{
    loteId: string; produtoId: string; produto: string; unidade: Unidade;
    codigo: string | null; quantidade: number; validade: string; diasRestantes: number;
    custoEmRisco: number; vencido: boolean;
  }>;
}

export type StatusDaContagem = 'ABERTO' | 'APLICADO' | 'CANCELADO';

export interface ItemContado {
  loteId: string;
  produtoId: string;
  produto: string;
  loteCodigo: string | null;
  /** O saldo congelado no instante da contagem (RN-EST-018). */
  esperada: number;
  contada: number;
  /** `contada − esperada`, calculada pelo servidor: negativa faltou, positiva apareceu. */
  divergencia: number;
  bateu: boolean;
  observacao: string | null;
  contadoEm: string;
}

export interface Contagem {
  id: string;
  status: StatusDaContagem;
  observacao: string | null;
  criadoEm: string;
  encerradoEm: string | null;
  quantasDivergem: number;
  saldoDasDivergencias: number;
  itens: ItemContado[];
}

const CHAVE = ['estoque'] as const;

export function useProdutos(somenteAtivos = true) {
  return useQuery({
    queryKey: [...CHAVE, 'produtos', somenteAtivos],
    queryFn: () => api<Produto[]>(`/v1/produtos?somenteAtivos=${somenteAtivos}`),
  });
}

export function useSaldos() {
  return useQuery({
    queryKey: [...CHAVE, 'saldos'],
    queryFn: () => api<Saldo[]>('/v1/produtos/saldos'),
  });
}

export function useAlertas() {
  return useQuery({
    queryKey: [...CHAVE, 'alertas'],
    queryFn: () => api<Alertas>('/v1/produtos/alertas'),
  });
}

export function useLotes(produtoId: string | null) {
  return useQuery({
    queryKey: [...CHAVE, 'lotes', produtoId],
    queryFn: () => api<Lote[]>(`/v1/produtos/${produtoId}/lotes?somenteComSaldo=true`),
    enabled: produtoId !== null,
  });
}

function aoEscrever(fila: ReturnType<typeof useQueryClient>) {
  return () => { void fila.invalidateQueries({ queryKey: CHAVE }); };
}

interface DadosDoProduto {
  nome: string;
  descricao: string | null;
  finalidade: Finalidade;
  unidade: Unidade;
  precoVenda: number | null;
}

/**
 * Cadastrar e corrigir num gancho só — a diferença é o verbo e a versão.
 *
 * A versão vai na query, como o servidor a pede: se não bater, alguém salvou por cima.
 */
export function useSalvarProduto() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ id, versao, ...dados }: DadosDoProduto & { id?: string; versao?: number }) =>
      id
        ? api<Produto>(`/v1/produtos/${id}?versao=${versao}`, {
          method: 'PUT', body: JSON.stringify(dados),
        })
        : api<Produto>('/v1/produtos', { method: 'POST', body: JSON.stringify(dados) }),
    onSuccess: aoEscrever(fila),
  });
}

export function useDefinirAtivacaoDoProduto() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ativo }: { id: string; ativo: boolean }) =>
      api<Produto>(`/v1/produtos/${id}/ativacao`, {
        method: 'PATCH', body: JSON.stringify({ ativo }),
      }),
    onSuccess: aoEscrever(fila),
  });
}

/** Vazio remove o código; nulo no mínimo remove o mínimo — e nulo não é zero. */
export function useDefinirCodigoDeBarras() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ id, codigo }: { id: string; codigo: string }) =>
      api<void>(`/v1/produtos/${id}/codigo-barras`, {
        method: 'PUT', body: JSON.stringify({ codigo }),
      }),
    onSuccess: aoEscrever(fila),
  });
}

export function useDefinirEstoqueMinimo() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ id, minimo }: { id: string; minimo: number | null }) =>
      api<void>(`/v1/produtos/${id}/estoque-minimo`, {
        method: 'PUT', body: JSON.stringify({ minimo }),
      }),
    onSuccess: aoEscrever(fila),
  });
}

export interface DadosDaEntrada {
  quantidade: number;
  custoUnitario: number;
  codigo: string | null;
  validade: string | null;
  recebidoEm: string | null;
}

export function useRegistrarEntrada(produtoId: string) {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: (dados: DadosDaEntrada) =>
      api<Lote>(`/v1/produtos/${produtoId}/lotes`, {
        method: 'POST', body: JSON.stringify(dados),
      }),
    onSuccess: aoEscrever(fila),
  });
}

/** Perda é do produto: o FEFO escolhe de que caixas ela sai. */
export function useRegistrarPerda(produtoId: string) {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: (dados: { quantidade: number; motivo: string }) =>
      api<{ quantidade: number; custoTotal: number; lotesAtingidos: number }>(
        `/v1/produtos/${produtoId}/perdas`, { method: 'POST', body: JSON.stringify(dados) }),
    onSuccess: aoEscrever(fila),
  });
}

/** Ajuste e devolução são do lote: quem tem o frasco na mão sabe a etiqueta. */
export function useMovimentoNoLote() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ loteId, tipo, quantidade, motivo }: {
      loteId: string; tipo: 'ajustes' | 'devolucoes'; quantidade: number; motivo: string;
    }) =>
      api<void>(`/v1/lotes/${loteId}/${tipo}`, {
        method: 'POST', body: JSON.stringify({ quantidade, motivo }),
      }),
    onSuccess: aoEscrever(fila),
  });
}

// ------------------------------------------------------------------ a contagem

/**
 * RN-EST-040 — a contagem aberta, achada sem guardar o id.
 *
 * Não haver contagem aberta é o estado de quase todo dia, e é por isso que o servidor responde
 * `204` em vez de erro.
 *
 * O `?? null` não é enfeite: o `204` vira `undefined` no cliente HTTP, e o TanStack Query
 * **recusa** `undefined` como dado de consulta — a consulta inteira iria para o estado de erro
 * justamente no caso normal. `null` é "perguntei e não há", que é o que se quer dizer.
 */
export function useContagemAberta() {
  return useQuery({
    queryKey: [...CHAVE, 'contagem'],
    queryFn: async () => (await api<Contagem | undefined>('/v1/inventarios/aberto')) ?? null,
  });
}

export function useAbrirContagem() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: (observacao: string | null) =>
      api<Contagem>('/v1/inventarios', { method: 'POST', body: JSON.stringify({ observacao }) }),
    onSuccess: aoEscrever(fila),
  });
}

/** `PUT`: recontar substitui, não soma (RN-EST-019) — e o verbo promete exatamente isso. */
export function useContarLote(contagemId: string) {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ loteId, contada, observacao }: {
      loteId: string; contada: number; observacao: string | null;
    }) =>
      api<Contagem>(`/v1/inventarios/${contagemId}/lotes/${loteId}`, {
        method: 'PUT', body: JSON.stringify({ contada, observacao }),
      }),
    onSuccess: aoEscrever(fila),
  });
}

export function useAplicarContagem(contagemId: string) {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: () =>
      api<{ linhasAjustadas: number }>(`/v1/inventarios/${contagemId}/aplicar`,
        { method: 'POST' }),
    onSuccess: aoEscrever(fila),
  });
}

export function useCancelarContagem(contagemId: string) {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: (motivo: string) =>
      api<void>(`/v1/inventarios/${contagemId}/cancelar`, {
        method: 'POST', body: JSON.stringify({ motivo }),
      }),
    onSuccess: aoEscrever(fila),
  });
}

// ------------------------------------------------------------------ como se lê

const MOEDA = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });

/** Nulo vira travessão, nunca "R$ 0,00": sem estoque não há média a tirar. */
export function dinheiro(valor: number | null | undefined): string {
  return valor == null ? '—' : MOEDA.format(valor);
}

const UNIDADES: Record<Unidade, string> = {
  UN: 'un', ML: 'ml', L: 'L', G: 'g', KG: 'kg',
};

/**
 * `3 un` · `1,5 L` — sem casas decimais quando não há casas.
 *
 * Um estoque de unidades inteiras escrito "10,0000 un" é o banco vazando na tela: a escala
 * existe para gramatura, não para frasco.
 */
export function quantidade(valor: number, unidade: Unidade): string {
  const numero = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 3 }).format(valor);
  return `${numero} ${UNIDADES[unidade]}`;
}

/**
 * `1 lote` · `3 lotes`.
 *
 * O "(s)" é o jeito de não decidir, e quem lê é que paga: "1 lote(s) contados" é uma frase que
 * ninguém escreveria à mão.
 */
export function plural(n: number, um: string, varios: string): string {
  return `${n} ${n === 1 ? um : varios}`;
}

export const FINALIDADES: Record<Finalidade, string> = {
  VENDA: 'Venda',
  CONSUMO_INTERNO: 'Uso interno',
  AMBOS: 'Venda e uso interno',
};

export const UNIDADES_PARA_ESCOLHER: Array<{ valor: Unidade; rotulo: string }> = [
  { valor: 'UN', rotulo: 'unidade' },
  { valor: 'ML', rotulo: 'mililitro' },
  { valor: 'L', rotulo: 'litro' },
  { valor: 'G', rotulo: 'grama' },
  { valor: 'KG', rotulo: 'quilo' },
];
