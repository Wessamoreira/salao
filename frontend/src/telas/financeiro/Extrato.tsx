import { useState } from 'react';
import {
  dinheiro, useAjustarRazao, useEstornarLancamento, useExtrato, TIPOS,
  type LinhaDoExtrato, type Periodo,
} from '../../api/financeiro';
import { Esqueleto } from '../../layout/Pagina';
import { mensagem } from './Financeiro';

/**
 * RT-FIN-004 — o extrato de uma pessoa, item a item.
 *
 * <h2>Estornar e ajustar moram juntos</h2>
 *
 * Quem abre uma linha errada quer uma das duas coisas e não sabe o nome delas. A tela pergunta o
 * que aconteceu — "essa linha não devia existir" ou "esse valor está errado" — e escolhe o verbo:
 * estornar anula, ajustar reaplica (RN-FIN-023 e RN-FIN-041).
 */
export function Extrato({ profissionalId, periodo }: {
  profissionalId: string;
  periodo: Periodo;
}) {
  const extrato = useExtrato(profissionalId, periodo);

  if (extrato.isPending) return <Esqueleto linhas={4} altura={32} />;
  if (extrato.isError) {
    return <p className="erro" role="alert">{mensagem(extrato.error)}</p>;
  }
  if (!extrato.data) return null;

  return (
    <div className="fin-extrato">
      <div className="fin-totais">
        {extrato.data.totais.map((total) => (
          <p key={total.tipo} className="fin-numero">
            <span>{TIPOS[total.tipo]}</span>
            <strong className={total.valor < 0 ? 'devendo' : undefined}>
              {dinheiro(total.valor)}
            </strong>
          </p>
        ))}
        <p className="fin-numero destaque">
          <span>a receber</span>
          <strong>{dinheiro(extrato.data.aReceber)}</strong>
        </p>
      </div>

      {extrato.data.linhas.length === 0
        ? <p className="fin-ajuda">Nenhum lançamento neste período.</p>
        : (
          <ul className="fin-linhas">
            {extrato.data.linhas.map((linha) => (
              <LinhaDoRazao key={linha.lancamentoId} linha={linha} />
            ))}
          </ul>
        )}

      <Ajuste profissionalId={profissionalId} />
    </div>
  );
}

function LinhaDoRazao({ linha }: { linha: LinhaDoExtrato }) {
  const estornar = useEstornarLancamento();
  const [aberta, definirAberta] = useState(false);
  const [motivo, definirMotivo] = useState('');

  return (
    <li>
      <div className="fin-linha">
        <span className="fin-linha-data">
          {new Date(`${linha.data}T12:00:00Z`).toLocaleDateString('pt-BR',
            { day: '2-digit', month: '2-digit', timeZone: 'UTC' })}
        </span>
        <span className="fin-linha-tipo">{TIPOS[linha.tipo]}</span>
        <span className="fin-linha-descricao">{linha.descricao}</span>
        <span className={`fin-linha-valor${linha.valor < 0 ? ' devendo' : ''}`}>
          {dinheiro(linha.valor)}
        </span>
        {/* Estornar um estorno é recusado pelo servidor (RN-FIN-023); o caminho é o ajuste. */}
        {linha.tipo !== 'ESTORNO' && (
          <button className="pagina-acao discreta" type="button"
            onClick={() => definirAberta(!aberta)}>
            {aberta ? 'Deixar' : 'Anular'}
          </button>
        )}
      </div>

      {aberta && (
        <form
          className="fin-correcao"
          aria-label={`Anular ${linha.descricao}`}
          onSubmit={(evento) => {
            evento.preventDefault();
            estornar.mutate({ id: linha.lancamentoId, motivo: motivo.trim() },
              { onSuccess: () => { definirAberta(false); definirMotivo(''); } });
          }}
        >
          <label className="fin-campo fin-campo-largo">
            <span>Por que anular</span>
            <input value={motivo} autoFocus placeholder="comissão lançada na pessoa errada"
              onChange={(e) => definirMotivo(e.target.value)} />
          </label>
          <button className="pagina-acao principal" type="submit"
            disabled={motivo.trim() === '' || estornar.isPending}>
            {estornar.isPending ? 'Anulando…' : `Anular ${dinheiro(linha.valor)}`}
          </button>
          <p className="fin-ajuda">
            A linha continua no extrato, com outra de sinal contrário ao lado. Para corrigir o
            <strong> valor</strong>, use o ajuste no fim da lista — anular e lançar de novo é o
            caminho, e é o que deixa rastro.
          </p>
          {estornar.isError && <p className="erro" role="alert">{mensagem(estornar.error)}</p>}
        </form>
      )}
    </li>
  );
}

/**
 * RN-FIN-041 — a correção que faltava.
 *
 * O valor se digita no sinal de quem recebe: positivo aumenta o que a pessoa tem a receber. No
 * razão a comissão é negativa, e pedir esse sinal aqui seria pedir que quem corrige um erro de
 * digitação conhecesse a contabilidade do livro.
 */
function Ajuste({ profissionalId }: { profissionalId: string }) {
  const ajustar = useAjustarRazao();
  const [aberto, definirAberto] = useState(false);
  const [valor, definirValor] = useState('');
  const [motivo, definirMotivo] = useState('');

  const numero = Number(valor.replace(',', '.'));
  const valido = valor.trim() !== '' && !Number.isNaN(numero) && numero !== 0
    && motivo.trim() !== '';

  if (!aberto) {
    return (
      <div className="fin-acoes">
        <button className="pagina-acao discreta" type="button"
          onClick={() => { ajustar.reset(); definirAberto(true); }}>
          Corrigir um valor
        </button>
      </div>
    );
  }

  return (
    <form
      className="fin-correcao"
      aria-label="Corrigir um valor"
      onSubmit={(evento) => {
        evento.preventDefault();
        if (!valido) return;
        ajustar.mutate({ profissionalId, valor: numero, motivo: motivo.trim() },
          { onSuccess: () => { definirAberto(false); definirValor(''); definirMotivo(''); } });
      }}
    >
      <label className="fin-campo">
        <span>Quanto a mais ou a menos</span>
        <input value={valor} inputMode="decimal" autoFocus placeholder="45,00"
          aria-label="Valor do ajuste"
          onChange={(e) => definirValor(e.target.value)} />
      </label>
      <label className="fin-campo fin-campo-largo">
        <span>Por quê</span>
        <input value={motivo} placeholder="comissão apurada a menos na comanda de ontem"
          onChange={(e) => definirMotivo(e.target.value)} />
      </label>

      <p className="fin-ajuda">
        Positivo <strong>aumenta</strong> o que ela tem a receber; negativo reduz. A linha entra
        com a data de hoje e fica no extrato, com este motivo.
      </p>

      {ajustar.isError && <p className="erro" role="alert">{mensagem(ajustar.error)}</p>}

      <div className="fin-acoes">
        <button className="pagina-acao principal" type="submit" disabled={!valido}>
          {ajustar.isPending ? 'Lançando…' : 'Lançar o ajuste'}
        </button>
        <button className="pagina-acao discreta" type="button"
          onClick={() => definirAberto(false)}>Cancelar</button>
      </div>
    </form>
  );
}
