import { useState } from 'react';
import { Link } from 'react-router-dom';
import {
  useDefinirAtivacao, useDefinirPerfil, useDefinirSenha, useDefinirTelefoneDoUsuario,
  useEditarUsuario, useResetarSegundoFator, type Perfil, type Usuario,
} from '../../api/acessos';
import { formatarTelefone } from '../../api/clientes';
import { mensagem, Copiavel } from './Acessos';
import { EscolhaDePerfil } from './EscolhaDePerfil';
import { senhaSugerida } from './senhaSugerida';

/**
 * Tudo que se faz com um acesso, separado pelo que cada coisa causa.
 *
 * <h2>Cada ato tem o seu botão</h2>
 *
 * Mudar o perfil e definir a senha **derrubam a sessão da pessoa**; corrigir o nome, não.
 * Escondê-los dentro de um "salvar" só faria um botão manso produzir uma consequência que
 * ninguém pediu — e a pessoa do outro lado descobriria sendo desconectada no meio de um
 * atendimento.
 *
 * <h2>Na própria conta, a tela explica em vez de desabilitar</h2>
 *
 * O servidor recusa rebaixar, desativar e trocar a senha da própria conta (RN-IAM-015 e
 * RN-IAM-025). Um botão cinza sem motivo faria o dono achar que é defeito; aqui está escrito
 * por que, e para onde ir.
 */
export function EditorDeAcesso({ usuario, souEu, aoFechar }: {
  usuario: Usuario;
  souEu: boolean;
  aoFechar: () => void;
}) {
  return (
    <div className="acs-editor">
      {/*
        De quem é esta conta, fixo no alto.
        São cinco blocos, três deles com ação que a pessoa do outro lado sente — e, rolando até
        "Remover segundo fator", o nome já teria saído da tela se estivesse só dentro do campo.
      */}
      <header className="acs-editor-topo">
        <div className="acs-editor-quem">
          <h3>{usuario.nome}</h3>
          <p>{usuario.email}</p>
        </div>
        <button className="pagina-acao discreta" type="button" onClick={aoFechar}>Fechar</button>
      </header>

      <Ficha usuario={usuario} />
      <PerfilDaConta usuario={usuario} souEu={souEu} />
      <Seguranca usuario={usuario} souEu={souEu} />
      <TelefoneDoAgente usuario={usuario} />
      <Acesso usuario={usuario} souEu={souEu} />
    </div>
  );
}

/** RN-IAM-024 — nome e e-mail. Trocar o e-mail encerra as sessões da pessoa. */
function Ficha({ usuario }: { usuario: Usuario }) {
  const salvar = useEditarUsuario();
  const [nome, definirNome] = useState(usuario.nome);
  const [email, definirEmail] = useState(usuario.email);

  const mudou = nome.trim() !== usuario.nome || email.trim() !== usuario.email;
  const trocaDeEmail = email.trim().toLowerCase() !== usuario.email.toLowerCase();

  return (
    <form
      className="acs-bloco"
      aria-label={`Ficha de ${usuario.nome}`}
      onSubmit={(evento) => {
        evento.preventDefault();
        if (!mudou || nome.trim() === '' || email.trim() === '') return;
        salvar.mutate({ id: usuario.id, nome: nome.trim(), email: email.trim() });
      }}
    >
      <h3>Ficha</h3>
      <div className="acs-campos">
        <label className="acs-campo acs-campo-largo">
          <span>Nome</span>
          <input value={nome} onChange={(e) => definirNome(e.target.value)} />
        </label>
        <label className="acs-campo acs-campo-largo">
          <span>E-mail de entrada</span>
          <input value={email} type="email" autoComplete="off"
            onChange={(e) => definirEmail(e.target.value)} />
        </label>
      </div>

      {trocaDeEmail && (
        <p className="acs-ajuda acs-atencao">
          O e-mail é a credencial de entrada: ao trocar, as sessões desta pessoa são encerradas
          e ela entra de novo com o endereço novo.
        </p>
      )}
      {salvar.isError && <p className="erro" role="alert">{mensagem(salvar.error)}</p>}

      <div className="acs-acoes-bloco">
        <button className="pagina-acao principal" type="submit"
          disabled={!mudou || salvar.isPending}>
          {salvar.isPending ? 'Salvando…' : 'Salvar ficha'}
        </button>
      </div>
    </form>
  );
}

