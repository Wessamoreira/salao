import { definirTokenAtual, tokenAtual } from '../sessao/sessao';
import { registrarEstadoDaApi } from '../pwa/conectado';

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
        const resposta = await enviar('/v1/auth/refresh', { method: 'POST' }, null);
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

/**
 * Respostas que são o PROXY dizendo que o backend não respondeu.
 *
 * Em produção o Caddy fica na frente, e em dev o Vite: com o backend fora do ar, o `fetch` não
 * falha — ele recebe um 502 do proxy. Sem esta lista, "Wi-Fi conectado com o servidor fora do ar",
 * que é exatamente o caso que `registrarEstadoDaApi` existe para perceber, passaria despercebido.
 */
const SERVIDOR_FORA = new Set([502, 503, 504]);

async function enviar(caminho: string, init: RequestInit, token: string | null) {
  // Um request comum não pode deixar a interface esperando para sempre. O SSE fornece
  // seu próprio AbortSignal e, por ser conexão longa, fica deliberadamente sem prazo.
  const controlador = init.signal ? null : new AbortController();
  const prazo = controlador
    ? window.setTimeout(() => controlador.abort(), 12_000)
    : null;
  try {
    const resposta = await fetch(`/api${caminho}`, {
      ...init,
      signal: init.signal ?? controlador?.signal,
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...init.headers,
      },
    });
    registrarEstadoDaApi(!SERVIDOR_FORA.has(resposta.status));
    return resposta;
  } catch (erro) {
    // Cancelamento pedido por quem chamou não é queda de rede. O SSE aborta ao desmontar — e o
    // StrictMode monta e desmonta todo efeito uma vez em dev —, e marcar a API como fora do ar
    // ali acendia "Sem conexão" com a rede funcionando. O erro original sobe intacto: quem
    // cancelou espera exatamente um AbortError.
    if (init.signal?.aborted) throw erro;

    registrarEstadoDaApi(false);
    // O resto vira ErroDaApi com texto em português. Sem isso a tela recebia o erro cru do
    // navegador — "Failed to fetch", ou "signal is aborted without reason" quando o prazo de
    // 12s estourava —, e quem mostra `erro.message` mostraria isso para a recepção.
    if (controlador?.signal.aborted) {
      throw new ErroDaApi(0, 'ER-INF-TEMPO_ESGOTADO',
        'O servidor demorou demais para responder. Verifique a conexão e tente de novo.');
    }
    throw new ErroDaApi(0, 'ER-INF-SEM_CONEXAO',
      'Sem conexão com o servidor. Verifique a internet e tente de novo.');
  } finally {
    if (prazo !== null) window.clearTimeout(prazo);
  }
}

/**
 * Toda chamada à API passa por aqui.
 *
 * Em 401, tenta renovar UMA vez e repete. Falhou a renovação, a sessão acabou de verdade:
 * limpa o token e deixa o roteador levar ao login — sem laço de tentativa, que só transformaria
 * uma sessão expirada numa rajada de requisições.
 */
export async function api<T>(caminho: string, init: RequestInit = {}): Promise<T> {
  const metodo = (init.method ?? 'GET').toUpperCase();
  if (metodo !== 'GET' && typeof navigator !== 'undefined' && !navigator.onLine) {
    throw new ErroDaApi(
      0,
      'ER-INF-SEM_CONEXAO',
      'Sem conexão. A alteração não foi enviada; tente novamente quando a internet voltar.',
    );
  }

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
/**
 * RT-AGD-012 — o stream de avisos do painel.
 *
 * `fetch` e não `EventSource`, e a razão é de segurança: `EventSource` não manda
 * cabeçalho, então autenticar exigiria o token na query string — que fica em log de
 * proxy, em histórico e em Referer. A regra do projeto é explícita: nunca dado sensível
 * em parâmetro de URL.
 *
 * O corpo de um SSE é texto simples separado por linha em branco. Ler com o
 * `ReadableStream` do fetch custa vinte linhas e resolve o problema inteiro.
 *
 * @returns função que encerra o stream
 */
export function ouvirEventos(caminho: string, aoAviso: () => void): () => void {
  const cancelador = new AbortController();

  (async () => {
    try {
      const resposta = await enviar(caminho, {
        headers: { Accept: 'text/event-stream' },
        signal: cancelador.signal,
      }, tokenAtual());
      if (!resposta.ok || !resposta.body) return;

      const leitor = resposta.body.getReader();
      const decodificador = new TextDecoder();
      let restante = '';

      for (;;) {
        const { done, value } = await leitor.read();
        if (done) break;
        restante += decodificador.decode(value, { stream: true });

        // Cada evento termina em linha em branco. O que sobra é evento pela metade e
        // fica para a próxima leitura — sem isso, um evento partido no meio de um
        // pacote TCP viraria dois avisos truncados.
        const partes = restante.split('\n\n');
        restante = partes.pop() ?? '';
        for (const parte of partes) {
          if (parte.includes('event: agenda')) aoAviso();
        }
      }
    } catch {
      // Rede caiu ou a aba fechou. O painel volta a pedir o retrato quando reconectar;
      // não há o que registrar aqui além de ruído.
    }
  })();

  return () => cancelador.abort();
}

export async function restaurarSessao(): Promise<boolean> {
  return (await renovarSessao()) !== null;
}

/** Exposto só para teste: zera a renovação em voo entre casos. */
export function _limparRenovacaoEmCurso() {
  renovacaoEmCurso = null;
}
