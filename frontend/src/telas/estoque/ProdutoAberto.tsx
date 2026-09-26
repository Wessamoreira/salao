import { useState } from 'react';
import {
  dinheiro, quantidade as emQuantidade, useDefinirAtivacaoDoProduto, useDefinirCodigoDeBarras,
  useDefinirEstoqueMinimo, useLotes, useMovimentoNoLote, useRegistrarEntrada, useRegistrarPerda,
  useSalvarProduto, plural, FINALIDADES, UNIDADES_PARA_ESCOLHER,
  type Finalidade, type Lote, type Produto, type Saldo, type Unidade,
} from '../../api/estoque';
import { pode, useCapacidades } from '../../api/capacidades';
import { Esqueleto } from '../../layout/Pagina';
import { mensagem } from './Estoque';

/**
 * Um produto aberto: a ficha, as caixas que chegaram, e o que dá para fazer com elas.
 *
 * <h2>Perda é do produto; ajuste é do lote</h2>
 *
 * Não é detalhe de API — é o que a pessoa sabe. Quem viu o xampu vazar no chão sabe o produto e a
 * quantidade; o FEFO escolhe de que caixas sai. Quem tem o frasco vencido na mão, com a etiqueta,
 * sabe o lote. Obrigar a escolher lote na perda faria alguém escolher um qualquer para poder
 * continuar, e o número certo entraria no lugar errado.
 */
export function ProdutoAberto({ produto, saldo, aoFechar }: {
  produto?: Produto;
  saldo?: Saldo;
  aoFechar: () => void;
}) {
  return (
    <div className="est-editor">
      {produto && (
        <header className="est-editor-topo">
          <div className="est-editor-quem">
            <h3>{produto.nome}</h3>
            <p>
              {saldo ? emQuantidade(saldo.quantidade, saldo.unidade) : ''}
              {saldo && ` · custo médio ${dinheiro(saldo.custoMedio)}`}
            </p>
          </div>
          <button className="pagina-acao discreta" type="button" onClick={aoFechar}>Fechar</button>
        </header>
      )}

      <Ficha produto={produto} aoFechar={aoFechar} />

      {produto && (
        <>
          <Lotes produto={produto} />
          <Perda produto={produto} />
          <Ativacao produto={produto} />
        </>
      )}
    </div>
  );
}

