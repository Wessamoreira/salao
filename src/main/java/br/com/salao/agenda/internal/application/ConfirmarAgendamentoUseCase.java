package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.AgendamentoConfirmado;
import br.com.salao.agenda.api.AgendamentoResumo;
import br.com.salao.agenda.api.ErrosDaAgenda;
import br.com.salao.agenda.api.StatusAgendamento;
import br.com.salao.agenda.internal.infra.AgendaJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.erro.ErrosDaInfra;
import br.com.salao.shared.tenant.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-AGD-004 — confirmar.
 *
 * <h2>Confirmar de novo não é erro</h2>
 *
 * <p>A cliente recebe o lembrete e clica duas vezes; a recepção confirma por telefone o que a
 * cliente já tinha confirmado pelo link. Nos dois casos o estado desejado <strong>já vale</strong>,
 * e devolver erro faria a tela mostrar falha para quem fez tudo certo.
 *
 * <p>Isso não afrouxa RN-AGD-007: a transição {@code CONFIRMADO → CONFIRMADO} continua não
 * existindo, e por isso nada é gravado e nenhum evento sai. O que muda é a resposta a quem pediu —
 * "já está assim" em vez de "não pode".
 *
 * <h2>Cabeça e blocos, sempre juntos</h2>
 *
 * <p>RN-AGD-006. Confirmar não altera a ocupação (os dois status ocupam a agenda), mas o caminho
 * precisa ser o mesmo de cancelar, onde altera — e o único jeito de ter certeza disso é não haver
 * outro caminho. {@code AgendaJdbc.alterarStatus} muda os dois ou nenhum.
 */
public class ConfirmarAgendamentoUseCase {

    private static final Logger log =
            LoggerFactory.getLogger(ConfirmarAgendamentoUseCase.class);

    private final AgendaJdbc agenda;
    private final AuditoriaApi auditoria;
    private final ApplicationEventPublisher eventos;

    public ConfirmarAgendamentoUseCase(AgendaJdbc agenda, AuditoriaApi auditoria,
                                       ApplicationEventPublisher eventos) {
        this.agenda = agenda;
        this.auditoria = auditoria;
        this.eventos = eventos;
    }

    /**
     * @param pelaCliente se quem confirmou foi a própria cliente (link do lembrete) ou o salão.
     *                    Vai para o evento: mandar "obrigado por confirmar" para quem não
     *                    confirmou nada é pior do que não mandar nada
     */
    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_ESCREVER_TODAS + "')")
    @Transactional
    public AgendamentoResumo executar(UUID agendamentoId, int versao, boolean pelaCliente) {
        AgendamentoResumo antes = agenda.porId(agendamentoId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDaAgenda.AGENDAMENTO_NAO_ENCONTRADO,
                        "agendamento " + agendamentoId + " não encontrado"));

        if (antes.status() == StatusAgendamento.CONFIRMADO) {
            return antes;
        }
        if (!antes.status().podeTransicionarPara(StatusAgendamento.CONFIRMADO)) {
            throw new ErroDeDominio(ErrosDaAgenda.TRANSICAO_INVALIDA,
                    "agendamento em " + antes.status() + " não pode ser confirmado");
        }

        if (agenda.alterarStatus(agendamentoId, StatusAgendamento.CONFIRMADO, versao) == 0) {
            throw new ErroDeDominio(ErrosDaInfra.VERSAO_DESATUALIZADA,
                    "o agendamento foi alterado por outra pessoa; recarregue a grade");
        }

        auditoria.registrar(RegistroDeAuditoria.alteracao("AGENDAMENTO_CONFIRMADO", "agendamento",
                agendamentoId, Map.of("status", antes.status().name()),
                Map.of("status", StatusAgendamento.CONFIRMADO.name(),
                        "pelaCliente", pelaCliente)));
        eventos.publishEvent(new AgendamentoConfirmado(TenantContext.obrigatorio(), agendamentoId,
                antes.clienteId(), antes.profissionalId(), antes.inicio(), pelaCliente));
        log.info("Agendamento {} confirmado {}", agendamentoId,
                pelaCliente ? "pela cliente" : "pelo salão");

        return agenda.porId(agendamentoId).orElseThrow();
    }
}
