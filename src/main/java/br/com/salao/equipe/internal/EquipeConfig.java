package br.com.salao.equipe.internal;

import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.equipe.internal.application.CadastrarProfissionalUseCase;
import br.com.salao.equipe.internal.application.DefinirAtivacaoDoProfissionalUseCase;
import br.com.salao.equipe.internal.application.DefinirJornadaUseCase;
import br.com.salao.equipe.internal.application.ListarProfissionaisUseCase;
import br.com.salao.equipe.internal.application.RegistrarBloqueioUseCase;
import br.com.salao.equipe.internal.application.RemoverBloqueioUseCase;
import br.com.salao.equipe.internal.infra.ProfissionaisJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/** RT-EQP-001 — fiação do módulo equipe. */
@Configuration(proxyBeanMethods = false)
public class EquipeConfig {

    @Bean
    public ProfissionaisJdbc profissionaisJdbc(DataSource dataSource) {
        return new ProfissionaisJdbc(JdbcClient.create(dataSource));
    }

    /**
     * {@code @Primary} porque {@code ProfissionaisJdbc} também é bean e também implementa a
     * interface. Sem ele há dois candidatos, e a injeção passa a depender do NOME do campo de
     * quem injeta — foi assim que {@code ClienteApi} quebrou na primeira vez que alguém usou
     * outro nome (RT-CFG-001).
     */
    @Bean
    @Primary
    public ProfissionalApi profissionalApi(ProfissionaisJdbc jdbc) {
        return jdbc;
    }

    @Bean
    public CadastrarProfissionalUseCase cadastrarProfissionalUseCase(
            ProfissionaisJdbc profissionais, AuditoriaApi auditoria) {
        return new CadastrarProfissionalUseCase(profissionais, auditoria);
    }

    @Bean
    public DefinirJornadaUseCase definirJornadaUseCase(ProfissionaisJdbc profissionais,
                                                       AuditoriaApi auditoria) {
        return new DefinirJornadaUseCase(profissionais, auditoria);
    }

    @Bean
    public ListarProfissionaisUseCase listarProfissionaisUseCase(ProfissionaisJdbc profissionais) {
        return new ListarProfissionaisUseCase(profissionais);
    }

    @Bean
    public RegistrarBloqueioUseCase registrarBloqueioUseCase(ProfissionaisJdbc profissionais,
                                                             AuditoriaApi auditoria) {
        return new RegistrarBloqueioUseCase(profissionais, auditoria);
    }

    @Bean
    public RemoverBloqueioUseCase removerBloqueioUseCase(ProfissionaisJdbc profissionais,
                                                         AuditoriaApi auditoria) {
        return new RemoverBloqueioUseCase(profissionais, auditoria);
    }

    @Bean
    public DefinirAtivacaoDoProfissionalUseCase definirAtivacaoDoProfissionalUseCase(
            ProfissionaisJdbc profissionais, AuditoriaApi auditoria) {
        return new DefinirAtivacaoDoProfissionalUseCase(profissionais, auditoria);
    }
}