function Ficha({ produto, aoFechar }: { produto?: Produto; aoFechar: () => void }) {
  const capacidades = useCapacidades();
  const salvar = useSalvarProduto();
  const codigo = useDefinirCodigoDeBarras();
  const minimo = useDefinirEstoqueMinimo();

  const [nome, definirNome] = useState(produto?.nome ?? '');
  const [descricao, definirDescricao] = useState(produto?.descricao ?? '');
  const [finalidade, definirFinalidade] = useState<Finalidade>(produto?.finalidade ?? 'VENDA');
  const [unidade, definirUnidade] = useState<Unidade>(produto?.unidade ?? 'UN');
  const [preco, definirPreco] = useState(
    produto?.precoVenda == null ? '' : String(produto.precoVenda));
  const [barras, definirBarras] = useState(produto?.codigoBarras ?? '');
  const [minimoTexto, definirMinimoTexto] = useState('');

  const novo = produto === undefined;
  // Pergunta por permissão, nunca por perfil: precificar é outro ato que cadastrar (RT-EST-011).
  const podePrecificar = pode(capacidades.data, 'produto:price:write');
  const podeSalvar = nome.trim() !== '' && !salvar.isPending;

  return (
    <form
      className="est-bloco"
      aria-label={novo ? 'Novo produto' : `Ficha de ${produto.nome}`}
      onSubmit={(evento) => {
        evento.preventDefault();
        if (!podeSalvar) return;
        salvar.mutate({
          id: produto?.id,
          versao: produto?.versao,
          nome: nome.trim(),
          descricao: descricao.trim() || null,
          finalidade,
          unidade,
          // Em branco é "sem preço", e sem preço o produto não é vendável — não é zero.
          precoVenda: !podePrecificar || preco.trim() === ''
            ? null : Number(preco.replace(',', '.')),
        }, { onSuccess: novo ? aoFechar : undefined });
      }}
    >
      <h3>Ficha</h3>
      <div className="est-campos">
        <label className="est-campo est-campo-largo">
          <span>Nome</span>
          <input value={nome} autoFocus={novo} placeholder="Shampoo hidratante 300ml"
            onChange={(e) => definirNome(e.target.value)} />
        </label>
        <label className="est-campo">
          <span>Para que serve</span>
          <select value={finalidade}
            onChange={(e) => definirFinalidade(e.target.value as Finalidade)}>
            {Object.entries(FINALIDADES).map(([valor, rotulo]) => (
              <option key={valor} value={valor}>{rotulo}</option>
            ))}
          </select>
        </label>
        <label className="est-campo">
          <span>Medida</span>
          {/* Só no cadastro: trocar a unidade depois reescreveria a história dos movimentos. */}
          <select value={unidade} disabled={!novo}
            onChange={(e) => definirUnidade(e.target.value as Unidade)}>
            {UNIDADES_PARA_ESCOLHER.map((u) => (
              <option key={u.valor} value={u.valor}>{u.rotulo}</option>
            ))}
          </select>
        </label>
        {podePrecificar && (
          <label className="est-campo">
            <span>Preço de venda</span>
            <input value={preco} inputMode="decimal" placeholder="sem preço"
              onChange={(e) => definirPreco(e.target.value)} />
          </label>
        )}
        <label className="est-campo est-campo-largo">
          <span>Descrição</span>
          <input value={descricao} onChange={(e) => definirDescricao(e.target.value)} />
        </label>
      </div>

      {!podePrecificar && (
        <p className="est-ajuda">
          Preço de venda é de quem precifica. Sem preço, o produto não aparece na comanda.
        </p>
      )}
      {salvar.isError && <p className="erro" role="alert">{mensagem(salvar.error)}</p>}

      <div className="est-acoes-bloco">
        <button className="pagina-acao principal" type="submit" disabled={!podeSalvar}>
          {salvar.isPending ? 'Salvando…' : novo ? 'Cadastrar' : 'Salvar ficha'}
        </button>
        {novo && (
          <button className="pagina-acao discreta" type="button" onClick={aoFechar}>
            Cancelar
          </button>
        )}
      </div>

      {/* Código de barras e mínimo têm rota própria no servidor, e é assim que a tela os trata:
          gravam sozinhos, sem passar pela ficha. */}
      {produto && (
        <div className="est-extras">
          <label className="est-campo">
            <span>Código de barras</span>
            <span className="est-com-acao">
              <input value={barras} inputMode="numeric"
                onChange={(e) => definirBarras(e.target.value)} />
              <button className="pagina-acao discreta" type="button"
                disabled={barras === (produto.codigoBarras ?? '') || codigo.isPending}
                onClick={() => codigo.mutate({ id: produto.id, codigo: barras.trim() })}>
                Gravar
              </button>
            </span>
          </label>
          <label className="est-campo">
            <span>Avisar abaixo de</span>
            <span className="est-com-acao">
              <input value={minimoTexto} inputMode="decimal" placeholder="sem mínimo"
                onChange={(e) => definirMinimoTexto(e.target.value)} />
              <button className="pagina-acao discreta" type="button"
                disabled={minimoTexto.trim() === '' || minimo.isPending}
                onClick={() => minimo.mutate({
                  id: produto.id, minimo: Number(minimoTexto.replace(',', '.')),
                })}>
                Gravar
              </button>
            </span>
          </label>
        </div>
      )}
      {(codigo.isError || minimo.isError) && (
        <p className="erro" role="alert">{mensagem(codigo.error ?? minimo.error)}</p>
      )}
    </form>
  );
}

/** As caixas, na ordem em que vão sair: vence primeiro, sai primeiro. */
function Lotes({ produto }: { produto: Produto }) {
  const lotes = useLotes(produto.id);
  const [dando, definirDando] = useState(false);

  return (
    <section className="est-bloco" aria-label="Lotes">
      <h3>Caixas em estoque</h3>

      {lotes.isPending && <Esqueleto linhas={2} altura={36} />}
      {lotes.data?.length === 0 && !dando && (
        <p className="est-ajuda">Nada em estoque. Dê entrada no que chegou.</p>
      )}

      {lotes.data && lotes.data.length > 0 && (
        <ul className="est-lotes">
          {lotes.data.map((lote) => (
            <LoteLinha key={lote.id} lote={lote} unidade={produto.unidade} />
          ))}
        </ul>
      )}

      {dando
        ? <Entrada produto={produto} aoFechar={() => definirDando(false)} />
        : (
          <div className="est-acoes-bloco">
            <button className="pagina-acao principal" type="button"
              onClick={() => definirDando(true)}>Dar entrada</button>
          </div>
        )}
    </section>
  );
}

