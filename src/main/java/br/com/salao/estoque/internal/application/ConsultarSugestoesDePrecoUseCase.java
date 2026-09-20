package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.internal.infra.PoliticaDePrecoJdbc;
import br.com.salao.estoque.internal.infra.SugestoesDePrecoJdbc;
import br.com.salao.iam.api.Permissao;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/** RT-EST-010 — o que a última geração achou, e a régua que a gerou. */
public class ConsultarSugestoesDePrecoUseCase {

    private final SugestoesDePrecoJdbc sugestoes;
    private final PoliticaDePrecoJdbc politica;

    public ConsultarSugestoesDePrecoUseCase(SugestoesDePrecoJdbc sugestoes,
                                            PoliticaDePrecoJdbc politica) {
        this.sugestoes = sugestoes;
        this.politica = politica;
    }

    /**
     * @param precoAtual  nulo é produto sem preço de venda
     * @param margemAtual nula pela mesma razão
     * @param explicacao  a redação da IA; nula até RT-EST-011
     */
    public record Item(UUID id, UUID produtoId, String produto, String motivo, String rotulo,
                       BigDecimal precoAtual, BigDecimal custoMedio, BigDecimal precoSugerido,
                       BigDecimal margemAtual, BigDecimal margemSugerida, String racional,
                       String explicacao) {
    }

    /**
     * @param margemMinima nula enquanto o salão não definiu a régua — e a tela pede que defina
     * @param geradaEm     nulo quando nunca se gerou
     */
    public record Painel(BigDecimal margemMinima, Instant geradaEm, List<Item> itens) {
    }

    @PreAuthorize("hasAuthority('" + Permissao.ESTOQUE_LER + "')")
    @Transactional(readOnly = true)
    public Painel executar() {
        var linhas = sugestoes.abertas();
        return new Painel(politica.margemMinima().orElse(null),
                linhas.isEmpty() ? null : linhas.getFirst().geradaEm(),
                linhas.stream()
                        .map(l -> new Item(l.id(), l.produtoId(), l.produto(), l.motivo().name(),
                                l.motivo().rotulo(), l.precoAtual(), l.custoMedio(),
                                l.precoSugerido(), l.margemAtual(), l.margemSugerida(),
                                l.racional(), l.explicacao()))
                        .toList());
    }
}
