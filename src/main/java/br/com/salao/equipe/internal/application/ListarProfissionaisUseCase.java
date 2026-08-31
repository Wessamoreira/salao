package br.com.salao.equipe.internal.application;

import br.com.salao.equipe.api.ProfissionalResumo;
import br.com.salao.equipe.internal.infra.ProfissionaisJdbc;
import br.com.salao.iam.api.Permissao;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EQP-001 — quem trabalha aqui.
 *
 * <p>Sem paginação: um salão tem de três a trinta profissionais. A regra de paginar existe para
 * listagem que cresce sem limite, e esta não cresce.
 *
 * <p>Inclui os inativos, com os ativos primeiro: quem administra precisa ver quem saiu — para
 * trazer de volta, e para entender um extrato antigo.
 */
public class ListarProfissionaisUseCase {

    private final ProfissionaisJdbc profissionais;

    public ListarProfissionaisUseCase(ProfissionaisJdbc profissionais) {
        this.profissionais = profissionais;
    }

    @PreAuthorize("hasAuthority('" + Permissao.USUARIO_GERENCIAR + "')")
    @Transactional(readOnly = true)
    public List<ProfissionalResumo> executar() {
        return profissionais.todos();
    }
}
