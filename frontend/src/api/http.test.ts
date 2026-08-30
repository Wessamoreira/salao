import { beforeEach, describe, expect, it, vi } from 'vitest';
import { _limparRenovacaoEmCurso, api, ErroDaApi } from './http';
import { definirTokenAtual, tokenAtual } from '../sessao/sessao';

function resposta(status: number, corpo: unknown = {}) {
  return {
    status,
    ok: status >= 200 && status < 300,
    json: async () => corpo,
  } as Response;
}

describe('cliente HTTP', () => {
  beforeEach(() => {
    _limparRenovacaoEmCurso();
    definirTokenAtual('token-velho');
    vi.restoreAllMocks();
  });

  it('renova UMA vez quando várias chamadas recebem 401 juntas', async () => {
    // O refresh é rotativo com detecção de reuso (RN-IAM-007): três renovações paralelas
    // apresentariam o MESMO cookie, e as duas perdedoras seriam lidas como reuso. O backend
    // revogaria a família inteira e deslogaria o usuário — por um descuido nosso, não por
    // ataque. Este teste é o que impede essa regressão.
    let renovacoes = 0;
    const buscar = vi.fn(async (url: string) => {
      if (url.includes('/auth/refresh')) {
        renovacoes += 1;
        return resposta(200, { tokenDeAcesso: 'token-novo' });
      }
      return tokenAtual() === 'token-novo' ? resposta(200, { ok: true }) : resposta(401, {});
    });
    vi.stubGlobal('fetch', buscar);

    await Promise.all([
      api('/v1/me/capabilities'),
      api('/v1/agendamentos'),
      api('/v1/clientes'),
    ]);

    expect(renovacoes).toBe(1);
    expect(tokenAtual()).toBe('token-novo');
  });

  it('repete a chamada com o token novo depois de renovar', async () => {
    const buscar = vi.fn(async (url: string) =>
      url.includes('/auth/refresh')
        ? resposta(200, { tokenDeAcesso: 'token-novo' })
        : (tokenAtual() === 'token-novo' ? resposta(200, { valor: 42 }) : resposta(401, {})));
    vi.stubGlobal('fetch', buscar);

    await expect(api<{ valor: number }>('/v1/algo')).resolves.toEqual({ valor: 42 });
  });

  it('não tenta renovar em loop quando a renovação falha', async () => {
    // Sessão expirada de verdade. Insistir transformaria isso numa rajada de requisições
    // contra um servidor que já respondeu que não.
    let chamadas = 0;
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      chamadas += 1;
      return url.includes('/auth/refresh') ? resposta(401, {}) : resposta(401, {});
    }));

    await expect(api('/v1/algo')).rejects.toBeInstanceOf(ErroDaApi);
    expect(chamadas).toBe(2); // a original e uma tentativa de renovar
    expect(tokenAtual()).toBeNull();
  });

  it('não tenta renovar a partir das próprias rotas de autenticação', async () => {
    // Um login recusado é 401 legítimo. Tentar renovar ali criaria uma chamada inútil e
    // um erro confuso no lugar de "e-mail ou senha incorretos".
    let renovacoes = 0;
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.includes('/auth/refresh')) renovacoes += 1;
      return resposta(401, { codigo: 'ER-IAM-CREDENCIAIS_INVALIDAS', detail: 'x' });
    }));

    await expect(api('/v1/auth/login', { method: 'POST' })).rejects.toBeInstanceOf(ErroDaApi);
    expect(renovacoes).toBe(0);
  });

  it('preserva o código estável do erro, que é o que a tela mapeia', async () => {
    vi.stubGlobal('fetch', vi.fn(async () =>
      resposta(422, { codigo: 'ER-IAM-DADOS_INVALIDOS', detail: 'texto do servidor' })));

    await api('/v1/algo').catch((e: ErroDaApi) => {
      expect(e.codigo).toBe('ER-IAM-DADOS_INVALIDOS');
      expect(e.status).toBe(422);
    });
  });
});

describe('sessão', () => {
  it('nunca escreve o token em localStorage', () => {
    // Inviolável nº 6: localStorage é XSS servido de bandeja. O token vive em memória e
    // o refresh, em cookie HttpOnly que o JavaScript não alcança.
    definirTokenAtual('segredo');
    const guardados = Object.keys(localStorage).map((k) => localStorage.getItem(k));

    expect(guardados.join(' ')).not.toContain('segredo');
  });
});
