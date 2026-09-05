package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.AgendamentoResumo;
import br.com.salao.agenda.api.ErrosDaAgenda;
import br.com.salao.agenda.api.NoShowRegistrado;
import br.com.salao.agenda.api.StatusAgendamento;
import br.com.salao.agenda.internal.infra.AgendaJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.erro.ErrosDaInfra;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-AGD-006 — a cliente não apareceu.
 *
 * <h2>Não se falta a um compromisso que ainda não começou</h2>
 *
 * <p>É a regra que distingue esta rotina do cancelamento. Cancelar é sobre o futuro — a cliente
 * avisa que não vem. No-show é sobre o passado: ela não veio, e isso só se sabe depois da hora.
 *
 * <p>Marcar antes registraria uma ausência que não aconteceu, e o status é terminal: quando a
 * cliente chegasse no horário certo, não haveria como desfazer. O relógio vem do port
 * {@code Relogio} (RN-INF-006), que é o que torna esta regra testável sem esperar.
 *
 * <h2>Basta ter começado, não terminado</h2>
 *
 * <p>Às 13h15 de um horário marcado para as 13h, a recepção já sabe. Exigir o fim (14h40) faria o
 * salão esperar uma hora e meia para registrar o que já era evidente — e o resto do horário, que
 * poderia receber um encaixe, ficaria bloqueado esse tempo todo.
 *
 * <p>Quanto esperar antes de desistir é decisão do salão e ainda não é configurável: hoje qualquer
 * instante depois do início serve.
 *
 * <h2>Quem está sendo atendido não faltou</h2>
 *
 * <p>{@code EM_ATENDIMENTO → NO_SHOW} não existe na máquina de estados, e é óbvio pelo motivo
 * certo: a cliente está na cadeira.
 */
public class RegistrarNoShowUseCase {

    private static final Logger log = LoggerFactory.getLogger(RegistrarNoShowUseCase.class);

    private final AgendaJdbc agenda;
    private final AuditoriaApi auditoria;
    private final ApplicationEventPublisher eventos;
    private final Relogio relogio;

    public RegistrarNoShowUseCase(AgendaJdbc agenda, AuditoriaApi auditoria,
                                  ApplicationEventPublisher eventos, Relogio relogio) {
        this.agenda = agenda;
        this.auditoria = auditoria;
        this.eventos = eventos;
        this.relogio = relogio;
    }

    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_ESCREVER_TODAS + "')")
    @Transactional
    public AgendamentoResumo executar(UUID agendamentoId, int versao) {
        AgendamentoResumo antes = agenda.porId(agendamentoId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDaAgenda.AGENDAMENTO_NAO_ENCONTRADO,
                        "agendamento " + agendamentoId + " não encontrado"));

        if (!antes.status().podeTransicionarPara(StatusAgendamento.NO_SHOW)) {
            throw new ErroDeDominio(ErrosDaAgenda.TRANSICAO_INVALIDA,
                    "agendamento em " + antes.status() + " não pode virar falta");
        }
        if (antes.inicio().isAfter(relogio.agora())) {
            throw new ErroDeDominio(ErrosDaAgenda.AINDA_NAO_COMECOU,
                    "o horário começa em " + antes.inicio() + "; ninguém faltou ainda");
        }

        if (agenda.alterarStatus(agendamentoId, StatusAgendamento.NO_SHOW, versao) == 0) {
            throw new ErroDeDominio(ErrosDaInfra.VERSAO_DESATUALIZADA,
                    "o agendamento foi alterado por outra pessoa; recarregue a grade");
        }

        int perdidos = antes.minutosDoProfissional();
        auditoria.registrar(RegistroDeAuditoria.alteracao("NO_SHOW_REGISTRADO", "agendamento",
                agendamentoId, Map.of("status", antes.status().name()),
                Map.of("status", StatusAgendamento.NO_SHOW.name(),
                        "minutosPerdidos", perdidos)));
        eventos.publishEvent(new NoShowRegistrado(TenantContext.obrigatorio(), agendamentoId,
                antes.clienteId(), antes.profissionalId(), antes.servicoId(), antes.inicio(),
                perdidos));
        log.info("No-show em {}: {} minutos de profissional perdidos", agendamentoId, perdidos);

        return agenda.porId(agendamentoId).orElseThrow();
    }
}
