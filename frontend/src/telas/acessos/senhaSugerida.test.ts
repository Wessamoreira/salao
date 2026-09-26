import { describe, expect, it } from 'vitest';
import { senhaSugerida } from './senhaSugerida';

describe('a senha que a tela sugere', () => {
  it('passa do mínimo que o servidor exige, mesmo no pior sorteio', () => {
    // O servidor recusa senha com menos de 12 caracteres. Uma frase com as quatro palavras mais
    // curtas da lista ainda precisa passar — senão a recusa apareceria de vez em quando, sem
    // ninguém entender por quê.
    for (let i = 0; i < 300; i += 1) {
      expect(senhaSugerida().length).toBeGreaterThanOrEqual(12);
    }
  });

  it('só tem o que se dita ao telefone: letras sem acento, dígitos e hífen', () => {
    for (let i = 0; i < 100; i += 1) {
      expect(senhaSugerida()).toMatch(/^[a-z]+(-[a-z]+){3}-\d{2}$/);
    }
  });

  it('não repete: sortear é o ponto', () => {
    const geradas = new Set(Array.from({ length: 200 }, () => senhaSugerida()));

    // 96⁴ × 100 combinações: duas iguais em duzentas seria defeito do sorteio, não azar.
    expect(geradas.size).toBe(200);
  });
});
