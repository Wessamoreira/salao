package br.com.salao.cliente.internal.application;

import br.com.salao.cliente.api.ErrosDoCliente;
import br.com.salao.cliente.internal.infra.ClientesJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CLI-005 — atender ao pedido de exclusão do titular (LGPD art. 18, VI).
 *
 * <h2>Anonimizar, não apagar</h2>
 *
 * <p>O histórico financeiro aponta para o cliente, e a legislação fiscal exige guardá-lo. Apagar a
 * linha quebraria o rastro que sustenta o extrato de comissão de quem trabalhou — o direito do
 * titular é sobre o <strong>dado pessoal</strong>, não sobre o registro contábil do salão.
 *
 * <p>Então o dado pessoal some e a linha fica.
 *
 * <h2>Uma honestidade sobre a palavra "anonimizar"</h2>
 *
 * <p>O {@code id} permanece, porque é ele que o histórico referencia. Do ponto de vista estrito da
 * LGPD (art. 12), isso é <strong>pseudonimização</strong>, não anonimização: a chave continua lá.
 * O que torna a operação defensável é que o dado que permitiria voltar à pessoa é destruído — e é
 * por isso que a trilha de auditoria precisou ser corrigida junto (V16). Chamar de anônimo um
 * registro que outra tabela sabe nomear seria mentir para quem confiou no sistema.
 *
 * <h2>Irreversível, e por isso com permissão própria</h2>
 *
 * <p>{@code cliente:anonymize} não está em {@code cliente:write}. A recepção cadastra e corrige o
 * dia inteiro; apagar o dado de alguém para sempre é outro tipo de ato, e o pedido do titular
 * chega a quem responde pelo salão — não a quem está atendendo o balcão.
 */
public class AnonimizarClienteUseCase {

    private static final Logger log = LoggerFactory.getLogger(AnonimizarClienteUseCase.class);

    /**
     * Sem sequência nem número: duas fichas anonimizadas ficam indistinguíveis no relatório, e é
     * assim que deve ser. Um "Cliente 7" convidaria alguém a descobrir quem era o sétimo.
     */
    static final String ROTULO = "Cliente anonimizado";

    private final ClientesJdbc clientes;
    private final AuditoriaApi auditoria;
    private final Relogio relogio;

    public AnonimizarClienteUseCase(ClientesJdbc clientes, AuditoriaApi auditoria,
                                    Relogio relogio) {
        this.clientes = clientes;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    @PreAuthorize("hasAuthority('" + Permissao.CLIENTE_ANONIMIZAR + "')")
    @Transactional
    public void executar(UUID clienteId) {
        if (!clientes.existe(clienteId)) {
            // Já anonimizado cai aqui também, e é o certo: repetir a operação não deve dizer
            // "pronto" sobre uma ficha que talvez seja de outro salão.
            throw new ErroDeDominio(ErrosDoCliente.CLIENTE_NAO_ENCONTRADO,
                    "cliente " + clienteId + " não encontrado");
        }

        int fichas = clientes.anonimizar(clienteId, ROTULO, relogio.agora());

        // A trilha registra que aconteceu, quem fez e quando — e nada mais. Guardar aqui o que
        // foi apagado transformaria o registro do apagamento em cópia do que se apagou.
        auditoria.registrar(RegistroDeAuditoria.alteracao("CLIENTE_ANONIMIZADO", "cliente",
                clienteId, null, Map.of("fichas", fichas)));
        log.info("Cliente {} anonimizado ({} ficha(s))", clienteId, fichas);
    }
}
