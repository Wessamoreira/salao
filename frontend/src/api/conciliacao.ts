import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './http';

/**
 * RT-FIN-009 — importar o relatório de vendas da Stone.
 *
 * Prévia e importação são POST com o arquivo: a prévia não grava nada, mas precisa do arquivo, e ele
 * não fica no servidor entre as duas. Quais campos existem e quais são obrigatórios vêm na prévia — é
 * regra do servidor, e a tela só desenha.
 */

export type Campo =
  | 'ID_NA_ADQUIRENTE' | 'DATA' | 'VALOR_BRUTO' | 'VALOR_LIQUIDO' | 'PRODUTO' | 'BANDEIRA'
  | 'PARCELAS' | 'NSU' | 'AUTORIZACAO' | 'SITUACAO';

export type Formato = 'BRASILEIRO' | 'INTERNACIONAL';

export interface CampoMapeavel {
  campo: Campo;
  rotulo: string;
  obrigatorio: boolean;
}

export interface LinhaDaPrevia {
  linha: number;
  estado: 'LIDA' | 'IGNORADA' | 'RECUSADA';
  motivo: string | null;
  idNaAdquirente: string | null;
  /** Hora local do salão, como no arquivo: `2026-09-18T14:32:00`, sem fuso. */
  capturadaEm: string | null;
  horaInformada: boolean | null;
  produto: 'CREDITO' | 'DEBITO' | 'OUTRO' | null;
  bandeira: string | null;
  parcelas: number | null;
  valorBruto: number | null;
  valorLiquido: number | null;
  nsu: string | null;
  autorizacao: string | null;
  situacao: 'APROVADA' | 'CANCELADA' | null;
}

export interface Previa {
  campos: CampoMapeavel[];
  colunas: string[];
  mapeamento: Partial<Record<Campo, string>>;
  formato: Formato;
  faltando: Campo[];
  amostra: LinhaDaPrevia[];
  linhas: number;
  lidas: number;
  ignoradas: number;
  recusadas: number;
  jaImportadoEm: string | null;
}

export interface Mapeamento {
  colunas: Partial<Record<Campo, string>>;
  formato: Formato;
}

export interface Resultado {
  id: string;
  importadaEm: string;
  linhas: number;
  novas: number;
  canceladas: number;
  repetidas: number;
  ignoradas: number;
  recusadas: number;
  /** As primeiras 500; o total está em `recusadas`. */
  recusas: { linha: number; motivo: string }[];
  /** RT-FIN-010: vendas que casaram com pagamentos do sistema ao fim da importação. */
  casadas: number;
  divergentes: number;
}

export interface ImportacaoResumo {
  id: string;
  importadaEm: string;
  nomeDoArquivo: string | null;
  quemImportou: string | null;
  linhas: number;
  novas: number;
  canceladas: number;
  repetidas: number;
  ignoradas: number;
  recusadas: number;
}

interface PaginaDeImportacoes {
  itens: ImportacaoResumo[];
  proximoCursor: string | null;
  temMais: boolean;
}

const ROTA = '/v1/conciliacao/importacoes';
const chaveDoHistorico = ['conciliacao', 'importacoes'] as const;

/** O arquivo e, quando há, o mapeamento — na mesma requisição, para um não ir sem o outro. */
function corpo(arquivo: File, mapeamento?: Mapeamento): FormData {
  const dados = new FormData();
  dados.append('arquivo', arquivo);
  if (mapeamento) {
    dados.append('mapeamento',
      new Blob([JSON.stringify(mapeamento)], { type: 'application/json' }));
  }
  return dados;
}

export function usePrevia() {
  return useMutation({
    mutationFn: ({ arquivo, mapeamento }: { arquivo: File; mapeamento?: Mapeamento }) =>
      api<Previa>(`${ROTA}/previa`, { method: 'POST', body: corpo(arquivo, mapeamento) }),
  });
}

