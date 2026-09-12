import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './http';

/**
 * Dinheiro como o servidor o serializa: um objeto com o valor em `numeric(19,4)`.
 *
 * O formato é do `Money` do domínio (ADR-0009), e a tela não o desembrulha para fazer
 * conta — só para mostrar. Toda soma acontece no servidor, e é isso que impede a tela
 * e o banco de divergirem num centavo justamente na hora de fechar (RN-ATD-008).
 */
export interface Dinheiro {
  valor: number;
}

const MOEDA = new Intl.NumberFormat('pt-BR', {
  style: 'currency',
  currency: 'BRL',
  minimumFractionDigits: 2,
});

/** Formata para exibir. `null` vira travessão, não "R$ 0,00" — ausência não é zero. */
export function reais(dinheiro: Dinheiro | null | undefined): string {
  if (dinheiro === null || dinheiro === undefined) return '—';
  return MOEDA.format(dinheiro.valor);
}

export type StatusDaComanda = 'ABERTA' | 'FECHADA' | 'ESTORNADA';
export type OrigemDaComanda = 'AGENDAMENTO' | 'AVULSA';
export type TipoDeItem = 'SERVICO' | 'PRODUTO';
export type PapelDoExecutante = 'PRINCIPAL' | 'ASSISTENTE';

export type FormaDePagamento =
  | 'DINHEIRO' | 'PIX' | 'TRANSFERENCIA' | 'CREDITO' | 'DEBITO' | 'CORTESIA' | 'FIADO';

/** O rótulo de cada forma, em português de gente (R-UX-20). */
export const FORMAS: { forma: FormaDePagamento; rotulo: string }[] = [
  { forma: 'DINHEIRO', rotulo: 'Dinheiro' },
  { forma: 'PIX', rotulo: 'Pix' },
  { forma: 'DEBITO', rotulo: 'Débito' },
  { forma: 'CREDITO', rotulo: 'Crédito' },
  { forma: 'TRANSFERENCIA', rotulo: 'Transferência' },
  { forma: 'FIADO', rotulo: 'Fiado' },
  { forma: 'CORTESIA', rotulo: 'Cortesia' },
];

export interface ComandaResumo {
  id: string;
  clienteId: string;
  numero: number;
  status: StatusDaComanda;
  origem: OrigemDaComanda;
  abertaEm: string;
  abertaPor: string | null;
  fechadaEm: string | null;
  valorBruto: Dinheiro;
  valorDesconto: Dinheiro;
  valorLiquido: Dinheiro;
  versao: number;
}

export interface ExecutanteDoItem {
  id: string;
  profissionalId: string;
  papel: PapelDoExecutante;
  percentualComissao: number;
  origem: string;
  baseCalculo: Dinheiro;
  valorComissao: Dinheiro;
}

export interface ItemDaComanda {
  id: string;
  tipo: TipoDeItem;
  servicoId: string | null;
  produtoId: string | null;
  agendamentoId: string | null;
  descricao: string;
  quantidade: number;
  precoUnitario: Dinheiro;
  desconto: Dinheiro;
  valorTotal: Dinheiro;
  executantes: ExecutanteDoItem[];
}

export interface ComandaDetalhada {
  comanda: ComandaResumo;
  itens: ItemDaComanda[];
}

export interface Pagamento {
  id: string;
  comandaId: string;
  forma: FormaDePagamento;
  valor: Dinheiro;
  bandeira: string | null;
  parcelas: number;
  nsu: string | null;
  autorizacao: string | null;
  taxaPrevista: Dinheiro | null;
  previsaoRecebimento: string | null;
  statusConciliacao: string;
  criadoEm: string;
}

/**
 * A conta como a tela de fechamento a lê.
 *
 * `restante` vem pronto do servidor (R-UX-08). Subtrair aqui seria a mesma conta em
 * dois lugares, e a divergência apareceria como um centavo que impede o fechamento.
 */
export interface ContaDaComanda {
  comanda: ComandaResumo;
  pagamentos: Pagamento[];
  totalPago: Dinheiro;
  restante: Dinheiro;
}

export interface ComandaEmAberto {
  comanda: ComandaResumo;
  clienteNome: string | null;
}

/**
 * A barra de comandas abertas (R-UX-07).
 *
 * `refetchInterval` porque outra pessoa no balcão abre e fecha comanda o tempo todo, e
 * uma barra que só atualiza quando esta aba age mostraria um salão que já mudou.
 */
export function useComandasAbertas() {
  return useQuery({
    queryKey: ['comandas', 'abertas'],
    queryFn: () => api<ComandaEmAberto[]>('/v1/comandas/abertas'),
    refetchInterval: 30 * 1000,
  });
}

export function useComanda(id: string | null) {
  return useQuery({
    queryKey: ['comandas', id],
    queryFn: () => api<ComandaDetalhada>(`/v1/comandas/${id}`),
    enabled: id !== null,
  });
}

export function useConta(id: string | null) {
  return useQuery({
    queryKey: ['comandas', id, 'conta'],
    queryFn: () => api<ContaDaComanda>(`/v1/comandas/${id}/conta`),
    enabled: id !== null,
  });
}

/**
 * Invalida comanda, conta e barra de uma vez.
 *
 * Toda ação desta tela mexe nas três: lançar item muda o total (comanda), muda o que
 * falta (conta) e muda o valor parcial na barra. Invalidar só uma deixaria a tela
 * mostrando dois números que não conversam.
 */
function useRecarregar(comandaId: string | null) {
  const fila = useQueryClient();
  return () => {
    fila.invalidateQueries({ queryKey: ['comandas', comandaId] });
    fila.invalidateQueries({ queryKey: ['comandas', 'abertas'] });
  };
}

