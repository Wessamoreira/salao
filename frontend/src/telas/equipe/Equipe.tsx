import { useState } from 'react';
import {
  resumoDaJornada, useAtivacaoDoProfissional, useJornada, useProfissionais, useSalvarProfissional,
  type Profissional, type TipoDeVinculo,
} from '../../api/equipe';
import { formatarTelefone } from '../../api/clientes';
import { ErroDaApi } from '../../api/http';
import { Esqueleto, Falha, Pagina, Vazio } from '../../layout/Pagina';
import { Secao } from '../../layout/Secao';
import { Bloqueios } from './Bloqueios';
import { Habilidades } from './Habilidades';
import { Jornada } from './Jornada';
import './equipe.css';

const VINCULO: Record<TipoDeVinculo, string> = {
  PARCEIRO: 'Parceira',
  EMPREGADO: 'Empregada',
  AUTONOMO: 'Autônoma',
};

/**
 * RT-EQP-005 — a equipe.
 *
 * <h2>É esta tela que faz a agenda existir</h2>
 *
 * A agenda abre dizendo "ninguém para atender ainda — aparece quando houver profissional
 * cadastrado com jornada". Estava certa e não havia conserto: cadastrar profissional e jornada
 * só era possível por chamada HTTP na mão. É o bloqueio nº 1 de `18-o-que-falta-para-usar.md`.
 *
 * <h2>A lista mostra o que decide o dia</h2>
 *
 * Quem atende, quando, e o que executa. Vínculo e comissão vêm depois, em corpo menor: são do
 * contrato, não da agenda — e quem abre esta tela de manhã está pensando na agenda.
 */
export function Equipe() {
  const profissionais = useProfissionais();
  const [editando, definirEditando] = useState<string | 'nova' | null>(null);

  return (
    <Pagina>
      <div className="eqp-tela">
        <Secao
          id="eqp-quem-atende"
          titulo="Quem atende"
          ajuda="A agenda desenha uma coluna para cada pessoa ativa com jornada."
          acoes={(
            <button className="pagina-acao principal" type="button"
              onClick={() => definirEditando(editando === 'nova' ? null : 'nova')}>
              {editando === 'nova' ? 'Cancelar' : 'Nova profissional'}
            </button>
          )}
        >
          {editando === 'nova' && <Ficha aoFechar={() => definirEditando(null)} />}

          {profissionais.isPending && <Esqueleto linhas={3} altura={64} />}
          {profissionais.isError && (
            <Falha aoTentar={() => profissionais.refetch()}>
              {mensagem(profissionais.error)}
            </Falha>
          )}

          {profissionais.data?.length === 0 && editando !== 'nova' && (
            <Vazio icone="cracha" titulo="Ninguém cadastrado ainda">
              Cadastre quem atende e defina a jornada. É o que faz a agenda sair do vazio.
            </Vazio>
          )}

          {profissionais.data && profissionais.data.length > 0 && (
            <ul className="eqp-lista">
              {profissionais.data.map((pessoa) => (
                <li key={pessoa.id}>
                  {editando === pessoa.id
                    ? (
                      <div className="eqp-editor">
                        <Ficha profissional={pessoa} aoFechar={() => definirEditando(null)} />
                        <Jornada profissionalId={pessoa.id} />
                        <Habilidades profissionalId={pessoa.id} />
                      </div>
                    )
                    : <Linha pessoa={pessoa} aoAbrir={() => definirEditando(pessoa.id)} />}
                </li>
              ))}
            </ul>
          )}
        </Secao>

        <Bloqueios />
      </div>
    </Pagina>
  );
}

