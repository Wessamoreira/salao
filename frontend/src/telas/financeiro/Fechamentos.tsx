import { useState } from 'react';
import {
  dinheiro, useEstornarFechamento, useFechamentos, type Fechamento, type Periodo,
} from '../../api/financeiro';
import { Esqueleto } from '../../layout/Pagina';
import { Secao } from '../../layout/Secao';
import { mensagem } from './Financeiro';

/**
 * RT-FIN-006 — os contracheques do período.
 *
 * <h2>Congelados, e nunca recalculados</h2>
 *
 * Reprocessar um fechamento de três meses atrás produz número diferente do repasse já pago, e aí
 * não se explica a diferença a quem recebeu. O que está aqui é o que foi pago.
 *
 * <h2>Estornado continua na lista</h2>
 *
 * O documento fica, marcado. Ele passa a contar que em tal dia pagou-se e depois anulou-se — que
 * é justamente o que explica o dinheiro que saiu da conta.
 */
export function Fechamentos({ periodo }: { periodo: Periodo }) {
  const fechamentos = useFechamentos(periodo);

  const vigentes = (fechamentos.data ?? []).filter((f) => f.estornadoEm === null);
  const pago = vigentes.reduce((soma, f) => soma + f.valorRepassado, 0);

  if (fechamentos.isSuccess && fechamentos.data.length === 0) return null;

  return (
    <Secao
      id="fin-fechamentos"
      titulo="Repasses do período"
      ajuda={fechamentos.data ? `${dinheiro(pago)} pagos.` : undefined}
    >
      {fechamentos.isPending && <Esqueleto linhas={2} altura={40} />}

      {fechamentos.data && (
        <ul className="fin-fechamentos">
          {fechamentos.data.map((f) => <LinhaDoFechamento key={f.id} fechamento={f} />)}
        </ul>
      )}
    </Secao>
  );
}

function LinhaDoFechamento({ fechamento }: { fechamento: Fechamento }) {
  const estornar = useEstornarFechamento();
  const [aberta, definirAberta] = useState(false);
  const [motivo, definirMotivo] = useState('');

  const estornado = fechamento.estornadoEm !== null;

  return (
    <li>
      <div className={`fin-fechamento${estornado ? ' estornado' : ''}`}>
        <span className="fin-vale-quem">{fechamento.profissionalNome}</span>
        <span className="fin-vale-valor">{dinheiro(fechamento.valorRepassado)}</span>
        <span className="fin-ajuda">
          {fechamento.saldoATransportar !== 0
            && `${dinheiro(fechamento.saldoATransportar)} atravessa para o próximo`}
        </span>
        {estornado
          ? <span className="fin-marca">estornado</span>
          : (
            <button className="pagina-acao discreta" type="button"
              onClick={() => definirAberta(!aberta)}>
              {aberta ? 'Deixar' : 'Estornar'}
            </button>
          )}
      </div>

      {aberta && (
        <form
          className="fin-correcao"
          aria-label={`Estornar o repasse de ${fechamento.profissionalNome}`}
          onSubmit={(evento) => {
            evento.preventDefault();
            estornar.mutate({ id: fechamento.id, motivo: motivo.trim() },
              { onSuccess: () => { definirAberta(false); definirMotivo(''); } });
          }}
        >
          <label className="fin-campo fin-campo-largo">
            <span>Por que estornar</span>
            <input value={motivo} autoFocus placeholder="pagamento não saiu do banco"
              onChange={(e) => definirMotivo(e.target.value)} />
          </label>
          <button className="pagina-acao principal" type="submit"
            disabled={motivo.trim() === '' || estornar.isPending}>
            {estornar.isPending
              ? 'Estornando…'
              : `Estornar ${dinheiro(fechamento.valorRepassado)}`}
          </button>
          <p className="fin-ajuda">
            {dinheiro(fechamento.valorRepassado)} voltam a ser devidos a{' '}
            {fechamento.profissionalNome}, e o período é liberado para novo fechamento.{' '}
            <strong>Se o dinheiro já saiu do banco</strong>, o registro certo é um vale — estornar
            devolve a dívida, não o dinheiro.
          </p>
          {estornar.isError && <p className="erro" role="alert">{mensagem(estornar.error)}</p>}
        </form>
      )}
    </li>
  );
}
