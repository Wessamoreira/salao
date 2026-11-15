import { useRef, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { ErroDaApi, api } from '../api/http';
import { definirTokenAtual } from '../sessao/sessao';
import { Autenticacao } from './Autenticacao';
import { saudacao } from './saudacao';
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

/**
 * Os dois erros em que o problema está no que foi digitado — e só eles marcam os campos.
 *
 * "Tentativas demais" e "sem conexão" não são culpa do e-mail nem da senha: marcar o campo
 * ali mandaria corrigir o que está certo.
 */
const DE_CREDENCIAL = new Set(['ER-IAM-CREDENCIAIS_INVALIDAS', 'ER-INF-DADOS_INVALIDOS']);

/** Um id fixo: o parágrafo do erro é um só, e os dois campos apontam para ele. */
const ID_DO_ERRO = 'login-erro';

export function Login() {
  const navegar = useNavigate();
  const [email, definirEmail] = useState('');
  const [senha, definirSenha] = useState('');
  const [erro, definirErro] = useState<{ texto: string; deCredencial: boolean } | null>(null);
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
      const codigo = e instanceof ErroDaApi ? (e.codigo ?? '') : '';
      definirErro({
        texto: MENSAGENS[codigo] ?? 'Não foi possível entrar agora. Tente de novo.',
        deCredencial: DE_CREDENCIAL.has(codigo),
      });
      /*
       * O foco vai para a SENHA, não para o e-mail.
       *
       * O servidor não diz qual dos dois está errado, de propósito — e quase sempre é a
       * senha: o e-mail a recepção digita certo, ou o gerenciador preenche. Mandar o foco
       * para o começo obrigaria a pular um campo que já estava bom, toda vez.
       *
       * Ler o aviso sem poder agir é meio caminho: o campo já vem selecionado, então a
       * próxima tecla substitui o que estava lá.
       */
      if (DE_CREDENCIAL.has(codigo)) {
        const campoDaSenha = formulario.current
          ?.querySelector<HTMLInputElement>('input[name="senha"]');
        campoDaSenha?.focus();
        campoDaSenha?.select();
      } else {
        formulario.current?.querySelector<HTMLInputElement>('input')?.focus();
      }
    } finally {
      definirEnviando(false);
    }
  }

  return (
    <Autenticacao titulo={saudacao()} apoio="Entre com a conta do salão.">
      <form ref={formulario} className="auth-formulario" onSubmit={entrar} noValidate>
        {erro && <p id={ID_DO_ERRO} className="auth-erro" role="alert">{erro.texto}</p>}

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
          invalido={erro?.deCredencial}
          idDoErro={ID_DO_ERRO}
          value={email}
          onChange={(e) => definirEmail(e.target.value)}
        />
        <Campo
          rotulo="Senha"
          type="password"
          name="senha"
          autoComplete="current-password"
          required
          invalido={erro?.deCredencial}
          idDoErro={ID_DO_ERRO}
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
