package br.com.salao.iam.internal.application;

import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.UsuarioResumo;
import br.com.salao.iam.internal.infra.UsuariosJdbc;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * RT-IAM-007 — listar os usuários do estabelecimento.
 *
 * <p>Sem paginação por keyset, ao contrário da regra geral do projeto: um salão tem entre cinco e
 * vinte usuários, e paginar isso seria cerimônia sem ganho. A regra existe para listagem que
 * cresce sem limite — clientes, agendamentos, movimentos —, e esta não cresce.
 */
public class ListarUsuariosUseCase {

    private final UsuariosJdbc usuarios;

    public ListarUsuariosUseCase(UsuariosJdbc usuarios) {
        this.usuarios = usuarios;
    }

    @PreAuthorize("hasAuthority('" + Permissao.USUARIO_GERENCIAR + "')")
    public List<UsuarioResumo> executar() {
        return usuarios.listar();
    }
}
