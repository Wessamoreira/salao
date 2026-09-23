import { describe, expect, it } from 'vitest';
import { ehDiaCivil, periodoDe } from './tempo';

describe('período do salão', () => {
  it('termina no dia do salão, não no da máquina', () => {
    // 02:30 UTC do dia 15 ainda é 23:30 do dia 14 em São Paulo.
    expect(periodoDe(7, 'America/Sao_Paulo', new Date('2026-09-15T02:30:00Z')))
      .toEqual({ de: '2026-09-08', ate: '2026-09-14' });
  });

  it('hoje é um dia só, e a virada de mês não quebra a conta', () => {
    expect(periodoDe(1, 'UTC', new Date('2026-03-01T10:00:00Z')))
      .toEqual({ de: '2026-03-01', ate: '2026-03-01' });
    expect(periodoDe(2, 'UTC', new Date('2026-03-01T10:00:00Z')))
      .toEqual({ de: '2026-02-28', ate: '2026-03-01' });
  });

  it('fuso que o navegador não conhece não derruba a tela', () => {
    expect(() => periodoDe(7, 'Marte/Olympus', new Date())).not.toThrow();
  });
});

describe('dia civil vindo de fora', () => {
  it('aceita só o que é dia de calendário de verdade', () => {
    // O dia virou endereço (`/agenda?dia=…`): ele chega de link colado e de favorito
    // antigo. 31 de fevereiro passa em qualquer regex de formato e não existe.
    expect(ehDiaCivil('2026-09-17')).toBe(true);
    expect(ehDiaCivil('2024-02-29')).toBe(true);
    expect(ehDiaCivil('2026-02-31')).toBe(false);
    expect(ehDiaCivil('2026-13-01')).toBe(false);
  });

  it('recusa o que nem parece dia, sem lançar', () => {
    expect(ehDiaCivil(null)).toBe(false);
    expect(ehDiaCivil('')).toBe(false);
    expect(ehDiaCivil('hoje')).toBe(false);
    expect(ehDiaCivil('17/09/2026')).toBe(false);
    expect(ehDiaCivil('2026-09-17T10:00:00Z')).toBe(false);
  });
});
