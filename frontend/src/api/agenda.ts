import { useEffect } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ouvirEventos } from './http';

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
    queryFn: () => api<AgendaDoDia>(`/v1/agendamentos/dia?data=${data}`),
    /*
     * Mantém a grade do dia anterior enquanto o novo chega.
     *
     * Sem isto, cada clique na flecha derrubava a grade inteira — doze colunas, a régua
     * de horas, todos os blocos — e a trocava por um parágrafo "Carregando a agenda…",
     * para remontar tudo meio segundo depois. Não é a demora que se sente como travada:
     * é o desmonte. A recepção navega entre dias o tempo todo.
     *
     * Quem avisa que o dado ainda não é do dia pedido é `isPlaceholderData`, que a tela
     * usa para esmaecer a grade. Mostrar sem avisar seria pior que a tela branca.
     */
    placeholderData: (anterior) => anterior,
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
      return api<HorarioDisponivel[]>(`/v1/disponibilidade?${busca}`);
    },
  });
}

export function useAgendamento(id: string | null) {
  return useQuery({
    queryKey: ['agendamento', id],
    enabled: Boolean(id),
    queryFn: () => api<Agendamento>(`/v1/agendamentos/${id}`),
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
      api<Agendamento>(`/v1/agendamentos/${id}/horario`, {
        method: 'PUT',
        body: JSON.stringify({ inicio, profissionalId, versao }),
      }),
    onSettled: () => fila.invalidateQueries({ queryKey: ['agenda'] }),
  });
}

// ------------------------------------------------------------------ RT-AGD-012

export type SituacaoNoPainel =
  | 'SOLICITADO' | 'CONFIRMADO' | 'EM_ATENDIMENTO';

export interface LinhaDoPainel {
  inicio: string;
  cliente: string;
  profissional: string;
  situacao: SituacaoNoPainel;
}

export interface Painel {
  estabelecimento: string;
  fuso: string;
  atualizadoEm: string;
  linhas: LinhaDoPainel[];
  restantes: number;
}

/**
 * O painel do balcão: retrato + aviso de que mudou.
 *
 * O aviso NÃO carrega o dado — ele faz o painel pedir o retrato de novo. Parece um
 * passo a mais e evita a classe de bug mais chata deste tipo de tela: um stream de
 * deltas precisa que o cliente aplique todos, na ordem, sem perder nenhum; quando ele
 * perde um (reconexão, aba em segundo plano, rede oscilando), a tela fica errada em
 * silêncio, para sempre, numa parede que ninguém olha de perto.
 */
export function usePainel() {
  const fila = useQueryClient();
  const consulta = useQuery({
    queryKey: ['painel'],
    queryFn: () => api<Painel>('/v1/painel'),
    // Rede de salão cai. O retrato tem custo baixo e a checagem periódica é o que faz
    // o painel se recuperar sozinho de um stream que morreu sem avisar.
    refetchInterval: 60_000,
  });

  useEffect(() => ouvirEventos('/v1/painel/eventos',
    () => fila.invalidateQueries({ queryKey: ['painel'] })), [fila]);

  return consulta;
}
