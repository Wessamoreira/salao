package br.com.salao.cliente.internal.application;

import br.com.salao.cliente.api.ClienteResumo;
import br.com.salao.cliente.api.ErrosDoCliente;
import br.com.salao.cliente.internal.infra.ClientesJdbc;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.erro.ErroDeDominio;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/** RT-CLI-001 — abrir a ficha de um cliente. */
public class ConsultarClienteUseCase {

    private final ClientesJdbc clientes;

    public ConsultarClienteUseCase(ClientesJdbc clientes) {
        this.clientes = clientes;
    }

    /**
     * Cliente de outro tenant devolve o mesmo 404 de cliente inexistente.
     *
     * <p>Não por escolha de mensagem: a RLS simplesmente não devolve a linha, então a diferença
     * não chega até aqui. É a falha fechada valendo por construção, e não por lembrança de quem
     * escreveu o {@code if}.
     */
    @PreAuthorize("hasAuthority('" + Permissao.CLIENTE_LER + "')")
    @Transactional(readOnly = true)
    public ClienteResumo executar(UUID id) {
        return clientes.porId(id).orElseThrow(() -> new ErroDeDominio(
                ErrosDoCliente.CLIENTE_NAO_ENCONTRADO, "cliente " + id + " não encontrado"));
    }
}
