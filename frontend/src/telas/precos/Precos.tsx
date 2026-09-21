import { useState } from 'react';
import {
  useAprovar, useDecisoesDePreco, useGerarSugestoes, usePainelDePrecos, useRecusar,
  useSalvarMargemMinima,
  type Decisao, type Sugestao,
} from '../../api/precos';
import { useCapacidades } from '../../api/capacidades';
import { ErroDaApi } from '../../api/http';
import { Esqueleto, Falha, Pagina } from '../../layout/Pagina';
import { formatar } from '../../layout/tempo';
import './precos.css';

/**
 * RT-EST-010 e RT-EST-011 — que preço está errado hoje, e o clique humano que decide.
 *
 * <h2>Os dois preços lado a lado, com as duas margens</h2>
 *
 * É o número que o dono precisa para discordar. Mostrar só o sugerido pediria confiança; mostrar os
 * dois pede decisão — que é o que esta tela existe para provocar.
 *
 * <h2>Nada aqui calcula preço</h2>
 *
 * A régua de 9,90, o chão de margem e os quatro motivos são regra do servidor. Se a tela recalculasse
 * qualquer um deles, passaria a haver duas versões da mesma regra, e a segunda seria a que ninguém
 * lembra de corrigir.
 */
export function Precos() {
  const { data: capacidades } = useCapacidades();
  const fuso = capacidades?.estabelecimento.timezone ?? 'America/Sao_Paulo';

  return (
    <Pagina>
      <div className="prc-tela">
        <Regua />
        <Sugestoes />
        <Trilha fuso={fuso} />
      </div>
    </Pagina>
  );
}

/** A margem mínima: o único número que o dono precisa dar para a rotina existir. */
function Regua() {
  const painel = usePainelDePrecos();
  const salvar = useSalvarMargemMinima();
  const [rascunho, definirRascunho] = useState<string | null>(null);
  const [salvo, definirSalvo] = useState(false);

  const atual = painel.data?.margemMinima;
  const valor = rascunho ?? (atual == null ? '' : String(atual).replace('.', ','));
  const numero = Number(valor.replace(',', '.'));
  const valido = valor.trim() !== '' && Number.isFinite(numero) && numero >= 0 && numero <= 1000;

  return (
    <section className="prc-bloco" aria-labelledby="prc-regua">
      <header className="prc-cabecalho">
        <div>
          <h2 id="prc-regua" className="prc-titulo">Margem mínima</h2>
          <p className="prc-ajuda">
            Nenhuma sugestão fica abaixo do custo médio mais esta margem — e aprovar também não deixa.
          </p>
        </div>
        {salvo && <span className="prc-salvo" role="status">Salvo</span>}
      </header>

      {painel.isPending ? <Esqueleto linhas={1} altura={44} />
        : (
          <form className="prc-regua-forma" onSubmit={(e) => {
            e.preventDefault();
            if (!valido || salvar.isPending) return;
            salvar.mutate(numero, {
              onSuccess: () => { definirRascunho(null); definirSalvo(true); },
            });
          }}>
            <label className="prc-com-sufixo">
              <input
                type="text" inputMode="decimal" value={valor} aria-label="Margem mínima"
                placeholder="30"
                onChange={(e) => { definirSalvo(false); definirRascunho(e.target.value); }}
              />
              <span aria-hidden="true">%</span>
            </label>
            <button className="pagina-acao principal" type="submit"
              disabled={!valido || salvar.isPending}>
              {salvar.isPending ? 'Salvando…' : 'Salvar margem'}
            </button>
            {salvar.isError && <p className="erro" role="alert">{mensagem(salvar.error)}</p>}
          </form>
        )}
    </section>
  );
}

