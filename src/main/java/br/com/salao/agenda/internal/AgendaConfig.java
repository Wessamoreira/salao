package br.com.salao.agenda.internal;

import br.com.salao.agenda.api.DisponibilidadeApi;
import br.com.salao.agenda.internal.application.BloquearGradeUseCase;
import br.com.salao.agenda.internal.application.CancelarAgendamentoUseCase;
import br.com.salao.agenda.internal.application.ConfirmarAgendamentoUseCase;
import br.com.salao.agenda.internal.application.ConsultarAgendamentoUseCase;
import br.com.salao.agenda.internal.application.ConsultarDisponibilidadeUseCase;
import br.com.salao.agenda.internal.application.EmpurrarAgendaUseCase;
import br.com.salao.agenda.internal.application.CriarAgendamentoUseCase;
import br.com.salao.agenda.internal.application.ReagendarUseCase;
import br.com.salao.agenda.internal.application.RegistrarNoShowUseCase;
import br.com.salao.agenda.internal.infra.AgendaJdbc;
import br.com.salao.catalogo.api.RecursoApi;
import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.cliente.api.ClienteApi;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.shared.idempotencia.Idempotencia;
import br.com.salao.shared.tempo.Relogio;
import javax.sql.DataSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/** RT-AGD-001 — fiação do módulo agenda. */
@Configuration(proxyBeanMethods = false)
public class AgendaConfig {

    @Bean
    public AgendaJdbc agendaJdbc(DataSource dataSource) {
        return new AgendaJdbc(JdbcClient.create(dataSource));
    }

    /** {@code @Primary} pela mesma razão dos outros módulos — ver RT-CFG-001. */
    @Bean
    @Primary
    public DisponibilidadeApi disponibilidadeApi(ConsultarDisponibilidadeUseCase useCase) {
        return useCase;
    }

    @Bean
    public ConsultarDisponibilidadeUseCase consultarDisponibilidadeUseCase(
            ProfissionalApi equipe, ServicoApi catalogo, RecursoApi recursos,
            EstabelecimentoApi estabelecimentos, AgendaJdbc agenda, Relogio relogio) {
        return new ConsultarDisponibilidadeUseCase(equipe, catalogo, recursos, estabelecimentos,
                agenda, relogio);
    }

    // ------------------------------------------------------------------ RT-AGD-002

    @Bean
    public CriarAgendamentoUseCase criarAgendamentoUseCase(
            ProfissionalApi equipe, ServicoApi catalogo, RecursoApi recursos, ClienteApi clientes,
            EstabelecimentoApi estabelecimentos, AgendaJdbc agenda, AuditoriaApi auditoria,
            ApplicationEventPublisher eventos, Idempotencia idempotencia, Relogio relogio) {
        return new CriarAgendamentoUseCase(equipe, catalogo, recursos, clientes, estabelecimentos,
                agenda, auditoria, eventos, idempotencia, relogio);
    }

    @Bean
    public ConsultarAgendamentoUseCase consultarAgendamentoUseCase(AgendaJdbc agenda) {
        return new ConsultarAgendamentoUseCase(agenda);
    }

    @Bean
    public ReagendarUseCase reagendarUseCase(ProfissionalApi equipe, RecursoApi recursos,
                                             EstabelecimentoApi estabelecimentos,
                                             AgendaJdbc agenda, AuditoriaApi auditoria,
                                             ApplicationEventPublisher eventos, Relogio relogio) {
        return new ReagendarUseCase(equipe, recursos, estabelecimentos, agenda, auditoria,
                eventos, relogio);
    }

    // ------------------------------------------------------------------ RT-AGD-004

    @Bean
    public ConfirmarAgendamentoUseCase confirmarAgendamentoUseCase(
            AgendaJdbc agenda, AuditoriaApi auditoria, ApplicationEventPublisher eventos) {
        return new ConfirmarAgendamentoUseCase(agenda, auditoria, eventos);
    }

    // ------------------------------------------------------------------ RT-AGD-005

    @Bean
    public CancelarAgendamentoUseCase cancelarAgendamentoUseCase(
            AgendaJdbc agenda, AuditoriaApi auditoria, ApplicationEventPublisher eventos) {
        return new CancelarAgendamentoUseCase(agenda, auditoria, eventos);
    }

    // ------------------------------------------------------------------ RT-AGD-006

    @Bean
    public RegistrarNoShowUseCase registrarNoShowUseCase(AgendaJdbc agenda,
                                                         AuditoriaApi auditoria,
                                                         ApplicationEventPublisher eventos,
                                                         Relogio relogio) {
        return new RegistrarNoShowUseCase(agenda, auditoria, eventos, relogio);
    }

    // ------------------------------------------------------------------ RT-AGD-007

    @Bean
    public BloquearGradeUseCase bloquearGradeUseCase(AgendaJdbc agenda, ProfissionalApi equipe,
                                                     RecursoApi recursos, AuditoriaApi auditoria) {
        return new BloquearGradeUseCase(agenda, equipe, recursos, auditoria);
    }

    // ------------------------------------------------------------------ RT-AGD-008

    @Bean
    public EmpurrarAgendaUseCase empurrarAgendaUseCase(ProfissionalApi equipe, RecursoApi recursos,
                                                       EstabelecimentoApi estabelecimentos,
                                                       AgendaJdbc agenda, AuditoriaApi auditoria,
                                                       ApplicationEventPublisher eventos,
                                                       Relogio relogio) {
        return new EmpurrarAgendaUseCase(equipe, recursos, estabelecimentos, agenda, auditoria,
                eventos, relogio);
    }
}
