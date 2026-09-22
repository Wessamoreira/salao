package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.ErrosDaAgenda;
import br.com.salao.agenda.api.EsperaNaFila;
import br.com.salao.agenda.api.Janela;
import br.com.salao.agenda.api.Recorrencia;
import br.com.salao.agenda.internal.infra.AgendaJdbc;
import br.com.salao.agenda.internal.infra.FilaDeEsperaJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-AGD-010 — um dia da série que não coube vai para a fila (RT-AGD-009).
 *
 * <p>Decisão do dono (22/09/2026): a recepção decide, <strong>um dia por vez</strong>. A cliente
 * pode não querer aquele dia avulso, e por isso nada entra na fila sozinho.
 *
 * <p>A espera é o dia inteiro, com o serviço e a profissional da série, e sem faixa de hora: a
 * cliente disse "quinta", e a recepção confirma a hora quando ligar.
 *
 * <h2>O dia precisa ser um dia que não coube</h2>
 *
 * <p>A data de uma das ocorrências — contada no fuso do salão, como a série é — e sem agendamento
 * da série nela. Qualquer outro dia é um pedido novo, e para ele existe a entrada comum, com a
 * janela que a cliente escolher.
 */
public class EnfileirarDiaDaSerieUseCase {

    private static final Logger log = LoggerFactory.getLogger(EnfileirarDiaDaSerieUseCase.class);

    /** O mesmo teto de RT-AGD-009: nenhuma série passa de 52 ocorrências. */
    private static final int MAXIMO_DE_OCORRENCIAS = 52;

    private final AgendaJdbc agenda;
    private final FilaDeEsperaJdbc fila;
    private final EstabelecimentoApi estabelecimentos;
    private final AuditoriaApi auditoria;
    private final Relogio relogio;

    public EnfileirarDiaDaSerieUseCase(AgendaJdbc agenda, FilaDeEsperaJdbc fila,
                                       EstabelecimentoApi estabelecimentos,
                                       AuditoriaApi auditoria, Relogio relogio) {
        this.agenda = agenda;
        this.fila = fila;
        this.estabelecimentos = estabelecimentos;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_ESCREVER_TODAS + "')")
    @Transactional
    public EsperaNaFila executar(UUID recorrenciaId, LocalDate dia) {
        UUID tenant = TenantContext.obrigatorio();
        Recorrencia serie = agenda.recorrenciaPorId(recorrenciaId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDaAgenda.RECORRENCIA_NAO_ENCONTRADA,
                        "recorrência " + recorrenciaId + " não encontrada"));
        ZoneId fuso = estabelecimentos.fusoDe(tenant);

        conferirDia(serie, dia, fuso);

        EsperaNaFila pedido = new EsperaNaFila(null, serie.clienteId(), serie.servicoId(),
                serie.profissionalId(), dia, dia, null, null, null, null, serie.id());
        UUID id = EntradaNaFila.gravar(fila, tenant, pedido);
        auditoria.registrar(RegistroDeAuditoria.criacao("fila_espera", id,
                Map.of("recorrenciaId", serie.id().toString(), "de", dia.toString(),
                        "ate", dia.toString())));
        log.info("Dia {} da série {} entrou na fila de espera", dia, serie.id());
        return fila.porId(id).orElseThrow();
    }

    private void conferirDia(Recorrencia serie, LocalDate dia, ZoneId fuso) {
        if (!serie.ativa()) {
            throw new ErroDeDominio(ErrosDaAgenda.DIA_FORA_DA_SERIE,
                    "a série foi encerrada; use a entrada comum da fila");
        }
        LocalDate hoje = relogio.agora().atZone(fuso).toLocalDate();
        if (dia == null || dia.isBefore(hoje)) {
            throw new ErroDeDominio(ErrosDaAgenda.DIA_FORA_DA_SERIE, "este dia já passou");
        }

        LocalDate primeiro = serie.primeiroInicio().atZone(fuso).toLocalDate();
        long dias = ChronoUnit.DAYS.between(primeiro, dia);
        long passo = 7L * serie.frequencia().semanas();
        if (dias < 0 || dias % passo != 0 || dias / passo >= MAXIMO_DE_OCORRENCIAS) {
            throw new ErroDeDominio(ErrosDaAgenda.DIA_FORA_DA_SERIE,
                    dia + " não é um dia desta série");
        }

        Janela doDia = new Janela(dia.atStartOfDay(fuso).toInstant(),
                dia.plusDays(1).atStartOfDay(fuso).toInstant());
        if (agenda.serieTemAgendamentoEm(serie.id(), doDia)) {
            throw new ErroDeDominio(ErrosDaAgenda.DIA_FORA_DA_SERIE,
                    "a série já tem horário em " + dia + "; este dia coube");
        }
    }
}
