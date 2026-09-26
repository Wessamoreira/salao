import { useState } from 'react';
import { Link } from 'react-router-dom';
import {
  plural, quantidade as emQuantidade, useAbrirContagem, useAplicarContagem,
  useCancelarContagem, useContagemAberta, useContarLote, useLotes, useProdutos,
  type Contagem as Folha, type Produto,
} from '../../api/estoque';
import { Esqueleto, Pagina } from '../../layout/Pagina';
import { Icone } from '../../layout/Icone';
import { Secao } from '../../layout/Secao';
import { LeitorDeCodigo } from './LeitorDeCodigo';
import { mensagem } from './Estoque';
import './estoque.css';

/**
 * RT-EST-013 — a contagem.
 *
 * <h2>A contagem é cega</h2>
 *
 * A tela <strong>não mostra o esperado</strong> enquanto se conta. Quem vê "10" conta até dez e
 * para — é o viés que RT-EST-006 já registrava como pendência, e é a razão de a contagem existir.
 * A divergência aparece depois, na folha de conferência, antes de aplicar.
 *
 * Isto é decisão de tela, e a tela sozinha não garante: o esperado continua disponível em
 * `GET /produtos/{id}/lotes`. Fechar de verdade exige o servidor recusar-se a informá-lo enquanto
 * há contagem aberta.
 *
 * <h2>A folha mostra só o que diverge</h2>
 *
 * Vinte linhas que bateram não são notícia; as três que não bateram são a decisão.
 */
export function Contagem() {
  const contagem = useContagemAberta();

  if (contagem.isPending) {
    return <Pagina><div className="est-tela"><Esqueleto linhas={3} altura={56} /></div></Pagina>;
  }

  return (
    <Pagina>
      <div className="est-tela">
        {contagem.data
          ? <EmAndamento folha={contagem.data} />
          : <Comecar />}
      </div>
    </Pagina>
  );
}

function Comecar() {
  const abrir = useAbrirContagem();
  const [observacao, definirObservacao] = useState('');

  return (
    <Secao
      id="est-comecar"
      titulo="Começar uma contagem"
      ajuda="O salão tem uma contagem de cada vez. Ela pode ser retomada de qualquer aparelho."
    >
      <form
        className="est-editor"
        aria-label="Começar contagem"
        onSubmit={(evento) => {
          evento.preventDefault();
          abrir.mutate(observacao.trim() || null);
        }}
      >
        <div className="est-bloco">
          <div className="est-campos">
            <label className="est-campo est-campo-largo">
              <span>Do que é esta contagem</span>
              <input value={observacao} autoFocus placeholder="Contagem do mês · só o depósito"
                onChange={(e) => definirObservacao(e.target.value)} />
            </label>
          </div>
          <p className="est-ajuda">
            Enquanto conta, a tela <strong>não mostra o que o sistema espera</strong>. Ver o
            número esperado enviesa a contagem — e a contagem existe justamente para descobrir
            que ele está errado.
          </p>
          {abrir.isError && <p className="erro" role="alert">{mensagem(abrir.error)}</p>}
          <div className="est-acoes-bloco">
            <button className="pagina-acao principal" type="submit" disabled={abrir.isPending}>
              {abrir.isPending ? 'Abrindo…' : 'Começar contagem'}
            </button>
            <Link className="pagina-acao discreta" to="/estoque">Voltar ao depósito</Link>
          </div>
        </div>
      </form>
    </Secao>
  );
}

function EmAndamento({ folha }: { folha: Folha }) {
  const [conferindo, definirConferindo] = useState(false);

  return conferindo
    ? <Conferencia folha={folha} aoVoltar={() => definirConferindo(false)} />
    : <Contando folha={folha} aoConferir={() => definirConferindo(true)} />;
}

