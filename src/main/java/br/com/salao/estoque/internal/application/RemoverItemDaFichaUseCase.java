package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.internal.infra.FichasJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-007 — tirar um insumo da ficha.
 *
 * <p>A ficha é configuração, não histórico: ela diz o que o serviço consome <strong>de agora em
 * diante</strong>. Apagar a linha não mexe em consumo já lançado — aquele é movimento de estoque,
 * imutável (RN-EST-015). É a mesma separação da regra de comissão, que muda sem recalcular o que
 * já foi pago (RN-FIN-007).
 *
 * <p>Remover o que não existe devolve sem reclamar: quem pediu quer o mesmo estado final.
 */
public class RemoverItemDaFichaUseCase {

    private final FichasJdbc fichas;
    private final AuditoriaApi auditoria;

    public RemoverItemDaFichaUseCase(FichasJdbc fichas, AuditoriaApi auditoria) {
        this.fichas = fichas;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.ESTOQUE_ESCREVER + "')")
    @Transactional
    public void executar(UUID servicoId, UUID produtoId) {
        if (fichas.remover(servicoId, produtoId) == 0) {
            return;
        }
        auditoria.registrar(RegistroDeAuditoria.alteracao("FICHA_TECNICA_REMOVIDA",
                "ficha_tecnica", servicoId,
                Map.of("produtoId", String.valueOf(produtoId)), null));
    }
}
