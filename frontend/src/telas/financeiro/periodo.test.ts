import { describe, expect, it } from 'vitest';
import { deslocar, periodoPorExtenso, ultimoPeriodoFechado } from '../../api/financeiro';

describe('o último período que terminou', () => {
  it('mensal: no dia 5 de abril, o período é março inteiro', () => {
    // Fecha-se o mês depois que ele acaba. Abrir em "mês corrente" ofereceria fechar um período
    // pela metade, que é o erro caro.
    expect(ultimoPeriodoFechado('2026-04-05', 'MENSAL'))
      .toEqual({ de: '2026-03-01', ate: '2026-03-31' });
  });

  it('mensal: no último dia do mês, ainda é o mês anterior — este não acabou', () => {
    expect(ultimoPeriodoFechado('2026-04-30', 'MENSAL'))
      .toEqual({ de: '2026-03-01', ate: '2026-03-31' });
  });

  it('mensal: em 1º de janeiro, volta para dezembro do ano anterior', () => {
    expect(ultimoPeriodoFechado('2026-01-01', 'MENSAL'))
      .toEqual({ de: '2025-12-01', ate: '2025-12-31' });
  });

  it('mensal: fevereiro bissexto termina no dia 29', () => {
    expect(ultimoPeriodoFechado('2028-03-10', 'MENSAL'))
      .toEqual({ de: '2028-02-01', ate: '2028-02-29' });
  });

  it('quinzenal: no dia 20, a quinzena fechada é a primeira do mês', () => {
    expect(ultimoPeriodoFechado('2026-04-20', 'QUINZENAL'))
      .toEqual({ de: '2026-04-01', ate: '2026-04-15' });
  });

  it('quinzenal: no dia 10, é a segunda quinzena do mês passado', () => {
    expect(ultimoPeriodoFechado('2026-04-10', 'QUINZENAL'))
      .toEqual({ de: '2026-03-16', ate: '2026-03-31' });
  });

  it('semanal: na quarta, a semana fechada é a de segunda a domingo anterior', () => {
    // 2026-04-15 é uma quarta; a semana anterior vai de 06/04 (segunda) a 12/04 (domingo).
    expect(ultimoPeriodoFechado('2026-04-15', 'SEMANAL'))
      .toEqual({ de: '2026-04-06', ate: '2026-04-12' });
  });

  it('semanal: na segunda, a semana que acabou ontem', () => {
    expect(ultimoPeriodoFechado('2026-04-13', 'SEMANAL'))
      .toEqual({ de: '2026-04-06', ate: '2026-04-12' });
  });
});

describe('navegar sem digitar data', () => {
  it('um mês inteiro anda um MÊS, e não o número de dias dele', () => {
    // Deslocar março por trinta e um dias cairia em 29/01 a 28/02, que não é "o mês anterior"
    // e não é nada — e é o mês inteiro que a maioria dos salões fecha.
    expect(deslocar({ de: '2026-03-01', ate: '2026-03-31' }, -1))
      .toEqual({ de: '2026-02-01', ate: '2026-02-28' });
    expect(deslocar({ de: '2026-01-01', ate: '2026-01-31' }, 1))
      .toEqual({ de: '2026-02-01', ate: '2026-02-28' });
  });

  it('um recorte qualquer anda o próprio tamanho', () => {
    expect(deslocar({ de: '2026-03-05', ate: '2026-03-11' }, -1))
      .toEqual({ de: '2026-02-26', ate: '2026-03-04' });
  });

  it('a quinzena anda quinze dias', () => {
    expect(deslocar({ de: '2026-04-01', ate: '2026-04-15' }, 1))
      .toEqual({ de: '2026-04-16', ate: '2026-04-30' });
  });

  it('ir e voltar devolve o mesmo período', () => {
    const periodo = { de: '2026-04-06', ate: '2026-04-12' };

    expect(deslocar(deslocar(periodo, 1), -1)).toEqual(periodo);
  });
});

describe('o período por extenso', () => {
  it('dentro do mesmo mês, o mês aparece uma vez', () => {
    expect(periodoPorExtenso({ de: '2026-09-01', ate: '2026-09-30' }))
      .toBe('1 — 30 de setembro de 2026');
  });

  it('atravessando o mês, os dois meses aparecem', () => {
    expect(periodoPorExtenso({ de: '2026-09-28', ate: '2026-10-04' }))
      .toBe('28 de set — 4 de out de 2026');
  });
});
