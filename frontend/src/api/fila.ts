import { useEffect } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ouvirEventos } from './http';

/**
 * RT-AGD-010 — a fila de espera e as vagas que abriram.
 *
 * Quem cabe numa vaga, em que ordem, e se chamar alguém exige motivo são regras do servidor
 * (RN-AGD-025, RN-AGD-026). A tela só desenha: não recalcula horário, não reordena, não decide
 * o pulo.
 */

export type Origem = 'CANCELAMENTO' | 'REAGENDAMENTO';
export type Resultado = 'ACEITOU' | 'RECUSOU' | 'NAO_ATENDEU';

export interface Chamada {
  resultado: Resultado;
  registradaEm: string;
  /** Nome de quem registrou; nulo quando não se sabe. */
  quem: string | null;
  motivoDoPulo: string | null;
}

export interface Candidata {
  esperaId: string;
  /** A ordem de chegada entre as candidatas desta vaga, a partir de 1. */
  posicao: number;
  clienteId: string;
  cliente: string | null;
  /** Só dígitos, E.164 sem `+`. */
  telefone: string | null;
  servicoId: string;
  servico: string | null;
  aceitaDe: string;
  aceitaAte: string;
  horaDe: string | null;
  horaAte: string | null;
  naFilaDesde: string;
  /** Os inícios possíveis nesta vaga, em ordem. */
  horarios: string[];
  chamadas: Chamada[];
  /** Chamar agora pularia alguém: o servidor vai pedir o motivo. */
  exigeMotivo: boolean;
}

export interface Vaga {
  id: string;
  profissionalId: string;
  profissional: string | null;
  inicio: string;
  fim: string;
  origem: Origem;
  candidatas: Candidata[];
}

export interface Espera {
  id: string;
  clienteId: string;
  cliente: string | null;
  servicoId: string;
  servico: string | null;
  /** Nulo é "qualquer uma". */
  profissionalId: string | null;
  profissional: string | null;
  aceitaDe: string;
  aceitaAte: string;
  horaDe: string | null;
  horaAte: string | null;
  observacao: string | null;
  naFilaDesde: string;
  /** A série de onde veio, quando é um dia dela que não coube. */
  recorrenciaId: string | null;
}

export interface NovaEspera {
  clienteId: string;
  servicoId: string;
  profissionalId: string | null;
  aceitaDe: string;
  aceitaAte: string;
  horaDe: string | null;
  horaAte: string | null;
  observacao: string | null;
}

export interface NovaChamada {
  vagaId: string;
  esperaId: string;
  resultado: Resultado;
  inicio?: string | null;
  motivoDoPulo?: string | null;
}

const chave = ['fila'] as const;

/**
 * As vagas abertas. Um cancelamento na agenda, em qualquer tela, abre uma vaga: o aviso do painel
 * (RT-AGD-012) diz que algo mudou, e a lista é pedida de novo.
 */
export function useVagasAbertas() {
  const fila = useQueryClient();
  const consulta = useQuery({
    queryKey: [...chave, 'vagas'],
    queryFn: () => api<Vaga[]>('/v1/vagas-abertas'),
  });

  useEffect(() => ouvirEventos('/v1/painel/eventos',
    () => fila.invalidateQueries({ queryKey: chave })), [fila]);

  return consulta;
}

export function useFilaDeEspera() {
  return useQuery({
    queryKey: [...chave, 'esperas'],
    queryFn: () => api<Espera[]>('/v1/fila-de-espera'),
  });
}

export function useRegistrarChamada() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ vagaId, ...corpo }: NovaChamada) =>
      api<unknown>(`/v1/vagas-abertas/${vagaId}/chamadas`, {
        method: 'POST', body: JSON.stringify(corpo),
      }),
    // Aceitar tira da fila e ocupa a agenda: as duas listas mudam.
    onSuccess: () => {
      fila.invalidateQueries({ queryKey: chave });
      fila.invalidateQueries({ queryKey: ['agenda'] });
    },
  });
}

export function useEntrarNaFila() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: (espera: NovaEspera) =>
      api<unknown>('/v1/fila-de-espera', { method: 'POST', body: JSON.stringify(espera) }),
    onSuccess: () => fila.invalidateQueries({ queryKey: chave }),
  });
}

/** As duas saídas da fila, que o relatório conta separadas. */
export function useSairDaFila() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ id, como }: { id: string; como: 'ATENDIDA' | 'DESISTIU' }) =>
      como === 'ATENDIDA'
        ? api<void>(`/v1/fila-de-espera/${id}/atendimento`, { method: 'PATCH' })
        : api<void>(`/v1/fila-de-espera/${id}`, { method: 'DELETE' }),
    onSuccess: () => fila.invalidateQueries({ queryKey: chave }),
  });
}
