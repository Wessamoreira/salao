import { useState } from 'react';
import type { ContaDaComanda, FormaDePagamento } from '../../api/comandas';
import { FORMAS, reais } from '../../api/comandas';
import { Icone } from '../../layout/Icone';

/**
 * RT-ATD-008 — o split, e o restante sempre visível.
 *
 * *"Split de pagamento é requisito, não extra"* (R-UX-08): metade cartão e metade pix
 * acontece o tempo todo. Por isso a lista de formas registradas fica ao lado do campo,
 * e não escondida atrás de um "adicionar outra forma".
 *
 * O valor do campo já vem preenchido com **o que falta** — que é o que a recepção vai
 * digitar em nove de cada dez vezes.
 *
 * RT-ATD-009 — a forma é escolhida em botões de rádio, não num `<select>`: é a escolha mais
 * repetida do dia, e um toque vale mais que abrir uma lista. Rádio nativo, então teclado
 * (setas) e leitor de tela funcionam sem nada a mais.
 */
export function Pagamentos({ conta, aoRegistrar, aoRemover, bloqueado, ocupado }: {
  conta: ContaDaComanda;
  aoRegistrar: (forma: FormaDePagamento, valor: number) => void;
  aoRemover: (pagamentoId: string) => void;
  bloqueado: boolean;
  /** Uma ação já está no ar: repetir o clique repetiria a ação. */
  ocupado: boolean;
}) {
  const [forma, setForma] = useState<FormaDePagamento>('DINHEIRO');
  const [valor, setValor] = useState('');

  const restante = conta.restante.valor;
  const quitada = restante <= 0;

  return (
    <section className="cmd-pagamentos" aria-label="Pagamento">
      {conta.pagamentos.length > 0 && (
      <ul className="cmd-pagos">
        {conta.pagamentos.map((p) => (
          <li key={p.id} className="cmd-pago">
            <span className="cmd-pago-forma">
              {FORMAS.find((f) => f.forma === p.forma)?.rotulo ?? p.forma}
              {p.parcelas > 1 && <span className="cmd-pago-parcelas"> {p.parcelas}x</span>}
            </span>
            <span className="cmd-pago-valor">{reais(p.valor)}</span>
            {!bloqueado && (
              <button type="button" className="cmd-remover" onClick={() => aoRemover(p.id)}
                disabled={ocupado} title="Remover"
                aria-label={`Remover pagamento de ${reais(p.valor)}`}>
                <Icone nome="fechar" tamanho={16} />
              </button>
            )}
          </li>
        ))}
      </ul>
      )}

      {!bloqueado && (
        <form
          className="cmd-forma"
          onSubmit={(e) => {
            e.preventDefault();
            const numero = Number((valor || String(restante)).replace(',', '.'));
            if (numero > 0) {
              aoRegistrar(forma, numero);
              setValor('');
            }
          }}
        >
          <fieldset className="cmd-formas">
            <legend className="cmd-rotulo">Forma</legend>
            {FORMAS.map((f) => (
              <label key={f.forma} className="cmd-forma-opcao">
                <input type="radio" name="cmd-forma" value={f.forma}
                  checked={forma === f.forma} onChange={() => setForma(f.forma)} />
                <span>{f.rotulo}</span>
              </label>
            ))}
          </fieldset>

          <label className="cmd-rotulo" htmlFor="cmd-valor">Valor</label>
          <input
            id="cmd-valor"
            className="cmd-campo"
            type="text"
            inputMode="decimal"
            /*
             * O que falta, já preenchido. É o número que a recepção vai digitar quase
             * sempre — e deixá-lo como placeholder, em vez de valor, mantém o campo
             * editável para o split sem obrigar a apagar nada.
             */
            placeholder={restante > 0 ? restante.toFixed(2).replace('.', ',') : '0,00'}
            value={valor}
            onChange={(e) => setValor(e.target.value)}
          />

          <button type="submit" className="cmd-botao" disabled={quitada || ocupado}>
            Registrar
          </button>
        </form>
      )}
    </section>
  );
}
