package br.com.salao.cliente.internal.application;

import br.com.salao.cliente.api.ErrosDoCliente;
import br.com.salao.cliente.api.EstadoDoConsentimento;
import br.com.salao.cliente.internal.infra.ClientesJdbc;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.erro.ErroDeDominio;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/** RT-CLI-004 — o que vale hoje para cada finalidade, inclusive as nunca perguntadas. */
public class ConsultarConsentimentosUseCase {

    private final ClientesJdbc clientes;

    public ConsultarConsentimentosUseCase(ClientesJdbc clientes) {
        this.clientes = clientes;
    }

    @PreAuthorize("hasAuthority('" + Permissao.CLIENTE_LER + "')")
    @Transactional(readOnly = true)
    public List<EstadoDoConsentimento> executar(UUID clienteId) {
        if (!clientes.existe(clienteId)) {
            throw new ErroDeDominio(ErrosDoCliente.CLIENTE_NAO_ENCONTRADO,
                    "cliente " + clienteId + " não encontrado");
        }
        return clientes.consentimentos(clienteId);
    }
}
