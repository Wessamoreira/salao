import { Pagina, Vazio } from '../../layout/Pagina';
import { useState } from 'react';
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
  const { data: detalhada } = useComanda(selecionadaId);
  const { data: conta } = useConta(selecionadaId);

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

  return (
    <Pagina>
    <div className="pagina-cheia cmd-tela">
      <BarraDeAbertas
        comandas={abertas ?? []}
        selecionadaId={selecionadaId}
        aoSelecionar={(id) => { setEscolhida(id); setErro(null); }}
      />

      {detalhada === undefined || conta === undefined ? (
        <Vazio icone="tesoura" titulo="Nenhuma comanda selecionada">
          Escolha uma comanda aberta na barra acima para lançar itens e receber.
        </Vazio>
      ) : (
        <section className="cmd-conta" aria-label={`Comanda ${detalhada.comanda.numero}`}>
          <header className="cmd-conta-topo">
            {/* `h2`: o `h1` desta página é "Atendimento", na barra de topo. A comanda é
                uma seção dentro dela — dois `h1` numa página é hierarquia quebrada para
                quem navega por leitor de tela. */}
            <h2 className="cmd-conta-titulo">
              Comanda <span className="cmd-conta-numero">#{detalhada.comanda.numero}</span>
            </h2>
            <span className={`cmd-selo cmd-selo-${detalhada.comanda.status.toLowerCase()}`}>
              {detalhada.comanda.status.toLowerCase()}
            </span>
          </header>

          {erro !== null && (
            <p className="cmd-erro" role="alert">{erro}</p>
          )}

          {!bloqueado && (
            <Lancar
              aoLancarServico={(servicoId, profissionalId) =>
                executar(lancarServico.mutateAsync({ servicoId, profissionalId }))}
              aoLancarProduto={(produtoId, quantidade) =>
                executar(lancarProduto.mutateAsync({ produtoId, quantidade }))}
            />
          )}

          <Itens
            itens={detalhada.itens}
            bloqueado={bloqueado}
            aoRemover={(itemId) => executar(removerItem.mutateAsync(itemId))}
            aoDescontar={(itemId, percentual) =>
              executar(aplicarDesconto.mutateAsync({ itemId, percentual }))}
          />

          <div className="cmd-totais">
            <Total rotulo="Bruto" valor={reais(detalhada.comanda.valorBruto)} />
            <Total rotulo="Desconto" valor={reais(detalhada.comanda.valorDesconto)} />
            <Total rotulo="Total" valor={reais(detalhada.comanda.valorLiquido)} forte />
          </div>

          <Pagamentos
            conta={conta}
            bloqueado={bloqueado}
            aoRegistrar={(forma, valor) =>
              executar(registrarPagamento.mutateAsync({ forma, valor }))}
            aoRemover={(pagamentoId) => executar(removerPagamento.mutateAsync(pagamentoId))}
          />

          <footer className="cmd-fechar">
            <div className="cmd-restante">
              <span className="cmd-restante-rotulo">Falta receber</span>
              <span className={
                'cmd-restante-valor' + (conta.restante.valor <= 0 ? ' quitada' : '')
              }>
                {reais(conta.restante)}
              </span>
            </div>
            {!bloqueado && (
              <button
                type="button"
                className="cmd-botao cmd-botao-primario"
                disabled={conta.restante.valor > 0 || fechar.isPending}
                onClick={() => executar(fechar.mutateAsync())}
              >
                Fechar comanda
              </button>
            )}
          </footer>
        </section>
      )}
    </div>
    </Pagina>
  );
}

function Total({ rotulo, valor, forte }: { rotulo: string; valor: string; forte?: boolean }) {
  return (
    <div className={forte ? 'cmd-total forte' : 'cmd-total'}>
      <span className="cmd-total-rotulo">{rotulo}</span>
      <span className="cmd-total-valor">{valor}</span>
    </div>
  );
}
