package br.com.salao.catalogo.internal.application;

import br.com.salao.catalogo.api.ServicoResumo;
import br.com.salao.catalogo.internal.infra.ServicosJdbc;
import br.com.salao.iam.api.Permissao;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CAT-001 — o cardápio.
 *
 * <p>Leitura com {@code agenda:read:all}, não com {@code config:manage}: quem marca horário
 * precisa ver os serviços, e é a recepção que marca. Só alterar é do dono.
 */
public class ListarServicosUseCase {

    private final ServicosJdbc servicos;

    public ListarServicosUseCase(ServicosJdbc servicos) {
        this.servicos = servicos;
    }

    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_LER_TODAS + "')"
            + " or hasAuthority('" + Permissao.AGENDA_LER_PROPRIA + "')"
            + " or hasAuthority('" + Permissao.CONFIG_GERENCIAR + "')")
    @Transactional(readOnly = true)
    public List<ServicoResumo> executar(boolean incluirInativos) {
        return incluirInativos ? servicos.todos() : servicos.ativos();
    }
}
