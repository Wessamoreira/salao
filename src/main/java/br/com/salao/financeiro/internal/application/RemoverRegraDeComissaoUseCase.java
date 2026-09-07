package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.api.ErrosDoFinanceiro;
import br.com.salao.financeiro.api.RegraDeComissao;
import br.com.salao.financeiro.internal.infra.RegrasDeComissaoJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-002 — tirar um nível da cascata.
 *
 * <p>Remover não muda comissão nenhuma já lançada: o percentual e a origem viraram snapshot no
 * executante quando o item foi lançado (RN-ATD-010). O que muda é o que valerá dali para frente —
 * e é justamente essa separação que faz a pergunta *"por que a minha comissão foi 30%?"* continuar
 * tendo resposta depois que a regra deixa de existir.
 */
public class RemoverRegraDeComissaoUseCase {

    private final RegrasDeComissaoJdbc regras;
    private final AuditoriaApi auditoria;

    public RemoverRegraDeComissaoUseCase(RegrasDeComissaoJdbc regras, AuditoriaApi auditoria) {
        this.regras = regras;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.CONFIG_GERENCIAR + "')")
    @Transactional
    public void executar(UUID id) {
        RegraDeComissao regra = regras.porId(id)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoFinanceiro.REGRA_NAO_ENCONTRADA,
                        "regra " + id + " não encontrada"));
        auditoria.registrar(RegistroDeAuditoria.alteracao("REGRA_COMISSAO_REMOVIDA",
                "regra_comissao", id,
                Map.of("escopo", regra.escopo().name(),
                        "percentual", regra.percentual().toPlainString()), Map.of()));
        regras.remover(id);
    }
}