function Contando({ folha, aoConferir }: { folha: Folha; aoConferir: () => void }) {
  const produtos = useProdutos();
  const [busca, definirBusca] = useState('');
  const [escolhido, definirEscolhido] = useState<Produto | null>(null);
  const [codigoSemDono, definirCodigoSemDono] = useState<string | null>(null);

  const procurado = busca.trim().toLowerCase();
  const encontrados = procurado === ''
    ? []
    : (produtos.data ?? []).filter((p) => p.nome.toLowerCase().includes(procurado));

  return (
    <Secao
      id="est-contando"
      titulo="Contando"
      ajuda={folha.observacao ?? 'Procure o produto, conte o que está na prateleira e anote.'}
      acoes={(
        <button className="pagina-acao principal" type="button" onClick={aoConferir}
          disabled={folha.itens.length === 0}>
          Conferir e aplicar
        </button>
      )}
    >
      <p className="est-ajuda">
        {folha.itens.length === 0
          ? 'Nenhum lote contado ainda.'
          : `${plural(folha.itens.length, 'lote já contado', 'lotes já contados')}. `
            + 'Contar de novo substitui o número.'}
      </p>

      {/*
        Sem `segurarFoco`, embora esta seja uma tela de bipar: logo depois do bipe a pessoa
        digita a quantidade, e um leitor que reconquista o foco tomaria o teclado do número —
        que é exatamente o modo de falha que o componente descreve para telas compartilhadas.
      */}
      <LeitorDeCodigo
        rotulo="Bipar o produto"
        aoLer={(codigo) => {
          // Resolve na lista que já está em memória, como a tela de lançamento faz: a rota
          // existe, e uma ida ao servidor por bipe seria latência entre a pessoa e a prateleira.
          const achado = (produtos.data ?? []).find((p) => p.codigoBarras === codigo);
          definirCodigoSemDono(achado ? null : codigo);
          definirEscolhido(achado ?? null);
          if (achado) definirBusca('');
        }}
      />
      {codigoSemDono && (
        <p className="erro" role="alert">
          Nenhum produto com o código {codigoSemDono}. Cadastre o código na ficha dele.
        </p>
      )}

      <label className="est-busca">
        <Icone nome="busca" />
        <input value={busca} placeholder="…ou procure pelo nome"
          aria-label="Procurar produto para contar"
          onChange={(e) => { definirBusca(e.target.value); definirEscolhido(null); }} />
      </label>

      {escolhido === null && encontrados.length > 0 && (
        <ul className="est-achados">
          {encontrados.slice(0, 8).map((produto) => (
            <li key={produto.id}>
              <button type="button" onClick={() => definirEscolhido(produto)}>
                {produto.nome}
              </button>
            </li>
          ))}
        </ul>
      )}

      {escolhido && <LotesParaContar produto={escolhido} folha={folha} />}
    </Secao>
  );
}

function LotesParaContar({ produto, folha }: { produto: Produto; folha: Folha }) {
  const lotes = useLotes(produto.id);
  const contar = useContarLote(folha.id);
  const [valores, definirValores] = useState<Record<string, string>>({});

  if (lotes.isPending) return <Esqueleto linhas={2} altura={40} />;

  const jaContado = new Map(folha.itens.map((item) => [item.loteId, item]));

  return (
    <div className="est-editor">
      <div className="est-bloco">
        <h3>{produto.nome}</h3>
        {lotes.data?.length === 0 && (
          <p className="est-ajuda">Este produto não tem caixa em estoque para contar.</p>
        )}

        <ul className="est-contar">
          {lotes.data?.map((lote) => {
            const item = jaContado.get(lote.id);
            return (
              <li key={lote.id}>
                <span className="est-contar-lote">
                  {lote.codigo ?? 'sem código'}
                  <small>
                    {lote.validade
                      ? `vence ${new Date(`${lote.validade}T12:00:00`).toLocaleDateString('pt-BR')}`
                      : 'sem validade'}
                  </small>
                </span>

                {/*
                  Aqui NÃO aparece `lote.quantidade`. É o esperado, e mostrá-lo enviesaria a
                  contagem — quem vê "10" conta até dez e para. O que se mostra é o que a
                  própria pessoa já anotou, que ela precisa ver para poder corrigir.
                */}
                {item && (
                  <span className="est-marca">
                    anotado: {emQuantidade(item.contada, produto.unidade)}
                  </span>
                )}

                <span className="est-com-acao">
                  <input inputMode="decimal" aria-label={`Quantas contou de ${lote.codigo ?? produto.nome}`}
                    value={valores[lote.id] ?? ''}
                    onChange={(e) =>
                      definirValores({ ...valores, [lote.id]: e.target.value })} />
                  <button className="pagina-acao discreta" type="button"
                    disabled={(valores[lote.id] ?? '').trim() === '' || contar.isPending}
                    onClick={() => contar.mutate({
                      loteId: lote.id,
                      contada: Number((valores[lote.id] ?? '').replace(',', '.')),
                      observacao: null,
                    }, {
                      onSuccess: () => definirValores({ ...valores, [lote.id]: '' }),
                    })}>
                    Anotar
                  </button>
                </span>
              </li>
            );
          })}
        </ul>

        {contar.isError && <p className="erro" role="alert">{mensagem(contar.error)}</p>}
      </div>
    </div>
  );
}

