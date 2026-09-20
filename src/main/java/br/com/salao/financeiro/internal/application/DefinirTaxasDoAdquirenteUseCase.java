package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.api.ErrosDoFinanceiro;
import br.com.salao.financeiro.internal.domain.FaixaDeTaxa;
import br.com.salao.financeiro.internal.domain.TaxaContratada;
import br.com.salao.financeiro.internal.infra.TaxasDoAdquirenteJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tempo.Relogio;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-012 — cadastrar o que o salão negociou com a adquirente (RN-FIN-037).
 *
 * <p>Tudo de uma vez, e não faixa a faixa: o contrato é uma proposta só, e salvar metade deixaria o
 * salão com débito novo e crédito velho sem ninguém perceber.
 */
public class DefinirTaxasDoAdquirenteUseCase {

    private static final Logger log = LoggerFactory.getLogger(DefinirTaxasDoAdquirenteUseCase.class);

    private static final BigDecimal CEM = new BigDecimal("100");
    static final int DIAS_MAXIMOS = 120;

    private final TaxasDoAdquirenteJdbc taxas;
    private final AuditoriaApi auditoria;
    private final Relogio relogio;

    public DefinirTaxasDoAdquirenteUseCase(TaxasDoAdquirenteJdbc taxas, AuditoriaApi auditoria,
                                           Relogio relogio) {
        this.taxas = taxas;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    @PreAuthorize("hasAuthority('" + Permissao.CONFIG_GERENCIAR + "')")
    @Transactional
    public void executar(List<TaxaContratada> faixas) {
        conferir(faixas);
        taxas.gravar(faixas, relogio.agora(), UsuarioAtual.idOuNulo());

        auditoria.registrar(RegistroDeAuditoria.alteracao("TAXAS_DO_ADQUIRENTE_DEFINIDAS",
                "taxa_do_adquirente", null, Map.of(),
                faixas.stream().collect(java.util.stream.Collectors.toMap(
                        t -> t.faixa().name(),
                        t -> t.percentual() + "% em D+" + t.diasParaReceber()))));
        log.info("Taxas da adquirente definidas: {}", faixas);
    }

    /**
     * As quatro faixas, sem repetir e sem faltar.
     *
     * <p>Aceitar um cadastro parcial faria a venda parcelada nascer sem previsão enquanto a de débito
     * nasce com — e o dono não teria como saber por que metade da tela está vazia.
     */
    private void conferir(List<TaxaContratada> faixas) {
        if (faixas == null || faixas.isEmpty()) {
            throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS, "informe as faixas");
        }
        var vistas = EnumSet.noneOf(FaixaDeTaxa.class);
        for (TaxaContratada taxa : faixas) {
            if (taxa.faixa() == null || !vistas.add(taxa.faixa())) {
                throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                        "cada faixa aparece uma vez");
            }
            if (taxa.percentual() == null || taxa.percentual().signum() < 0
                    || taxa.percentual().compareTo(CEM) >= 0) {
                throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                        "o percentual de " + taxa.faixa().rotulo() + " precisa ficar entre 0 e 100");
            }
            if (taxa.diasParaReceber() < 0 || taxa.diasParaReceber() > DIAS_MAXIMOS) {
                throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                        "o prazo de " + taxa.faixa().rotulo() + " precisa ficar entre 0 e "
                                + DIAS_MAXIMOS + " dias");
            }
        }
        if (!vistas.containsAll(EnumSet.allOf(FaixaDeTaxa.class))) {
            throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                    "informe as quatro faixas: débito, crédito à vista, 2 a 6x e 7 a 12x");
        }
    }
}
