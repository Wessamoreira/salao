import { useState, type ReactNode } from 'react';
import {
  ultimoAcesso, useCriarAcesso, usePerfis, useUsuarios, type Perfil, type PerfilDisponivel,
  type Usuario,
} from '../../api/acessos';
import { useCapacidades } from '../../api/capacidades';
import { ErroDaApi } from '../../api/http';
import { Esqueleto, Falha, Pagina, Vazio } from '../../layout/Pagina';
import { Secao } from '../../layout/Secao';
import { EditorDeAcesso } from './EditorDeAcesso';
import { EscolhaDePerfil } from './EscolhaDePerfil';
import { senhaSugerida } from './senhaSugerida';
import './acessos.css';

/**
 * RT-IAM-009 — quem entra no sistema.
 *
 * <h2>É esta tela que tira o sistema de uma pessoa só</h2>
 *
 * Dar acesso à recepção exigia uma chamada HTTP na mão. Nove endpoints de usuário estão
 * entregues desde a Fase 0 e nenhum tinha tela — e a falta dela escondeu dois buracos com efeito
 * permanente: um e-mail digitado errado queimava o endereço para sempre, e quem esquecia a senha
 * não tinha caminho de volta.
 *
 * <h2>A lista mostra o que decide o dia de quem administra</h2>
 *
 * Quem entra, com que poder, e o que está pendente: segundo fator que falta configurar, conta
 * criada que nunca foi usada — que quase sempre quer dizer que a senha não chegou.
 */
export function Acessos() {
  const capacidades = useCapacidades();
  const usuarios = useUsuarios();
  const perfis = usePerfis();
  const [abrindo, definirAbrindo] = useState<string | 'novo' | null>(null);

  const porPerfil = new Map((perfis.data ?? []).map((p) => [p.perfil, p]));

  return (
    <Pagina>
      <div className="acs-tela">
        <Secao
          id="acs-quem-entra"
          titulo="Quem entra no sistema"
          ajuda="Cada pessoa com o seu login. Conta compartilhada apaga de quem foi cada ação."
          acoes={(
            <button className="pagina-acao principal" type="button"
              onClick={() => definirAbrindo(abrindo === 'novo' ? null : 'novo')}>
              {abrindo === 'novo' ? 'Cancelar' : 'Novo acesso'}
            </button>
          )}
        >
          {abrindo === 'novo' && <NovoAcesso aoFechar={() => definirAbrindo(null)} />}

          {usuarios.isPending && <Esqueleto linhas={3} altura={64} />}
          {usuarios.isError && (
            <Falha aoTentar={() => usuarios.refetch()}>{mensagem(usuarios.error)}</Falha>
          )}

          {usuarios.data?.length === 0 && abrindo !== 'novo' && (
            <Vazio icone="escudo" titulo="Nenhum acesso cadastrado">
              Crie o login de quem trabalha no salão. Cada pessoa com o seu.
            </Vazio>
          )}

          {usuarios.data && usuarios.data.length > 0 && (
            <ul className="acs-lista">
              {usuarios.data.map((usuario) => (
                <li key={usuario.id}>
                  {abrindo === usuario.id
                    ? (
                      <EditorDeAcesso usuario={usuario}
                        souEu={usuario.id === capacidades.data?.usuarioId}
                        aoFechar={() => definirAbrindo(null)} />
                    )
                    : (
                      <Linha usuario={usuario} perfil={porPerfil.get(usuario.perfil)}
                        souEu={usuario.id === capacidades.data?.usuarioId}
                        aoAbrir={() => definirAbrindo(usuario.id)} />
                    )}
                </li>
              ))}
            </ul>
          )}
        </Secao>
      </div>
    </Pagina>
  );
}

function Linha({ usuario, perfil, souEu, aoAbrir }: {
  usuario: Usuario;
  perfil: PerfilDisponivel | undefined;
  souEu: boolean;
  aoAbrir: () => void;
}) {
  return (
    <article className={`acs-pessoa${usuario.ativo ? '' : ' inativa'}`}>
      <span className="acs-inicial" aria-hidden="true">{usuario.nome.charAt(0)}</span>

      <div className="acs-quem">
        <h3>
          {usuario.nome}
          {souEu && <span className="acs-voce">você</span>}
        </h3>
        <p className="acs-email">{usuario.email}</p>
      </div>

      <p className="acs-perfil">{perfil?.rotulo ?? usuario.perfil}</p>

      <p className="acs-situacao">
        <Situacao usuario={usuario} exigeMfa={perfil?.exigeMfa ?? false} />
      </p>

      <div className="acs-acoes">
        <button className="pagina-acao discreta" type="button" onClick={aoAbrir}>Abrir</button>
      </div>
    </article>
  );
}

/**
 * O que está pendente nesta conta, ou o último acesso quando não há pendência.
 *
 * Uma linha só, e a mais importante: mostrar as três ao mesmo tempo faria a lista virar um
 * parágrafo por pessoa, e a pendência — que é a única acionável — se perderia no meio.
 */
function Situacao({ usuario, exigeMfa }: { usuario: Usuario; exigeMfa: boolean }) {
  if (!usuario.ativo) return <span className="acs-marca">sem acesso</span>;

  // Antes do segundo fator: quem nunca entrou não tem como ter configurado nada, e o que
  // resolve o caso dela é a senha chegar — não o autenticador.
  if (usuario.ultimoAcessoEm === null) {
    return <span className="acs-marca atencao">nunca entrou</span>;
  }
  // RN-IAM-014: o servidor não deixa passar da inscrição do segundo fator. Não é pendência da
  // tela nem do salão — é o que vai acontecer na próxima entrada dela.
  if (exigeMfa && !usuario.mfaAtivo) {
    return <span className="acs-marca atencao">configura o segundo fator ao entrar</span>;
  }
  return <>{ultimoAcesso(usuario.ultimoAcessoEm)}</>;
}

