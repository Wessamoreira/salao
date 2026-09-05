package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.ErrosDaAgenda;
import br.com.salao.agenda.api.Recorrencia;
import br.com.salao.agenda.internal.infra.AgendaJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-AGD-009 — "a Marilda não vem mais toda quinta".
 *
 * <h2>Encerrar a série cancela o futuro, não o passado</h2>
 *
 * <p>As ocorrências que já aconteceram continuam onde estão: elas são o histórico do que foi feito,
 * e mexer nelas mudaria o relatório do mês passado.
 *
 * <h2>Cada cancelamento é um cancelamento de verdade</h2>
 *
 * <p>Não há atalho: cada ocorrência futura passa por {@code CancelarAgendamentoUseCase}, com motivo
 * e trilha. Apagar as linhas em massa seria mais rápido e perderia exatamente o que RT-AGD-005
 * existe para guardar — e o histórico da cliente diria que ela nunca teve aquele horário.
 *
 * <h2>Aqui é tudo ou nada, ao contrário de criar</h2>
 *
 * <p>Criar uma série é parcial de propósito: a terceira quinta ser feriado é esperado. Encerrar não
 * tem equivalente — um cancelamento só falha por versão desatualizada ou status impossível, e
 * qualquer um dos dois significa que a série mudou debaixo de quem pediu o encerramento.
 *
 * <p>Então uma transação só: os cancelamentos entram nela, e uma série meio encerrada — metade
 * cancelada, a regra ainda ativa — não chega a existir.
 */
public class EncerrarRecorrenciaUseCase {

    private static final Logger log = LoggerFactory.getLogger(EncerrarRecorrenciaUseCase.class);

    private final AgendaJdbc agenda;
    private final CancelarAgendamentoUseCase cancelar;
    private final AuditoriaApi auditoria;
    private final Relogio relogio;

    public EncerrarRecorrenciaUseCase(AgendaJdbc agenda, CancelarAgendamentoUseCase cancelar,
                                      AuditoriaApi auditoria, Relogio relogio) {
        this.agenda = agenda;
        this.cancelar = cancelar;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    /** @return quantas ocorrências futuras foram canceladas */
    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_ESCREVER_TODAS + "')")
    @Transactional
    public int executar(UUID recorrenciaId, String motivo) {
        Recorrencia antes = agenda.recorrenciaPorId(recorrenciaId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDaAgenda.RECORRENCIA_NAO_ENCONTRADA,
                        "recorrência " + recorrenciaId + " não encontrada"));
        if (motivo == null || motivo.isBlank()) {
            throw new ErroDeDominio(ErrosDaAgenda.MOTIVO_OBRIGATORIO,
                    "encerrar uma série cancela horários já prometidos; diga por quê");
        }

        List<UUID> futuras = agenda.ocorrenciasFuturasDe(recorrenciaId, relogio.agora());
        int canceladas = 0;
        for (UUID ocorrencia : futuras) {
            var atual = agenda.porId(ocorrencia).orElseThrow();
            cancelar.executar(ocorrencia, atual.versao(), motivo, false);
            canceladas++;
        }
        agenda.encerrarRecorrencia(recorrenciaId);

        auditoria.registrar(RegistroDeAuditoria.alteracao("RECORRENCIA_ENCERRADA", "recorrencia",
                recorrenciaId, Map.of("ativa", antes.ativa()),
                Map.of("ativa", false, "motivo", motivo, "canceladas", canceladas)));
        log.info("Série {} encerrada: {} ocorrências futuras canceladas", recorrenciaId,
                canceladas);
        return canceladas;
    }
}
