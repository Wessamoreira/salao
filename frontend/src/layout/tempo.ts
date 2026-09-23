/*
 * O calendário do salão — nasceu na revisão do agente (RT-CNV-007) e serve a toda tela que mostra
 * data ou hora do salão.
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
 * O texto é um dia civil de verdade?
 *
 * Existe porque dia passou a ser endereço: `/agenda?dia=…` vem de um link colado, de um
 * favorito antigo, de alguém editando a barra. `2026-02-31` casa com qualquer regex de
 * formato e **não existe** — e `Intl` não devolve nada para uma data inválida: ele lança,
 * e leva a tela junto. Quem lê um dia de fora do sistema confere aqui antes de usar.
 */
export function ehDiaCivil(texto: string | null): texto is string {
  if (!texto || !/^\d{4}-\d{2}-\d{2}$/.test(texto)) return false;
  const data = new Date(`${texto}T00:00:00Z`);
  // A ida e volta é o que reprova 31/02: o `Date` normaliza para 03/03 e deixa de casar.
  return !Number.isNaN(data.getTime()) && data.toISOString().slice(0, 10) === texto;
}

/** O fuso da máquina — o melhor palpite enquanto o do salão não chegou do servidor. */
export function fusoDoNavegador(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
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
