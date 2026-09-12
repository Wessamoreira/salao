package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.ErrosDoEstoque;
import br.com.salao.estoque.api.TipoDeMovimento;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-005 — mandar mercadoria de volta ao fornecedor.
 *
 * <h2>É outro fato que a devolução da cliente</h2>
 *
 * <p>{@code DEVOLUCAO} já existia e já é escrita pelo estorno de comanda: as unidades
 * <strong>voltam</strong> ao estoque, nos lotes de onde saíram (RT-ATD-007). Aqui o estoque
 * <strong>sai</strong>, a contraparte é o fornecedor, e "quanto devolvemos ao fornecedor este mês?"
 * é pergunta de compras, não de atendimento.
 *
 * <p>Com um tipo só, essa pergunta dependeria de filtrar por sinal e de ninguém nunca ter
 * misturado os dois. Daí {@code DEVOLUCAO_AO_FORNECEDOR} (V45).
 *
 * <h2>Sempre por lote</h2>
 *
 * <p>Devolve-se uma caixa de uma nota específica, com um custo e uma validade específicos. FEFO
 * aqui escolheria o lote errado com frequência — e o errado é o que está no depósito, não o que
 * está na caixa que vai voltar.
 */
public class DevolverAoFornecedorUseCase {

    private static final Logger log = LoggerFactory.getLogger(DevolverAoFornecedorUseCase.class);

    private final MovimentoComMotivo movimentos;
    private final AuditoriaApi auditoria;

    public DevolverAoFornecedorUseCase(MovimentoComMotivo movimentos, AuditoriaApi auditoria) {
        this.movimentos = movimentos;
        this.auditoria = auditoria;
    }

    /** @param quantidade quantas unidades voltam, sempre positiva — o sinal é decidido aqui */
    @PreAuthorize("hasAuthority('" + Permissao.ESTOQUE_ESCREVER + "')")
    @Transactional
    public UUID executar(UUID loteId, BigDecimal quantidade, String motivo) {
        if (quantidade == null || quantidade.signum() <= 0) {
            // Positiva na entrada e negada aqui: pedir o sinal a quem chama convidaria a mandar
            // "-3" e devolver ao contrário, aumentando o estoque de um produto que foi embora.
            throw new ErroDeDominio(ErrosDoEstoque.QUANTIDADE_INVALIDA,
                    "informe quantas unidades voltam ao fornecedor; veio " + quantidade);
        }
        UUID movimento = movimentos.noLote(loteId, TipoDeMovimento.DEVOLUCAO_AO_FORNECEDOR,
                quantidade.negate(), motivo);

        auditoria.registrar(RegistroDeAuditoria.criacao("devolucao_ao_fornecedor", movimento,
                Map.of("loteId", String.valueOf(loteId),
                        "quantidade", quantidade.stripTrailingZeros().toPlainString(),
                        "motivo", motivo.strip())));

        log.info("Devolvidas {} unidades do lote {} ao fornecedor: {}",
                quantidade.stripTrailingZeros().toPlainString(), loteId, motivo.strip());
        return movimento;
    }
}