function LoteLinha({ lote, unidade }: { lote: Lote; unidade: Unidade }) {
  const movimento = useMovimentoNoLote();
  const [abrindo, definirAbrindo] = useState<'ajustes' | 'devolucoes' | null>(null);
  const [valor, definirValor] = useState('');
  const [motivo, definirMotivo] = useState('');

  return (
    <li>
      <div className="est-lote">
        <span className="est-lote-quanto">{emQuantidade(lote.quantidade, unidade)}</span>
        <span className="est-lote-validade">
          {lote.validade
            ? `vence ${new Date(`${lote.validade}T12:00:00`).toLocaleDateString('pt-BR')}`
            : 'sem validade'}
        </span>
        <span className="est-ajuda">{lote.codigo ?? '—'} · {dinheiro(lote.custoUnitario)}</span>
        <span className="est-lote-acoes">
          <button className="pagina-acao discreta" type="button"
            onClick={() => definirAbrindo(abrindo === 'ajustes' ? null : 'ajustes')}>
            Ajustar
          </button>
          <button className="pagina-acao discreta" type="button"
            onClick={() => definirAbrindo(abrindo === 'devolucoes' ? null : 'devolucoes')}>
            Devolver
          </button>
        </span>
      </div>

      {abrindo && (
        <form
          className="est-movimento"
          aria-label={abrindo === 'ajustes' ? 'Ajustar lote' : 'Devolver ao fornecedor'}
          onSubmit={(evento) => {
            evento.preventDefault();
            movimento.mutate({
              loteId: lote.id, tipo: abrindo,
              quantidade: Number(valor.replace(',', '.')), motivo: motivo.trim(),
            }, { onSuccess: () => { definirAbrindo(null); definirValor(''); definirMotivo(''); } });
          }}
        >
          <label className="est-campo">
            <span>{abrindo === 'ajustes' ? 'Diferença' : 'Quantas voltam'}</span>
            <input value={valor} inputMode="decimal"
              placeholder={abrindo === 'ajustes' ? '-3' : '2'}
              onChange={(e) => definirValor(e.target.value)} />
          </label>
          <label className="est-campo est-campo-largo">
            <span>Motivo</span>
            <input value={motivo} onChange={(e) => definirMotivo(e.target.value)}
              placeholder={abrindo === 'ajustes'
                ? 'três frascos quebrados na estante' : 'veio com a tampa rompida'} />
          </label>
          <button className="pagina-acao principal" type="submit"
            disabled={valor.trim() === '' || movimento.isPending}>
            {movimento.isPending ? 'Registrando…' : 'Registrar'}
          </button>
          {abrindo === 'ajustes' && (
            <p className="est-ajuda">
              Com sinal: <strong>−3</strong> sumiram três, <strong>3</strong> apareceram três.
            </p>
          )}
          {movimento.isError && <p className="erro" role="alert">{mensagem(movimento.error)}</p>}
        </form>
      )}
    </li>
  );
}

