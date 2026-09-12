package br.com.salao.estoque.internal.application;

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
 * RT-EST-005 — a contagem não bate com o sistema.
 *
 * <h2>Por lote, e nos dois sentidos</h2>
 *
 * <p>Ajuste é sobre unidades identificadas: quem conta tem a etiqueta na mão. E o sinal vai para os
 * dois lados — faltou (negativo) ou apareceu (positivo). Unidades que aparecem entram
 * <strong>no custo do lote</strong> em que apareceram, e é por isso que o lote é obrigatório:
 * atribuí-las a um lote escolhido por FEFO inventaria um custo, e o custo médio ponderado
 * (RT-EST-003) passaria a mentir a partir dali.
 *
 * <h2>Não é o inventário</h2>
 *
 * <p>Esta rotina é o ajuste avulso, com motivo, de quem achou uma divergência. A contagem completa
 * com divergência e ajuste em lote é RT-EST-006 — e ela vai usar este mesmo caminho por baixo, o
 * que é justamente o motivo de a constraint do motivo estar no banco: o próximo caminho de escrita
 * não pode gravar ajuste sem explicação.
 */
public class RegistrarAjusteUseCase {

    private static final Logger log = LoggerFactory.getLogger(RegistrarAjusteUseCase.class);

    private final MovimentoComMotivo movimentos;
    private final AuditoriaApi auditoria;

    public RegistrarAjusteUseCase(MovimentoComMotivo movimentos, AuditoriaApi auditoria) {
        this.movimentos = movimentos;
        this.auditoria = auditoria;
    }

    /**
     * @param quantidade positiva apareceu, negativa faltou. Zero é recusado
     * @return o id do movimento
     */
    @PreAuthorize("hasAuthority('" + Permissao.ESTOQUE_ESCREVER + "')")
    @Transactional
    public UUID executar(UUID loteId, BigDecimal quantidade, String motivo) {
        UUID movimento = movimentos.noLote(loteId, TipoDeMovimento.AJUSTE, quantidade, motivo);

        auditoria.registrar(RegistroDeAuditoria.criacao("ajuste_de_estoque", movimento, Map.of(
                "loteId", String.valueOf(loteId),
                "quantidade", quantidade.stripTrailingZeros().toPlainString(),
                "motivo", motivo.strip())));

        log.warn("Ajuste de {} no lote {}: {}", quantidade.stripTrailingZeros().toPlainString(),
                loteId, motivo.strip());
        return movimento;
    }
}
