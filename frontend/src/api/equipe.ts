import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './http';

export type TipoDeVinculo = 'PARCEIRO' | 'EMPREGADO' | 'AUTONOMO';

export interface Profissional {
  id: string;
  nome: string;
  apelido: string | null;
  documento: string;
  vinculo: TipoDeVinculo;
  telefone: string | null;
  email: string | null;
  /** Nulo quer dizer "usa a cascata do serviço" (RT-FIN-002). Zero é uma comissão de verdade. */
  comissaoPercentual: number | null;
  contratoInicio: string | null;
  contratoFim: string | null;
  contratoAssinadoEm: string | null;
  ativo: boolean;
  versao: number;
}

export interface Intervalo {
  inicio: string;
  fim: string;
}

/**
 * A semana como o servidor a devolve: dia da semana ISO (1 = segunda) → intervalos.
 *
 * Dia ausente é dia sem trabalho. O almoço não tem campo: ele é o buraco entre dois intervalos
 * do mesmo dia (RN-EQP-006), e inventar um terceiro jeito de representá-lo aqui seria criar a
 * primeira divergência.
 */
export type Jornada = Record<string, Intervalo[]>;

export interface Habilidades {
  executaTodos: boolean;
  servicos: string[];
  duracoes: Record<string, number>;
}

export type MotivoDoBloqueio = 'FERIADO' | 'FERIAS' | 'CURSO' | 'ATESTADO' | 'FOLGA';

export interface Bloqueio {
  id: string;
  profissionalId: string | null;
  motivo: MotivoDoBloqueio;
  observacao: string | null;
  dataInicio: string;
  dataFim: string;
  horaInicio: string | null;
  horaFim: string | null;
}

const CHAVE = ['equipe'] as const;

export function useProfissionais() {
  return useQuery({
    queryKey: [...CHAVE, 'profissionais'],
    queryFn: () => api<Profissional[]>('/v1/profissionais'),
  });
}

export function useJornada(profissionalId: string | null) {
  return useQuery({
    queryKey: [...CHAVE, 'jornada', profissionalId],
    queryFn: () => api<Jornada>(`/v1/profissionais/${profissionalId}/jornada`),
    enabled: profissionalId !== null,
  });
}

export function useHabilidades(profissionalId: string | null) {
  return useQuery({
    queryKey: [...CHAVE, 'habilidades', profissionalId],
    queryFn: () => api<Habilidades>(`/v1/profissionais/${profissionalId}/habilidades`),
    enabled: profissionalId !== null,
  });
}

interface DadosDaFicha {
  nome: string;
  apelido: string | null;
  telefone: string | null;
  email: string | null;
  comissaoPercentual: number | null;
  contratoInicio: string | null;
  contratoFim: string | null;
  contratoAssinadoEm: string | null;
}

/**
 * Cadastrar e editar num gancho só — a diferença é o verbo, a identidade e a versão.
 *
 * `documento` e `vinculo` são desmontados do resto e só entram no POST: depois de cadastrada, a
 * identidade jurídica da relação não se edita (RN-EQP-017), e é AQUI que isso é garantido — num
 * lugar só, e não em cada tela que monte um formulário.
 */
export function useSalvarProfissional() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ id, versao, documento, vinculo, ...dados }: DadosDaFicha & {
      id?: string; versao?: number; documento?: string; vinculo?: TipoDeVinculo;
    }) =>
      id
        ? api<Profissional>(`/v1/profissionais/${id}`, {
          method: 'PUT', body: JSON.stringify({ ...dados, versao }),
        })
        : api<Profissional>('/v1/profissionais', {
          method: 'POST', body: JSON.stringify({ ...dados, documento, vinculo }),
        }),
    onSuccess: () => { void fila.invalidateQueries({ queryKey: CHAVE }); },
  });
}

export function useAtivacaoDoProfissional() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ativo }: { id: string; ativo: boolean }) =>
      api<Profissional>(`/v1/profissionais/${id}/ativacao`, {
        method: 'PATCH', body: JSON.stringify({ ativo }),
      }),
    onSuccess: () => { void fila.invalidateQueries({ queryKey: CHAVE }); },
  });
}

