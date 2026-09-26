import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ErroDaApi, api } from '../../api/http';
import { definirTokenAtual } from '../../sessao/sessao';
import { Autenticacao } from '../Autenticacao';
import { Campo } from '../Campo';

/**
 * RT-IAM-009 — trocar a própria senha.
 *
 * <h2>Existia no servidor e não tinha por onde ser feito</h2>
 *
 * {@code POST /v1/me/senha} está entregue desde a Fase 0. Sem esta tela, ninguém no sistema
 * conseguia trocar a própria senha — nem a recepção, que não entra em Acessos.
 *
 * <h2>Trocar a senha é sair</h2>
 *
 * Todas as sessões caem, inclusive esta (RN-IAM-016): quem troca a senha está cortando um
 * acesso que suspeita existir, e manter as sessões abertas manteria justamente o que se quer
 * cortar. A tela diz isso antes, e leva de volta à entrada depois — em vez de deixar a próxima
 * ação falhar com "sessão expirada" e parecer defeito.
 */
export function MinhaSenha() {
  const navegar = useNavigate();
  const [atual, definirAtual] = useState('');
  const [nova, definirNova] = useState('');
  const [repetida, definirRepetida] = useState('');
  const [erro, definirErro] = useState<string | null>(null);
  const [trocada, definirTrocada] = useState(false);
  const [enviando, definirEnviando] = useState(false);

  const curta = nova.length > 0 && nova.length < 12;
  const diferentes = repetida.length > 0 && nova !== repetida;
  const podeTrocar = atual !== '' && nova.length >= 12 && nova === repetida && !enviando;

  async function trocar() {
    if (!podeTrocar) return;
    definirErro(null);
    definirEnviando(true);
    try {
      await api<void>('/v1/me/senha', {
        method: 'POST', body: JSON.stringify({ senhaAtual: atual, senhaNova: nova }),
      });
      definirTrocada(true);
    } catch (e) {
      definirErro(e instanceof ErroDaApi
        ? e.message : 'Não foi possível trocar a senha agora.');
    } finally {
      definirEnviando(false);
    }
  }

  if (trocada) {
    return (
      <Autenticacao
        titulo="Senha trocada"
        apoio="Todas as sessões foram encerradas, inclusive esta. Entre de novo com a senha nova."
      >
        <div className="auth-formulario">
          <button className="auth-acao" type="button" autoFocus
            onClick={() => { definirTokenAtual(null); navegar('/entrar', { replace: true }); }}>
            Entrar de novo
          </button>
        </div>
      </Autenticacao>
    );
  }

  return (
    <Autenticacao
      titulo="Trocar minha senha"
      apoio="Ao trocar, você sai de todos os aparelhos onde esta conta está aberta."
    >
      <form className="auth-formulario" onSubmit={(e) => { e.preventDefault(); void trocar(); }}>
        {/* `autoComplete` certo em cada um: sem isso o gerenciador de senhas guarda a antiga
            no lugar da nova, e a pessoa fica trancada por causa de um atributo. */}
        <Campo rotulo="Senha atual" type="password" name="senha-atual"
          autoComplete="current-password" autoFocus
          value={atual} onChange={(e) => definirAtual(e.target.value)} />
        <Campo rotulo="Senha nova" type="password" name="senha-nova"
          autoComplete="new-password" dica="Ao menos 12 caracteres."
          invalido={curta} idDoErro="senha-problema"
          value={nova} onChange={(e) => definirNova(e.target.value)} />
        <Campo rotulo="Repita a senha nova" type="password" name="senha-nova-repetida"
          autoComplete="new-password" invalido={diferentes} idDoErro="senha-problema"
          value={repetida} onChange={(e) => definirRepetida(e.target.value)} />

        {/* Um lugar só para o problema, seja de comprimento, de digitação ou do servidor:
            três mensagens simultâneas sobre a mesma senha é ruído. */}
        {(curta || diferentes || erro) && (
          <p id="senha-problema" className="auth-erro" role="alert">
            {erro ?? (curta ? 'A senha nova precisa de ao menos 12 caracteres.'
              : 'As duas senhas novas não são iguais.')}
          </p>
        )}

        <button className="auth-acao" type="submit" disabled={!podeTrocar}>
          {enviando ? 'Trocando…' : 'Trocar senha'}
        </button>
      </form>
    </Autenticacao>
  );
}
