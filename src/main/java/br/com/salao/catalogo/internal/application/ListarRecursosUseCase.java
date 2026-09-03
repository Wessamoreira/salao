package br.com.salao.catalogo.internal.application;

import br.com.salao.catalogo.api.Recurso;
import br.com.salao.catalogo.internal.infra.RecursosJdbc;
import br.com.salao.iam.api.Permissao;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CAT-003 — listar os recursos.
 *
 * <p>Ler basta {@code agenda:read:*}, como o cardápio (RN-CAT-004): quem marca precisa entender por
 * que um horário não está disponível, e "os dois lavatórios estão ocupados" só é uma explicação se
 * a recepção souber que existem dois.
 */
public class ListarRecursosUseCase {

    private final RecursosJdbc recursos;

    public ListarRecursosUseCase(RecursosJdbc recursos) {
        this.recursos = recursos;
    }

    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_LER_TODAS + "')"
            + " or hasAuthority('" + Permissao.AGENDA_LER_PROPRIA + "')"
            + " or hasAuthority('" + Permissao.CONFIG_GERENCIAR + "')")
    @Transactional(readOnly = true)
    public List<Recurso> executar(boolean incluirInativos) {
        return incluirInativos ? recursos.todos() : recursos.ativos();
    }
}
