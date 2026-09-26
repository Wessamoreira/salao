import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  dinheiro, plural, quantidade as emQuantidade, useAlertas, useContagemAberta, useProdutos,
  useSaldos,
  type Alertas, type Produto, type Saldo,
} from '../../api/estoque';
import { ErroDaApi } from '../../api/http';
import { Esqueleto, Falha, Pagina, Vazio } from '../../layout/Pagina';
import { Icone } from '../../layout/Icone';
import { Secao } from '../../layout/Secao';
import { ProdutoAberto } from './ProdutoAberto';
import './estoque.css';

/**
 * RT-EST-013 — o depósito.
 *
 * <h2>A falta de tela escondia oito casos de uso sem porta</h2>
 *
 * Perda, ajuste, devolução e os cinco do inventário existiam, testados, desde a Fase 3 — e nenhum
 * tinha rota HTTP. As duas rotinas já escreviam isso nas próprias pendências.
 *
 * <h2>O alerta vem antes da lista, e some quando não há alerta</h2>
 *
 * É a única banda desta tela sobre a qual se age hoje: comprar o que acabou, usar o que vai
 * vencer. Uma caixa "0 alertas" ocuparia o alto da tela todo dia para não dizer nada.
 */
export function Estoque() {
  const [busca, definirBusca] = useState('');
  // Sem isto, "Trazer de volta" seria inalcançável: o servidor lista só os ativos por padrão, e
  // um produto tirado do catálogo sumiria da tela junto com o botão que o traz de volta.
  const [incluirInativos, definirIncluirInativos] = useState(false);
  const [abrindo, definirAbrindo] = useState<string | 'novo' | null>(null);

  const produtos = useProdutos(!incluirInativos);
  const saldos = useSaldos();
  const alertas = useAlertas();
  const contagem = useContagemAberta();

  const porId = useMemo(
    () => new Map((produtos.data ?? []).map((p) => [p.id, p])), [produtos.data]);
  const emFalta = useMemo(
    () => new Set((alertas.data?.abaixoDoMinimo ?? []).map((a) => a.produtoId)),
    [alertas.data]);
  const vencendo = useMemo(
    () => new Set((alertas.data?.vencendo ?? []).map((a) => a.produtoId)), [alertas.data]);

  const procurado = busca.trim().toLowerCase();
  const lista = (saldos.data ?? [])
    .filter((s) => porId.has(s.produtoId))
    .filter((s) => procurado === '' || s.nome.toLowerCase().includes(procurado));

  const valorParado = (saldos.data ?? []).reduce((soma, s) => soma + s.valorEmEstoque, 0);

  return (
    <Pagina>
      <div className="est-tela">
        {alertas.data && !vazio(alertas.data) && <Atencao alertas={alertas.data} />}

        <Secao
          id="est-deposito"
          titulo="No depósito"
          ajuda={saldos.data
            ? `${dinheiro(valorParado)} parados na prateleira.`
            : 'Quanto tem de cada produto, e a que custo.'}
          acoes={(
            <button className="pagina-acao principal" type="button"
              onClick={() => definirAbrindo(abrindo === 'novo' ? null : 'novo')}>
              {abrindo === 'novo' ? 'Cancelar' : 'Novo produto'}
            </button>
          )}
        >
          {abrindo === 'novo' && (
            <ProdutoAberto aoFechar={() => definirAbrindo(null)} />
          )}

          <div className="est-filtros">
            <label className="est-busca">
              <Icone nome="busca" />
              <input value={busca} placeholder="Procurar produto"
                aria-label="Procurar produto"
                onChange={(e) => definirBusca(e.target.value)} />
            </label>
            <label className="est-incluir">
              <input type="checkbox" checked={incluirInativos}
                onChange={(e) => definirIncluirInativos(e.target.checked)} />
              <span>Mostrar os que saíram do catálogo</span>
            </label>
          </div>

          {(produtos.isPending || saldos.isPending) && <Esqueleto linhas={4} altura={56} />}
          {saldos.isError && (
            <Falha aoTentar={() => saldos.refetch()}>{mensagem(saldos.error)}</Falha>
          )}

          {saldos.data && lista.length === 0 && abrindo !== 'novo' && (
            <Vazio icone="caixa" titulo={procurado ? 'Nada com esse nome' : 'Depósito vazio'}>
              {procurado
                ? 'Tente outro pedaço do nome.'
                : 'Cadastre o primeiro produto e dê entrada no que chegou.'}
            </Vazio>
          )}

          {lista.length > 0 && (
            <ul className="est-lista">
              {lista.map((saldo) => (
                <li key={saldo.produtoId}>
                  {abrindo === saldo.produtoId
                    ? (
                      <ProdutoAberto produto={porId.get(saldo.produtoId)!} saldo={saldo}
                        aoFechar={() => definirAbrindo(null)} />
                    )
                    : (
                      <Linha saldo={saldo} produto={porId.get(saldo.produtoId)!}
                        emFalta={emFalta.has(saldo.produtoId)}
                        vencendo={vencendo.has(saldo.produtoId)}
                        aoAbrir={() => definirAbrindo(saldo.produtoId)} />
                    )}
                </li>
              ))}
            </ul>
          )}
        </Secao>

        <Secao
          id="est-contagem"
          titulo="Contagem"
          ajuda="Conferir a prateleira com o sistema, e deixar registrado o que não batia."
        >
          {contagem.isPending && <Esqueleto linhas={1} altura={56} />}
          {contagem.isSuccess && (
            contagem.data
              ? (
                <div className="est-contagem-aberta">
                  <p>
                    <strong>Contagem em andamento</strong>
                    {contagem.data.observacao && ` — ${contagem.data.observacao}`}
                  </p>
                  <p className="est-ajuda">
                    {contagem.data.itens.length === 0
                      ? 'Nenhum lote contado ainda.'
                      : `${plural(contagem.data.itens.length,
                        'lote contado', 'lotes contados')}.`}
                    {' '}Aberta em {new Date(contagem.data.criadoEm).toLocaleString('pt-BR')}.
                  </p>
                  <Link className="pagina-acao principal" to="/estoque/contagem">
                    Continuar a contagem
                  </Link>
                </div>
              )
              : (
                <div className="est-contagem-aberta">
                  <p className="est-ajuda">
                    Nenhuma contagem aberta. O salão tem uma de cada vez.
                  </p>
                  <Link className="pagina-acao principal" to="/estoque/contagem">
                    Começar contagem
                  </Link>
                </div>
              )
          )}
        </Secao>
      </div>
    </Pagina>
  );
}

