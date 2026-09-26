import { useState } from 'react';
import {
  dinheiro, useCancelarVale, usePrevia, useRegistrarVale, useVales, FORMAS_DO_VALE,
  type FormaDoVale, type Periodo, type Vale,
} from '../../api/financeiro';
import { Esqueleto, Vazio } from '../../layout/Pagina';
import { Secao } from '../../layout/Secao';
import { mensagem } from './Financeiro';

/**
 * RT-FIN-005 — os adiantamentos do período.
 *
 * <h2>A competência é de hoje, e a tela diz isso</h2>
 *
 * O vale entra sempre no dia do salão (RN-FIN-014) — não se escolhe a data. Olhando um período
 * passado, um vale registrado agora não vai aparecer nesta lista, e a tela avisa antes, em vez de
 * deixar a pessoa procurar o lançamento que sumiu.
 */
export function Vales({ periodo }: { periodo: Periodo }) {
  const vales = useVales(periodo);
  const previa = usePrevia(periodo);
  const [registrando, definirRegistrando] = useState(false);

  const entregues = (vales.data ?? []).filter((v) => !v.cancelado);
  const total = entregues.reduce((soma, v) => soma + v.valor, 0);

  return (
    <Secao
      id="fin-vales"
      titulo="Vales"
      ajuda={vales.data
        ? `${dinheiro(total)} adiantados no período.`
        : 'O que já saiu da gaveta e vai descontar do repasse.'}
      acoes={(
        <button className="pagina-acao principal" type="button"
          onClick={() => definirRegistrando(!registrando)}>
          {registrando ? 'Cancelar' : 'Entregar vale'}
        </button>
      )}
    >
      {registrando && (
        <NovoVale periodo={periodo} pessoas={previa.data?.linhas ?? []}
          aoFechar={() => definirRegistrando(false)} />
      )}

      {vales.isPending && <Esqueleto linhas={2} altura={40} />}
      {vales.data?.length === 0 && !registrando && (
        <Vazio icone="cifrao" titulo="Nenhum vale neste período">
          Adiantamentos aparecem aqui e descontam no fechamento.
        </Vazio>
      )}

      {vales.data && vales.data.length > 0 && (
        <ul className="fin-vales">
          {vales.data.map((vale) => <LinhaDoVale key={vale.id} vale={vale} />)}
        </ul>
      )}
    </Secao>
  );
}

function LinhaDoVale({ vale }: { vale: Vale }) {
  const cancelar = useCancelarVale();
  const [aberta, definirAberta] = useState(false);
  const [motivo, definirMotivo] = useState('');

  return (
    <li>
      <div className={`fin-vale${vale.cancelado ? ' cancelado' : ''}`}>
        <span className="fin-vale-quem">{vale.profissionalNome}</span>
        <span className="fin-vale-valor">{dinheiro(vale.valor)}</span>
        <span className="fin-ajuda">
          {new Date(`${vale.dataCompetencia}T12:00:00Z`)
            .toLocaleDateString('pt-BR', { timeZone: 'UTC' })}
          {' · '}
          {FORMAS_DO_VALE.find((f) => f.valor === vale.forma)?.rotulo ?? vale.forma}
          {vale.motivo && ` · ${vale.motivo}`}
        </span>
        {vale.cancelado
          ? <span className="fin-marca">cancelado</span>
          : (
            <button className="pagina-acao discreta" type="button"
              onClick={() => definirAberta(!aberta)}>
              {aberta ? 'Deixar' : 'Cancelar'}
            </button>
          )}
      </div>

      {aberta && (
        <form
          className="fin-correcao"
          aria-label={`Cancelar o vale de ${vale.profissionalNome}`}
          onSubmit={(evento) => {
            evento.preventDefault();
            cancelar.mutate({ id: vale.id, motivo: motivo.trim() },
              { onSuccess: () => { definirAberta(false); definirMotivo(''); } });
          }}
        >
          <label className="fin-campo fin-campo-largo">
            <span>Por que cancelar</span>
            <input value={motivo} autoFocus placeholder="o dinheiro não chegou a sair"
              onChange={(e) => definirMotivo(e.target.value)} />
          </label>
          <button className="pagina-acao principal" type="submit"
            disabled={motivo.trim() === '' || cancelar.isPending}>
            {cancelar.isPending ? 'Cancelando…' : 'Cancelar o vale'}
          </button>
          <p className="fin-ajuda">
            O vale continua registrado, marcado, com uma linha de sinal contrário no razão.
          </p>
          {cancelar.isError && <p className="erro" role="alert">{mensagem(cancelar.error)}</p>}
        </form>
      )}
    </li>
  );
}

function NovoVale({ periodo, pessoas, aoFechar }: {
  periodo: Periodo;
  pessoas: Array<{ profissionalId: string; profissionalNome: string }>;
  aoFechar: () => void;
}) {
  const registrar = useRegistrarVale();
  const [profissionalId, definirProfissional] = useState('');
  const [valor, definirValor] = useState('');
  const [forma, definirForma] = useState<FormaDoVale>('DINHEIRO');
  const [motivo, definirMotivo] = useState('');

  const numero = Number(valor.replace(',', '.'));
  const valido = profissionalId !== '' && numero > 0 && !registrar.isPending;

  // RN-FIN-014: a competência é hoje, sempre. Num período passado, o vale não vai aparecer aqui.
  const hoje = new Date().toISOString().slice(0, 10);
  const foraDoPeriodo = hoje < periodo.de || hoje > periodo.ate;

  return (
    <form
      className="fin-correcao"
      aria-label="Entregar vale"
      onSubmit={(evento) => {
        evento.preventDefault();
        if (!valido) return;
        registrar.mutate({
          profissionalId, valor: numero, forma, motivo: motivo.trim() || null,
        }, { onSuccess: aoFechar });
      }}
    >
      <label className="fin-campo">
        <span>Para quem</span>
        <select value={profissionalId} onChange={(e) => definirProfissional(e.target.value)}>
          <option value="">escolha</option>
          {pessoas.map((p) => (
            <option key={p.profissionalId} value={p.profissionalId}>{p.profissionalNome}</option>
          ))}
        </select>
      </label>
      <label className="fin-campo">
        <span>Quanto</span>
        <input value={valor} inputMode="decimal" placeholder="200,00"
          aria-label="Valor do vale"
          onChange={(e) => definirValor(e.target.value)} />
      </label>
      <label className="fin-campo">
        <span>Por onde saiu</span>
        <select value={forma} onChange={(e) => definirForma(e.target.value as FormaDoVale)}>
          {FORMAS_DO_VALE.map((f) => (
            <option key={f.valor} value={f.valor}>{f.rotulo}</option>
          ))}
        </select>
      </label>
      <label className="fin-campo fin-campo-largo">
        <span>Observação</span>
        <input value={motivo} onChange={(e) => definirMotivo(e.target.value)} />
      </label>

      {foraDoPeriodo && (
        <p className="fin-ajuda fin-atencao">
          O vale entra com a data de <strong>hoje</strong>, e você está olhando outro período —
          ele não vai aparecer nesta lista.
        </p>
      )}
      {registrar.isError && <p className="erro" role="alert">{mensagem(registrar.error)}</p>}

      <div className="fin-acoes">
        <button className="pagina-acao principal" type="submit" disabled={!valido}>
          {registrar.isPending ? 'Registrando…' : 'Registrar o vale'}
        </button>
        <button className="pagina-acao discreta" type="button" onClick={aoFechar}>Cancelar</button>
      </div>
    </form>
  );
}
