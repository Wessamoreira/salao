package br.com.salao.atendimento.internal.application;

import br.com.salao.atendimento.api.ComandaDetalhada;
import br.com.salao.atendimento.api.ComandaFechada;
import br.com.salao.atendimento.api.ComandaResumo;
import br.com.salao.atendimento.api.ErrosDoAtendimento;
import br.com.salao.atendimento.api.ExecutanteDoItem;
import br.com.salao.atendimento.api.ItemDaComanda;
import br.com.salao.atendimento.api.TipoDeItem;
import br.com.salao.atendimento.internal.infra.ComandasJdbc;
import br.com.salao.estoque.api.BaixaDeEstoque;
import br.com.salao.estoque.api.EstoqueApi;
import br.com.salao.estoque.api.TipoDeMovimento;
import br.com.salao.financeiro.api.ApuracaoDaComanda;
import br.com.salao.financeiro.api.PagamentoApi;
import br.com.salao.financeiro.api.RazaoApi;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-ATD-006 — fechar a comanda.
 *
 * <p>É a rotina que dá sentido às anteriores: aqui os itens viram faturamento, o estoque diminui, a
 * comissão para de mudar e o atendimento vira registro.
 *
 * <h2>Tudo numa transação, e nesta ordem</h2>
 *
 * <ol>
 *   <li><strong>Conferir que está pago.</strong> Fechar com saldo aberto faria a comanda afirmar
 *       que o atendimento foi pago quando não foi.</li>
 *   <li><strong>Fechar a comanda</strong>, com {@code where status = 'ABERTA'}. Vem antes da baixa
 *       de propósito: assim dois fechamentos simultâneos não chegam os dois ao FEFO, e o estoque
 *       não é baixado duas vezes pela mesma venda.</li>
 *   <li><strong>Baixar o estoque</strong> de cada item de produto, e gravar no item o custo com que
 *       as unidades saíram.</li>
 *   <li><strong>Publicar o evento.</strong></li>
 * </ol>
 *
 * <p>Se qualquer passo falhar, nada acontece — inclusive o fechamento. Uma comanda fechada com
 * estoque não baixado seria a pior combinação possível: o dinheiro entrou, o produto saiu, e o
 * sistema acha que ele ainda está no armário.
 *
 * <h2>A comissão não é "congelada" aqui — ela para de mudar</h2>
 *
 * <p>Não há passo de snapshot. {@code percentual_comissao} e {@code regra_origem} já eram snapshot
 * desde o lançamento (RN-ATD-010), e {@code base_calculo} e {@code valor_comissao} acompanhavam o
 * desconto enquanto a comanda estava aberta. Fechada, ela não aceita mais alteração (RN-ATD-006) —
 * e é isso, e só isso, que congela os números.
 *
 * <p>Apurar a comissão — transformá-la em lançamento e em extrato — é RT-FIN-003, que ouve o evento
 * publicado aqui.
 *
 * <h2>Saldo insuficiente recusa o fechamento</h2>
 *
 * <p>É a escolha difícil da rotina, e ela trava o caixa: a cliente está na frente, o produto foi
 * entregue, e o sistema diz que não tem.
 *
 * <p>A alternativa — fechar com estoque negativo — resolveria o minuto e estragaria o mês: o
 * inventário passa a mentir, e ninguém descobre até a contagem. Já a recusa tem conserto imediato e
 * correto na maioria das vezes, porque a causa quase sempre é uma caixa que chegou e não foi
 * registrada: a mensagem diz qual produto e quanto há, e registrar a entrada é uma tela.
 *
 * <p>O caso em que a recusa incomoda de verdade — o produto sumiu mesmo — precisa de
 * {@code PERDA}, que é RT-EST-005. Está na lista de pendências.
 */
public class FecharComandaUseCase {

    private static final Logger log = LoggerFactory.getLogger(FecharComandaUseCase.class);

    private final ComandasJdbc comandas;
    private final PagamentoApi pagamentos;
    private final EstoqueApi estoque;
    private final RazaoApi razao;
    private final AuditoriaApi auditoria;
    private final ApplicationEventPublisher eventos;
    private final Relogio relogio;

    public FecharComandaUseCase(ComandasJdbc comandas, PagamentoApi pagamentos,
                                EstoqueApi estoque, RazaoApi razao, AuditoriaApi auditoria,
                                ApplicationEventPublisher eventos, Relogio relogio) {
        this.comandas = comandas;
        this.pagamentos = pagamentos;
        this.estoque = estoque;
        this.razao = razao;
        this.auditoria = auditoria;
        this.eventos = eventos;
        this.relogio = relogio;
    }

