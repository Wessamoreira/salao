import { beforeEach, describe, expect, it, vi } from 'vitest';
import { _limparRenovacaoEmCurso, api, ErroDaApi, ouvirEventos } from './http';
import { registrarEstadoDaApi } from '../pwa/conectado';
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
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: true });
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

  it('queda de rede chega como ErroDaApi em português, e não como o erro cru do navegador', async () => {
    // "Failed to fetch" na tela da recepção é o que acontece quando o erro sobe cru.
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));

    await expect(api('/v1/agendamentos')).rejects.toMatchObject({
      codigo: 'ER-INF-SEM_CONEXAO', status: 0,
    });
  });

  it('prazo de 12s estourado vira ER-INF-TEMPO_ESGOTADO', async () => {
    // Sem a tradução, a mensagem era "signal is aborted without reason", em inglês.
    vi.useFakeTimers();
    try {
      vi.stubGlobal('fetch', vi.fn((_url: string, init: RequestInit) =>
        new Promise((_resolver, rejeitar) => {
          init.signal?.addEventListener('abort',
            () => rejeitar(new DOMException('aborted', 'AbortError')));
        })));

      const chamada = expect(api('/v1/agendamentos')).rejects.toMatchObject({
        codigo: 'ER-INF-TEMPO_ESGOTADO', status: 0,
      });
      await vi.advanceTimersByTimeAsync(12_000);
      await chamada;
    } finally {
      vi.useRealTimers();
    }
  });

  it('cancelar o SSE não acende "Sem conexão"', async () => {
    /*
     * O SSE aborta ao desmontar, e o StrictMode desmonta todo efeito uma vez em dev. Antes, o
     * catch marcava a API como fora do ar em QUALQUER falha — inclusive nesse cancelamento
     * pedido por quem chamou —, e a faixa aparecia com a rede funcionando.
     */
    registrarEstadoDaApi(true);
    const avisos = vi.fn();
    window.addEventListener('salao:conectividade', avisos);
    vi.stubGlobal('fetch', vi.fn((_url: string, init: RequestInit) =>
      new Promise((_resolver, rejeitar) => {
        init.signal?.addEventListener('abort',
          () => rejeitar(new DOMException('aborted', 'AbortError')));
      })));

    const encerrar = ouvirEventos('/v1/agenda/eventos', () => {});
    encerrar();
    await new Promise((pronto) => setTimeout(pronto, 0));

    expect(avisos).not.toHaveBeenCalled();
    window.removeEventListener('salao:conectividade', avisos);
  });

  it('502 do proxy conta como servidor fora do ar, mesmo com o fetch tendo "funcionado"', async () => {
    // Backend caído atrás do Caddy não faz o fetch falhar: devolve 502. É o caso "Wi-Fi ok,
    // servidor fora" que `registrarEstadoDaApi` existe para perceber.
    registrarEstadoDaApi(true);
    const avisos = vi.fn();
    window.addEventListener('salao:conectividade', avisos);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response('Bad Gateway', { status: 502 })));

    await expect(api('/v1/agendamentos')).rejects.toMatchObject({ status: 502 });

    expect(avisos).toHaveBeenCalledOnce();
    window.removeEventListener('salao:conectividade', avisos);
    registrarEstadoDaApi(true);
  });

  it('recusa uma escrita offline na hora, sem enfileirar nem chamar a rede', async () => {
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: false });
    const buscar = vi.fn();
    vi.stubGlobal('fetch', buscar);

    await expect(api('/v1/agendamentos', {
      method: 'POST', body: JSON.stringify({}),
    })).rejects.toMatchObject({ codigo: 'ER-INF-SEM_CONEXAO', status: 0 });
    expect(buscar).not.toHaveBeenCalled();
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
