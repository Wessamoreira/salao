package br.com.salao.cliente.internal.application;

import br.com.salao.cliente.api.CanalDeConsentimento;
import br.com.salao.cliente.api.ErrosDoCliente;
import br.com.salao.cliente.api.Finalidade;
import br.com.salao.cliente.internal.infra.ClientesJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CLI-004 — registrar o que a cliente decidiu sobre cada finalidade.
 *
 * <h2>Conceder e revogar são a mesma operação</h2>
 *
 * <p>A LGPD exige que revogar seja tão fácil quanto consentir (art. 8 §5). A forma mais confiável
 * de cumprir isso não é lembrar de facilitar o "não": é <strong>não ter dois caminhos</strong>. Um
 * endpoint separado para revogação seria, inevitavelmente, o mais estreito dos dois — menos usado,
 * menos testado, e o primeiro a quebrar sem ninguém perceber.
 *
 * <h2>Append-only, e por quê</h2>
 *
 * <p>Cada decisão é uma linha nova. A pergunta que esta tabela precisa responder não é "o que vale
 * hoje", é "<strong>o que valia no dia em que a mensagem foi enviada?</strong>" — e uma linha
 * editável só sabe responder a primeira, que é justamente a resposta inútil num questionamento.
 *
 * <p>A garantia é permissão de banco, não convenção: {@code salao_app} não tem {@code UPDATE} nem
 * {@code DELETE} nesta tabela.
 *
 * <h2>Sem campo de observação</h2>
 *
 * <p>Havia um, e saiu em V16. Campo livre digitado pela recepção recebe dado pessoal mais cedo ou
 * mais tarde — e como a tabela é append-only, a anonimização (RT-CLI-005) <strong>não conseguiria
 * limpá-lo</strong>. Conceder {@code UPDATE} para resolver desfaria a garantia que faz o registro
 * valer como prova. Canal, data, decisão e autor já respondem "onde está a prova de que ela
 * aceitou?".
 */
public class RegistrarConsentimentoUseCase {

    private static final Logger log = LoggerFactory.getLogger(RegistrarConsentimentoUseCase.class);

    private final ClientesJdbc clientes;
    private final AuditoriaApi auditoria;
    private final Relogio relogio;

    public RegistrarConsentimentoUseCase(ClientesJdbc clientes, AuditoriaApi auditoria,
                                         Relogio relogio) {
        this.clientes = clientes;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    @PreAuthorize("hasAuthority('" + Permissao.CLIENTE_ESCREVER + "')")
    @Transactional
    public void executar(UUID clienteId, Finalidade finalidade, boolean concedido,
                         CanalDeConsentimento canal) {
        if (!clientes.existe(clienteId)) {
            throw new ErroDeDominio(ErrosDoCliente.CLIENTE_NAO_ENCONTRADO,
                    "cliente " + clienteId + " não encontrado");
        }

        clientes.registrarConsentimento(TenantContext.obrigatorio(), clienteId, finalidade,
                concedido, canal, relogio.agora(), UsuarioAtual.idOuNulo());

        // A trilha registra a decisão porque é ela que se apresenta num questionamento — e aqui,
        // ao contrário do cadastro (RN-CLI-007), não há PII a proteger: só id, finalidade e canal.
        auditoria.registrar(RegistroDeAuditoria.alteracao("CONSENTIMENTO_REGISTRADO", "cliente",
                clienteId, null,
                Map.of("finalidade", finalidade.name(),
                        "concedido", concedido,
                        "canal", canal.name())));
        log.info("Consentimento de {} para {}: {}", clienteId, finalidade, concedido);
    }
}