/**
 * Criar acesso: nome, e-mail, perfil e a senha que vai ser entregue.
 *
 * Depois de criada, a senha não se lê de novo — o banco guarda o hash. A tela mostra e-mail e
 * senha juntos para copiar, e diz isso, em vez de deixar a pessoa descobrir recarregando.
 */
function NovoAcesso({ aoFechar }: { aoFechar: () => void }) {
  const criar = useCriarAcesso();
  const [nome, definirNome] = useState('');
  const [email, definirEmail] = useState('');
  const [perfil, definirPerfil] = useState<Perfil>('RECEPCAO');
  const [senha, definirSenha] = useState(senhaSugerida);

  const pode = nome.trim() !== '' && email.trim() !== '' && senha.length >= 12
    && !criar.isPending;

  if (criar.isSuccess) {
    return (
      <section className="acs-editor acs-pronto" aria-label="Acesso criado">
        <div className="acs-bloco">
          <h3>Acesso criado</h3>
          <p className="acs-ajuda">
            Mande estes dados para {nome.trim()} por um caminho que só ela leia. A senha
            <strong> não aparece de novo</strong>: depois daqui, só definindo outra.
          </p>
          <dl className="acs-credencial">
            <dt>E-mail</dt><dd>{email.trim()}</dd>
            <dt>Senha</dt><dd>{senha}</dd>
          </dl>
          <div className="acs-acoes-bloco">
            <Copiavel texto={`E-mail: ${email.trim()}\nSenha: ${senha}`}>
              Copiar e-mail e senha
            </Copiavel>
            <button className="pagina-acao discreta" type="button" onClick={aoFechar}>
              Fechar
            </button>
          </div>
        </div>
      </section>
    );
  }

  return (
    <form
      className="acs-editor"
      aria-label="Novo acesso"
      onSubmit={(evento) => {
        evento.preventDefault();
        if (!pode) return;
        criar.mutate({ nome: nome.trim(), email: email.trim(), senha, perfil });
      }}
    >
      <div className="acs-bloco">
        <h3>Quem é</h3>
        <div className="acs-campos">
          <label className="acs-campo acs-campo-largo">
            <span>Nome</span>
            <input value={nome} autoFocus placeholder="Bia Nogueira"
              onChange={(e) => definirNome(e.target.value)} />
          </label>
          <label className="acs-campo acs-campo-largo">
            <span>E-mail de entrada</span>
            <input value={email} type="email" autoComplete="off" placeholder="bia@salao.com.br"
              onChange={(e) => definirEmail(e.target.value)} />
          </label>
        </div>
      </div>

      <div className="acs-bloco">
        <h3>O que vai poder fazer</h3>
        <EscolhaDePerfil grupo="perfil-novo" valor={perfil} aoEscolher={definirPerfil} />
      </div>

      <div className="acs-bloco">
        <h3>Senha para entregar</h3>
        <div className="acs-campos">
          <label className="acs-campo acs-campo-senha">
            <span>Senha inicial</span>
            {/* À vista de propósito: quem cria o acesso precisa ler a senha para entregá-la. */}
            <input value={senha} autoComplete="off" spellCheck={false}
              onChange={(e) => definirSenha(e.target.value)} />
          </label>
          <button className="pagina-acao discreta acs-gerar" type="button"
            onClick={() => definirSenha(senhaSugerida())}>
            Gerar outra
          </button>
        </div>
        <p className="acs-ajuda">
          Quatro palavras sorteadas: dá para ditar ao telefone sem soletrar, e ninguém adivinha.
        </p>

        {criar.isError && <p className="erro" role="alert">{mensagem(criar.error)}</p>}

        <div className="acs-acoes-bloco">
          <button className="pagina-acao principal" type="submit" disabled={!pode}>
            {criar.isPending ? 'Criando…' : 'Criar acesso'}
          </button>
          <button className="pagina-acao discreta" type="button" onClick={aoFechar}>
            Cancelar
          </button>
        </div>
      </div>
    </form>
  );
}

/**
 * Copiar sem prometer o que não deu certo.
 *
 * A área de transferência não existe em página sem HTTPS nem em navegador com a permissão
 * negada. Dizer "copiado" nesses casos faria alguém colar nada numa mensagem e mandar.
 */
export function Copiavel({ texto, children }: { texto: string; children: ReactNode }) {
  const [estado, definirEstado] = useState<'parado' | 'copiado' | 'falhou'>('parado');

  return (
    <button className="pagina-acao discreta" type="button"
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(texto);
          definirEstado('copiado');
        } catch {
          definirEstado('falhou');
        }
        window.setTimeout(() => definirEstado('parado'), 4000);
      }}>
      {estado === 'copiado' ? 'Copiado'
        : estado === 'falhou' ? 'Copie na mão' : children}
    </button>
  );
}

/** A recusa do servidor, com as palavras dele. */
export function mensagem(erro: unknown): string {
  if (erro instanceof ErroDaApi) return erro.message;
  return 'Não foi possível falar com o servidor. Tente de novo.';
}
