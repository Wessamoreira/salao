import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './http';

/**
 * RT-FIN-013 — o dinheiro de quem trabalha.
 *
 * Todo número de dinheiro chega como número: as rotas do módulo devolviam `Money`, que serializa
 * como `{ valor: … }`. Passaram a ter DTO de tela, como o catálogo e o estoque.
 */

export type TipoDeLancamento =
  | 'RECEITA_SERVICO' | 'RECEITA_PRODUTO' | 'COMISSAO' | 'TAXA_ADQUIRENTE'
  | 'VALE' | 'REPASSE' | 'AJUSTE' | 'ESTORNO';

export type FormaDoVale = 'DINHEIRO' | 'PIX' | 'TRANSFERENCIA';

export interface Periodo {
  de: string;
  ate: string;
}

export interface LinhaDaPrevia {
  profissionalId: string;
  profissionalNome: string;
  saldoAnterior: number;
  movimentoDoPeriodo: number;
  /** Antes do piso: negativo quando é a pessoa que deve ao salão. */
  saldoFinal: number;
  aRepassar: number;
}

export interface Previa {
  de: string;
  ate: string;
  totalARepassar: number;
  quantosRecebem: number;
  /** O hash das posições. É ele que garante que se confirma o número que se viu. */
  versao: string;
  linhas: LinhaDaPrevia[];
}

export interface LinhaDoExtrato {
  data: string;
  tipo: TipoDeLancamento;
  descricao: string;
  /** Já no sinal de quem recebe: comissão positiva, vale negativo. */
  valor: number;
  origemTipo: string;
  origemId: string;
  /** O id da linha do razão — é por ele que se estorna. */
  lancamentoId: string;
}

export interface Extrato {
  profissionalId: string;
  nome: string;
  de: string;
  ate: string;
  saldoAnterior: number;
  aReceber: number;
  saldoFinal: number;
  totais: Array<{ tipo: TipoDeLancamento; valor: number }>;
  linhas: LinhaDoExtrato[];
}

export interface Vale {
  id: string;
  profissionalId: string;
  profissionalNome: string;
  /** Sempre positivo: é o que saiu da gaveta. No extrato aparece com menos. */
  valor: number;
  forma: FormaDoVale;
  dataCompetencia: string;
  motivo: string | null;
  cancelado: boolean;
}

export interface Fechamento {
  id: string;
  profissionalId: string;
  profissionalNome: string;
  periodoInicio: string;
  periodoFim: string;
  saldoAnterior: number;
  movimentoDoPeriodo: number;
  valorRepassado: number;
  saldoATransportar: number;
  /** Nulo enquanto o documento vale. Preenchido, ele é histórico. */
  estornadoEm: string | null;
}

const CHAVE = ['financeiro'] as const;

export function usePrevia({ de, ate }: Periodo) {
  return useQuery({
    queryKey: [...CHAVE, 'previa', de, ate],
    queryFn: () => api<Previa>(`/v1/fechamentos/previa?de=${de}&ate=${ate}`),
    // A prévia envelhece: uma comanda fechada no balcão muda o número. Recarregar ao voltar
    // para a aba é o que evita confirmar um total que já não existe.
    staleTime: 0,
  });
}

export function useExtrato(profissionalId: string | null, { de, ate }: Periodo) {
  return useQuery({
    queryKey: [...CHAVE, 'extrato', profissionalId, de, ate],
    queryFn: () => api<Extrato>(`/v1/extratos/${profissionalId}?de=${de}&ate=${ate}`),
    enabled: profissionalId !== null,
  });
}

export function useVales({ de, ate }: Periodo) {
  return useQuery({
    queryKey: [...CHAVE, 'vales', de, ate],
    queryFn: () => api<Vale[]>(`/v1/vales?de=${de}&ate=${ate}`),
  });
}

export function useFechamentos({ de, ate }: Periodo) {
  return useQuery({
    queryKey: [...CHAVE, 'fechamentos', de, ate],
    queryFn: () => api<Fechamento[]>(`/v1/fechamentos?de=${de}&ate=${ate}`),
  });
}

function aoEscrever(fila: ReturnType<typeof useQueryClient>) {
  return () => { void fila.invalidateQueries({ queryKey: CHAVE }); };
}