function Sugestoes() {
  const painel = usePainelDePrecos();
  const gerar = useGerarSugestoes();
  // Uma decisão por vez: abrir a segunda fecha a primeira, e o que foi digitado não fica órfão.
  const [decidindo, definirDecidindo] = useState<{ id: string; acao: 'APROVAR' | 'RECUSAR' } | null>(null);

  const semRegua = painel.data != null && painel.data.margemMinima == null;

  return (
    <section className="prc-bloco" aria-labelledby="prc-sugestoes">
      <header className="prc-cabecalho">
        <div>
          <h2 id="prc-sugestoes" className="prc-titulo">Sugestões</h2>
          <p className="prc-ajuda">
            {painel.data?.geradaEm
              ? `Da última vez que você pediu. Nada muda de preço sem o seu clique.`
              : 'Nada muda de preço sem o seu clique.'}
          </p>
        </div>
        <button className="pagina-acao discreta" type="button"
          disabled={gerar.isPending || semRegua}
          onClick={() => { gerar.reset(); gerar.mutate(); }}>
          {gerar.isPending ? 'Procurando…' : 'Procurar preços errados'}
        </button>
      </header>

      {semRegua && (
        <p className="prc-aviso" role="status">
          Defina a margem mínima acima. Sem ela o sistema não sabe qual é o piso, e um piso escolhido
          por ele seria um número que ninguém decidiu.
        </p>
      )}
      {gerar.isError && <p className="erro" role="alert">{mensagem(gerar.error)}</p>}

      {painel.isPending ? <Esqueleto linhas={3} altura={120} />
        : painel.isError ? <Falha aoTentar={() => painel.refetch()}>{mensagem(painel.error)}</Falha>
          : painel.data.itens.length === 0
            ? (
              <p className="prc-nada">
                {painel.data.geradaEm
                  ? 'Nenhum preço fora da régua agora.'
                  : 'Peça uma busca para ver o que está fora da régua.'}
              </p>
            )
            : (
              <ul className="prc-lista">
                {painel.data.itens.map((sugestao) => (
                  <Cartao
                    key={sugestao.id}
                    sugestao={sugestao}
                    aberta={decidindo?.id === sugestao.id ? decidindo.acao : null}
                    aoAbrir={(acao) => definirDecidindo(
                      decidindo?.id === sugestao.id && decidindo.acao === acao
                        ? null : { id: sugestao.id, acao })}
                    aoFechar={() => definirDecidindo(null)}
                  />
                ))}
              </ul>
            )}
    </section>
  );
}

function Cartao({ sugestao, aberta, aoAbrir, aoFechar }: {
  sugestao: Sugestao;
  aberta: 'APROVAR' | 'RECUSAR' | null;
  aoAbrir: (acao: 'APROVAR' | 'RECUSAR') => void;
  aoFechar: () => void;
}) {
  const aprovar = useAprovar();
  const recusar = useRecusar();
  // Com os dois centavos: "49,9" num campo de preço se lê como número pela metade.
  const [preco, definirPreco] = useState(sugestao.precoSugerido.toFixed(2).replace('.', ','));
  const [motivo, definirMotivo] = useState('');

  const numero = Number(preco.replace(',', '.'));
  const precoValido = preco.trim() !== '' && Number.isFinite(numero) && numero > 0;
  const motivoValido = motivo.trim().length >= 3;

  return (
    <li className="prc-cartao" data-motivo={sugestao.motivo}>
      <p className="prc-etiqueta">{sugestao.rotulo}</p>
      <h3 className="prc-produto">{sugestao.produto}</h3>
      <p className="prc-racional">{sugestao.racional}</p>
      {sugestao.explicacao && <p className="prc-explicacao">{sugestao.explicacao}</p>}

      <dl className="prc-precos">
        <div>
          <dt>Hoje</dt>
          <dd>
            <span className="prc-valor">{reais(sugestao.precoAtual)}</span>
            <span className="prc-margem">{percentual(sugestao.margemAtual)}</span>
          </dd>
        </div>
        <div className="destaque">
          <dt>Sugerido</dt>
          <dd>
            <span className="prc-valor">{reais(sugestao.precoSugerido)}</span>
            <span className="prc-margem">{percentual(sugestao.margemSugerida)}</span>
          </dd>
        </div>
      </dl>

      <div className="prc-acoes">
        <button type="button" className="prc-botao" aria-expanded={aberta === 'APROVAR'}
          onClick={() => { aprovar.reset(); aoAbrir('APROVAR'); }}>
          Aprovar
        </button>
        <button type="button" className="prc-botao" aria-expanded={aberta === 'RECUSAR'}
          onClick={() => { recusar.reset(); aoAbrir('RECUSAR'); }}>
          Recusar
        </button>
      </div>

      {aberta === 'APROVAR' && (
        <form className="prc-decisao" onSubmit={(e) => {
          e.preventDefault();
          if (!precoValido || aprovar.isPending) return;
          aprovar.mutate({ id: sugestao.id, preco: numero }, { onSuccess: aoFechar });
        }}>
          <label className="prc-decisao-campo" htmlFor={`prc-preco-${sugestao.id}`}>
            Aplicar o preço de
          </label>
          <span className="prc-com-sufixo">
            <span aria-hidden="true">R$</span>
            <input id={`prc-preco-${sugestao.id}`} type="text" inputMode="decimal" value={preco}
              autoFocus onChange={(e) => definirPreco(e.target.value)} />
          </span>
          <p className="prc-ajuda">
            Vem com o sugerido, e dá para mudar. Abaixo da margem mínima o sistema recusa.
          </p>
          {aprovar.isError && <p className="erro" role="alert">{mensagem(aprovar.error)}</p>}
          <div className="prc-decisao-botoes">
            <button className="pagina-acao principal" type="submit"
              disabled={!precoValido || aprovar.isPending}>
              {aprovar.isPending ? 'Aplicando…' : 'Aplicar'}
            </button>
            <button className="pagina-acao discreta" type="button" onClick={aoFechar}>Cancelar</button>
          </div>
        </form>
      )}

      {aberta === 'RECUSAR' && (
        <form className="prc-decisao" onSubmit={(e) => {
          e.preventDefault();
          if (!motivoValido || recusar.isPending) return;
          recusar.mutate({ id: sugestao.id, motivo: motivo.trim() }, { onSuccess: aoFechar });
        }}>
          <label className="prc-decisao-campo" htmlFor={`prc-motivo-${sugestao.id}`}>Por quê?</label>
          <textarea id={`prc-motivo-${sugestao.id}`} rows={2} maxLength={500} value={motivo}
            autoFocus placeholder="Ex.: cliente fiel leva esse produto toda semana"
            onChange={(e) => definirMotivo(e.target.value)} />
          <p className="prc-ajuda">
            Este produto para de pedir a mesma coisa por 90 dias. Se o custo mudar, ele volta.
          </p>
          {recusar.isError && <p className="erro" role="alert">{mensagem(recusar.error)}</p>}
          <div className="prc-decisao-botoes">
            <button className="pagina-acao principal" type="submit"
              disabled={!motivoValido || recusar.isPending}>
              {recusar.isPending ? 'Gravando…' : 'Confirmar recusa'}
            </button>
            <button className="pagina-acao discreta" type="button" onClick={aoFechar}>Cancelar</button>
          </div>
        </form>
      )}
    </li>
  );
}

