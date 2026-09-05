package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.AgendaEmpurrada;
import br.com.salao.agenda.api.AgendamentoResumo;
import br.com.salao.agenda.api.ErrosDaAgenda;
import br.com.salao.agenda.internal.infra.AgendaJdbc;
import br.com.salao.agenda.internal.infra.AgendaJdbc.AgendamentoAEmpurrar;
import br.com.salao.catalogo.api.RecursoApi;
import br.com.salao.catalogo.api.SequenciaDeBlocos;
import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-AGD-008 — a Ana chegou meia hora atrasada.
 *
 * <p>Seis clientes na agenda dela. Sem esta rotina, a recepção arrasta seis blocos à mão enquanto
 * as seis ligam perguntando — e cada arrasto é uma transação que pode falhar sozinha, deixando a
 * agenda num estado que ninguém escolheu.
 *
 * <h2>Tudo ou nada</h2>
 *
 * <p>É a decisão central. Uma agenda empurrada pela metade é <strong>pior</strong> do que uma agenda
 * não empurrada: a recepção não consegue dizer em que estado ela está, e já ligou para parte das
 * clientes avisando um horário novo que talvez não exista.
 *
 * <p>Então uma transação só. Se qualquer um dos atendimentos não couber, nada se move e o erro
 * nomeia qual barrou — que é o que a recepção precisa para decidir se cancela aquele, se fala com a
 * cliente, ou se empurra menos.
 *
 * <h2>Apaga todos, depois grava todos</h2>
 *
 * <p>Empurrar para frente move o primeiro para cima do segundo. Movê-los um a um exigiria ir do
 * último para o primeiro — e do primeiro para o último quando o empurrão é negativo, o que é
 * exatamente o tipo de detalhe que se acerta na implementação e se perde na primeira manutenção.
 *
 * <p>Apagando os blocos de <strong>todos</strong> antes de gravar qualquer um, a ordem deixa de
 * importar. É a lição de RT-AGD-003 ("o agendamento colide consigo mesmo") aplicada a um conjunto.
 *
 * <h2>Sair da jornada é a consequência esperada, não um erro</h2>
 *
 * <p>Se a Ana atrasou meia hora, o dia dela termina meia hora depois. Recusar o empurrão porque o
 * último atendimento passa das 18h tornaria a rotina inútil no caso em que ela mais serve.
 *
 * <p>Então o empurrão <strong>implica</strong> forçar a jornada, com o atraso registrado como
 * justificativa na trilha. O que ele não implica é conflito: cair em cima do atendimento de outra
 * pessoa, ou do lavatório dela, continua sendo recusa.
 */
public class EmpurrarAgendaUseCase {

    private static final Logger log = LoggerFactory.getLogger(EmpurrarAgendaUseCase.class);

    /** Um dia de deslocamento é quase certamente um erro de digitação, não um atraso. */
    private static final int LIMITE_DE_MINUTOS = 24 * 60;

    private final ProfissionalApi equipe;
    private final EstabelecimentoApi estabelecimentos;
    private final AgendaJdbc agenda;
    private final AuditoriaApi auditoria;
    private final ApplicationEventPublisher eventos;
    private final ReservaDeHorario reserva;

