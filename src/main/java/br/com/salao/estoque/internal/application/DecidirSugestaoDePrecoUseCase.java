package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.ErrosDoEstoque;
import br.com.salao.estoque.internal.domain.MotivoDaSugestao;
import br.com.salao.estoque.internal.domain.MotorDePreco;
import br.com.salao.estoque.internal.infra.DecisoesDePrecoJdbc;
import br.com.salao.estoque.internal.infra.PoliticaDePrecoJdbc;
import br.com.salao.estoque.internal.infra.SugestoesDePrecoJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tempo.Relogio;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-011 — o clique humano que fecha a Fase 6 (RN-EST-034 a 036).
 *
 * <h2>O chão vale também para quem digita</h2>
 *
 * <p>O dono pode ajustar o número, mas não abaixo de {@code custo médio × (1 + margem mínima)} — e o
 * custo é o <strong>congelado na sugestão</strong>, que é o que está na tela. Aprovar deve fazer o que
 * a tela prometeu; se o custo andou desde a geração, a rodada seguinte diz.
 *
 * <p>Quem quiser mesmo assim tem a tela de produto, que sempre aceitou qualquer preço. O caminho de
 * fora existe de propósito: ele deixa rastro em outro lugar, e não passa por uma tela que diz
 * "sugerido".
 *
 * <h2>Aplicar passa por onde sempre passou</h2>
 *
 * <p>Chama {@link DefinirPrecoDeVendaUseCase}, o mesmo da tela de produto. Um segundo caminho para
 * escrever preço seria um segundo lugar para esquecer a permissão, a auditoria e a validação.
 */
public class DecidirSugestaoDePrecoUseCase {

    private static final Logger log = LoggerFactory.getLogger(DecidirSugestaoDePrecoUseCase.class);

    static final int MOTIVO_MINIMO = 3;
    static final int MOTIVO_MAXIMO = 500;

    private final SugestoesDePrecoJdbc sugestoes;
    private final DecisoesDePrecoJdbc decisoes;
    private final PoliticaDePrecoJdbc politica;
    private final DefinirPrecoDeVendaUseCase definirPreco;
    private final AuditoriaApi auditoria;
    private final Relogio relogio;

    public DecidirSugestaoDePrecoUseCase(SugestoesDePrecoJdbc sugestoes,
                                         DecisoesDePrecoJdbc decisoes,
                                         PoliticaDePrecoJdbc politica,
                                         DefinirPrecoDeVendaUseCase definirPreco,
                                         AuditoriaApi auditoria, Relogio relogio) {
        this.sugestoes = sugestoes;
        this.decisoes = decisoes;
        this.politica = politica;
        this.definirPreco = definirPreco;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    /** @param preco nulo aplica o sugerido; informado, precisa respeitar o chão */
    @PreAuthorize("hasAuthority('" + Permissao.PRODUTO_PRECO_ESCREVER + "')")
    @Transactional
    public void aprovar(UUID sugestaoId, Money preco) {
        var sugestao = abrir(sugestaoId);
        Money aplicado = preco == null ? new Money(sugestao.precoSugerido()) : preco;
        if (aplicado.valor().signum() <= 0) {
            throw new ErroDeDominio(ErrosDoEstoque.DADOS_INVALIDOS,
                    "o preço precisa ser maior que zero");
        }
        conferirChao(aplicado, sugestao.custoMedio(), sugestao.motivo());

        definirPreco.executar(sugestao.produtoId(), aplicado);
        registrar(sugestao, "APLICADA", aplicado.valor(), null);

        log.info("Preço do produto {}: sugerido {}, aplicado {} ({})", sugestao.produto(),
                sugestao.precoSugerido(), aplicado.valor(), sugestao.motivo());
    }

    @PreAuthorize("hasAuthority('" + Permissao.PRODUTO_PRECO_ESCREVER + "')")
    @Transactional
    public void recusar(UUID sugestaoId, String justificativa) {
        var sugestao = abrir(sugestaoId);
        String limpa = justificativa == null ? "" : justificativa.strip();
        if (limpa.length() < MOTIVO_MINIMO || limpa.length() > MOTIVO_MAXIMO) {
            throw new ErroDeDominio(ErrosDoEstoque.DADOS_INVALIDOS,
                    "escreva o motivo, entre " + MOTIVO_MINIMO + " e " + MOTIVO_MAXIMO
                            + " caracteres");
        }
        registrar(sugestao, "RECUSADA", null, limpa);

        log.warn("Sugestão de preço recusada em {} ({}): {}", sugestao.produto(), sugestao.motivo(),
                limpa);
    }

    // ------------------------------------------------------------------ apoio

    private SugestoesDePrecoJdbc.Linha abrir(UUID sugestaoId) {
        return sugestoes.porId(sugestaoId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoEstoque.SUGESTAO_NAO_ENCONTRADA,
                        "sugestão " + sugestaoId + " não encontrada ou já decidida"));
    }

