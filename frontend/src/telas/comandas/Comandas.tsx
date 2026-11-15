import { Pagina, Vazio } from '../../layout/Pagina';
import { useState, type ReactNode } from 'react';
import { Icone } from '../../layout/Icone';
import { ErroDaApi } from '../../api/http';
import {
  reais, useAplicarDesconto, useComanda, useComandasAbertas, useConta, useFechar,
  useLancarProduto, useLancarServico, useRegistrarPagamento, useRemoverItem,
  useRemoverPagamento,
} from '../../api/comandas';
import { BarraDeAbertas } from './BarraDeAbertas';
import { Itens } from './Itens';
import { Lancar } from './Lancar';
import { Pagamentos } from './Pagamentos';
import './comandas.css';

/**
 * RT-ATD-008 — a tela de fechamento, em um passo só.
 *
 * <h2>Uma tela, sem navegação</h2>
 *
 * R-UX-08 pede serviços, produtos, desconto e pagamento na mesma tela. Não é preferência
 * estética: quem opera isto está com a cliente na frente, e cada passo de navegação é
 * uma chance de perder o fio no meio de uma conta.
 *
 * <h2>O restante vem do servidor, sempre</h2>
 *
 * Nenhuma soma acontece aqui. Lançar item, descontar e pagar devolvem a comanda ou a
 * conta inteira, e a tela mostra o que chegou. Subtrair no front seria a mesma conta em
 * dois lugares (RN-ATD-008) — e a divergência apareceria como um centavo que impede o
 * fechamento, com a cliente esperando.
 */
export function Comandas() {
  const [escolhida, setEscolhida] = useState<string | null>(null);
  const [erro, setErro] = useState<string | null>(null);

  const { data: abertas } = useComandasAbertas();

  /*
   * A seleção é DERIVADA, não sincronizada por efeito: enquanto ninguém escolheu, vale
   * a primeira comanda aberta — que é a mais antiga, e a que R-UX-07 diz ser a que
   * interessa. Guardar isso num efeito faria a tela renderizar uma vez sem seleção
   * antes de se corrigir, e piscaria a cada chegada da lista.
   */
  const selecionadaId = escolhida ?? abertas?.[0]?.comanda.id ?? null;
  const { data: detalhada, isFetching: relendoComanda } = useComanda(selecionadaId);
  const { data: conta, isFetching: relendoConta } = useConta(selecionadaId);

  const lancarServico = useLancarServico(selecionadaId);
  const lancarProduto = useLancarProduto(selecionadaId);
  const removerItem = useRemoverItem(selecionadaId);
  const aplicarDesconto = useAplicarDesconto(selecionadaId);
  const registrarPagamento = useRegistrarPagamento(selecionadaId);
  const removerPagamento = useRemoverPagamento(selecionadaId);
  const fechar = useFechar(selecionadaId);

  const reclamar = (e: unknown) => {
    // A mensagem do servidor é a que serve: ela diz quanto falta, quanto há em estoque,
    // qual é o teto de desconto. Trocá-la por "não foi possível" apagaria exatamente a
    // parte acionável (R-UX-20).
    setErro(e instanceof ErroDaApi ? e.message : 'Não foi possível concluir a operação.');
  };
  const executar = <T,>(promessa: Promise<T>) => {
    setErro(null);
    promessa.catch(reclamar);
  };

  const comanda = detalhada?.comanda;
  const bloqueado = comanda !== undefined && comanda.status !== 'ABERTA';
  const clienteNome = abertas?.find((a) => a.comanda.id === selecionadaId)?.clienteNome;

  return (
    <Pagina>
    <div className="pagina-cheia cmd-tela">
      <BarraDeAbertas
        comandas={abertas ?? []}
        selecionadaId={selecionadaId}
        aoSelecionar={(id) => { setEscolhida(id); setErro(null); }}
      />

      {detalhada === undefined || conta === undefined ? (
        <div className="cmd-sem-comanda">
          <Vazio icone="tesoura" titulo="Nenhuma comanda selecionada">
            Escolha uma comanda aberta na lista para lançar itens e receber.
          </Vazio>
        </div>
      ) : (
        /*
          RT-ATD-009 — duas colunas dentro da comanda: o TRABALHO (lançar, itens) e o
          DINHEIRO (pago, forma, falta receber, fechar). A do dinheiro tem rolagem própria e
          pé fixo: "Falta receber" e o botão de fechar ficam à vista com qualquer número de
          itens — é o que R-UX-08 pede, e o que a tela antiga, com tudo numa coluna só, não
          cumpria.
        */
        <section className="cmd-conta" aria-label={`Comanda ${detalhada.comanda.numero}`}>
          <div className="cmd-trabalho">
            <header className="cmd-conta-topo">
              {/* `h2`: o `h1` desta página é "Atendimento", na barra de topo. */}
              <h2 className="cmd-conta-titulo">
                Comanda <span className="cmd-conta-numero">#{detalhada.comanda.numero}</span>
                {clienteNome && <span className="cmd-conta-cliente">{clienteNome}</span>}
              </h2>
              <span className={`cmd-selo cmd-selo-${detalhada.comanda.status.toLowerCase()}`}>
                {ROTULO_DO_STATUS[detalhada.comanda.status] ?? detalhada.comanda.status}
              </span>
            </header>

            {erro !== null && (
              <p className="cmd-erro" role="alert">{erro}</p>
            )}

            {/*
              Enquanto uma ação está no ar, os controles que a repetiriam ficam desarmados.
              O caso que doía era o pagamento: o campo de valor se limpa no envio, mas o envio
              cai no `restante` quando ele está vazio — e o restante só é relido depois. Dois
              cliques cobravam a mesma quantia duas vezes.
            */}
            {!bloqueado && (
              <AreaDeLancar key={detalhada.comanda.id} vazia={detalhada.itens.length === 0}>
                <Lancar
                  ocupado={lancarServico.isPending || lancarProduto.isPending}
                  aoLancarServico={(servicoId, profissionalId) =>
                    executar(lancarServico.mutateAsync({ servicoId, profissionalId }))}
                  aoLancarProduto={(produtoId, quantidade) =>
                    executar(lancarProduto.mutateAsync({ produtoId, quantidade }))}
                />
              </AreaDeLancar>
            )}

            {/*
              A metade que o servidor acabou de devolver aparece na hora; a outra é relida e
              fica esmaecida enquanto não chega — mesmo idioma da agenda. Número velho sem
              aviso é que não pode (RN-ATD-008).
            */}
            <div className="cmd-metade" aria-busy={relendoComanda}>
              <Itens
                itens={detalhada.itens}
                bloqueado={bloqueado}
                ocupado={removerItem.isPending || aplicarDesconto.isPending}
                aoRemover={(itemId) => executar(removerItem.mutateAsync(itemId))}
                aoDescontar={(itemId, percentual) =>
                  executar(aplicarDesconto.mutateAsync({ itemId, percentual }))}
              />

              <div className="cmd-totais">
                <Total rotulo="Bruto" valor={reais(detalhada.comanda.valorBruto)} />
                <Total rotulo="Desconto" valor={reais(detalhada.comanda.valorDesconto)} />
                <Total rotulo="Total" valor={reais(detalhada.comanda.valorLiquido)} forte />
              </div>
            </div>
          </div>

          <div className="cmd-metade cmd-receber" aria-busy={relendoConta}>
            {/* Os dois números do recibo, ambos do servidor: nenhuma soma aqui (RN-ATD-008). */}
            <dl className="cmd-resumo">
              <div><dt>Total da comanda</dt><dd>{reais(conta.comanda.valorLiquido)}</dd></div>
              <div><dt>Pago</dt><dd>{reais(conta.totalPago)}</dd></div>
            </dl>

            <Pagamentos
              conta={conta}
              bloqueado={bloqueado}
              ocupado={registrarPagamento.isPending || removerPagamento.isPending}
              aoRegistrar={(forma, valor) =>
                executar(registrarPagamento.mutateAsync({ forma, valor }))}
              aoRemover={(pagamentoId) => executar(removerPagamento.mutateAsync(pagamentoId))}
            />

            <footer className="cmd-fechar">
              <div className={'cmd-restante' + (conta.restante.valor <= 0 ? ' quitada' : '')}>
                <span className="cmd-restante-rotulo">Falta receber</span>
                <span className="cmd-restante-valor">{reais(conta.restante)}</span>
              </div>
              {!bloqueado && (
                <>
                  <button
                    type="button"
                    className="cmd-botao cmd-botao-primario"
                    disabled={conta.restante.valor > 0 || fechar.isPending}
                    aria-describedby={conta.restante.valor > 0 ? 'cmd-por-que-nao-fecha' : undefined}
                    onClick={() => executar(fechar.mutateAsync())}
                  >
                    Fechar comanda
                  </button>
                  {/* Botão cinza sem motivo é a pergunta que a recepção faz ao gerente. */}
                  {conta.restante.valor > 0 && (
                    <p id="cmd-por-que-nao-fecha" className="cmd-por-que">
                      Falta receber {reais(conta.restante)} para fechar.
                    </p>
                  )}
                </>
              )}
            </footer>
          </div>
        </section>
      )}
    </div>
    </Pagina>
  );
}

