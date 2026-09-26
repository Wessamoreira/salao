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

// ------------------------------------------------------------------ RT-AGD-013

export type MotivoDoBloqueio = 'ALMOCO' | 'FERIAS' | 'CURSO' | 'FERIADO' | 'PESSOAL'
  | 'MANUTENCAO';

export type Frequencia = 'SEMANAL' | 'QUINZENAL';

export interface SerieCriada {
  recorrencia: { id: string };
  agendamentos: string[];
  /** Os instantes que não couberam, na ordem em que teriam acontecido. */
  naoCouberam: string[];
}

/** Toda escrita da grade invalida o dia inteiro: mover libera um horário e ocupa outro. */
function aoEscrever(fila: ReturnType<typeof useQueryClient>) {
  return () => { void fila.invalidateQueries({ queryKey: ['agenda'] }); };
}

export interface NovoAgendamento {
  clienteId: string;
  profissionalId: string;
  servicoId: string;
  inicio: string;
  /** Quando o servidor recusa por estar fora da jornada, a tela pergunta e reenvia com isto. */
  forcarForaJornada?: boolean;
  justificativa?: string;
}

/**
 * Marcar pela grade.
 *
 * `origem: PAINEL` sempre — marcar pela grade **é** marcar pelo balcão, e é a origem que decide
 * o status inicial (RN-AGD-013). Um seletor de origem aqui só produziria origem errada.
 */
export function useCriarAgendamento() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: (dados: NovoAgendamento) =>
      api<Agendamento>('/v1/agendamentos', {
        method: 'POST', body: JSON.stringify({ ...dados, origem: 'PAINEL' }),
      }),
    onSuccess: aoEscrever(fila),
  });
}

/** RT-AGD-009 — a cliente que vem toda semana. A resposta traz o que não coube. */
export function useCriarSerie() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: (dados: NovoAgendamento & { frequencia: Frequencia; quantidade: number }) =>
      api<SerieCriada>('/v1/recorrencias', {
        method: 'POST',
        body: JSON.stringify({
          clienteId: dados.clienteId, profissionalId: dados.profissionalId,
          servicoId: dados.servicoId, primeiroInicio: dados.inicio,
          frequencia: dados.frequencia, quantidade: dados.quantidade,
        }),
      }),
    onSuccess: aoEscrever(fila),
  });
}

/** RT-AGD-010 — um dia que não coube vai para a fila, um por vez. */
export function usePorDiaNaFila(recorrenciaId: string) {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: (dia: string) =>
      api<{ id: string }>(`/v1/recorrencias/${recorrenciaId}/fila-de-espera`, {
        method: 'POST', body: JSON.stringify({ dia }),
      }),
    onSuccess: aoEscrever(fila),
  });
}

export function useConfirmar() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ id, versao }: { id: string; versao: number }) =>
      api<Agendamento>(`/v1/agendamentos/${id}/confirmacao`, {
        method: 'PATCH', body: JSON.stringify({ versao, pelaCliente: false }),
      }),
    onSuccess: aoEscrever(fila),
  });
}

/** O motivo é obrigatório: é ele que sustenta a conversa sobre quem desmarca demais. */
export function useCancelar() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ id, versao, motivo, pelaCliente }: {
      id: string; versao: number; motivo: string; pelaCliente: boolean;
    }) =>
      api<Agendamento>(`/v1/agendamentos/${id}/cancelamento`, {
        method: 'PATCH', body: JSON.stringify({ versao, motivo, pelaCliente }),
      }),
    onSuccess: aoEscrever(fila),
  });
}

/** Sem motivo, ao contrário do cancelamento: o motivo da falta é a própria ausência. */
export function useRegistrarFalta() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ id, versao }: { id: string; versao: number }) =>
      api<Agendamento>(`/v1/agendamentos/${id}/falta`, {
        method: 'PATCH', body: JSON.stringify({ versao }),
      }),
    onSuccess: aoEscrever(fila),
  });
}

export function useBloquear() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: (dados: {
      profissionalId: string; motivo: MotivoDoBloqueio; descricao: string | null;
      inicio: string; fim: string;
    }) => api<BloqueioDaGrade>('/v1/bloqueios-de-agenda', {
      method: 'POST', body: JSON.stringify(dados),
    }),
    onSuccess: aoEscrever(fila),
  });
}

export function useRemoverBloqueio() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: (id: string) =>
      api<void>(`/v1/bloqueios-de-agenda/${id}`, { method: 'DELETE' }),
    onSuccess: aoEscrever(fila),
  });
}

export const MOTIVOS_DO_BLOQUEIO: Array<{ valor: MotivoDoBloqueio; rotulo: string }> = [
  { valor: 'PESSOAL', rotulo: 'Compromisso pessoal' },
  { valor: 'ALMOCO', rotulo: 'Almoço' },
  { valor: 'CURSO', rotulo: 'Curso' },
  { valor: 'FERIAS', rotulo: 'Férias' },
  { valor: 'FERIADO', rotulo: 'Feriado' },
  { valor: 'MANUTENCAO', rotulo: 'Manutenção' },
];
