import { useState } from 'react';
import { useCapacidades } from '../../api/capacidades';
import {
  useBloqueios, useProfissionais, useRegistrarBloqueio, useRemoverBloqueio,
  type MotivoDoBloqueio,
} from '../../api/equipe';
import { Esqueleto } from '../../layout/Pagina';
import { Secao } from '../../layout/Secao';
import { diaCivil, formatar, fusoDoNavegador, somarDias } from '../../layout/tempo';
import { mensagem } from './Equipe';

const MOTIVO: Record<MotivoDoBloqueio, string> = {
  FERIADO: 'Feriado',
  FERIAS: 'Férias',
  CURSO: 'Curso',
  ATESTADO: 'Atestado',
  FOLGA: 'Folga',
};

/**
 * Férias, curso, atestado e feriado — o que tira alguém da agenda sem tirar da equipe.
 *
 * <h2>Sem profissional quer dizer o salão inteiro</h2>
 *
 * É o feriado (RN-EQP-009), e é por isso que o seletor começa em "todo o salão" em vez de exigir
 * uma escolha: quem abre esta seção no fim do ano está cadastrando 25 de dezembro.
 *
 * <h2>Só o que ainda importa</h2>
 *
 * A lista mostra de hoje em diante. Bloqueio que já passou não é decisão pendente — é histórico,
 * e histórico de folga não é o assunto desta tela.
 */
export function Bloqueios() {
  const { data: capacidades } = useCapacidades();
  const fuso = capacidades?.estabelecimento?.timezone ?? fusoDoNavegador();
  const hoje = diaCivil(new Date(), fuso);

  const profissionais = useProfissionais();
  const bloqueios = useBloqueios(hoje, somarDias(hoje, 365));
  const registrar = useRegistrarBloqueio();
  const remover = useRemoverBloqueio();

  const [aberto, definirAberto] = useState(false);
  const [profissionalId, definirProfissionalId] = useState('');
  const [motivo, definirMotivo] = useState<MotivoDoBloqueio>('FERIAS');
  const [de, definirDe] = useState(hoje);
  const [ate, definirAte] = useState(hoje);
  const [observacao, definirObservacao] = useState('');

  const nomeDe = (id: string | null) => {
    if (id === null) return 'todo o salão';
    const pessoa = profissionais.data?.find((p) => p.id === id);
    return pessoa ? (pessoa.apelido ?? pessoa.nome) : 'alguém da equipe';
  };

  return (
    <Secao
      id="eqp-bloqueios"
      titulo="Férias, cursos e feriados"
      ajuda="O que tira alguém da agenda sem tirar da equipe. De hoje em diante."
      acoes={(
        <button className="pagina-acao discreta" type="button"
          onClick={() => definirAberto(!aberto)}>
          {aberto ? 'Cancelar' : 'Registrar ausência'}
        </button>
      )}
    >
      {aberto && (
        <form
          className="eqp-bloqueio-forma"
          aria-label="Registrar ausência"
          onSubmit={(evento) => {
            evento.preventDefault();
            if (registrar.isPending) return;
            registrar.mutate({
              profissionalId: profissionalId || null,
              motivo,
              observacao: observacao.trim() || null,
              dataInicio: de,
              dataFim: ate,
              // Dia inteiro: a hora existe para o pedaço de um dia só (RN-EQP-011), e essa é a
              // exceção — quem precisa dela hoje registra dois dias e ajusta depois.
              horaInicio: null,
              horaFim: null,
            }, { onSuccess: () => { definirAberto(false); definirObservacao(''); } });
          }}
        >
          <label className="eqp-campo">
            <span>Quem</span>
            <select value={profissionalId}
              onChange={(e) => definirProfissionalId(e.target.value)}>
              <option value="">Todo o salão</option>
              {profissionais.data?.filter((p) => p.ativo).map((pessoa) => (
                <option key={pessoa.id} value={pessoa.id}>{pessoa.apelido ?? pessoa.nome}</option>
              ))}
            </select>
          </label>

          <label className="eqp-campo">
            <span>Motivo</span>
            <select value={motivo}
              onChange={(e) => definirMotivo(e.target.value as MotivoDoBloqueio)}>
              {Object.entries(MOTIVO).map(([valor, rotulo]) => (
                <option key={valor} value={valor}>{rotulo}</option>
              ))}
            </select>
          </label>

          <label className="eqp-campo">
            <span>De</span>
            <input type="date" value={de} onChange={(e) => definirDe(e.target.value)} />
          </label>
          <label className="eqp-campo">
            <span>Até</span>
            <input type="date" value={ate} onChange={(e) => definirAte(e.target.value)} />
          </label>
          <label className="eqp-campo eqp-campo-largo">
            <span>Observação</span>
            <input value={observacao} onChange={(e) => definirObservacao(e.target.value)}
              placeholder="opcional" />
          </label>

          <button className="pagina-acao principal" type="submit" disabled={registrar.isPending}>
            {registrar.isPending ? 'Salvando…' : 'Registrar'}
          </button>
        </form>
      )}

      {registrar.isError && <p className="erro" role="alert">{mensagem(registrar.error)}</p>}
      {remover.isError && <p className="erro" role="alert">{mensagem(remover.error)}</p>}

      {bloqueios.isPending && <Esqueleto linhas={2} altura={40} />}
      {bloqueios.data?.length === 0 && (
        <p className="eqp-nada">Ninguém de férias, e nenhum feriado cadastrado daqui para frente.</p>
      )}

      {bloqueios.data && bloqueios.data.length > 0 && (
        <ul className="eqp-bloqueios">
          {bloqueios.data.map((bloqueio) => (
            <li key={bloqueio.id}>
              <strong>{MOTIVO[bloqueio.motivo]}</strong>
              <span className="eqp-bloqueio-quem">{nomeDe(bloqueio.profissionalId)}</span>
              <span className="eqp-bloqueio-quando">
                {periodo(bloqueio.dataInicio, bloqueio.dataFim)}
              </span>
              {bloqueio.observacao && (
                <span className="eqp-bloqueio-obs">{bloqueio.observacao}</span>
              )}
              <button type="button" className="pagina-acao discreta"
                disabled={remover.isPending}
                aria-label={`Tirar ${MOTIVO[bloqueio.motivo].toLowerCase()} de `
                  + `${nomeDe(bloqueio.profissionalId)}`}
                onClick={() => remover.mutate(bloqueio.id)}>
                Tirar
              </button>
            </li>
          ))}
        </ul>
      )}
    </Secao>
  );
}

/** `12/10` para um dia; `12/10 a 20/10` para um período. */
function periodo(de: string, ate: string): string {
  const dia = (data: string) => formatar(new Date(`${data}T12:00:00Z`), 'UTC',
    { day: '2-digit', month: '2-digit' });
  return de === ate ? dia(de) : `${dia(de)} a ${dia(ate)}`;
}
