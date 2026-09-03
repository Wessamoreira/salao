package br.com.salao.catalogo.internal.application;

import br.com.salao.catalogo.api.ErrosDoCatalogo;
import br.com.salao.catalogo.api.Recurso;
import br.com.salao.catalogo.internal.infra.RecursosJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CAT-003 — aposentar um recurso, e a recusa que protege a agenda.
 *
 * <h2>Desativar um recurso que alguma receita exige é recusado</h2>
 *
 * <p>Se passasse, todo serviço que depende dele ficaria impossível de marcar: a agenda procuraria
 * um lavatório livre, não acharia nenhum — porque não existe mais nenhum — e simplesmente deixaria
 * de oferecer horário. Sem erro, sem aviso, e sem ninguém conseguindo explicar por que a coloração
 * sumiu da agenda.
 *
 * <p>O erro lista os serviços. É a diferença entre "não pode" e "não pode; conserte estes três".
 *
 * <h2>Só os serviços ativos contam</h2>
 *
 * <p>Um serviço desativado não pode ser marcado, então não há agenda para quebrar. É também a saída
 * do salão que precisa mesmo aposentar o recurso: tira o serviço do cardápio primeiro, e a ordem
 * das duas operações é a mesma ordem em que as coisas acontecem na vida.
 *
 * <h2>Isto não é "o lavatório quebrou hoje"</h2>
 *
 * <p>Indisponibilidade temporária é outra coisa, e é bloqueio — o análogo de RT-EQP-003 para
 * recurso. Não existe ainda; está registrado como pendência. Desativar é aposentar.
 */
public class DefinirAtivacaoDoRecursoUseCase {

    private final RecursosJdbc recursos;
    private final AuditoriaApi auditoria;

    public DefinirAtivacaoDoRecursoUseCase(RecursosJdbc recursos, AuditoriaApi auditoria) {
        this.recursos = recursos;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.CONFIG_GERENCIAR + "')")
    @Transactional
    public Recurso executar(UUID id, boolean ativo) {
        Recurso antes = recursos.porId(id).orElseThrow(() -> new ErroDeDominio(
                ErrosDoCatalogo.RECURSO_NAO_ENCONTRADO, "recurso " + id + " não encontrado"));

        if (antes.ativo() == ativo) {
            return antes;
        }
        if (!ativo) {
            List<String> emUso = recursos.servicosAtivosQueExigem(id);
            if (!emUso.isEmpty()) {
                throw new ErroDeDominio(ErrosDoCatalogo.RECURSO_EM_USO,
                        "o recurso " + antes.nome() + " é exigido por " + String.join(", ", emUso),
                        Map.of("servicos", emUso));
            }
        }

        recursos.definirAtivacao(id, ativo);
        auditoria.registrar(RegistroDeAuditoria.alteracao(
                ativo ? "RECURSO_REATIVADO" : "RECURSO_DESATIVADO", "recurso", id,
                Map.of("ativo", antes.ativo()), Map.of("ativo", ativo)));
        return recursos.porId(id).orElseThrow();
    }
}
