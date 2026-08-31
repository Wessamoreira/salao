package br.com.salao.equipe.internal.application;

import br.com.salao.equipe.api.ErrosDaEquipe;
import br.com.salao.equipe.api.ProfissionalResumo;
import br.com.salao.equipe.internal.infra.ProfissionaisJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EQP-001 — desativar quem saiu, reativar quem voltou.
 *
 * <h2>Desativar, nunca apagar</h2>
 *
 * <p>O histórico aponta para quem executou cada serviço e cada comanda. Apagar quebraria o rastro
 * que sustenta o extrato de comissão — inclusive o de meses já pagos, que é justamente o que
 * alguém vai querer conferir quando houver dúvida.
 *
 * <p>É a mesma decisão de RT-IAM-007 para usuários, pelo mesmo motivo.
 */
public class DefinirAtivacaoDoProfissionalUseCase {

    private static final Logger log =
            LoggerFactory.getLogger(DefinirAtivacaoDoProfissionalUseCase.class);

    private final ProfissionaisJdbc profissionais;
    private final AuditoriaApi auditoria;

    public DefinirAtivacaoDoProfissionalUseCase(ProfissionaisJdbc profissionais,
                                                AuditoriaApi auditoria) {
        this.profissionais = profissionais;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.USUARIO_GERENCIAR + "')")
    @Transactional
    public ProfissionalResumo executar(UUID id, boolean ativo) {
        ProfissionalResumo antes = profissionais.porId(id).orElseThrow(() -> new ErroDeDominio(
                ErrosDaEquipe.PROFISSIONAL_NAO_ENCONTRADO, "profissional " + id + " não encontrado"));

        if (antes.ativo() != ativo) {
            profissionais.definirAtivacao(id, ativo);
            auditoria.registrar(RegistroDeAuditoria.alteracao(
                    ativo ? "PROFISSIONAL_REATIVADO" : "PROFISSIONAL_DESATIVADO",
                    "profissional", id, Map.of("ativo", antes.ativo()), Map.of("ativo", ativo)));
            log.info("Profissional {} agora está {}", id, ativo ? "ativo" : "inativo");
        }
        // Repetir a operação não é erro: duas abas abertas chegam aqui, e recusar diria que algo
        // deu errado quando o estado desejado já é o que existe.
        return profissionais.porId(id).orElseThrow();
    }
}
