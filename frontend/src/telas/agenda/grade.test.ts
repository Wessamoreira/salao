import { describe, expect, it } from 'vitest';
import {
  cargaEmMinutos, faixaDoDia, horaLocal, posicao, posicaoDoAgora, rotuloDeCarga, rotuloDeHora,
  PIXELS_POR_MINUTO,
} from './grade';

const SAO_PAULO = 'America/Sao_Paulo';

/** 2026-09-17, quinta. Instantes em UTC — o servidor sempre manda assim. */
const as = (hora: number, minuto = 0) =>
  new Date(Date.UTC(2026, 8, 17, hora + 3, minuto)).toISOString();

describe('hora local', () => {
  it('converte para o fuso do salão, não para o do navegador', () => {
    // O servidor manda UTC; a grade desenha em hora de parede. Sem a conversão pelo fuso
    // do salão, um salão em Manaus veria a agenda uma hora deslocada — e ninguém
    // percebe, porque a tela continua coerente consigo mesma.
    expect(horaLocal(as(14), SAO_PAULO)).toBe(14);
    expect(horaLocal(as(14), 'UTC')).toBe(17);
  });

  it('devolve a fração dos minutos', () => {
    expect(horaLocal(as(14, 30), SAO_PAULO)).toBe(14.5);
  });

  it('formata o rótulo com dois dígitos', () => {
    expect(rotuloDeHora(as(9, 5), SAO_PAULO)).toBe('09:05');
  });
});

describe('faixa do dia', () => {
  it('sem nada, mostra o horário comercial', () => {
    expect(faixaDoDia([], SAO_PAULO)).toEqual({ primeiraHora: 8, ultimaHora: 20 });
  });

  it('abre uma hora antes e uma depois do que existe', () => {
    // A folga é para o arrasto ter para onde ir: sem ela, mover o primeiro atendimento
    // meia hora para trás exigiria rolar uma tela que não rola.
    const faixa = faixaDoDia([{ inicio: as(9), fim: as(18) }], SAO_PAULO);

    expect(faixa).toEqual({ primeiraHora: 8, ultimaHora: 19 });
  });

  it('o fim na hora cheia não estende a faixa', () => {
    // 18h00 termina às 18h. Sem o arredondamento para cima só do que passa da hora,
    // toda jornada que fecha em ponto ganharia uma hora vazia no fim.
    const faixa = faixaDoDia([{ inicio: as(10), fim: as(18) }], SAO_PAULO);

    expect(faixa.ultimaHora).toBe(19);
  });

  it('um atendimento que passa da hora cheia estende', () => {
    const faixa = faixaDoDia([{ inicio: as(10), fim: as(18, 30) }], SAO_PAULO);

    expect(faixa.ultimaHora).toBe(20);
  });

  it('não passa da meia-noite', () => {
    const faixa = faixaDoDia([{ inicio: as(0), fim: as(23, 30) }], SAO_PAULO);

    expect(faixa.primeiraHora).toBe(0);
    expect(faixa.ultimaHora).toBe(24);
  });
});

describe('posição na pista', () => {
  it('o topo é a distância do início da faixa', () => {
    const faixa = { primeiraHora: 8, ultimaHora: 20 };

    const { topo, altura } = posicao(as(9), as(9, 45), faixa, SAO_PAULO);

    expect(topo).toBe(60 * PIXELS_POR_MINUTO);
    expect(altura).toBe(45 * PIXELS_POR_MINUTO);
  });

  it('um bloco muito curto ainda é clicável', () => {
    // Um bloco de 5 minutos daria 7px: fino demais para acertar com o mouse, e a
    // recepção clicaria no vizinho.
    const faixa = { primeiraHora: 8, ultimaHora: 20 };

    expect(posicao(as(9), as(9, 5), faixa, SAO_PAULO).altura).toBe(12);
  });
});

describe('linha do agora', () => {
  const faixa = { primeiraHora: 8, ultimaHora: 20 };

  it('marca onde estamos no dia de hoje', () => {
    // O achado mais sério da crítica de design: sem ela, um calendário é uma tabela.
    const agora = new Date(as(10, 24));

    expect(posicaoDoAgora(agora, '2026-09-17', faixa, SAO_PAULO))
      .toBeCloseTo(144 * PIXELS_POR_MINUTO, 5);
  });

  it('não aparece na agenda de outro dia', () => {
    // Sem isso, quem abre a agenda de amanhã vê a linha grudada no topo dizendo que
    // "agora" é 8h de um dia que não é hoje.
    const agora = new Date(as(10, 24));

    expect(posicaoDoAgora(agora, '2026-09-18', faixa, SAO_PAULO)).toBeNull();
  });

  it('não aparece quando o instante está fora da faixa mostrada', () => {
    const agora = new Date(as(6));

    expect(posicaoDoAgora(agora, '2026-09-17', faixa, SAO_PAULO)).toBeNull();
  });
});

describe('carga do dia', () => {
  it('conta os minutos da profissional, não a duração', () => {
    // Numa coloração de 100 minutos, 40 são pausa e ela está livre. Contar os 100 diria
    // que a agenda está cheia quando ainda cabe um corte no meio — e é justamente este
    // número que decide se dá para encaixar mais um.
    const dia = [{ minutosDoProfissional: 60 }, { minutosDoProfissional: 45 }];

    expect(cargaEmMinutos(dia)).toBe(105);
    expect(rotuloDeCarga(105)).toBe('1h45');
  });

  it('formata com dois dígitos nos minutos', () => {
    expect(rotuloDeCarga(305)).toBe('5h05');
    expect(rotuloDeCarga(120)).toBe('2h00');
  });
});