function PerfilDaConta({ usuario, souEu }: { usuario: Usuario; souEu: boolean }) {
  const mudar = useDefinirPerfil();
  const [escolhido, definirEscolhido] = useState<Perfil>(usuario.perfil);

  if (souEu) {
    return (
      <section className="acs-bloco" aria-label="Perfil">
        <h3>Perfil</h3>
        <p className="acs-ajuda">
          Esta é a sua conta. O próprio perfil não se muda — quem percebe o engano seria
          justamente quem acabou de perder o acesso. Peça a outro administrador.
        </p>
      </section>
    );
  }

  return (
    <section className="acs-bloco" aria-label="Perfil">
      <h3>Perfil</h3>
      <EscolhaDePerfil grupo={`perfil-${usuario.id}`} valor={escolhido}
        aoEscolher={definirEscolhido} />

      {escolhido !== usuario.perfil && (
        <p className="acs-ajuda acs-atencao">
          Mudar o perfil encerra as sessões desta pessoa: o acesso antigo continuaria valendo
          por alguns minutos, e num rebaixamento é justamente essa a janela que não se deixa
          aberta.
        </p>
      )}
      {mudar.isError && <p className="erro" role="alert">{mensagem(mudar.error)}</p>}

      <div className="acs-acoes-bloco">
        <button className="pagina-acao principal" type="button"
          disabled={escolhido === usuario.perfil || mudar.isPending}
          onClick={() => mudar.mutate({ id: usuario.id, perfil: escolhido })}>
          {mudar.isPending ? 'Mudando…' : 'Mudar perfil'}
        </button>
      </div>
    </section>
  );
}

/** Senha e segundo fator: as duas coisas que trancam alguém para fora, e as duas saídas. */
function Seguranca({ usuario, souEu }: { usuario: Usuario; souEu: boolean }) {
  const definir = useDefinirSenha();
  const resetar = useResetarSegundoFator();
  const [senha, definirSenha] = useState<string | null>(null);

  if (souEu) {
    return (
      <section className="acs-bloco" aria-label="Segurança">
        <h3>Segurança</h3>
        <p className="acs-ajuda">
          Na própria conta, a senha se troca apresentando a atual — é o que protege quem deixa a
          sessão aberta no balcão.{' '}
          <Link to="/seguranca/senha">Trocar minha senha</Link>
          {' · '}
          <Link to="/seguranca/segundo-fator">Segundo fator</Link>
        </p>
      </section>
    );
  }

  return (
    <section className="acs-bloco" aria-label="Segurança">
      <h3>Segurança</h3>

      <p className="acs-estado">
        {usuario.mfaAtivo
          ? 'Segundo fator configurado.'
          : 'Sem segundo fator configurado.'}
      </p>

      {senha === null ? (
        <div className="acs-acoes-bloco">
          <button className="pagina-acao discreta" type="button"
            onClick={() => { definir.reset(); definirSenha(senhaSugerida()); }}>
            Definir senha nova
          </button>
          {usuario.mfaAtivo && (
            <button className="pagina-acao discreta" type="button" disabled={resetar.isPending}
              onClick={() => resetar.mutate(usuario.id)}>
              {resetar.isPending ? 'Removendo…' : 'Remover segundo fator'}
            </button>
          )}
        </div>
      ) : (
        <div className="acs-senha">
          <label className="acs-campo acs-campo-senha">
            <span>Senha nova de {usuario.nome}</span>
            {/* Visível: quem define a senha de outra pessoa precisa lê-la para entregar. */}
            <input value={senha} autoComplete="off" spellCheck={false}
              onChange={(e) => definirSenha(e.target.value)} />
          </label>

          {definir.isSuccess ? (
            <p className="acs-ajuda">
              Pronto. Esta pessoa saiu de todos os aparelhos e entra com esta senha — que não
              aparece de novo depois que você fechar.
            </p>
          ) : (
            <p className="acs-ajuda">
              Ao definir, esta pessoa sai de todos os aparelhos. Mande a senha por um caminho
              que só ela leia.
            </p>
          )}
          {definir.isError && <p className="erro" role="alert">{mensagem(definir.error)}</p>}

          <div className="acs-acoes-bloco">
            {!definir.isSuccess && (
              <>
                <button className="pagina-acao principal" type="button"
                  disabled={senha.length < 12 || definir.isPending}
                  onClick={() => definir.mutate({ id: usuario.id, senha })}>
                  {definir.isPending ? 'Definindo…' : 'Definir senha'}
                </button>
                <button className="pagina-acao discreta" type="button"
                  onClick={() => definirSenha(senhaSugerida())}>
                  Gerar outra
                </button>
              </>
            )}
            <Copiavel texto={`E-mail: ${usuario.email}\nSenha: ${senha}`}>
              Copiar e-mail e senha
            </Copiavel>
            <button className="pagina-acao discreta" type="button"
              onClick={() => { definirSenha(null); definir.reset(); }}>
              {definir.isSuccess ? 'Fechar' : 'Cancelar'}
            </button>
          </div>
        </div>
      )}

      {resetar.isError && <p className="erro" role="alert">{mensagem(resetar.error)}</p>}
      {resetar.isSuccess && (
        <p className="acs-ajuda" role="status">
          Segundo fator removido. Na próxima entrada, esta pessoa configura o aplicativo de
          novo — e é obrigatório se o perfil exigir.
        </p>
      )}
    </section>
  );
}