/**
 * Fecha o período, levando a versão da prévia que está na tela.
 *
 * Se alguém fechou uma comanda ou registrou um vale no meio, o servidor recusa com
 * `ER-FIN-PREVIA_DESATUALIZADA` — e é assim que o número confirmado é o número que se viu.
 */
export function useFecharPeriodo() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ de, ate, versaoDaPrevia }: Periodo & { versaoDaPrevia: string }) =>
      api<Fechamento[]>('/v1/fechamentos', {
        method: 'POST', body: JSON.stringify({ de, ate, versaoDaPrevia }),
      }),
    onSuccess: aoEscrever(fila),
  });
}

export function useEstornarFechamento() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ id, motivo }: { id: string; motivo: string }) =>
      api<Fechamento>(`/v1/fechamentos/${id}/estorno`, {
        method: 'POST', body: JSON.stringify({ motivo }),
      }),
    onSuccess: aoEscrever(fila),
  });
}

export function useRegistrarVale() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: (dados: {
      profissionalId: string; valor: number; forma: FormaDoVale; motivo: string | null;
    }) => api<Vale>('/v1/vales', { method: 'POST', body: JSON.stringify(dados) }),
    onSuccess: aoEscrever(fila),
  });
}

export function useCancelarVale() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ id, motivo }: { id: string; motivo: string }) =>
      api<Vale>(`/v1/vales/${id}/cancelamento`, {
        method: 'POST', body: JSON.stringify({ motivo }),
      }),
    onSuccess: aoEscrever(fila),
  });
}

/** RT-FIN-007 — anula uma linha do razão. O que ela fez continua registrado. */
export function useEstornarLancamento() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ id, motivo }: { id: string; motivo: string }) =>
      api<{ anuladas: number }>(`/v1/lancamentos/${id}/estorno`, {
        method: 'POST', body: JSON.stringify({ motivo }),
      }),
    onSuccess: aoEscrever(fila),
  });
}

/**
 * RN-FIN-041 — corrige o razão com uma linha nova.
 *
 * `valor` no sinal de quem recebe: positivo aumenta o que a pessoa tem a receber. O servidor
 * inverte uma vez, ao gravar — a tela não conhece a contabilidade do razão.
 */
export function useAjustarRazao() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: (dados: { profissionalId: string; valor: number; motivo: string }) =>
      api<{ id: string; valorParaQuemRecebe: number }>('/v1/lancamentos', {
        method: 'POST', body: JSON.stringify(dados),
      }),
    onSuccess: aoEscrever(fila),
  });
}

// ------------------------------------------------------------------ como se lê

const MOEDA = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });

export function dinheiro(valor: number | null | undefined): string {
  return valor == null ? '—' : MOEDA.format(valor);
}

export const TIPOS: Record<TipoDeLancamento, string> = {
  RECEITA_SERVICO: 'Serviço',
  RECEITA_PRODUTO: 'Produto',
  COMISSAO: 'Comissão',
  TAXA_ADQUIRENTE: 'Taxa da maquininha',
  VALE: 'Vale',
  REPASSE: 'Repasse',
  AJUSTE: 'Ajuste',
  ESTORNO: 'Estorno',
};

export const FORMAS_DO_VALE: Array<{ valor: FormaDoVale; rotulo: string }> = [
  { valor: 'DINHEIRO', rotulo: 'Dinheiro da gaveta' },
  { valor: 'PIX', rotulo: 'Pix' },
  { valor: 'TRANSFERENCIA', rotulo: 'Transferência' },
];

export type Periodicidade = 'SEMANAL' | 'QUINZENAL' | 'MENSAL';

/**
 * O último período que já **terminou**, na periodicidade do salão.
 *
 * Fecha-se o mês depois que ele acaba. Abrir em "mês corrente" ofereceria fechar um período pela
 * metade — que é o erro caro —, e abrir em branco obrigaria a digitar duas datas antes de ver
 * qualquer coisa.
 *
 * @param hoje o dia civil do salão (`AAAA-MM-DD`), nunca o do servidor nem o do navegador
 */
