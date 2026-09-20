package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.ErrosDoEstoque;
import br.com.salao.estoque.internal.infra.PoliticaDePrecoJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tempo.Relogio;
import java.math.BigDecimal;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-010 — a margem mínima do salão (RN-EST-030).
 *
 * <p>É o único número que o dono precisa dar para a rotina existir, e mexer nele muda toda sugestão
 * seguinte — por isso passa pela auditoria.
 */
public class DefinirPoliticaDePrecoUseCase {

    private static final Logger log = LoggerFactory.getLogger(DefinirPoliticaDePrecoUseCase.class);

    /** Revenda de cosmético trabalha com margens que assustam quem vem de outro ramo. */
    static final BigDecimal MARGEM_MAXIMA = new BigDecimal("1000");

    private final PoliticaDePrecoJdbc politica;
    private final AuditoriaApi auditoria;
    private final Relogio relogio;

    public DefinirPoliticaDePrecoUseCase(PoliticaDePrecoJdbc politica, AuditoriaApi auditoria,
                                         Relogio relogio) {
        this.politica = politica;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    @PreAuthorize("hasAuthority('" + Permissao.CONFIG_GERENCIAR + "')")
    @Transactional
    public void executar(BigDecimal margemMinima) {
        if (margemMinima == null || margemMinima.signum() < 0
                || margemMinima.compareTo(MARGEM_MAXIMA) > 0) {
            throw new ErroDeDominio(ErrosDoEstoque.DADOS_INVALIDOS,
                    "a margem mínima precisa ficar entre 0 e " + MARGEM_MAXIMA + "%");
        }
        politica.definir(margemMinima, relogio.agora(), UsuarioAtual.idOuNulo());

        auditoria.registrar(RegistroDeAuditoria.alteracao("POLITICA_DE_PRECO_DEFINIDA",
                "politica_de_preco", null, Map.of(),
                Map.of("margemMinima", margemMinima.toPlainString())));
        log.info("Margem mínima definida em {}%", margemMinima);
    }
}
