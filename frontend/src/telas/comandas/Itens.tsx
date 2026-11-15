import { useState } from 'react';
import type { ItemDaComanda } from '../../api/comandas';
import { reais } from '../../api/comandas';
import { Icone } from '../../layout/Icone';

/**
 * RT-ATD-008 — as linhas da comanda, com desconto e remoção no lugar.
 *
 * Sem navegação: R-UX-08 pede serviços, produtos, desconto e pagamento na mesma tela.
 * Abrir um diálogo para descontar tiraria a recepção do contexto no meio de uma conta
 * que a cliente está esperando.
 */
export function Itens({ itens, aoRemover, aoDescontar, bloqueado, ocupado }: {
  itens: ItemDaComanda[];
  aoRemover: (itemId: string) => void;
  aoDescontar: (itemId: string, percentual: number) => void;
  bloqueado: boolean;
  /** Uma ação já está no ar: repetir o clique repetiria a ação. */
  ocupado: boolean;
}) {
  if (itens.length === 0) {
    return (
      <p className="cmd-itens-vazio">
        Nada lançado ainda. Lance um serviço ou um produto nas linhas acima.
      </p>
    );
  }

  return (
    <div className="cmd-itens-rolagem" role="region" aria-label="Itens da comanda"
      tabIndex={0}>
      {/* No toque a linha vira cartão (RT-INF-018); os papéis explícitos mantêm a tabela
          para o leitor de tela depois da troca de `display`. */}
      <table className="cmd-itens tabela-cartoes" role="table">
        <caption className="cmd-oculto">Itens da comanda</caption>
        <thead role="rowgroup">
          <tr role="row">
            <th scope="col" role="columnheader">Item</th>
            <th scope="col" role="columnheader" className="cmd-num">Qtd</th>
            <th scope="col" role="columnheader" className="cmd-num">Unitário</th>
            <th scope="col" role="columnheader" className="cmd-num">Desconto</th>
            <th scope="col" role="columnheader" className="cmd-num">Total</th>
            <th scope="col" role="columnheader"><span className="cmd-oculto">Ações</span></th>
          </tr>
        </thead>
        <tbody role="rowgroup">
          {itens.map((item) => (
            <Linha
              key={item.id}
              item={item}
              bloqueado={bloqueado}
              ocupado={ocupado}
              aoRemover={aoRemover}
              aoDescontar={aoDescontar}
            />
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Linha({ item, aoRemover, aoDescontar, bloqueado, ocupado }: {
  item: ItemDaComanda;
  aoRemover: (itemId: string) => void;
  aoDescontar: (itemId: string, percentual: number) => void;
  bloqueado: boolean;
  ocupado: boolean;
}) {
  const [percentual, setPercentual] = useState('');

  return (
    <tr role="row">
      <th scope="row" role="rowheader" className="cmd-item-nome">
        <span className="cmd-item-descricao">{item.descricao}</span>
        {/* Uma linha de apoio, em caixa normal: tipo e quem fez, separados por ponto. */}
        <span className="cmd-item-meta">
          {[item.tipo === 'SERVICO' ? 'Serviço' : 'Produto',
            ...item.executantes.map((e) =>
              `${e.papel === 'PRINCIPAL' ? 'executou' : 'ajudou'} ${e.percentualComissao}%`),
          ].join(' · ')}
        </span>
      </th>
      <td role="cell" data-rotulo="Qtd" className="cmd-num">{item.quantidade}</td>
      <td role="cell" data-rotulo="Unitário" className="cmd-num">{reais(item.precoUnitario)}</td>
      <td role="cell" data-rotulo="Desconto" className="cmd-num">
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
            <button type="submit" className="cmd-oculto" disabled={ocupado}>
              Aplicar desconto
            </button>
          </form>
        )}
      </td>
      <td role="cell" data-rotulo="Total" className="cmd-num cmd-item-total">{reais(item.valorTotal)}</td>
      <td role="cell" className="cmd-acoes">
        {!bloqueado && (
          <button
            type="button"
            className="cmd-remover"
            onClick={() => aoRemover(item.id)}
            disabled={ocupado}
            aria-label={`Remover ${item.descricao}`}
            title="Remover"
          >
            {/* Era "REMOVER" em caixa alta sublinhado, em toda linha: o elemento mais repetido
                da tabela gritava mais que o item. Ícone discreto, com nome para o leitor. */}
            <Icone nome="fechar" tamanho={16} />
          </button>
        )}
      </td>
    </tr>
  );
}