/** RT-EST-011 — o que já foi decidido, e com que informação. */
function Trilha({ fuso }: { fuso: string }) {
  const consulta = useDecisoesDePreco();
  const itens = consulta.data?.pages.flatMap((pagina) => pagina.itens) ?? [];

  return (
    <section className="prc-bloco" aria-labelledby="prc-trilha">
      <h2 id="prc-trilha" className="prc-titulo">Decisões</h2>
      <p className="prc-ajuda">
        O que foi aplicado e o que foi recusado, com os números da época.
      </p>

      {consulta.isPending ? <Esqueleto linhas={3} altura={36} />
        : consulta.isError ? <Falha aoTentar={() => consulta.refetch()}>{mensagem(consulta.error)}</Falha>
          : itens.length === 0 ? <p className="prc-nada">Nenhuma decisão ainda.</p>
            : (
              <div className="prc-rolagem">
                <table className="prc-tabela">
                  <thead>
                    <tr>
                      <th>Quando</th>
                      <th>Produto</th>
                      <th>O que foi feito</th>
                      <th>Motivo da recusa</th>
                    </tr>
                  </thead>
                  <tbody>
                    {itens.map((decisao) => (
                      <tr key={decisao.id}>
                        <td>
                          {quando(decisao.decididaEm, fuso)}
                          {decisao.quemDecidiu && <span className="prc-quem"> · {decisao.quemDecidiu}</span>}
                        </td>
                        <td>{decisao.produto}</td>
                        <td>{oQueFoiFeito(decisao)}</td>
                        <td className="prc-justificativa">{decisao.justificativa ?? '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
      {consulta.hasNextPage && (
        <div className="prc-mais">
          <button className="pagina-acao discreta" type="button"
            disabled={consulta.isFetchingNextPage} onClick={() => consulta.fetchNextPage()}>
            {consulta.isFetchingNextPage ? 'Carregando…' : 'Carregar mais'}
          </button>
        </div>
      )}
    </section>
  );
}

function oQueFoiFeito(decisao: Decisao): string {
  if (decisao.decisao === 'RECUSADA') {
    return `Recusou ${reais(decisao.precoSugerido)}`;
  }
  const ajustou = decisao.precoAplicado !== decisao.precoSugerido;
  return `${reais(decisao.precoAnterior)} → ${reais(decisao.precoAplicado)}`
    + (ajustou ? ` (sugerido ${reais(decisao.precoSugerido)})` : '');
}

function reais(valor: number | null): string {
  return valor == null ? '—'
    : new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(valor);
}

function percentual(valor: number | null): string {
  return valor == null ? 'sem preço'
    : `${valor.toFixed(1).replace('.', ',')}% de margem`;
}

function quando(instante: string, fuso: string): string {
  return formatar(new Date(instante), fuso, {
    day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  });
}

function mensagem(erro: unknown): string {
  return erro instanceof ErroDaApi ? erro.message : 'Tente de novo em instantes.';
}
