import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
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
    onSuccess: () => fila.invalidateQueries({ queryKey: chaveDoHistorico }),
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
