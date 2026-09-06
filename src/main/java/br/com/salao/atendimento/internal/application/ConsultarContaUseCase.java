package br.com.salao.atendimento.internal.application;

import br.com.salao.atendimento.api.ComandaResumo;
import br.com.salao.atendimento.api.ContaDaComanda;
import br.com.salao.atendimento.internal.infra.ComandasJdbc;
import br.com.salao.financeiro.api.PagamentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.dinheiro.Money;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-001 — quanto já entrou e quanto falta.
 *
 * <p>É a consulta que a tela de fechamento faz a cada forma acrescentada (R-UX-08). Separada da
 * comanda detalhada porque são duas perguntas diferentes: *o que foi consumido* e *o que foi pago*
 * — e a barra do balcão só precisa da primeira.
 */
public class ConsultarContaUseCase {

    private final ComandasJdbc comandas;
    private final PagamentoApi pagamentos;

    public ConsultarContaUseCase(ComandasJdbc comandas, PagamentoApi pagamentos) {
        this.comandas = comandas;
        this.pagamentos = pagamentos;
    }

    @PreAuthorize("hasAuthority('" + Permissao.COMANDA_ABRIR + "')"
            + " or hasAuthority('" + Permissao.COMANDA_FECHAR + "')")
    @Transactional(readOnly = true)
    public Optional<ContaDaComanda> executar(UUID comandaId) {
        return comandas.porId(comandaId).map(this::conta);
    }

    private ContaDaComanda conta(ComandaResumo comanda) {
        Money pago = pagamentos.totalPago(comanda.id());
        return new ContaDaComanda(comanda, pagamentos.daOrigem(comanda.id()), pago,
                comanda.valorLiquido().subtrair(pago));
    }
}
