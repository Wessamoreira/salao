package br.com.salao.atendimento.internal.application;

import br.com.salao.atendimento.api.ComandaResumo;
import br.com.salao.atendimento.api.ContaDaComanda;
import br.com.salao.atendimento.api.ErrosDoAtendimento;
import br.com.salao.atendimento.internal.infra.ComandasJdbc;
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
 * RT-FIN-001 — desfazer um pagamento digitado errado.
 *
 * <p>Só enquanto a comanda está aberta. Depois de fechada, o dinheiro já virou comissão apurada e
 * lançamento no razão, e desfazer é estorno (RT-ATD-007) — que é lançamento novo de sinal
 * contrário, nunca linha apagada.
 *
 * <p>Aqui apaga mesmo, pela mesma razão do item lançado errado (RN-ATD-009): pagamento digitado com
 * o valor trocado não é fato do caixa, é digitação. A trilha fica na auditoria, que é append-only e
 * guarda forma e valor do que saiu.
 */
public class RemoverPagamentoUseCase {

    private static final Logger log = LoggerFactory.getLogger(RemoverPagamentoUseCase.class);

    private final ComandasJdbc comandas;
    private final PagamentoApi pagamentos;
    private final AuditoriaApi auditoria;

    public RemoverPagamentoUseCase(ComandasJdbc comandas, PagamentoApi pagamentos,
                                   AuditoriaApi auditoria) {
        this.comandas = comandas;
        this.pagamentos = pagamentos;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.COMANDA_FECHAR + "')")
    @Transactional
    public ContaDaComanda executar(UUID comandaId, UUID pagamentoId) {
        ComandaResumo comanda = ComandaEmEdicao.exigirAberta(comandas, comandaId);
        PagamentoResumo pagamento = pagamentos.porId(pagamentoId)
                .filter(p -> p.comandaId().equals(comandaId))
                .orElseThrow(() -> new ErroDeDominio(
                        ErrosDoAtendimento.PAGAMENTO_NAO_E_DESTA_COMANDA,
                        "pagamento " + pagamentoId + " não é da comanda " + comandaId));

        auditoria.registrar(RegistroDeAuditoria.alteracao("PAGAMENTO_REMOVIDO", "pagamento",
                pagamentoId, Map.of("forma", pagamento.forma().name(),
                        "valor", pagamento.valor().toString()), Map.of()));
        pagamentos.remover(pagamentoId);
        log.info("Pagamento {} removido da comanda {}", pagamentoId, comanda.numero());

        Money pago = pagamentos.totalPago(comandaId);
        return new ContaDaComanda(comanda, pagamentos.daOrigem(comandaId), pago,
                comanda.valorLiquido().subtrair(pago));
    }
}
