package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.AgendamentoResumo;
import br.com.salao.agenda.api.ErrosDaAgenda;
import br.com.salao.agenda.internal.infra.AgendaJdbc;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.erro.ErroDeDominio;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-AGD-002 — ler um agendamento.
 *
 * <p>Agendamento de outro salão devolve <strong>404 e não 403</strong>: a RLS já o esconde, e
 * responder 403 confirmaria que aquele id existe em algum lugar. É a mesma decisão que o escopo
 * {@code :own} vai precisar quando existir.
 */
public class ConsultarAgendamentoUseCase {

    private final AgendaJdbc agenda;

    public ConsultarAgendamentoUseCase(AgendaJdbc agenda) {
        this.agenda = agenda;
    }

    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_LER_TODAS + "')"
            + " or hasAuthority('" + Permissao.AGENDA_LER_PROPRIA + "')")
    @Transactional(readOnly = true)
    public AgendamentoResumo porId(UUID id) {
        return agenda.porId(id).orElseThrow(() -> new ErroDeDominio(
                ErrosDaAgenda.AGENDAMENTO_NAO_ENCONTRADO,
                "agendamento " + id + " não encontrado"));
    }
}
