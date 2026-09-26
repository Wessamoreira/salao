package br.com.salao.iam.internal.application;

import br.com.salao.iam.api.PerfilDisponivel;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.domain.CatalogoDePerfis;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * RT-IAM-009 — os perfis que se pode dar a alguém, com o que cada um concede.
 *
 * <p>Atrás de {@code usuario:manage} embora não leia nada do banco: a lista conta como o salão é
 * organizado por dentro — quem vê dinheiro, quem cria acesso, quem exige segundo fator. É mapa da
 * casa, e mapa da casa não se entrega a quem não administra a casa.
 */
public class ListarPerfisUseCase {

    @PreAuthorize("hasAuthority('" + Permissao.USUARIO_GERENCIAR + "')")
    public List<PerfilDisponivel> executar() {
        return CatalogoDePerfis.todos();
    }
}
