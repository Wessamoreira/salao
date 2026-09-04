package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.AgendamentoReagendado;
import br.com.salao.agenda.api.AgendamentoResumo;
import br.com.salao.agenda.api.ErrosDaAgenda;
import br.com.salao.agenda.api.Janela;
import br.com.salao.agenda.api.StatusAgendamento;
import br.com.salao.agenda.internal.infra.AgendaJdbc;
import br.com.salao.catalogo.api.RecursoApi;
import br.com.salao.catalogo.api.SequenciaDeBlocos;
import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.erro.ErrosDaInfra;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-AGD-003 — arrastar o agendamento na grade.
 *
 * <h2>Apagar antes de gravar, na mesma transação</h2>
 *
 * <p>É a decisão que faz a rotina funcionar. Mover das 13h para as 13h15 é mover para um horário
 * que <strong>se sobrepõe ao atual</strong>: sem apagar os blocos antigos primeiro, o agendamento
 * colidiria consigo mesmo na exclusion constraint e a recepção veria "horário ocupado" apontando
 * para o próprio atendimento que está tentando mover.
 *
 * <p>Na mesma transação porque, se ela falhar depois do delete, o horário não pode ficar vago: o
 * rollback devolve os blocos antigos e a cliente continua marcada onde estava.
 *
 * <h2>A duração pode mudar sozinha, e é correto</h2>
 *
 * <p>Arrastar para a coluna de outra profissional recalcula a receita: se a Ana faz escova em 30
 * minutos e a Júlia em 45, o bloco <em>cresce</em> ao mudar de coluna. É o que RN-CAT-013 diz, e é
 * o que a grade precisa mostrar — esconder isso seria prometer um horário que não existe.
 *
 * <p>Mas arrastar dentro da <em>mesma</em> coluna preserva a duração atual, inclusive uma que tenha
 * sido forçada na criação. Quem move um bloco de 90 minutos quinze minutos para frente espera um
 * bloco de 90 minutos.
 */