function Entrada({ produto, aoFechar }: { produto: Produto; aoFechar: () => void }) {
  const registrar = useRegistrarEntrada(produto.id);
  const [quantidade, definirQuantidade] = useState('');
  const [custo, definirCusto] = useState('');
  const [codigo, definirCodigo] = useState('');
  const [validade, definirValidade] = useState('');

  const pode = quantidade.trim() !== '' && custo.trim() !== '' && !registrar.isPending;

  return (
    <form
      className="est-movimento"
      aria-label="Dar entrada"
      onSubmit={(evento) => {
        evento.preventDefault();
        if (!pode) return;
        registrar.mutate({
          quantidade: Number(quantidade.replace(',', '.')),
          // Zero é válido: amostra e brinde do fornecedor chegam sem custar nada.
          custoUnitario: Number(custo.replace(',', '.')),
          codigo: codigo.trim() || null,
          validade: validade || null,
          recebidoEm: null,
        }, { onSuccess: aoFechar });
      }}
    >
      <label className="est-campo">
        <span>Quantas chegaram</span>
        <input value={quantidade} inputMode="decimal" autoFocus
          onChange={(e) => definirQuantidade(e.target.value)} />
      </label>
      <label className="est-campo">
        <span>Custo de cada</span>
        <input value={custo} inputMode="decimal" placeholder="0,00"
          onChange={(e) => definirCusto(e.target.value)} />
      </label>
      <label className="est-campo">
        <span>Vence em</span>
        <input type="date" value={validade} onChange={(e) => definirValidade(e.target.value)} />
      </label>
      <label className="est-campo">
        <span>Lote do fabricante</span>
        <input value={codigo} onChange={(e) => definirCodigo(e.target.value)} />
      </label>

      {registrar.isError && <p className="erro" role="alert">{mensagem(registrar.error)}</p>}

      <div className="est-acoes-bloco">
        <button className="pagina-acao principal" type="submit" disabled={!pode}>
          {registrar.isPending ? 'Registrando…' : 'Registrar entrada'}
        </button>
        <button className="pagina-acao discreta" type="button" onClick={aoFechar}>Cancelar</button>
      </div>
    </form>
  );
}

function Perda({ produto }: { produto: Produto }) {
  const registrar = useRegistrarPerda(produto.id);
  const [quantidade, definirQuantidade] = useState('');
  const [motivo, definirMotivo] = useState('');

  const pode = quantidade.trim() !== '' && motivo.trim() !== '' && !registrar.isPending;

  return (
    <form
      className="est-bloco"
      aria-label="Registrar perda"
      onSubmit={(evento) => {
        evento.preventDefault();
        if (!pode) return;
        registrar.mutate({
          quantidade: Number(quantidade.replace(',', '.')), motivo: motivo.trim(),
        }, { onSuccess: () => { definirQuantidade(''); definirMotivo(''); } });
      }}
    >
      <h3>Perda</h3>
      <p className="est-ajuda">
        Quebrou, vazou, venceu na prateleira. O sistema escolhe de que caixas tirar — a mais
        velha primeiro.
      </p>

      <div className="est-campos">
        <label className="est-campo">
          <span>Quanto se perdeu</span>
          <input value={quantidade} inputMode="decimal"
            onChange={(e) => definirQuantidade(e.target.value)} />
        </label>
        <label className="est-campo est-campo-largo">
          <span>O que aconteceu</span>
          <input value={motivo} placeholder="caixa molhada na chuva"
            onChange={(e) => definirMotivo(e.target.value)} />
        </label>
      </div>

      {registrar.isError && <p className="erro" role="alert">{mensagem(registrar.error)}</p>}
      {registrar.isSuccess && (
        <p className="est-ajuda" role="status">
          Registrado: {dinheiro(registrar.data.custoTotal)} em{' '}
          {plural(registrar.data.lotesAtingidos, 'caixa', 'caixas')}.
        </p>
      )}

      <div className="est-acoes-bloco">
        <button className="pagina-acao discreta" type="submit" disabled={!pode}>
          {registrar.isPending ? 'Registrando…' : 'Registrar perda'}
        </button>
      </div>
    </form>
  );
}

function Ativacao({ produto }: { produto: Produto }) {
  const ativacao = useDefinirAtivacaoDoProduto();

  return (
    <section className="est-bloco" aria-label="Situação do produto">
      <h3>Situação</h3>
      <p className="est-ajuda">
        Tirar do catálogo não apaga nada: o histórico de movimento continua apontando para ele.
      </p>
      {ativacao.isError && <p className="erro" role="alert">{mensagem(ativacao.error)}</p>}
      <div className="est-acoes-bloco">
        <button className="pagina-acao discreta" type="button" disabled={ativacao.isPending}
          onClick={() => ativacao.mutate({ id: produto.id, ativo: !produto.ativo })}>
          {produto.ativo ? 'Tirar do catálogo' : 'Trazer de volta'}
        </button>
      </div>
    </section>
  );
}