    public EmpurrarAgendaUseCase(ProfissionalApi equipe, RecursoApi recursos,
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

    /**
     * @param minutos quanto empurrar. Negativo puxa para trás — a profissional chegou adiantada,
     *                ou o atraso foi menor do que se pensava
     */
    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_ESCREVER_TODAS + "')")
    @Transactional
    public List<AgendamentoResumo> executar(UUID profissionalId, Instant aPartirDe, int minutos,
                                            String justificativa) {
        if (minutos == 0) {
            throw new ErroDeDominio(ErrosDaAgenda.EMPURRAO_NAO_COUBE,
                    "empurrar zero minutos não move nada");
        }
        if (Math.abs(minutos) > LIMITE_DE_MINUTOS) {
            throw new ErroDeDominio(ErrosDaAgenda.EMPURRAO_NAO_COUBE,
                    "deslocamento de " + minutos + " minutos passa de um dia; confira o valor");
        }
        if (justificativa == null || justificativa.isBlank()) {
            // O empurrão força a jornada de todo mundo que ele toca. Forçar sem dizer por quê é o
            // mesmo que não ter regra — a mesma exigência de RT-AGD-002.
            throw new ErroDeDominio(ErrosDaAgenda.EMPURRAO_NAO_COUBE,
                    "empurrar a agenda exige justificativa, que vai para a trilha");
        }
        if (!equipe.estaAtivo(profissionalId)) {
            throw new ErroDeDominio(ErrosDaAgenda.PROFISSIONAL_NAO_EXECUTA,
                    "profissional " + profissionalId + " não encontrado ou inativo");
        }

        List<AgendamentoAEmpurrar> aMover = agenda.moveisDe(profissionalId, aPartirDe);
        if (aMover.isEmpty()) {
            return List.of();
        }

        UUID tenant = TenantContext.obrigatorio();
        ZoneId fuso = estabelecimentos.fusoDe(tenant);
        Duration deslocamento = Duration.ofMinutes(minutos);

        // Apaga TODOS antes de gravar qualquer um: assim o conjunto não disputa consigo mesmo e a
        // ordem das escritas deixa de importar.
        aMover.forEach(alvo -> agenda.apagarBlocos(alvo.id()));

        var movidos = new ArrayList<UUID>(aMover.size());
        for (AgendamentoAEmpurrar alvo : aMover) {
            SequenciaDeBlocos receita = equipe.receitaDe(profissionalId, alvo.servicoId())
                    .orElseThrow(() -> new ErroDeDominio(ErrosDaAgenda.PROFISSIONAL_NAO_EXECUTA,
                            "o profissional deixou de executar o serviço de um dos atendimentos"));

            var montada = reserva.montar(profissionalId, receita,
                    alvo.inicio().plus(deslocamento), fuso,
                    // Forçar jornada e passado: se a Ana atrasou, o dia dela termina mais tarde, e
                    // puxar para trás pode encostar no que já passou.
                    new ReservaDeHorario.Forcamentos(true, true, justificativa));

            agenda.moverCabeca(alvo.id(), alvo.versao(), profissionalId, montada.periodo());
            try {
                agenda.inserirBlocos(tenant, alvo.id(), montada.blocos(), alvo.status());
            } catch (DataIntegrityViolationException e) {
                // A transação inteira cai. Nomear qual barrou é o que a recepção precisa para
                // decidir: cancela aquele, fala com a cliente, ou empurra menos.
                throw new ErroDeDominio(ErrosDaAgenda.EMPURRAO_NAO_COUBE,
                        "o atendimento das " + alvo.inicio() + " não cabe "
                                + minutos + " minutos adiante; nada foi movido");
            }
            movidos.add(alvo.id());
        }

        auditoria.registrar(RegistroDeAuditoria.alteracao("AGENDA_EMPURRADA", "profissional",
                profissionalId, Map.of("aPartirDe", aPartirDe.toString()),
                trilha(minutos, justificativa, movidos)));
        eventos.publishEvent(new AgendaEmpurrada(tenant, profissionalId, aPartirDe, minutos,
                movidos));
        log.info("Agenda de {} empurrada em {} minutos: {} atendimento(s)", profissionalId,
                minutos, movidos.size());

        return movidos.stream().map(id -> agenda.porId(id).orElseThrow()).toList();
    }

    private Map<String, Object> trilha(int minutos, String justificativa, List<UUID> movidos) {
        var dados = new LinkedHashMap<String, Object>();
        dados.put("minutos", minutos);
        dados.put("justificativa", justificativa);
        dados.put("atendimentos", movidos.size());
        // O empurrão força a jornada de todos que toca — fica dito, não implícito.
        dados.put("forcouForaJornada", true);
        return dados;
    }
}