/** RN-IAM-022 — ligar um telefone a uma conta é conceder permissão, não preencher cadastro. */
function TelefoneDoAgente({ usuario }: { usuario: Usuario }) {
  const salvar = useDefinirTelefoneDoUsuario();
  const [telefone, definirTelefone] = useState(formatarTelefone(usuario.telefone));

  const mudou = telefone.replace(/\D/g, '') !== (usuario.telefone ?? '').replace(/\D/g, '');

  return (
    <form
      className="acs-bloco"
      aria-label="Telefone do agente"
      onSubmit={(evento) => {
        evento.preventDefault();
        if (mudou) salvar.mutate({ id: usuario.id, telefone: telefone.trim() });
      }}
    >
      <h3>Telefone no agente</h3>
      <p className="acs-ajuda">
        Quem escrever de <strong>deste número</strong> para o WhatsApp do salão age com as
        permissões desta conta. Deixe em branco para desligar.
      </p>

      <div className="acs-campos">
        <label className="acs-campo">
          <span>Telefone</span>
          <input value={telefone} inputMode="tel" placeholder="(11) 98765-4321"
            onChange={(e) => definirTelefone(e.target.value)} />
        </label>
      </div>

      {salvar.isError && <p className="erro" role="alert">{mensagem(salvar.error)}</p>}

      <div className="acs-acoes-bloco">
        <button className="pagina-acao discreta" type="submit" disabled={!mudou || salvar.isPending}>
          {salvar.isPending ? 'Salvando…' : 'Salvar telefone'}
        </button>
      </div>
    </form>
  );
}

function Acesso({ usuario, souEu }: { usuario: Usuario; souEu: boolean }) {
  const ativacao = useDefinirAtivacao();

  return (
    <section className="acs-bloco" aria-label="Acesso ao sistema">
      <h3>Acesso</h3>
      {souEu ? (
        <p className="acs-ajuda">
          A própria conta não se desativa. Peça a outro administrador.
        </p>
      ) : (
        <>
          <p className="acs-ajuda">
            Tirar o acesso não apaga nada: o histórico continua apontando para esta pessoa.
          </p>
          {ativacao.isError && <p className="erro" role="alert">{mensagem(ativacao.error)}</p>}
          <div className="acs-acoes-bloco">
            <button className="pagina-acao discreta" type="button" disabled={ativacao.isPending}
              onClick={() => ativacao.mutate({ id: usuario.id, ativo: !usuario.ativo })}>
              {usuario.ativo ? 'Tirar o acesso' : 'Devolver o acesso'}
            </button>
          </div>
        </>
      )}
    </section>
  );
}