function Linha({ pessoa, aoAbrir }: { pessoa: Profissional; aoAbrir: () => void }) {
  const jornada = useJornada(pessoa.id);
  const ativacao = useAtivacaoDoProfissional();
  const chamada = pessoa.apelido ?? pessoa.nome;

  return (
    <article className={`eqp-pessoa${pessoa.ativo ? '' : ' inativa'}`}>
      <span className="eqp-inicial" aria-hidden="true">{chamada.charAt(0)}</span>

      <div className="eqp-quem">
        <h3>{chamada}</h3>
        {chamada !== pessoa.nome && <p className="eqp-formal">{pessoa.nome}</p>}
        <p className="eqp-contrato">
          {VINCULO[pessoa.vinculo]}
          {pessoa.comissaoPercentual != null && ` · ${pessoa.comissaoPercentual}% de comissão`}
          {/* RN-EQP-003: parceria sem contrato é registrada, não bloqueada — mas é obrigação
              legal do salão, e some do radar sem um lembrete. */}
          {pessoa.vinculo === 'PARCEIRO' && !pessoa.contratoAssinadoEm && (
            <span className="eqp-alerta"> contrato pendente</span>
          )}
        </p>
      </div>

      <p className="eqp-jornada-resumo">
        {jornada.isPending ? '' : resumoDaJornada(jornada.data)}
      </p>

      <div className="eqp-acoes">
        <button className="pagina-acao discreta" type="button" onClick={aoAbrir}>Abrir</button>
        <button className="pagina-acao discreta" type="button" disabled={ativacao.isPending}
          onClick={() => ativacao.mutate({ id: pessoa.id, ativo: !pessoa.ativo })}>
          {pessoa.ativo ? 'Tirar da equipe' : 'Trazer de volta'}
        </button>
      </div>

      {ativacao.isError && <p className="erro" role="alert">{mensagem(ativacao.error)}</p>}
    </article>
  );
}

/**
 * A ficha: o que se cadastra uma vez e se corrige de vez em quando.
 *
 * Documento e vínculo só aparecem no cadastro. Depois são identidade (RN-EQP-017): trocá-los
 * faria uma pessoa jurídica herdar o extrato de comissão de outra.
 */
