import { useState } from 'react';
import type { ItemDaComanda } from '../../api/comandas';
import { reais } from '../../api/comandas';

/**
 * RT-ATD-008 — as linhas da comanda, com desconto e remoção no lugar.
 *
 * Sem navegação: R-UX-08 pede serviços, produtos, desconto e pagamento na mesma tela.
 * Abrir um diálogo para descontar tiraria a recepção do contexto no meio de uma conta
 * que a cliente está esperando.
 */
export function Itens({ itens, aoRemover, aoDescontar, bloqueado }: {
  itens: ItemDaComanda[];
  aoRemover: (itemId: string) => void;
  aoDescontar: (itemId: string, percentual: number) => void;
  bloqueado: boolean;
}) {
  if (itens.length === 0) {
    return (
      <p className="cmd-itens-vazio">
        Nada lançado ainda. Use os campos acima para acrescentar serviço ou produto.
      </p>
    );
  }

  return (
    <div className="cmd-itens-rolagem" role="region" aria-label="Itens da comanda"
      tabIndex={0}>
      <table className="cmd-itens">
        <caption className="cmd-oculto">Itens da comanda</caption>
        <thead>
          <tr>
            <th scope="col">Item</th>
            <th scope="col" className="cmd-num">Qtd</th>
            <th scope="col" className="cmd-num">Unitário</th>
            <th scope="col" className="cmd-num">Desconto</th>
            <th scope="col" className="cmd-num">Total</th>
            <th scope="col"><span className="cmd-oculto">Ações</span></th>
          </tr>
        </thead>
        <tbody>
          {itens.map((item) => (
            <Linha
              key={item.id}
              item={item}
              bloqueado={bloqueado}
              aoRemover={aoRemover}
              aoDescontar={aoDescontar}
            />
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Linha({ item, aoRemover, aoDescontar, bloqueado }: {
  item: ItemDaComanda;
  aoRemover: (itemId: string) => void;
  aoDescontar: (itemId: string, percentual: number) => void;
  bloqueado: boolean;
}) {
  const [percentual, setPercentual] = useState('');

  return (
    <tr>
      <th scope="row" className="cmd-item-nome">
        <span>{item.descricao}</span>
        <span className="cmd-item-tipo">{item.tipo === 'SERVICO' ? 'serviço' : 'produto'}</span>
        {item.executantes.map((e) => (
          <span key={e.id} className="cmd-item-executante">
            {e.papel === 'PRINCIPAL' ? 'executou' : 'ajudou'} · {e.percentualComissao}%
          </span>
        ))}
      </th>
      <td className="cmd-num">{item.quantidade}</td>
      <td className="cmd-num">{reais(item.precoUnitario)}</td>
      <td className="cmd-num">
        {bloqueado ? (
          reais(item.desconto)
        ) : (
          <form
            className="cmd-desconto"
            onSubmit={(e) => {
              e.preventDefault();
              const numero = Number(percentual.replace(',', '.'));
              if (!Number.isNaN(numero)) aoDescontar(item.id, numero);
              setPercentual('');
            }}
          >
            <input
              className="cmd-desconto-campo"
              type="text"
              inputMode="decimal"
              aria-label={`Desconto em % no item ${item.descricao}`}
              placeholder={item.desconto.valor > 0 ? reais(item.desconto) : '0%'}
              value={percentual}
              onChange={(e) => setPercentual(e.target.value)}
            />
            <button type="submit" className="cmd-oculto">Aplicar desconto</button>
          </form>
        )}
      </td>
      <td className="cmd-num cmd-item-total">{reais(item.valorTotal)}</td>
      <td className="cmd-acoes">
        {!bloqueado && (
          <button
            type="button"
            className="cmd-remover"
            onClick={() => aoRemover(item.id)}
            aria-label={`Remover ${item.descricao}`}
          >
            remover
          </button>
        )}
      </td>
    </tr>
  );
}
