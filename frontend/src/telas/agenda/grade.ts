/**
 * O cálculo da grade, separado do React.
 *
 * Converter instante em pixel é a parte que erra silenciosamente — um bloco meia hora
 * fora do lugar não quebra nada, só mostra a coisa errada. Aqui é função pura e tem
 * teste; dentro do componente seria verificável só olhando.
 */

/**
 * A densidade da grade — quantos minutos vale uma linha (R-UX-11, F1 do checklist do front).
 *
 * Um salão de três profissionais e um de quinze não cabem na mesma régua: no primeiro sobra
 * tela e o dia fica curto demais para clicar com precisão; no segundo, a mesma régua obriga a
 * rolar o dia inteiro para ver a tarde. Régua única não é neutra — ela é a régua de um
 * tamanho de salão só.
 *
 * O que muda é o TEMPO de uma linha, não a altura dela: a linha continua com 42px em toda
 * densidade, porque é a altura em que um nome e uma hora cabem sem apertar. Padrão é o que a
 * grade sempre teve (30 minutos, 84px por hora).
 */
export const DENSIDADES = { ampla: 15, padrao: 30, compacta: 60 } as const;

export type Densidade = keyof typeof DENSIDADES;

/** A altura de uma linha da grade, em pixels. Constante em toda densidade — ver acima. */
export const ALTURA_DA_LINHA = 42;

/** Quanto vale um minuto em pixels, na densidade escolhida. */
export function pixelsPorMinuto(densidade: Densidade): number {
  return ALTURA_DA_LINHA / DENSIDADES[densidade];
}

/**
 * As linhas da grade, em pixels a partir do topo.
 *
 * A linha da hora cheia é mais forte que as outras: sem essa distinção, a grade densa vira
 * papel pautado e ninguém acha as 14h sem contar linha por linha. Na densidade de 60 minutos
 * toda linha é hora cheia, e o resultado é o mesmo desenho com menos traço.
 */
export function linhasDaGrade(faixa: FaixaDoDia, densidade: Densidade)
  : Array<{ minuto: number; topo: number; cheia: boolean }> {
  const passo = DENSIDADES[densidade];
  const px = pixelsPorMinuto(densidade);
  const total = (faixa.ultimaHora - faixa.primeiraHora) * 60;
  const linhas = [];
  for (let minuto = 0; minuto <= total; minuto += passo) {
    linhas.push({ minuto, topo: minuto * px, cheia: minuto % 60 === 0 });
  }
  return linhas;
}

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
export function posicao(inicio: string, fim: string, faixa: FaixaDoDia, fuso: string,
                        px: number) {
  const de = horaLocal(inicio, fuso);
  const ate = horaLocal(fim, fuso);
  return {
    topo: (de - faixa.primeiraHora) * 60 * px,
    // Piso de 12px: na grade compacta um encaixe de 15 minutos mede 10px, e um bloco mais
    // fino que o dedo que precisa acertá-lo não é informação, é ruído.
    altura: Math.max((ate - de) * 60 * px, 12),
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
                               fuso: string, px: number): number | null {
  const diaDoAgora = new Intl.DateTimeFormat('en-CA', { timeZone: fuso }).format(agora);
  if (diaDoAgora !== dia) return null;

  const hora = horaLocal(agora.toISOString(), fuso);
  if (hora < faixa.primeiraHora || hora > faixa.ultimaHora) return null;
  return (hora - faixa.primeiraHora) * 60 * px;
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

/**
 * O caminho inverso: de pixel para instante.
 *
 * É o que faz o arrasto virar horário (RT-AGD-013). Mora aqui, junto do cálculo que desenha,
 * porque as duas direções precisam concordar — se `posicao` e isto discordarem em um passo, a
 * tarja é desenhada num lugar e gravada em outro, e ninguém percebe até a cliente chegar.
 *
 * O resultado é **preso ao passo da densidade**: numa grade de trinta minutos não existe 14h07,
 * e deixar o pixel decidir produziria horários que a recepção não escolheu e não consegue
 * repetir.
 */
export function instanteDoPixel(topo: number, faixa: FaixaDoDia, dia: string, fuso: string,
                                px: number, densidade: Densidade): string {
  const passo = DENSIDADES[densidade];
  const minutosNaPista = Math.max(0, topo) / px;
  const absoluto = faixa.primeiraHora * 60 + minutosNaPista;
  const preso = Math.round(absoluto / passo) * passo;
  return instanteLocal(dia, preso, fuso);
}

/**
 * O período que o arrasto desenhou, na ordem certa e com pelo menos um passo.
 *
 * Arrastar para cima é tão natural quanto para baixo, e um período invertido seria recusado pelo
 * domínio (`Janela` exige fim depois do início) com um erro que não diz nada a quem só arrastou
 * no sentido contrário. Um clique sem arrasto vira um passo — que é o que a pessoa quis dizer.
 */
export function periodoDoArrasto(topoA: number, topoB: number, faixa: FaixaDoDia, dia: string,
                                 fuso: string, px: number, densidade: Densidade)
  : { inicio: string; fim: string } {
  const de = Math.min(topoA, topoB);
  const ate = Math.max(topoA, topoB);
  const umPasso = DENSIDADES[densidade] * px;
  return {
    inicio: instanteDoPixel(de, faixa, dia, fuso, px, densidade),
    fim: instanteDoPixel(Math.max(ate, de + umPasso), faixa, dia, fuso, px, densidade),
  };
}

/**
 * O instante UTC que, lido no fuso do salão, é este dia nestes minutos.
 *
 * Duas passadas de propósito: o deslocamento do fuso depende do instante, e o instante depende do
 * deslocamento. Uma passada erra em uma hora nos dois domingos do ano em que o relógio muda — e
 * errar uma hora na agenda de um domingo de mudança é exatamente o tipo de defeito que ninguém
 * reproduz depois.
 */
function instanteLocal(dia: string, minutos: number, fuso: string): string {
  const [ano, mes, d] = dia.split('-').map(Number);
  const comoSeFosseUtc = Date.UTC(ano, mes - 1, d, 0, minutos);
  let instante = comoSeFosseUtc - deslocamentoEm(new Date(comoSeFosseUtc), fuso);
  instante = comoSeFosseUtc - deslocamentoEm(new Date(instante), fuso);
  return new Date(instante).toISOString();
}

/** Quanto o fuso do salão está adiantado em relação ao UTC, naquele instante, em milissegundos. */
function deslocamentoEm(quando: Date, fuso: string): number {
  const partes = new Intl.DateTimeFormat('en-US', {
    timeZone: fuso, hour12: false,
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  }).formatToParts(quando);
  const p = Object.fromEntries(partes.map((x) => [x.type, x.value]));
  // `% 24`: com `hour12: false` a meia-noite sai como "24" em alguns motores.
  const lidoComoUtc = Date.UTC(Number(p.year), Number(p.month) - 1, Number(p.day),
    Number(p.hour) % 24, Number(p.minute), Number(p.second));
  return lidoComoUtc - quando.getTime();
}
