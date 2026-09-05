import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './http';

export type StatusAgendamento =
  | 'SOLICITADO' | 'CONFIRMADO' | 'EM_ATENDIMENTO'
  | 'CONCLUIDO' | 'CANCELADO' | 'NO_SHOW';

export type TipoDeBloco = 'ATIVO' | 'PAUSA' | 'BLOQUEIO';

export interface Janela {
  inicio: string;
  fim: string;
}

/**
 * Um bloco como o servidor o materializou.
 *
 * `profissionalId` nulo é a pausa: a cliente está no salão e a profissional está livre.
 * A grade precisa desenhar os dois de formas diferentes, e é esta a informação que
 * permite — não o tipo sozinho, porque um BLOQUEIO também não tem cliente.
 */
export interface BlocoDoAgendamento {
  ordem: number;
  tipo: TipoDeBloco;
  janela: Janela;
  profissionalId: string | null;
  recursoId: string | null;
  unidade: number | null;
}

export interface Agendamento {
  id: string;
  status: StatusAgendamento;
  inicio: string;
  fim: string;
  clienteId: string;
  profissionalId: string;
  servicoId: string;
  origem: string;
  observacao: string | null;
  blocos: BlocoDoAgendamento[];
  minutosDoProfissional: number;
  versao: number;
}

export interface HorarioDisponivel {
  inicio: string;
  fim: string;
  profissionalId: string;
  profissional: string;
  minutosDoProfissional: number;
}

export interface ProfissionalNaGrade {
  id: string;
  nome: string;
  apelido: string | null;
}

export interface BloqueioDaGrade {
  id: string;
  profissionalId: string | null;
  recursoId: string | null;
  motivo: string;
  descricao: string | null;
  janela: Janela;
}

/**
 * Tudo o que a grade de um dia precisa, numa chamada só.
 *
 * Uma e não quatro: a recepção troca de dia a cada poucos segundos, e cada troca que
 * dispara quatro requisições produz quatro momentos em que a tela está pela metade —
 * colunas sem blocos, blocos sem nome.
 */
export interface AgendaDoDia {
  data: string;
  fuso: string;
  profissionais: ProfissionalNaGrade[];
  /** O que cada profissional atende no dia, já sem bloqueios de jornada. */
  jornadas: Record<string, Janela[]>;
  agendamentos: Agendamento[];
  bloqueios: BloqueioDaGrade[];
  nomesDeClientes: Record<string, string>;
}

export function useAgendaDoDia(data: string) {
  return useQuery({
    queryKey: ['agenda', data],
    queryFn: () => api<AgendaDoDia>(`/api/v1/agendamentos/dia?data=${data}`),
  });
}

export function useDisponibilidade(data: string, servicoId?: string, profissionalId?: string) {
  return useQuery({
    queryKey: ['disponibilidade', data, servicoId, profissionalId],
    // Sem serviço não há o que perguntar: a disponibilidade depende da receita.
    enabled: Boolean(servicoId),
    queryFn: () => {
      const busca = new URLSearchParams({ data, servico: servicoId! });
      if (profissionalId) busca.set('profissional', profissionalId);
      return api<HorarioDisponivel[]>(`/api/v1/disponibilidade?${busca}`);
    },
  });
}

export function useAgendamento(id: string | null) {
  return useQuery({
    queryKey: ['agendamento', id],
    enabled: Boolean(id),
    queryFn: () => api<Agendamento>(`/api/v1/agendamentos/${id}`),
  });
}

interface Reagendamento {
  id: string;
  inicio: string;
  profissionalId?: string;
  versao: number;
}

/**
 * Arrastar na grade.
 *
 * Invalida a agenda inteira e não só o agendamento movido: mover libera o horário
 * antigo e ocupa o novo, então a grade de todo mundo mudou — inclusive a de outra
 * profissional, se o arrasto atravessou colunas.
 */
export function useReagendar() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ id, inicio, profissionalId, versao }: Reagendamento) =>
      api<Agendamento>(`/api/v1/agendamentos/${id}/horario`, {
        method: 'PUT',
        body: JSON.stringify({ inicio, profissionalId, versao }),
      }),
    onSettled: () => fila.invalidateQueries({ queryKey: ['agenda'] }),
  });
}
