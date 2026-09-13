import { useRef, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { ErroDaApi, api } from '../api/http';
import { definirTokenAtual } from '../sessao/sessao';
import { Autenticacao } from './Autenticacao';
import { Campo } from './Campo';

interface RespostaDeLogin {
  tokenDeAcesso?: string;
  desafio?: string;
  segundoFatorNecessario?: boolean;
}

/**
 * O texto do erro é decidido AQUI, a partir do código estável — nunca do campo `detail`
 * da resposta. É o que permite ao backend melhorar a mensagem sem tocar no front, e
 * traduzir depois sem reescrever nada.
 *
 * Nenhuma mensagem pede desculpa e nenhuma é vaga: cada uma diz o que aconteceu e o que
 * fazer em seguida. "Ocorreu um erro" manda a recepção tentar de novo às cegas.
 */
const MENSAGENS: Record<string, string> = {
  'ER-IAM-CREDENCIAIS_INVALIDAS': 'E-mail ou senha incorretos. Confira e tente de novo.',
  'ER-IAM-ACESSO_BLOQUEADO': 'Tentativas demais. Espere alguns minutos antes de tentar de novo.',
  'ER-INF-DADOS_INVALIDOS': 'Confira o e-mail e a senha.',
  'ER-INF-SEM_CONEXAO': 'Sem conexão. Entrar exige internet.',
  'ER-INF-TEMPO_ESGOTADO': 'O servidor demorou para responder. Tente de novo em instantes.',
};

export function Login() {
  const navegar = useNavigate();
  const [email, definirEmail] = useState('');
  const [senha, definirSenha] = useState('');
  const [erro, definirErro] = useState<string | null>(null);
  const [enviando, definirEnviando] = useState(false);
  const formulario = useRef<HTMLFormElement>(null);

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
          ? (MENSAGENS[e.codigo ?? ''] ?? 'Não foi possível entrar agora. Tente de novo.')
          : 'Não foi possível entrar agora. Tente de novo.',
      );
      // O foco volta para o primeiro campo: quem errou a senha vai digitar de novo, e
      // ler o aviso sem poder agir é meio caminho.
      formulario.current?.querySelector<HTMLInputElement>('input')?.focus();
    } finally {
      definirEnviando(false);
    }
  }

  return (
    <Autenticacao titulo="Entrar" apoio="Use a conta do salão.">
      <form ref={formulario} className="auth-formulario" onSubmit={entrar} noValidate>
        {erro && <p className="auth-erro" role="alert">{erro}</p>}

        <Campo
          rotulo="E-mail"
          type="email"
          name="email"
          autoComplete="username"
          inputMode="email"
          autoCapitalize="none"
          spellCheck={false}
          required
          autoFocus
          value={email}
          onChange={(e) => definirEmail(e.target.value)}
        />
        <Campo
          rotulo="Senha"
          type="password"
          name="senha"
          autoComplete="current-password"
          required
          value={senha}
          onChange={(e) => definirSenha(e.target.value)}
        />

        <button className="auth-acao" type="submit" disabled={enviando}>
          {enviando ? 'Entrando…' : 'Entrar'}
        </button>
      </form>
    </Autenticacao>
  );
}
