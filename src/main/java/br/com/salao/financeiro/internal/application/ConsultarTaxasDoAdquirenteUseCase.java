package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.internal.domain.FaixaDeTaxa;
import br.com.salao.financeiro.internal.infra.TaxasDoAdquirenteJdbc;
import br.com.salao.iam.api.Permissao;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/** RT-FIN-012 — as quatro faixas, cadastradas ou não. */
public class ConsultarTaxasDoAdquirenteUseCase {

    private final TaxasDoAdquirenteJdbc taxas;

    public ConsultarTaxasDoAdquirenteUseCase(TaxasDoAdquirenteJdbc taxas) {
        this.taxas = taxas;
    }

    /** @param percentual e {@code diasParaReceber} nulos quando a faixa ainda não foi negociada */
    public record Faixa(String faixa, String rotulo, BigDecimal percentual, Integer diasParaReceber) {
    }

    /**
     * As quatro sempre, inclusive as vazias.
     *
     * <p>Devolver só as cadastradas deixaria a tela decidir quais campos existem — e a lista de faixas
     * é regra do servidor, não desenho do front (inviolável 6).
     */
    @PreAuthorize("hasAuthority('" + Permissao.FINANCEIRO_LER_TODOS + "')")
    @Transactional(readOnly = true)
    public List<Faixa> executar() {
        var contratadas = taxas.contratadas();
        return java.util.Arrays.stream(FaixaDeTaxa.values())
                .map(faixa -> {
                    var taxa = contratadas.get(faixa);
                    return new Faixa(faixa.name(), faixa.rotulo(),
                            taxa == null ? null : taxa.percentual(),
                            taxa == null ? null : taxa.diasParaReceber());
                })
                .toList();
    }
}