    @PreAuthorize("hasAuthority('" + Permissao.COMANDA_FECHAR + "')")
    @Transactional
    public ComandaDetalhada executar(UUID comandaId) {
        ComandaResumo comanda = ComandaEmEdicao.exigirAberta(comandas, comandaId);

        Money restante = comanda.valorLiquido().subtrair(pagamentos.totalPago(comandaId));
        if (restante.valor().signum() > 0) {
            throw new ErroDeDominio(ErrosDoAtendimento.COMANDA_COM_SALDO_ABERTO,
                    "faltam " + restante + " para fechar a comanda " + comanda.numero());
        }
        if (restante.ehNegativo()) {
            // Só acontece depois de reabrir e tirar item (RT-ATD-007): enquanto aberta, nenhum
            // pagamento passa do que falta. Fechar assim registraria faturamento maior do que o
            // atendimento, e o troco devido ficaria sem rastro nenhum.
            throw new ErroDeDominio(ErrosDoAtendimento.COMANDA_PAGA_A_MAIS,
                    "há " + restante.negado() + " pagos a mais; remova ou ajuste um pagamento");
        }

        Instant agora = relogio.agora();
        if (comandas.fechar(comandaId, agora) == 0) {
            // Outra transação fechou entre a leitura e agora. Sem esta conferência, as duas
            // chegariam ao FEFO e o estoque seria baixado duas vezes pela mesma venda.
            throw new ErroDeDominio(ErrosDoAtendimento.COMANDA_NAO_ESTA_ABERTA,
                    "a comanda " + comanda.numero() + " acabou de ser fechada");
        }

        int produtosBaixados = baixarProdutos(comandaId);
        int lancamentos = razao.apurar(apuracaoDe(comanda));

        auditoria.registrar(RegistroDeAuditoria.alteracao("COMANDA_FECHADA", "comanda", comandaId,
                Map.of("status", "ABERTA"),
                Map.of("status", "FECHADA", "valorLiquido", comanda.valorLiquido().toString(),
                        "produtosBaixados", produtosBaixados, "lancamentos", lancamentos)));
        eventos.publishEvent(new ComandaFechada(TenantContext.obrigatorio(), comandaId,
                comanda.clienteId(), comanda.numero(), comanda.valorLiquido().valor(), agora));

        log.info("Comanda {} fechada por {} ({} produto(s) baixado(s))", comanda.numero(),
                comanda.valorLiquido(), produtosBaixados);
        return new ComandaDetalhada(comandas.porId(comandaId).orElseThrow(),
                comandas.itensDe(comandaId));
    }

    /**
     * O que esta comanda gerou, do ponto de vista do razão.
     *
     * <p>Quem monta é o atendimento, que tem os itens; quem decide em que linhas isso vira é o
     * financeiro (RT-FIN-003). Este caso de uso não sabe que comissão entra com sinal negativo nem
     * a que mês a linha pertence — e não deveria.
     *
     * <p>É por isso que a apuração é uma <strong>chamada</strong> e não um ouvinte do evento: se o
     * financeiro buscasse os itens, ele dependeria do atendimento, que já depende dele. Seria
     * ciclo.
     */
    private ApuracaoDaComanda apuracaoDe(ComandaResumo comanda) {
        Money servicos = Money.ZERO;
        Money produtos = Money.ZERO;
        List<ApuracaoDaComanda.ComissaoAApurar> comissoes = new ArrayList<>();

        for (ItemDaComanda item : comandas.itensDe(comanda.id())) {
            if (item.tipo() == TipoDeItem.SERVICO) {
                servicos = servicos.somar(item.valorTotal());
            } else {
                produtos = produtos.somar(item.valorTotal());
            }
            for (ExecutanteDoItem executante : item.executantes()) {
                comissoes.add(new ApuracaoDaComanda.ComissaoAApurar(
                        executante.profissionalId(), executante.valorComissao(),
                        executante.percentualComissao(), executante.origem(),
                        item.descricao()));
            }
        }
        return new ApuracaoDaComanda(comanda.id(), comanda.numero(), servicos, produtos,
                List.copyOf(comissoes));
    }

    /**
     * Baixa o estoque de cada item de produto e grava no item o custo da retirada.
     *
     * <p>Item de serviço não mexe em estoque aqui: o que um serviço consome é ficha técnica, e isso
     * é RT-EST-007.
     */
    private int baixarProdutos(UUID comandaId) {
        int baixados = 0;
        for (ItemDaComanda item : comandas.itensDe(comandaId)) {
            if (item.tipo() == TipoDeItem.SERVICO) {
                /*
                 * RT-EST-007 — o que o serviço gastou, pela ficha técnica.
                 *
                 * A origem é o item de comanda, a mesma do produto vendido: é isso que faz a
                 * reabertura devolver o consumo sozinha, por `estoque.devolver("ITEM_COMANDA",
                 * item.id())`, sem esta rotina precisar saber que a reabertura existe.
                 *
                 * E a falta de insumo NÃO interrompe o fechamento. O serviço já foi executado;
                 * recusar aqui pararia o balcão com a cliente na cadeira por um motivo contábil.
                 * Quem baixa decide isso (`EstoqueApi.consumirPorServico`), e quem tinha de
                 * saber é o log.
                 */
                estoque.consumirPorServico(item.servicoId(), "ITEM_COMANDA", item.id());
                continue;
            }
            if (item.tipo() != TipoDeItem.PRODUTO) {
                continue;
            }
            BaixaDeEstoque baixa = estoque.baixar(item.produtoId(), item.quantidade(),
                    TipoDeMovimento.VENDA, "ITEM_COMANDA", item.id());
            comandas.definirCustoDoItem(item.id(), baixa.custoMedioUnitario());
            baixados++;
        }
        return baixados;
    }
}
