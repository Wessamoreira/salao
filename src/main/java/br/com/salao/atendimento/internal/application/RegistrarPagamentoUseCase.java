package br.com.salao.atendimento.internal.application;

import br.com.salao.atendimento.api.ComandaResumo;
import br.com.salao.atendimento.api.ContaDaComanda;
import br.com.salao.atendimento.api.ErrosDoAtendimento;
import br.com.salao.atendimento.internal.infra.ComandasJdbc;
import br.com.salao.financeiro.api.DadosDoPagamento;
import br.com.salao.financeiro.api.PagamentoApi;
import br.com.salao.financeiro.api.PagamentoResumo;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-001 — receber pela comanda.
 *
 * <h2>Quem sabe quanto falta é quem tem a comanda</h2>
 *
 * <p>O {@code financeiro} guarda o pagamento e sabe somá-lo; ele não sabe se a comanda existe, se
 * está aberta, nem quanto ela deve. A regra "não pode passar do que falta" mora aqui porque o total
 * é daqui — e porque a direção da dependência precisa ser esta: fechar comanda (RT-ATD-006) é
 * transacional em estoque, pagamento e comissão, e quem orquestra é o {@code atendimento}. Se o
 * financeiro também dependesse dele, seria ciclo.
 *
 * <h2>Troco não é pagamento</h2>
 *
 * <p>Quem entrega cem numa conta de oitenta pagou oitenta. Registrar cem faria o faturamento do dia
 * crescer sozinho e o caixa nunca fecharia — e o troco não é dado do sistema, é da gaveta.
 *
 * <h2>Só comanda aberta recebe</h2>
 *
 * <p>Depois de fechada, mexer no dinheiro é estorno (RT-ATD-007), com trilha. Aceitar um pagamento
 * a mais numa comanda fechada mudaria um valor que já virou comissão apurada e lançamento no razão.
 */
public class RegistrarPagamentoUseCase {

    private static final Logger log = LoggerFactory.getLogger(RegistrarPagamentoUseCase.class);

    private final ComandasJdbc comandas;
    private final PagamentoApi pagamentos;
    private final AuditoriaApi auditoria;

    public RegistrarPagamentoUseCase(ComandasJdbc comandas, PagamentoApi pagamentos,
                                     AuditoriaApi auditoria) {
        this.comandas = comandas;
        this.pagamentos = pagamentos;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.COMANDA_FECHAR + "')")
    @Transactional
    public ContaDaComanda executar(UUID comandaId, DadosDoPagamento dados) {
        ComandaResumo comanda = ComandaEmEdicao.exigirAberta(comandas, comandaId);
        Money restante = comanda.valorLiquido().subtrair(pagamentos.totalPago(comandaId));

        if (dados != null && dados.valor() != null && dados.valor().maiorQue(restante)) {
            throw new ErroDeDominio(ErrosDoAtendimento.PAGAMENTO_PASSA_DO_TOTAL,
                    "faltam " + restante + " e o pagamento é de " + dados.valor());
        }

        PagamentoResumo pagamento = pagamentos.registrar(comandaId, dados);
        auditoria.registrar(RegistroDeAuditoria.criacao("pagamento", pagamento.id(),
                Map.of("comandaId", comandaId.toString(), "forma", pagamento.forma().name(),
                        "valor", pagamento.valor().toString())));
        log.info("Pagamento de {} em {} registrado na comanda {}", pagamento.valor(),
                pagamento.forma(), comanda.numero());
        return conta(comanda);
    }

    private ContaDaComanda conta(ComandaResumo comanda) {
        Money pago = pagamentos.totalPago(comanda.id());
        return new ContaDaComanda(comanda, pagamentos.daOrigem(comanda.id()), pago,
                comanda.valorLiquido().subtrair(pago));
    }
}
