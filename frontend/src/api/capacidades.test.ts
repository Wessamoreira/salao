import { describe, expect, it } from 'vitest';
import { pode, type Capacidades } from './capacidades';

describe('perguntar por permissão', () => {
  const comPermissoes = (permissoes: string[]) =>
    ({ permissoes } as unknown as Capacidades);

  it('responde sim para o que está na lista', () => {
    expect(pode(comPermissoes(['agenda:write:all']), 'agenda:write:all')).toBe(true);
    expect(pode(comPermissoes(['agenda:read:all']), 'agenda:write:all')).toBe(false);
  });

  it('sem capacidades ainda, a resposta é não — que é o lado seguro', () => {
    expect(pode(undefined, 'agenda:write:all')).toBe(false);
  });

  it('capacidades SEM o campo de permissões não derruba a tela', () => {
    // Um backend de antes do campo, ou uma resposta pela metade, produzia
    // `undefined.includes` e a tela inteira sumia. É a mesma lição de `grupos` na casca: um
    // campo que o front usa para decidir o que desenhar não pode derrubar o que ele desenha.
    const semCampo = {} as unknown as Capacidades;

    expect(() => pode(semCampo, 'agenda:write:all')).not.toThrow();
    expect(pode(semCampo, 'agenda:write:all')).toBe(false);
  });
});