export function useSalvarJornada(profissionalId: string) {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: (semana: Jornada) =>
      api<Jornada>(`/v1/profissionais/${profissionalId}/jornada`, {
        method: 'PUT', body: JSON.stringify(semana),
      }),
    onSuccess: () => { void fila.invalidateQueries({ queryKey: CHAVE }); },
  });
}

export function useSalvarHabilidades(profissionalId: string) {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: (habilidades: Habilidades) =>
      api<Habilidades>(`/v1/profissionais/${profissionalId}/habilidades`, {
        method: 'PUT', body: JSON.stringify(habilidades),
      }),
    onSuccess: () => { void fila.invalidateQueries({ queryKey: CHAVE }); },
  });
}

export function useBloqueios(de: string, ate: string) {
  return useQuery({
    queryKey: [...CHAVE, 'bloqueios', de, ate],
    queryFn: () => api<Bloqueio[]>(`/v1/bloqueios-de-jornada?inicio=${de}&fim=${ate}`),
  });
}

export function useRegistrarBloqueio() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: (bloqueio: Omit<Bloqueio, 'id'>) =>
      api<Bloqueio>('/v1/bloqueios-de-jornada', {
        method: 'POST', body: JSON.stringify(bloqueio),
      }),
    onSuccess: () => { void fila.invalidateQueries({ queryKey: CHAVE }); },
  });
}

export function useRemoverBloqueio() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: (id: string) =>
      api<void>(`/v1/bloqueios-de-jornada/${id}`, { method: 'DELETE' }),
    onSuccess: () => { void fila.invalidateQueries({ queryKey: CHAVE }); },
  });
}

/** Segunda a domingo, na ordem em que a semana de trabalho é pensada. */
export const DIAS: Array<{ iso: string; curto: string; longo: string }> = [
  { iso: '1', curto: 'seg', longo: 'segunda-feira' },
  { iso: '2', curto: 'ter', longo: 'terça-feira' },
  { iso: '3', curto: 'qua', longo: 'quarta-feira' },
  { iso: '4', curto: 'qui', longo: 'quinta-feira' },
  { iso: '5', curto: 'sex', longo: 'sexta-feira' },
  { iso: '6', curto: 'sáb', longo: 'sábado' },
  { iso: '7', curto: 'dom', longo: 'domingo' },
];

/** `seg–sex 09:00–18:00` quando a semana é regular; a lista dos dias quando não é. */
export function resumoDaJornada(jornada: Jornada | undefined): string {
  if (!jornada) return '';
  const comTrabalho = DIAS.filter((dia) => (jornada[dia.iso] ?? []).length > 0);
  if (comTrabalho.length === 0) return 'sem jornada';

  const faixa = (iso: string) => (jornada[iso] ?? [])
    .map((i) => `${i.inicio.slice(0, 5)}–${i.fim.slice(0, 5)}`).join(' e ');
  const primeira = faixa(comTrabalho[0].iso);
  const todosIguais = comTrabalho.every((dia) => faixa(dia.iso) === primeira);

  // Sequência corrida de dias, que é o caso normal de salão: seg–sáb.
  const seguidos = comTrabalho.every((dia, i) =>
    i === 0 || Number(dia.iso) === Number(comTrabalho[i - 1].iso) + 1);

  const dias = todosIguais && seguidos && comTrabalho.length > 1
    ? `${comTrabalho[0].curto}–${comTrabalho.at(-1)!.curto}`
    : comTrabalho.map((dia) => dia.curto).join(', ');

  // Dias com horários diferentes não viram uma faixa só: dizer "ter–sáb 09:00–18:00" quando o
  // sábado fecha às 16:00 é um resumo que mente. A tela abre o detalhe num clique.
  return todosIguais ? `${dias} ${primeira}` : `${dias} · horários variam`;
}