function Conferencia({ folha, aoVoltar }: { folha: Folha; aoVoltar: () => void }) {
  const aplicar = useAplicarContagem(folha.id);
  const cancelar = useCancelarContagem(folha.id);
  const [motivo, definirMotivo] = useState('');
  const [cancelando, definirCancelando] = useState(false);

  const divergentes = folha.itens.filter((item) => !item.bateu);
  const bateram = folha.itens.length - divergentes.length;

  if (aplicar.isSuccess) {
    return (
      <Secao id="est-aplicada" titulo="Contagem aplicada">
        <p className="est-ajuda">
          {aplicar.data.linhasAjustadas === 0
            ? 'A contagem bateu inteira. Nenhum ajuste foi necessário.'
            : `${aplicar.data.linhasAjustadas === 1
              ? '1 linha virou ajuste de estoque'
              : `${aplicar.data.linhasAjustadas} linhas viraram ajuste de estoque`}.`}
        </p>
        <div className="est-acoes-bloco">
          <Link className="pagina-acao principal" to="/estoque">Voltar ao depósito</Link>
        </div>
      </Secao>
    );
  }

  return (
    <Secao
      id="est-conferencia"
      titulo="Folha de conferência"
      ajuda={`${bateram === 1 ? '1 lote bateu' : `${bateram} lotes bateram`}.`
        + ` ${divergentes.length === 1 ? '1 não bateu' : `${divergentes.length} não bateram`}.`}
    >
      <div className="est-editor">
        <div className="est-bloco">
          {divergentes.length === 0 ? (
            <p className="est-ajuda">
              Tudo o que foi contado bate com o sistema. Aplicar não vai mexer em nada.
            </p>
          ) : (
            <ul className="est-divergencias">
              {divergentes.map((item) => (
                <li key={item.loteId}>
                  <span className="est-alerta-nome">{item.produto}</span>
                  <span className="est-ajuda">{item.loteCodigo ?? 'sem código'}</span>
                  <span className="est-divergencia-numeros">
                    esperado {item.esperada} · contado {item.contada}
                  </span>
                  <span className={`est-marca${item.divergencia < 0 ? ' grave' : ''}`}>
                    {item.divergencia > 0 ? `+${item.divergencia}` : item.divergencia}
                  </span>
                </li>
              ))}
            </ul>
          )}

          {/* R-UX-05: a confirmação diz o efeito em número, e não "tem certeza?". */}
          <p className="est-ajuda">
            Aplicar escreve <strong>{plural(divergentes.length, 'ajuste', 'ajustes')}</strong>
            {' '}de estoque, saldo
            líquido de <strong>{folha.saldoDasDivergencias}</strong>. Não volta atrás.
          </p>

          {aplicar.isError && <p className="erro" role="alert">{mensagem(aplicar.error)}</p>}
          {cancelar.isError && <p className="erro" role="alert">{mensagem(cancelar.error)}</p>}

          <div className="est-acoes-bloco">
            <button className="pagina-acao principal" type="button" disabled={aplicar.isPending}
              onClick={() => aplicar.mutate()}>
              {aplicar.isPending ? 'Aplicando…' : 'Aplicar contagem'}
            </button>
            <button className="pagina-acao discreta" type="button" onClick={aoVoltar}>
              Continuar contando
            </button>
            <button className="pagina-acao discreta" type="button"
              onClick={() => definirCancelando(!cancelando)}>
              Descartar a contagem
            </button>
          </div>

          {cancelando && (
            <form
              className="est-movimento"
              aria-label="Descartar a contagem"
              onSubmit={(evento) => {
                evento.preventDefault();
                cancelar.mutate(motivo.trim());
              }}
            >
              <label className="est-campo est-campo-largo">
                <span>Por que descartar</span>
                <input value={motivo} autoFocus placeholder="recontar amanhã com a equipe"
                  onChange={(e) => definirMotivo(e.target.value)} />
              </label>
              <button className="pagina-acao discreta" type="submit"
                disabled={motivo.trim() === '' || cancelar.isPending}>
                {cancelar.isPending ? 'Descartando…' : 'Descartar'}
              </button>
              <p className="est-ajuda">
                Nada é escrito no estoque. A contagem fica registrada como cancelada.
              </p>
            </form>
          )}
        </div>
      </div>
    </Secao>
  );
}
