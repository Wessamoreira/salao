import { definirTokenAtual, tokenAtual } from '../sessao/sessao';

export class ErroDaApi extends Error {
  readonly status: number;
  /** Código estável do catálogo (`ER-IAM-...`). O front mapeia ISTO, nunca o texto. */
  readonly codigo: string | null;

  constructor(status: number, codigo: string | null, mensagem: string) {
    super(mensagem);
    this.status = status;
    this.codigo = codigo;
  }
}

interface RespostaDeSessao {
  tokenDeAcesso: string;
}

/**
 * Renovação em voo. **Uma por vez, e isto não é otimização.**
 *
 * O refresh é rotativo com detecção de reuso (RN-IAM-007): usar um token já trocado revoga a
 * FAMÍLIA inteira. Se três requisições receberem 401 juntas e cada uma disparar seu refresh,
 * as três apresentam o mesmo cookie — a primeira rotaciona, e as outras duas são classificadas
 * como reuso. O backend derruba a sessão, corretamente, por um acidente de concorrência do
 * front.
 *
 * A janela de tolerância de 10s no servidor perdoa esse caso hoje, mas depender dela seria
 * construir sobre uma folga que existe para rede instável, não para descuido nosso.
 */
let renovacaoEmCurso: Promise<string | null> | null = null;

async function renovarSessao(): Promise<string | null> {
  if (!renovacaoEmCurso) {
    renovacaoEmCurso = (async () => {
      try {
        const resposta = await fetch('/api/v1/auth/refresh', {
          method: 'POST',
          credentials: 'include', // o cookie HttpOnly é a credencial aqui
        });
        if (!resposta.ok) return null;
        const corpo = (await resposta.json()) as RespostaDeSessao;
        definirTokenAtual(corpo.tokenDeAcesso);
        return corpo.tokenDeAcesso;
      } catch {
        return null;
      } finally {
        renovacaoEmCurso = null;
      }
    })();
  }
  return renovacaoEmCurso;
}

async function enviar(caminho: string, init: RequestInit, token: string | null) {
  return fetch(`/api${caminho}`, {
    ...init,
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init.headers,
    },
  });
}

/**
 * Toda chamada à API passa por aqui.
 *
 * Em 401, tenta renovar UMA vez e repete. Falhou a renovação, a sessão acabou de verdade:
 * limpa o token e deixa o roteador levar ao login — sem laço de tentativa, que só transformaria
 * uma sessão expirada numa rajada de requisições.
 */
export async function api<T>(caminho: string, init: RequestInit = {}): Promise<T> {
  let resposta = await enviar(caminho, init, tokenAtual());

  if (resposta.status === 401 && !caminho.startsWith('/v1/auth/')) {
    const novoToken = await renovarSessao();
    if (!novoToken) {
      definirTokenAtual(null);
      throw new ErroDaApi(401, 'ER-IAM-SESSAO_EXPIRADA', 'Sessão expirada.');
    }
    resposta = await enviar(caminho, init, novoToken);
  }

  if (resposta.status === 204) return undefined as T;

  const corpo = await resposta.json().catch(() => null);
  if (!resposta.ok) {
    throw new ErroDaApi(
      resposta.status,
      corpo?.codigo ?? null,
      corpo?.detail ?? 'Não foi possível concluir a operação.',
    );
  }
  return corpo as T;
}

/** Restaura a sessão no boot trocando o cookie por um token novo. */
export async function restaurarSessao(): Promise<boolean> {
  return (await renovarSessao()) !== null;
}

/** Exposto só para teste: zera a renovação em voo entre casos. */
export function _limparRenovacaoEmCurso() {
  renovacaoEmCurso = null;
}