export function useLancarServico(comandaId: string | null) {
  const recarregar = useRecarregar(comandaId);
  return useMutation({
    mutationFn: (dados: { servicoId: string; profissionalId: string; quantidade?: number }) =>
      api<ComandaDetalhada>(`/v1/comandas/${comandaId}/itens`, {
        method: 'POST',
        body: JSON.stringify(dados),
      }),
    onSuccess: recarregar,
  });
}

export function useLancarProduto(comandaId: string | null) {
  const recarregar = useRecarregar(comandaId);
  return useMutation({
    mutationFn: (dados: { produtoId: string; quantidade?: number }) =>
      api<ComandaDetalhada>(`/v1/comandas/${comandaId}/produtos`, {
        method: 'POST',
        body: JSON.stringify(dados),
      }),
    onSuccess: recarregar,
  });
}

export function useRemoverItem(comandaId: string | null) {
  const recarregar = useRecarregar(comandaId);
  return useMutation({
    mutationFn: (itemId: string) =>
      api<ComandaDetalhada>(`/v1/comandas/${comandaId}/itens/${itemId}`, { method: 'DELETE' }),
    onSuccess: recarregar,
  });
}

export function useAplicarDesconto(comandaId: string | null) {
  const recarregar = useRecarregar(comandaId);
  return useMutation({
    mutationFn: ({ itemId, percentual, valor }:
      { itemId: string; percentual?: number; valor?: number }) =>
      api<ComandaDetalhada>(`/v1/comandas/${comandaId}/itens/${itemId}/desconto`, {
        method: 'PUT',
        body: JSON.stringify(percentual !== undefined ? { percentual } : { valor }),
      }),
    onSuccess: recarregar,
  });
}

export function useRegistrarPagamento(comandaId: string | null) {
  const recarregar = useRecarregar(comandaId);
  return useMutation({
    mutationFn: (dados: { forma: FormaDePagamento; valor: number; parcelas?: number }) =>
      api<ContaDaComanda>(`/v1/comandas/${comandaId}/pagamentos`, {
        method: 'POST',
        body: JSON.stringify(dados),
      }),
    onSuccess: recarregar,
  });
}

export function useRemoverPagamento(comandaId: string | null) {
  const recarregar = useRecarregar(comandaId);
  return useMutation({
    mutationFn: (pagamentoId: string) =>
      api<ContaDaComanda>(`/v1/comandas/${comandaId}/pagamentos/${pagamentoId}`,
        { method: 'DELETE' }),
    onSuccess: recarregar,
  });
}

export function useFechar(comandaId: string | null) {
  const recarregar = useRecarregar(comandaId);
  return useMutation({
    mutationFn: () =>
      api<ComandaDetalhada>(`/v1/comandas/${comandaId}/fechamento`, { method: 'POST' }),
    onSuccess: recarregar,
  });
}

export function useAbrirComanda() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: (clienteId: string) =>
      api<{ comanda: ComandaResumo; jaEstavaAberta: boolean }>('/v1/comandas', {
        method: 'POST',
        body: JSON.stringify({ clienteId }),
      }),
    onSuccess: () => fila.invalidateQueries({ queryKey: ['comandas', 'abertas'] }),
  });
}

/** O cardápio e a equipe, para os campos de lançamento. */
export interface ServicoResumo { id: string; nome: string; ativo: boolean }
export interface ProfissionalResumo { id: string; nome: string; apelido: string | null }
export interface ProdutoVendavel {
  id: string; nome: string; precoVenda: number | null;
  /** RT-EST-009. Nulo na maioria: quem etiqueta o próprio produto não tem código impresso. */
  codigoBarras: string | null;
  vendavel: boolean;
}

/**
 * Acha o produto do código bipado na lista JÁ carregada.
 *
 * Sem ir ao servidor de propósito, embora a rota exista (`GET /v1/produtos/por-codigo-barras`).
 * A lista do balcão já veio inteira para desenhar o select, o bipe tem de responder no ritmo do
 * gatilho, e no salão a internet cai. A rota serve às telas onde o produto pode estar FORA da
 * lista — inventário, entrada de nota —, não a esta.
 *
 * A comparação é literal: RN-EST-026 guarda o código exatamente como o leitor o emite, e
 * "normalizar" aqui (tirar zero à esquerda, calcular dígito) desencontraria o que o banco casou.
 */
export function produtoDoCodigo(
  produtos: ProdutoVendavel[], codigo: string,
): ProdutoVendavel | undefined {
  return produtos.find((p) => p.codigoBarras === codigo);
}

export function useServicos() {
  return useQuery({
    queryKey: ['servicos'],
    queryFn: () => api<ServicoResumo[]>('/v1/servicos'),
    staleTime: 5 * 60 * 1000,
  });
}

export function useProfissionais() {
  return useQuery({
    queryKey: ['profissionais'],
    queryFn: () => api<ProfissionalResumo[]>('/v1/profissionais'),
    staleTime: 5 * 60 * 1000,
  });
}

export function useProdutosVendaveis() {
  return useQuery({
    queryKey: ['produtos', 'vendaveis'],
    queryFn: () => api<ProdutoVendavel[]>('/v1/produtos?somenteVendaveis=true'),
    staleTime: 60 * 1000,
  });
}

/**
 * Há quanto tempo a comanda está aberta, em horas.
 *
 * R-UX-07 pede alerta visual passadas 3 horas. A conta é da tela porque o dado que a
 * responde — `abertaEm` — já veio, e pedir ao servidor "quantas horas faz" seria uma
 * chamada por linha para desenhar uma barra.
 */
export function horasAberta(abertaEm: string, agora: Date = new Date()): number {
  return (agora.getTime() - new Date(abertaEm).getTime()) / 3_600_000;
}
