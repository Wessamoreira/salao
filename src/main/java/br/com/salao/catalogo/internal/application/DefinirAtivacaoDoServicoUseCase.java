package br.com.salao.catalogo.internal.application;

import br.com.salao.catalogo.api.ErrosDoCatalogo;
import br.com.salao.catalogo.api.ServicoResumo;
import br.com.salao.catalogo.internal.infra.ServicosJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CAT-001 — tirar do cardápio sem apagar.
 *
 * <p>O histórico de atendimento aponta para o serviço. Apagar quebraria o relatório do que foi
 * vendido — e o extrato de comissão, que é calculado sobre ele. Desativar tira do cardápio e
 * mantém o passado inteligível.
 *
 * <p>Mesma decisão de cliente e profissional, pelo mesmo motivo — e aqui ela vale, ao contrário do
 * bloqueio de jornada (RT-EQP-003), que não tem nada apontando para si.
 */
public class DefinirAtivacaoDoServicoUseCase {

    private final ServicosJdbc servicos;
    private final AuditoriaApi auditoria;

    public DefinirAtivacaoDoServicoUseCase(ServicosJdbc servicos, AuditoriaApi auditoria) {
        this.servicos = servicos;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.CONFIG_GERENCIAR + "')")
    @Transactional
    public ServicoResumo executar(UUID id, boolean ativo) {
        ServicoResumo antes = servicos.porId(id).orElseThrow(() -> new ErroDeDominio(
                ErrosDoCatalogo.SERVICO_NAO_ENCONTRADO, "serviço " + id + " não encontrado"));

        if (antes.ativo() != ativo) {
            servicos.definirAtivacao(id, ativo);
            auditoria.registrar(RegistroDeAuditoria.alteracao(
                    ativo ? "SERVICO_REATIVADO" : "SERVICO_DESATIVADO", "servico", id,
                    Map.of("ativo", antes.ativo()), Map.of("ativo", ativo)));
        }
        return servicos.porId(id).orElseThrow();
    }
}