/**
 * No toque, o formulário de lançar fica atrás de um botão (RT-ATD-010).
 *
 * No celular a tela é uma coluna só, e o formulário (serviço, quem executou, bipe, produto,
 * quantidade, dois botões) ocupava a tela inteira ANTES dos itens: para ver o que já estava na
 * comanda era preciso rolar por ele. Recolhido, o que aparece depois do cabeçalho é a conta.
 *
 * Comanda vazia abre com ele aberto — não há conta para mostrar, e o próximo passo é lançar.
 * No desktop o botão não existe e o formulário está sempre à vista, como antes: lá ele cabe ao
 * lado da tabela. O formulário é sempre renderizado (só o CSS o recolhe), então nenhum estado
 * do que se estava preenchendo se perde ao recolher.
 */
function AreaDeLancar({ vazia, children }: { vazia: boolean; children: ReactNode }) {
  const [aberta, definirAberta] = useState(vazia);
  return (
    <div className="cmd-lancar-area" data-aberta={aberta || undefined}>
      <button
        type="button"
        className="cmd-lancar-abrir"
        aria-expanded={aberta}
        aria-controls="cmd-lancar-corpo"
        onClick={() => definirAberta((valor) => !valor)}
      >
        Adicionar à comanda
        <Icone nome="chevron" tamanho={20} />
      </button>
      <div id="cmd-lancar-corpo" className="cmd-lancar-corpo">{children}</div>
    </div>
  );
}

/** O status em português de gente, com maiúscula — o selo é rótulo, não código. */
const ROTULO_DO_STATUS: Record<string, string> = {
  ABERTA: 'Aberta', FECHADA: 'Fechada', ESTORNADA: 'Estornada',
};

function Total({ rotulo, valor, forte }: { rotulo: string; valor: string; forte?: boolean }) {
  return (
    <div className={forte ? 'cmd-total forte' : 'cmd-total'}>
      <span className="cmd-total-rotulo">{rotulo}</span>
      <span className="cmd-total-valor">{valor}</span>
    </div>
  );
}
