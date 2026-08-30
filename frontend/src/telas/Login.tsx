import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { ErroDaApi, api } from '../api/http';
import { definirTokenAtual } from '../sessao/sessao';

interface RespostaDeLogin {
  tokenDeAcesso?: string;
  desafio?: string;
  segundoFatorNecessario?: boolean;
}

/**
 * O texto do erro é decidido AQUI, a partir do código estável — nunca do campo `detail`
 * da resposta. É o que permite ao backend melhorar a mensagem sem tocar no front, e
 * traduzir depois sem reescrever nada.
 */
const MENSAGENS: Record<string, string> = {
  'ER-IAM-CREDENCIAIS_INVALIDAS': 'E-mail ou senha incorretos.',
  'ER-IAM-ACESSO_BLOQUEADO': 'Muitas tentativas. Tente de novo em alguns minutos.',
  'ER-INF-DADOS_INVALIDOS': 'Confira o e-mail e a senha.',
};

export function Login() {
  const navegar = useNavigate();
  const [email, definirEmail] = useState('');
  const [senha, definirSenha] = useState('');
  const [erro, definirErro] = useState<string | null>(null);
  const [enviando, definirEnviando] = useState(false);

  async function entrar(evento: FormEvent) {
    evento.preventDefault();
    definirErro(null);
    definirEnviando(true);
    try {
      const resposta = await api<RespostaDeLogin>('/v1/auth/login', {
        method: 'POST',
        body: JSON.stringify({ email, senha }),
      });
      if (resposta.segundoFatorNecessario && resposta.desafio) {
        // O desafio sozinho não abre nada; ele só atesta que a senha foi conferida.
        navegar('/entrar/verificacao', { state: { desafio: resposta.desafio } });
        return;
      }
      definirTokenAtual(resposta.tokenDeAcesso ?? null);
      navegar('/');
    } catch (e) {
      definirErro(
        e instanceof ErroDaApi
          ? (MENSAGENS[e.codigo ?? ''] ?? 'Não foi possível entrar agora.')
          : 'Não foi possível entrar agora.',
      );
    } finally {
      definirEnviando(false);
    }
  }

  return (
    <main style={{ minHeight: '100dvh', display: 'grid', placeItems: 'center',
      background: 'var(--papel-fundo)', padding: 'var(--e-6)' }}>
      <form onSubmit={entrar} style={{ width: 360, background: 'var(--papel)',
        border: '1px solid var(--regua-forte)', borderRadius: 'var(--raio-2)',
        padding: 'var(--e-8)' }}>
        <h1 style={{ fontFamily: 'var(--fonte-identidade)', fontSize: 'var(--t-25)',
          margin: 0, fontWeight: 400, letterSpacing: '-0.01em' }}>Studio</h1>
        <p style={{ fontSize: 'var(--t-12)', color: 'var(--tinta-fraca)',
          margin: 'var(--e-1) 0 var(--e-6)' }}>Entre para ver a agenda de hoje.</p>

        <Campo rotulo="E-mail" tipo="email" valor={email} aoMudar={definirEmail} autoFocus />
        <Campo rotulo="Senha" tipo="password" valor={senha} aoMudar={definirSenha} />

        {erro && (
          <p role="alert" style={{ fontSize: 'var(--t-12)', color: 'var(--status-falta)',
            margin: '0 0 var(--e-4)' }}>{erro}</p>
        )}

        <button type="submit" disabled={enviando} style={{ width: '100%', border: 0,
          borderRadius: 'var(--raio-2)', padding: 'var(--e-3)', background: 'var(--tinta)',
          color: 'var(--papel)', fontFamily: 'inherit', fontSize: 'var(--t-14)',
          fontWeight: 600, cursor: enviando ? 'wait' : 'pointer' }}>
          {enviando ? 'Entrando…' : 'Entrar'}
        </button>
      </form>
    </main>
  );
}

function Campo({ rotulo, tipo, valor, aoMudar, autoFocus }: {
  rotulo: string; tipo: string; valor: string;
  aoMudar: (v: string) => void; autoFocus?: boolean;
}) {
  return (
    <label style={{ display: 'block', marginBottom: 'var(--e-4)' }}>
      <span style={{ display: 'block', fontSize: 'var(--t-11)', letterSpacing: '0.08em',
        textTransform: 'uppercase', color: 'var(--tinta-fraca)', fontWeight: 600,
        marginBottom: 'var(--e-1)' }}>{rotulo}</span>
      <input type={tipo} value={valor} autoFocus={autoFocus} required
        onChange={(e) => aoMudar(e.target.value)}
        style={{ width: '100%', boxSizing: 'border-box', padding: 'var(--e-2) var(--e-3)',
          border: '1px solid var(--regua-forte)', borderRadius: 'var(--raio-2)',
          background: 'var(--papel)', fontFamily: 'inherit', fontSize: 'var(--t-14)',
          color: 'var(--tinta)' }} />
    </label>
  );
}