public class ReagendarUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReagendarUseCase.class);

    private final ProfissionalApi equipe;
    private final EstabelecimentoApi estabelecimentos;
    private final AgendaJdbc agenda;
    private final AuditoriaApi auditoria;
    private final ApplicationEventPublisher eventos;
    private final ReservaDeHorario reserva;

    public ReagendarUseCase(ProfissionalApi equipe, RecursoApi recursos,
                            EstabelecimentoApi estabelecimentos, AgendaJdbc agenda,
                            AuditoriaApi auditoria, ApplicationEventPublisher eventos,
                            Relogio relogio) {
        this.equipe = equipe;
        this.estabelecimentos = estabelecimentos;
        this.agenda = agenda;
        this.auditoria = auditoria;
        this.eventos = eventos;
        this.reserva = new ReservaDeHorario(equipe, recursos, agenda, relogio);
    }

    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_ESCREVER_TODAS + "')")
    @Transactional
    public AgendamentoResumo executar(PedidoDeReagendamento pedido) {
        UUID tenant = TenantContext.obrigatorio();
        AgendamentoResumo antes = agenda.porId(pedido.agendamentoId())
                .orElseThrow(() -> new ErroDeDominio(ErrosDaAgenda.AGENDAMENTO_NAO_ENCONTRADO,
                        "agendamento " + pedido.agendamentoId() + " não encontrado"));

        if (!antes.status().podeSerMovido()) {
            throw new ErroDeDominio(ErrosDaAgenda.NAO_PODE_REAGENDAR,
                    "agendamento em " + antes.status() + " não se move");
        }

        UUID profissionalId = pedido.profissionalId() == null
                ? antes.profissionalId()
                : pedido.profissionalId();

        // RN-AGD-001 de novo, e não por precaução: arrastar para outra coluna é justamente o
        // movimento que pode cair em quem não faz aquele serviço.
        SequenciaDeBlocos receita = equipe.receitaDe(profissionalId, antes.servicoId())
                .orElseThrow(() -> new ErroDeDominio(ErrosDaAgenda.PROFISSIONAL_NAO_EXECUTA,
                        "o profissional " + profissionalId + " não executa este serviço"));
        receita = comDuracao(receita, duracaoAPreservar(pedido, antes, profissionalId));

        // Apagar ANTES de montar a reserva: a alocação de unidades de recurso também precisa
        // enxergar o próprio agendamento como já liberado, senão ele disputaria o lavatório
        // consigo mesmo.
        agenda.apagarBlocos(antes.id());

        var montada = reserva.montar(profissionalId, receita, pedido.inicio(),
                estabelecimentos.fusoDe(tenant),
                new ReservaDeHorario.Forcamentos(pedido.forcarForaJornada(),
                        pedido.forcarPassado(), pedido.justificativa()));

        int movidas = agenda.moverCabeca(antes.id(), pedido.versao(), profissionalId,
                montada.periodo());
        if (movidas == 0) {
            // O agendamento existe (a leitura acima o achou), então o que não bate é a versão:
            // outra pessoa arrastou o mesmo bloco entre a tela e este clique.
            throw new ErroDeDominio(ErrosDaInfra.VERSAO_DESATUALIZADA,
                    "o agendamento foi alterado por outra pessoa; recarregue a grade");
        }
        try {
            agenda.inserirBlocos(tenant, antes.id(), montada.blocos(), antes.status());
        } catch (DataIntegrityViolationException e) {
            throw traduzir(e);
        }

        auditoria.registrar(RegistroDeAuditoria.alteracao("AGENDAMENTO_REAGENDADO", "agendamento",
                antes.id(), trilha(antes.profissionalId(), antes.inicio(), antes.fim()),
                comForcamentos(trilha(profissionalId, montada.periodo().inicio(),
                        montada.periodo().fim()), pedido)));
        eventos.publishEvent(new AgendamentoReagendado(tenant, antes.id(), antes.clienteId(),
                antes.profissionalId(), profissionalId, antes.inicio(),
                montada.periodo().inicio(), montada.periodo().fim()));
        log.info("Agendamento {} movido de {} para {}", antes.id(), antes.inicio(),
                montada.periodo().inicio());

        return agenda.porId(antes.id()).orElseThrow();
    }

    /**
     * Qual duração vale no destino.
     *
     * <p>Explícita no pedido vence. Sem ela, mudar de coluna adota o ritmo de quem vai atender
     * (RN-CAT-013) e ficar na mesma coluna preserva o que estava — inclusive uma duração forçada
     * na criação, que de outro modo se perderia no primeiro arrasto.
     */
    private Integer duracaoAPreservar(PedidoDeReagendamento pedido, AgendamentoResumo antes,
                                      UUID profissionalId) {
        if (pedido.duracaoMinutos() != null) {
            return pedido.duracaoMinutos();
        }
        if (!profissionalId.equals(antes.profissionalId())) {
            return null;
        }
        return (int) Duration.between(antes.inicio(), antes.fim()).toMinutes();
    }

    private SequenciaDeBlocos comDuracao(SequenciaDeBlocos receita, Integer minutos) {
        if (minutos == null || minutos == receita.duracaoTotal()) {
            return receita;
        }
        try {
            return receita.comDuracaoTotal(minutos);
        } catch (IllegalArgumentException e) {
            throw new ErroDeDominio(ErrosDaAgenda.NAO_PODE_REAGENDAR, e.getMessage());
        }
    }

    private ErroDeDominio traduzir(DataIntegrityViolationException e) {
        String mensagem = e.getMostSpecificCause().getMessage();
        if (mensagem != null && mensagem.contains("bloco_sem_sobreposicao_recurso")) {
            return new ErroDeDominio(ErrosDaAgenda.RECURSO_OCUPADO,
                    "o recurso foi tomado enquanto este agendamento era movido");
        }
        return new ErroDeDominio(ErrosDaAgenda.CONFLITO_HORARIO,
                "o horário foi tomado enquanto este agendamento era movido");
    }

    private Map<String, Object> trilha(UUID profissionalId, java.time.Instant inicio,
                                       java.time.Instant fim) {
        var dados = new LinkedHashMap<String, Object>();
        dados.put("profissionalId", profissionalId.toString());
        dados.put("inicio", inicio.toString());
        dados.put("fim", fim.toString());
        return dados;
    }

    private Map<String, Object> comForcamentos(Map<String, Object> destino,
                                               PedidoDeReagendamento pedido) {
        if (pedido.forcarForaJornada() || pedido.forcarPassado()) {
            destino.put("forcado", true);
            destino.put("justificativa", pedido.justificativa());
        }
        return destino;
    }
}
