/*
 * RT-CNV-007 — o calendário do salão, para a revisão do agente.
 *
 * Os dias são dias civis NO FUSO DO SALÃO (ADR-0009): às 23h30 de São Paulo, o servidor em UTC já
 * está no dia seguinte, e "hoje" na tela tem de ser o hoje de quem está no balcão.
 */

/** O período em dias civis do salão, terminando hoje, os dois extremos inclusivos. */
export function periodoDe(dias: number, fuso: string, agora: Date): { de: string; ate: string } {
  const ate = diaCivil(agora, fuso);
  return { de: somarDias(ate, -(dias - 1)), ate };
}

/** `AAAA-MM-DD` do instante, no fuso informado. */
export function diaCivil(quando: Date, fuso: string): string {
  return formatar(quando, fuso, { year: 'numeric', month: '2-digit', day: '2-digit' }, 'en-CA');
}

/** Soma dias a um dia civil, pela aritmética de calendário — sem passar por fuso nenhum. */
export function somarDias(dia: string, quantos: number): string {
  const [ano, mes, data] = dia.split('-').map(Number);
  return new Date(Date.UTC(ano, mes - 1, data + quantos)).toISOString().slice(0, 10);
}

/**
 * `Intl.DateTimeFormat` lança `RangeError` para fuso que o navegador não conhece, e derrubaria a
 * tela inteira. Fuso errado é defeito pequeno; tela branca é o maior de todos (ver HoraDoSalao).
 */
export function formatar(quando: Date, fuso: string, opcoes: Intl.DateTimeFormatOptions,
  local = 'pt-BR'): string {
  try {
    return new Intl.DateTimeFormat(local, { ...opcoes, timeZone: fuso }).format(quando);
  } catch {
    return new Intl.DateTimeFormat(local, opcoes).format(quando);
  }
}
