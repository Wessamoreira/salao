package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.BaixaDeEstoque;
import br.com.salao.estoque.api.TipoDeMovimento;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-005 — quebrou, venceu, sumiu.
 *
 * <h2>Por FEFO, e não por lote escolhido</h2>
 *
 * <p>"Sumiram três shampoos" não diz de qual lote, e é assim que a perda chega no balcão. FEFO é a
 * resposta certa quando ninguém sabe: tira do que vence primeiro, que é o mais provável de ter
 * sido o perdido e o que menos estraga a projeção de validade.
 *
 * <p>Quando alguém SABE o lote — o frasco vencido que está na mão, com o código na etiqueta —, o
 * caminho é o ajuste, que é por lote. A diferença entre os dois não é burocrática: é quem sabe o
 * quê.
 *
 * <h2>Sem origem, com motivo</h2>
 *
 * <p>Venda e consumo interno têm origem — item de comanda, serviço — e a origem explica a saída.
 * Perda não tem: o único registro do porquê é o que a pessoa digitar, e é por isso que ele é
 * obrigatório aqui e na constraint do banco (V45).
 */
public class RegistrarPerdaUseCase {

    private static final Logger log = LoggerFactory.getLogger(RegistrarPerdaUseCase.class);

    private final BaixaFefo baixa;
    private final MovimentoComMotivo movimentos;
    private final AuditoriaApi auditoria;

    public RegistrarPerdaUseCase(BaixaFefo baixa, MovimentoComMotivo movimentos,
                                 AuditoriaApi auditoria) {
        this.baixa = baixa;
        this.movimentos = movimentos;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.ESTOQUE_ESCREVER + "')")
    @Transactional
    public BaixaDeEstoque executar(UUID produtoId, BigDecimal quantidade, String motivo) {
        // Confere o motivo ANTES de mexer no estoque: a constraint do banco recusaria depois, mas
        // com uma violação que não diz o que fazer.
        movimentos.exigirMotivo(motivo);

        BaixaDeEstoque perdida = baixa.baixar(produtoId, quantidade, TipoDeMovimento.PERDA,
                null, null, motivo.strip());

        auditoria.registrar(RegistroDeAuditoria.criacao("perda_de_estoque", produtoId, Map.of(
                "quantidade", quantidade.stripTrailingZeros().toPlainString(),
                "custoTotal", perdida.custoTotal().toString(),
                "lotes", String.valueOf(perdida.parcelas().size()),
                "motivo", motivo.strip())));

        // WARN e não INFO: perda é dinheiro saindo sem venda, e o dono quer ver isso no log.
        log.warn("Perda de {} do produto {} ({} em custo): {}",
                quantidade.stripTrailingZeros().toPlainString(), produtoId,
                perdida.custoTotal(), motivo.strip());
        return perdida;
    }
}
