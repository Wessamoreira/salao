/**
 * O cálculo da grade, separado do React.
 *
 * Converter instante em pixel é a parte que erra silenciosamente — um bloco meia hora
 * fora do lugar não quebra nada, só mostra a coisa errada. Aqui é função pura e tem
 * teste; dentro do componente seria verificável só olhando.
 */

/** Quanto vale um minuto em pixels. 1.4 dá 84px por hora: cabe o dia numa tela sem rolar. */
export const PIXELS_POR_MINUTO = 1.4;

export interface FaixaDoDia {
  /** Primeira hora cheia mostrada. */
  primeiraHora: number;
  /** Última hora cheia mostrada, exclusiva. */
  ultimaHora: number;
}

/**
 * A faixa que a grade mostra.
 *
 * Não é 0h–24h: um salão que abre às 9h teria dois terços da tela em branco, e a
 * recepção rolaria toda vez para achar o dia. A faixa vem do que existe — jornadas e
 * agendamentos —, com uma hora de folga de cada lado para o arrasto ter para onde ir.
 */
export function faixaDoDia(instantes: Array<{ inicio: string; fim: string }>,
                           fuso: string): FaixaDoDia {
  if (instantes.length === 0) return { primeiraHora: 8, ultimaHora: 20 };

  let menor = 24;
  let maior = 0;
  for (const item of instantes) {
    menor = Math.min(menor, horaLocal(item.inicio, fuso));
    // O fim exatamente na hora cheia não estende a faixa: 18h00 termina às 18h.
    maior = Math.max(maior, Math.ceil(horaLocal(item.fim, fuso)));
  }
  return {
    primeiraHora: Math.max(0, Math.floor(menor) - 1),
    ultimaHora: Math.min(24, maior + 1),
  };
}

/** A hora do dia, com fração, no fuso do salão. */
export function horaLocal(instante: string, fuso: string): number {
  const formatador = new Intl.DateTimeFormat('pt-BR', {
    timeZone: fuso, hour: '2-digit', minute: '2-digit', hour12: false,
  });
  const partes = formatador.formatToParts(new Date(instante));
  const hora = Number(partes.find((p) => p.type === 'hour')?.value ?? 0);
  const minuto = Number(partes.find((p) => p.type === 'minute')?.value ?? 0);
  return hora + minuto / 60;
}

export function rotuloDeHora(instante: string, fuso: string): string {
  return new Intl.DateTimeFormat('pt-BR', {
    timeZone: fuso, hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(new Date(instante));
}

/** Onde um período cai na pista, em pixels a partir do topo. */
export function posicao(inicio: string, fim: string, faixa: FaixaDoDia, fuso: string) {
  const de = horaLocal(inicio, fuso);
  const ate = horaLocal(fim, fuso);
  return {
    topo: (de - faixa.primeiraHora) * 60 * PIXELS_POR_MINUTO,
    altura: Math.max((ate - de) * 60 * PIXELS_POR_MINUTO, 12),
  };
}

/**
 * A linha do "agora" — o achado mais sério da crítica de design.
 *
 * Sem ela um calendário é uma tabela. Devolve `null` quando o instante está fora da
 * faixa mostrada, para a linha não aparecer grudada no topo em quem abre a agenda de
 * amanhã.
 */
export function posicaoDoAgora(agora: Date, dia: string, faixa: FaixaDoDia,
                               fuso: string): number | null {
  const diaDoAgora = new Intl.DateTimeFormat('en-CA', { timeZone: fuso }).format(agora);
  if (diaDoAgora !== dia) return null;

  const hora = horaLocal(agora.toISOString(), fuso);
  if (hora < faixa.primeiraHora || hora > faixa.ultimaHora) return null;
  return (hora - faixa.primeiraHora) * 60 * PIXELS_POR_MINUTO;
}

/**
 * Quanto do dia a profissional já tem vendido, em minutos DELA.
 *
 * Não é a soma das durações: numa coloração de 100 minutos, 40 são pausa e ela está
 * livre. Contar os 100 diria que a agenda está cheia quando ainda cabe um corte no
 * meio — que é justamente o número que decide se dá para encaixar mais um.
 */
export function cargaEmMinutos(agendamentos: Array<{ minutosDoProfissional: number }>): number {
  return agendamentos.reduce((soma, a) => soma + a.minutosDoProfissional, 0);
}

export function rotuloDeCarga(minutos: number): string {
  const horas = Math.floor(minutos / 60);
  const resto = minutos % 60;
  return `${horas}h${String(resto).padStart(2, '0')}`;
}
