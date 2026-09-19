package br.com.salao.financeiro.internal.application;

import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.tempo.Relogio;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-010 — o botão "Conciliar agora".
 *
 * <p>Para o pagamento registrado depois da importação daquele período: sem isto, ele esperaria a próxima
 * importação para casar. Rodar de novo é inofensivo — cada lado tem no máximo um par.
 */
public class ConciliarUseCase {

    private final Conciliador conciliador;
    private final Relogio relogio;

    public ConciliarUseCase(Conciliador conciliador, Relogio relogio) {
        this.conciliador = conciliador;
        this.relogio = relogio;
    }

    @PreAuthorize("hasAuthority('" + Permissao.FINANCEIRO_CONCILIAR + "')")
    @Transactional
    public Conciliador.Rodada executar() {
        return conciliador.conciliar(relogio.agora());
    }
}
