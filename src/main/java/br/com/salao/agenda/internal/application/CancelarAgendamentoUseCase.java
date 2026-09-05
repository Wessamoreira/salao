package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.AgendamentoCancelado;
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
 * RT-AGD-005 — cancelar, com motivo.
 *
 * <h2>É aqui que o horário volta para a agenda</h2>
 *
 * <p>{@code CANCELADO} não está em {@code OCUPAM_A_AGENDA}, que é o mesmo conjunto do filtro das
 * exclusion constraints. Mudar o status dos blocos — e não só o da cabeça — é o que faz o horário
 * ser oferecido de novo (RN-AGD-006). As rotinas anteriores exercitaram esse caminho sem que ele
 * mudasse nada; esta é a primeira em que mudar de verdade importa.
 *
 * <p>O recurso volta junto, pelo mesmo mecanismo: o lavatório reservado para uma coloração
 * cancelada fica livre no instante do cancelamento, sem código específico.
 *
 * <h2>Terminal é terminal</h2>
 *
 * <p>Não existe "descancelar". Desfazer devolveria à agenda um horário que, entre o cancelamento e
 * o arrependimento, pode ter sido oferecido e vendido a outra pessoa — e aí duas clientes teriam a
 * mesma cadeira, que é a reclamação nº 1 do caderno. Quem se arrependeu marca de novo, e o sistema
 * dirá honestamente se o horário ainda existe.
 *
 * <h2>Cancelar de novo NÃO é como confirmar de novo</h2>
 *
 * <p>Em RT-AGD-004, confirmar o que já estava confirmado devolve o estado atual: o pedido era
 * inócuo. Aqui o segundo cancelamento chegaria com <strong>outro motivo</strong> e outra autoria, e
 * aceitá-lo em silêncio sobrescreveria o registro do que de fato aconteceu — ou o descartaria sem
 * dizer. Cancelar um cancelado é {@code ER-AGD-TRANSICAO_INVALIDA}.
 */
public class CancelarAgendamentoUseCase {

    private static final Logger log =
            LoggerFactory.getLogger(CancelarAgendamentoUseCase.class);

    private final AgendaJdbc agenda;
    private final AuditoriaApi auditoria;
    private final ApplicationEventPublisher eventos;

    public CancelarAgendamentoUseCase(AgendaJdbc agenda, AuditoriaApi auditoria,
                                      ApplicationEventPublisher eventos) {
        this.agenda = agenda;
        this.auditoria = auditoria;
        this.eventos = eventos;
    }

    /**
     * @param motivo      texto livre, obrigatório. Sem ele o histórico da cliente vira uma coluna
     *                    de "cancelado" sem sentido
     * @param pelaCliente quem desmarcou. "A Marilda desmarcou três vezes" e "eu remarquei a Marilda
     *                    três vezes porque a Ana faltou" são conversas diferentes, e sem isto
     *                    viram a mesma linha no relatório
     */
    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_ESCREVER_TODAS + "')")
    @Transactional
    public AgendamentoResumo executar(UUID agendamentoId, int versao, String motivo,
                                      boolean pelaCliente) {
        String limpo = motivo == null ? null : motivo.strip();
        if (limpo == null || limpo.isEmpty()) {
            throw new ErroDeDominio(ErrosDaAgenda.MOTIVO_OBRIGATORIO,
                    "informe por que o atendimento foi cancelado");
        }

        AgendamentoResumo antes = agenda.porId(agendamentoId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDaAgenda.AGENDAMENTO_NAO_ENCONTRADO,
                        "agendamento " + agendamentoId + " não encontrado"));

        if (!antes.status().podeTransicionarPara(StatusAgendamento.CANCELADO)) {
            throw new ErroDeDominio(ErrosDaAgenda.TRANSICAO_INVALIDA,
                    "agendamento em " + antes.status() + " não pode ser cancelado");
        }

        if (agenda.alterarStatus(agendamentoId, StatusAgendamento.CANCELADO, versao, limpo,
                pelaCliente) == 0) {
            throw new ErroDeDominio(ErrosDaInfra.VERSAO_DESATUALIZADA,
                    "o agendamento foi alterado por outra pessoa; recarregue a grade");
        }

        auditoria.registrar(RegistroDeAuditoria.alteracao("AGENDAMENTO_CANCELADO", "agendamento",
                agendamentoId, Map.of("status", antes.status().name()),
                Map.of("status", StatusAgendamento.CANCELADO.name(),
                        "motivo", limpo, "pelaCliente", pelaCliente)));

        // A janela liberada viaja no evento: é o que a fila de espera precisa para achar quem
        // cabe ali, e buscar o agendamento de volta não serviria — ele acabou de deixar de existir
        // para a agenda.
        eventos.publishEvent(new AgendamentoCancelado(TenantContext.obrigatorio(), agendamentoId,
                antes.clienteId(), antes.profissionalId(), antes.servicoId(),
                antes.inicio(), antes.fim(), pelaCliente));
        log.info("Agendamento {} cancelado {} ({} às {})", agendamentoId,
                pelaCliente ? "pela cliente" : "pelo salão", antes.inicio(), antes.fim());

        return agenda.porId(agendamentoId).orElseThrow();
    }
}