export function ultimoPeriodoFechado(hoje: string, periodicidade: Periodicidade): Periodo {
  const [ano, mes, dia] = hoje.split('-').map(Number);

  if (periodicidade === 'MENSAL') {
    const primeiroDesteMes = new Date(Date.UTC(ano, mes - 1, 1));
    const fim = new Date(primeiroDesteMes.getTime() - 86_400_000);
    return { de: iso(new Date(Date.UTC(fim.getUTCFullYear(), fim.getUTCMonth(), 1))), ate: iso(fim) };
  }

  if (periodicidade === 'QUINZENAL') {
    // Duas quinzenas por mês: 1–15 e 16 até o fim. A que terminou é a anterior à de hoje.
    if (dia <= 15) {
      const ultimoDoMesPassado = new Date(Date.UTC(ano, mes - 1, 0));
      return {
        de: iso(new Date(Date.UTC(ultimoDoMesPassado.getUTCFullYear(),
          ultimoDoMesPassado.getUTCMonth(), 16))),
        ate: iso(ultimoDoMesPassado),
      };
    }
    return { de: iso(new Date(Date.UTC(ano, mes - 1, 1))), ate: iso(new Date(Date.UTC(ano, mes - 1, 15))) };
  }

  // SEMANAL: a semana anterior, de segunda a domingo.
  const hojeUtc = new Date(Date.UTC(ano, mes - 1, dia));
  const diaDaSemana = (hojeUtc.getUTCDay() + 6) % 7; // 0 = segunda
  const segundaDestaSemana = new Date(hojeUtc.getTime() - diaDaSemana * 86_400_000);
  const domingoPassado = new Date(segundaDestaSemana.getTime() - 86_400_000);
  return { de: iso(new Date(domingoPassado.getTime() - 6 * 86_400_000)), ate: iso(domingoPassado) };
}

/**
 * O período anterior ou seguinte — é como se navega sem digitar data.
 *
 * **Um mês inteiro anda um mês; qualquer outro recorte anda o próprio tamanho.** Deslocar março
 * por trinta e um dias cairia em 29 de janeiro a 28 de fevereiro, que não é "o mês anterior" e
 * não é nada — e é o mês inteiro que a maioria dos salões fecha.
 */
export function deslocar({ de, ate }: Periodo, passos: number): Periodo {
  if (ehMesInteiro({ de, ate })) {
    const [ano, mes] = de.split('-').map(Number);
    const primeiro = new Date(Date.UTC(ano, mes - 1 + passos, 1));
    const ultimo = new Date(Date.UTC(primeiro.getUTCFullYear(), primeiro.getUTCMonth() + 1, 0));
    return { de: iso(primeiro), ate: iso(ultimo) };
  }
  const inicio = new Date(`${de}T12:00:00Z`);
  const fim = new Date(`${ate}T12:00:00Z`);
  const dias = Math.round((fim.getTime() - inicio.getTime()) / 86_400_000) + 1;
  const salto = passos * dias * 86_400_000;
  return { de: iso(new Date(inicio.getTime() + salto)), ate: iso(new Date(fim.getTime() + salto)) };
}

function ehMesInteiro({ de, ate }: Periodo): boolean {
  const [ano, mes, dia] = de.split('-').map(Number);
  const ultimo = new Date(Date.UTC(ano, mes, 0));
  return dia === 1 && ate === iso(ultimo);
}

/** `1 — 30 de setembro de 2026` · `28 de set — 4 de out de 2026`. */
export function periodoPorExtenso({ de, ate }: Periodo): string {
  const inicio = new Date(`${de}T12:00:00Z`);
  const fim = new Date(`${ate}T12:00:00Z`);
  const mesmoMes = de.slice(0, 7) === ate.slice(0, 7);
  const dia = (d: Date) => d.getUTCDate();
  const mesCurto = (d: Date) =>
    d.toLocaleDateString('pt-BR', { month: 'short', timeZone: 'UTC' }).replace('.', '');
  const mesLongo = (d: Date) =>
    d.toLocaleDateString('pt-BR', { month: 'long', timeZone: 'UTC' });

  return mesmoMes
    ? `${dia(inicio)} — ${dia(fim)} de ${mesLongo(fim)} de ${fim.getUTCFullYear()}`
    : `${dia(inicio)} de ${mesCurto(inicio)} — ${dia(fim)} de ${mesCurto(fim)} `
      + `de ${fim.getUTCFullYear()}`;
}

function iso(quando: Date): string {
  return quando.toISOString().slice(0, 10);
}