function vazio(alertas: Alertas): boolean {
  return alertas.abaixoDoMinimo.length === 0 && alertas.vencendo.length === 0;
}

/**
 * O que pede ação hoje: o que acabou e o que vai vencer.
 *
 * As duas perguntas têm causas diferentes — uma se resolve comprando, a outra usando ou
 * descartando —, mas quem olha é a mesma pessoa no mesmo momento do dia (RT-EST-008).
 */
function Atencao({ alertas }: { alertas: Alertas }) {
  return (
    <Secao
      id="est-atencao"
      titulo="Precisa de atenção"
      ajuda={alertas.custoEmRisco > 0
        ? `${dinheiro(alertas.custoEmRisco)} em lote que vai vencer.`
        : undefined}
    >
      <div className="est-atencao">
        {alertas.abaixoDoMinimo.length > 0 && (
          <div>
            <h3>Acabando</h3>
            <ul className="est-alertas">
              {alertas.abaixoDoMinimo.map((item) => (
                <li key={item.produtoId}>
                  <span className="est-alerta-nome">{item.nome}</span>
                  <span className={`est-marca${item.zerado ? ' grave' : ''}`}>
                    {item.zerado ? 'acabou' : emQuantidade(item.quantidade, item.unidade)}
                  </span>
                  {/* O número do pedido de compra, calculado pelo servidor. */}
                  <span className="est-ajuda">
                    comprar {emQuantidade(item.faltando, item.unidade)} para voltar ao mínimo
                  </span>
                </li>
              ))}
            </ul>
          </div>
        )}

        {alertas.vencendo.length > 0 && (
          <div>
            <h3>Vencendo</h3>
            <ul className="est-alertas">
              {alertas.vencendo.map((item) => (
                <li key={item.loteId}>
                  <span className="est-alerta-nome">{item.produto}</span>
                  <span className={`est-marca${item.vencido ? ' grave' : ''}`}>
                    {item.vencido
                      ? `venceu há ${plural(Math.abs(item.diasRestantes), 'dia', 'dias')}`
                      : item.diasRestantes === 0
                        ? 'vence hoje'
                        : `vence em ${plural(item.diasRestantes, 'dia', 'dias')}`}
                  </span>
                  <span className="est-ajuda">
                    {emQuantidade(item.quantidade, item.unidade)} · {dinheiro(item.custoEmRisco)}
                  </span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </Secao>
  );
}

function Linha({ saldo, produto, emFalta, vencendo, aoAbrir }: {
  saldo: Saldo;
  produto: Produto;
  emFalta: boolean;
  vencendo: boolean;
  aoAbrir: () => void;
}) {
  return (
    <article className={`est-produto${produto.ativo ? '' : ' inativo'}`}>
      <div className="est-quem">
        <h3>{produto.nome}</h3>
        <p className="est-sub">
          {produto.codigoBarras ? `${produto.codigoBarras} · ` : ''}
          {produto.vendavel ? dinheiro(produto.precoVenda) : 'não vendável'}
        </p>
      </div>

      <p className="est-quantidade">
        {emQuantidade(saldo.quantidade, saldo.unidade)}
        {emFalta && <span className="est-marca">abaixo do mínimo</span>}
        {vencendo && <span className="est-marca">tem lote vencendo</span>}
      </p>

      {/*
        Uma coluna de dinheiro só. Custo médio e valor parado lado a lado, sem rótulo, são dois
        números que se confundem — e o custo médio é detalhe do produto, não da varredura: ele
        aparece por extenso quando o produto abre.
      */}
      <p className="est-valor">{dinheiro(saldo.valorEmEstoque)}</p>

      <div className="est-acoes">
        <button className="pagina-acao discreta" type="button" onClick={aoAbrir}>Abrir</button>
      </div>
    </article>
  );
}

/** A recusa do servidor, com as palavras dele. */
export function mensagem(erro: unknown): string {
  if (erro instanceof ErroDaApi) return erro.message;
  return 'Não foi possível falar com o servidor. Tente de novo.';
}