    /**
     * RT-EST-012: na queima de vencimento o chão é o próprio custo médio — margem zero, sem
     * prejuízo. É a única exceção ao chão de margem, e ela vem do motivo gravado na sugestão, não de
     * um parâmetro que a tela possa mandar (RN-EST-037).
     */
    private void conferirChao(Money aplicado, BigDecimal custoMedio, MotivoDaSugestao motivo) {
        if (motivo.chaoNoCusto()) {
            if (aplicado.menorQue(new Money(custoMedio))) {
                throw new ErroDeDominio(ErrosDoEstoque.PRECO_ABAIXO_DO_MINIMO,
                        "na queima de vencimento o preço pode ir até o custo médio, e não abaixo: "
                                + reais(custoMedio));
            }
            return;
        }
        BigDecimal margemMinima = politica.margemMinima()
                .orElseThrow(() -> new ErroDeDominio(ErrosDoEstoque.SEM_POLITICA_DE_PRECO,
                        "defina a margem mínima antes de decidir"));
        Money chao = MotorDePreco.chaoDeMargem(new Money(custoMedio), margemMinima);
        if (aplicado.menorQue(chao)) {
            throw new ErroDeDominio(ErrosDoEstoque.PRECO_ABAIXO_DO_MINIMO,
                    "com custo médio de " + reais(custoMedio) + " e margem mínima de "
                            + margemMinima.setScale(1, RoundingMode.HALF_UP).toPlainString()
                                    .replace('.', ',')
                            + "%, o preço não pode ficar abaixo de " + reais(chao.valor()));
        }
    }

    private void registrar(SugestoesDePrecoJdbc.Linha sugestao, String decisao,
                           BigDecimal precoAplicado, String justificativa) {
        UUID id = decisoes.registrar(new DecisoesDePrecoJdbc.Nova(sugestao.produtoId(), decisao,
                sugestao.motivo(), sugestao.precoAtual(), sugestao.custoMedio(),
                sugestao.precoSugerido(), precoAplicado, sugestao.racional(), justificativa),
                relogio.agora(), UsuarioAtual.idOuNulo());
        // Decidida sai da lista de abertas, na mesma transação.
        sugestoes.remover(sugestao.id());

        Map<String, Object> detalhes = new HashMap<>();
        detalhes.put("produto", sugestao.produto());
        detalhes.put("motivo", sugestao.motivo().name());
        detalhes.put("precoSugerido", sugestao.precoSugerido().toPlainString());
        if (precoAplicado != null) {
            detalhes.put("precoAplicado", precoAplicado.toPlainString());
        }
        if (justificativa != null) {
            detalhes.put("justificativa", justificativa);
        }
        auditoria.registrar(RegistroDeAuditoria.alteracao(
                "APLICADA".equals(decisao) ? "SUGESTAO_DE_PRECO_APLICADA"
                        : "SUGESTAO_DE_PRECO_RECUSADA",
                "decisao_de_preco", id, Map.of(), Map.copyOf(detalhes)));
    }

    private static String reais(BigDecimal valor) {
        return "R$ " + valor.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }
}
