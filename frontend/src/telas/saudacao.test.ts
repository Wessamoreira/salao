import { describe, expect, it } from 'vitest';
import { saudacao } from './saudacao';

describe('a saudação da porta', () => {
  const as = (hora: number) => new Date(2026, 10, 15, hora, 30);

  it('segue o costume, não o relógio astronômico: meio-dia é tarde, 18h é noite', () => {
    expect(saudacao(as(5))).toBe('Bom dia.');
    expect(saudacao(as(11))).toBe('Bom dia.');
    expect(saudacao(as(12))).toBe('Boa tarde.');
    expect(saudacao(as(17))).toBe('Boa tarde.');
    expect(saudacao(as(18))).toBe('Boa noite.');
    expect(saudacao(as(2))).toBe('Boa noite.');
  });
});
