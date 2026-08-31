package br.com.salao.equipe.internal.application;

import br.com.salao.equipe.api.ErrosDaEquipe;
import br.com.salao.equipe.internal.infra.ProfissionaisJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EQP-003 — desfazer um bloqueio.
 *
 * <h2>Aqui apaga mesmo, e é o certo</h2>
 *
 * <p>Contraria a regra de "desativar, nunca apagar" que vale para cliente e profissional — e por
 * um motivo: bloqueio não tem histórico dependente. Nada aponta para ele, e um bloqueio lançado
 * por engano precisa sumir de verdade, senão a agenda continua com um buraco que ninguém explica.
 *
 * <p>Cliente e profissional são o oposto: o histórico financeiro aponta para eles.
 */
public class RemoverBloqueioUseCase {

    private final ProfissionaisJdbc profissionais;
    private final AuditoriaApi auditoria;

    public RemoverBloqueioUseCase(ProfissionaisJdbc profissionais, AuditoriaApi auditoria) {
        this.profissionais = profissionais;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.USUARIO_GERENCIAR + "')")
    @Transactional
    public void executar(UUID id) {
        if (profissionais.removerBloqueio(id) == 0) {
            // Zero linhas: não existe, ou é de outro salão e a RLS não o mostra. A resposta é a
            // mesma nos dois casos, e é o certo — dizer "existe, mas não é seu" já é informação.
            throw new ErroDeDominio(ErrosDaEquipe.BLOQUEIO_NAO_ENCONTRADO,
                    "bloqueio " + id + " não encontrado");
        }
        auditoria.registrar(RegistroDeAuditoria.alteracao("BLOQUEIO_REMOVIDO",
                "bloqueio_de_jornada", id, Map.of("existia", true), null));
    }
}