export function useImportar() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ arquivo, mapeamento }: { arquivo: File; mapeamento: Mapeamento }) =>
      api<Resultado>(ROTA, { method: 'POST', body: corpo(arquivo, mapeamento) }),
    // A importação também casa (RT-FIN-010): o histórico e a conferência mudam juntos.
    onSuccess: () => fila.invalidateQueries({ queryKey: ['conciliacao'] }),
  });
}

export function useImportacoes() {
  return useInfiniteQuery({
    queryKey: chaveDoHistorico,
    queryFn: ({ pageParam }) => api<PaginaDeImportacoes>(
      pageParam ? `${ROTA}?cursor=${encodeURIComponent(pageParam)}` : ROTA),
    initialPageParam: null as string | null,
    getNextPageParam: (ultima) => (ultima.temMais ? ultima.proximoCursor : null),
  });
}

// ------------------------------------------------------------------ RT-FIN-010 — a conferência

/** As quatro perguntas: o que difere, o que só a maquininha tem, o que só o sistema tem, o que bate. */
export type Recorte = 'DIVERGENTES' | 'SO_NA_MAQUININHA' | 'SO_NO_SISTEMA' | 'CONCILIADAS';

export interface Totais {
  quantidade: number;
  valor: number;
}

export interface ResumoDaConciliacao {
  de: string;
  ate: string;
  conciliadas: Totais;
  divergentes: Totais;
  soNaMaquininha: Totais;
  soNoSistema: Totais;
  /** Pagamentos no cartão em dias que nenhuma importação cobre — falta de arquivo, e não problema. */
  semExtrato: Totais;
}

/** Nos pares, os dois lados; nas sobras, só o que existe. Instantes em UTC. */
export interface ItemDaConciliacao {
  id: string;
  instante: string;
  vendaId: string | null;
  idNaAdquirente: string | null;
  capturadaEm: string | null;
  horaInformada: boolean | null;
  valorVenda: number | null;
  produto: 'CREDITO' | 'DEBITO' | 'OUTRO' | null;
  bandeira: string | null;
  parcelas: number | null;
  pagamentoId: string | null;
  registradoEm: string | null;
  valorPagamento: number | null;
  forma: 'CREDITO' | 'DEBITO' | null;
  criterio: 'NSU' | 'AUTORIZACAO' | 'VALOR_E_HORA' | null;
  divergencias: ('VALOR' | 'FORMA' | 'BANDEIRA' | 'PARCELAS' | 'CANCELADA_NA_ADQUIRENTE')[];
}

export interface Rodada {
  casadas: number;
  divergentes: number;
  canceladasDepois: number;
}

interface PaginaDeItens {
  itens: ItemDaConciliacao[];
  proximoCursor: string | null;
  temMais: boolean;
}

export function useResumoDaConciliacao(de: string, ate: string) {
  return useQuery({
    queryKey: ['conciliacao', 'resumo', de, ate],
    queryFn: () => api<ResumoDaConciliacao>(`/v1/conciliacao/resumo?de=${de}&ate=${ate}`),
  });
}

export function useItensDaConciliacao(recorte: Recorte, de: string, ate: string) {
  return useInfiniteQuery({
    queryKey: ['conciliacao', 'itens', recorte, de, ate],
    queryFn: ({ pageParam }) => api<PaginaDeItens>(`/v1/conciliacao/itens?recorte=${recorte}`
      + `&de=${de}&ate=${ate}${pageParam ? `&cursor=${encodeURIComponent(pageParam)}` : ''}`),
    initialPageParam: null as string | null,
    getNextPageParam: (ultima) => (ultima.temMais ? ultima.proximoCursor : null),
  });
}

/** "Conciliar agora": casa o que ficou sem par desde a última importação. */
export function useConciliarAgora() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: () => api<Rodada>('/v1/conciliacao/casamentos', { method: 'POST' }),
    onSuccess: () => fila.invalidateQueries({ queryKey: ['conciliacao'] }),
  });
}