function Ficha({ profissional, aoFechar }: { profissional?: Profissional; aoFechar: () => void }) {
  const salvar = useSalvarProfissional();
  const [nome, definirNome] = useState(profissional?.nome ?? '');
  const [apelido, definirApelido] = useState(profissional?.apelido ?? '');
  const [documento, definirDocumento] = useState('');
  const [vinculo, definirVinculo] = useState<TipoDeVinculo>(profissional?.vinculo ?? 'PARCEIRO');
  // Guardado em E.164 (RN-EQP-016) e mostrado como se lê: quem corrige um telefone não
  // deve ter de reconhecer o próprio número escrito como o banco guarda. O servidor
  // normaliza de novo ao salvar, seja qual for o formato digitado.
  const [telefone, definirTelefone] = useState(formatarTelefone(profissional?.telefone ?? null));
  const [email, definirEmail] = useState(profissional?.email ?? '');
  const [comissao, definirComissao] = useState(
    profissional?.comissaoPercentual == null ? '' : String(profissional.comissaoPercentual));
  const [assinadoEm, definirAssinadoEm] = useState(profissional?.contratoAssinadoEm ?? '');

  const novo = profissional === undefined;
  const podeSalvar = nome.trim() !== '' && (!novo || documento.trim() !== '') && !salvar.isPending;

  return (
    <form
      className="eqp-ficha"
      aria-label={novo ? 'Nova profissional' : `Editar ${profissional.nome}`}
      onSubmit={(evento) => {
        evento.preventDefault();
        if (!podeSalvar) return;
        salvar.mutate({
          id: profissional?.id,
          versao: profissional?.versao,
          // Sempre enviados daqui; quem decide que eles não entram no PUT é o gancho
          // (RN-EQP-017). Repetir a condição aqui seria a mesma regra em dois lugares — e o
          // segundo é o que ninguém lembra de corrigir.
          documento: documento.replace(/\D/g, ''),
          vinculo,
          nome: nome.trim(),
          apelido: apelido.trim() || null,
          telefone: telefone.trim() || null,
          email: email.trim() || null,
          // Vazio é "usa a cascata do serviço" (RT-FIN-002) — e é diferente de zero.
          comissaoPercentual: comissao.trim() === ''
            ? null : Number(comissao.replace(',', '.')),
          contratoInicio: profissional?.contratoInicio ?? null,
          contratoFim: profissional?.contratoFim ?? null,
          contratoAssinadoEm: assinadoEm || null,
        }, { onSuccess: aoFechar });
      }}
    >
      <div className="eqp-campos">
        <label className="eqp-campo eqp-campo-largo">
          <span>Nome</span>
          <input value={nome} autoFocus onChange={(e) => definirNome(e.target.value)}
            placeholder="Ana Ribeiro" />
        </label>
        <label className="eqp-campo">
          <span>Como é chamada</span>
          <input value={apelido} onChange={(e) => definirApelido(e.target.value)}
            placeholder="Ana" />
        </label>

        {novo && (
          <>
            <label className="eqp-campo">
              <span>Vínculo</span>
              <select value={vinculo}
                onChange={(e) => definirVinculo(e.target.value as TipoDeVinculo)}>
                <option value="PARCEIRO">Parceira (CNPJ)</option>
                <option value="EMPREGADO">Empregada (CPF)</option>
                <option value="AUTONOMO">Autônoma (CPF)</option>
              </select>
            </label>
            <label className="eqp-campo">
              {/* O tipo de documento é consequência do vínculo (RN-EQP-002), e o rótulo diz
                  qual é — em vez de deixar a recusa do servidor ensinar. */}
              <span>{vinculo === 'PARCEIRO' ? 'CNPJ' : 'CPF'}</span>
              <input value={documento} inputMode="numeric"
                onChange={(e) => definirDocumento(e.target.value)} />
            </label>
          </>
        )}

        <label className="eqp-campo">
          <span>Telefone</span>
          <input value={telefone} inputMode="tel" placeholder="(11) 98765-4321"
            onChange={(e) => definirTelefone(e.target.value)} />
        </label>
        <label className="eqp-campo eqp-campo-largo">
          <span>E-mail</span>
          <input value={email} type="email" onChange={(e) => definirEmail(e.target.value)} />
        </label>
        <label className="eqp-campo">
          <span>Comissão</span>
          <span className="eqp-com-sufixo">
            <input value={comissao} inputMode="decimal" placeholder="cascata"
              aria-label="Percentual de comissão"
              onChange={(e) => definirComissao(e.target.value)} />
            <span aria-hidden="true">%</span>
          </span>
        </label>
        <label className="eqp-campo">
          <span>Contrato assinado em</span>
          <input type="date" value={assinadoEm}
            onChange={(e) => definirAssinadoEm(e.target.value)} />
        </label>
      </div>

      <p className="eqp-ajuda">
        Comissão em branco quer dizer <strong>usar a cascata do serviço</strong>. Zero é uma
        comissão de verdade — a de quem recebe salário.
      </p>

      {salvar.isError && <p className="erro" role="alert">{mensagem(salvar.error)}</p>}

      <div className="eqp-jornada-acoes">
        <button className="pagina-acao principal" type="submit" disabled={!podeSalvar}>
          {salvar.isPending ? 'Salvando…' : novo ? 'Cadastrar' : 'Salvar ficha'}
        </button>
        <button className="pagina-acao discreta" type="button" onClick={aoFechar}>
          {novo ? 'Cancelar' : 'Fechar'}
        </button>
      </div>
    </form>
  );
}

/** A recusa do servidor, com as palavras dele — "PARCEIRO exige CNPJ" diz o que fazer. */
export function mensagem(erro: unknown): string {
  if (erro instanceof ErroDaApi) return erro.message;
  return 'Não foi possível falar com o servidor. Tente de novo.';
}
