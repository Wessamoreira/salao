package br.com.salao.agenda.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.agenda.api.ErrosDaAgenda;
import br.com.salao.agenda.api.OrigemDoAgendamento;
import br.com.salao.catalogo.api.TipoDeBloco;
import br.com.salao.catalogo.internal.application.CadastrarServicoUseCase;
import br.com.salao.catalogo.internal.application.EtapaBruta;
import br.com.salao.cliente.internal.application.CadastrarClienteUseCase;
import br.com.salao.cliente.internal.application.DadosDoCliente;
import br.com.salao.equipe.api.TipoDeVinculo;
import br.com.salao.equipe.internal.application.CadastrarProfissionalUseCase;
import br.com.salao.equipe.internal.application.DadosDoProfissional;
import br.com.salao.equipe.internal.application.DefinirJornadaUseCase;
import br.com.salao.equipe.internal.application.PeriodoBruto;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.agente.FerramentaDeEscrita.Aceite;
import br.com.salao.shared.agente.FerramentaDeEscrita.AceiteRegistrado;
import br.com.salao.shared.agente.FerramentaDeEscrita.Efetivacao;
import br.com.salao.shared.agente.FerramentaDeEscrita.Simulacao;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.erro.ErrosDaInfra;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** RT-CNV-005 — as ferramentas da agenda, chamadas diretamente, com os casos de uso reais. */
@WithMockUser(authorities = { Permissao.USUARIO_GERENCIAR, Permissao.CONFIG_GERENCIAR,
        Permissao.CLIENTE_ESCREVER, Permissao.AGENDA_LER_TODAS,
        Permissao.AGENDA_ESCREVER_TODAS })
class FerramentasDaAgendaIT extends AbstractPostgresIT {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate DIA = LocalDate.now(SAO_PAULO).plusDays(7);
    private static final String[] CPFS = { "11144477735", "52998224725" };

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private DefinirJornadaUseCase definirJornada;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private CriarAgendamentoUseCase criar;
    @Autowired private FerramentaConsultarDisponibilidade disponibilidade;
    @Autowired private FerramentaAgendar agendar;
    @Autowired private PlatformTransactionManager transacoes;

    private final JsonMapper json = JsonMapper.builder().build();

    private UUID salao() {
        return provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    private UUID profissional(UUID tenant, String nome, int cpf) {
        return profissional(tenant, nome, cpf, null);
    }

    private UUID profissional(UUID tenant, String nome, int cpf, String telefone) {
        UUID id = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional(nome, null, CPFS[cpf], TipoDeVinculo.EMPREGADO,
                        telefone, null, null, null, null, null))).id();
        TenantContext.executar(tenant, () -> definirJornada.executar(id,
                Map.of(DIA.getDayOfWeek(),
                        List.of(new PeriodoBruto(LocalTime.of(9, 0), LocalTime.of(18, 0))))));
        return id;
    }

    private UUID marilda(UUID tenant) {
        return TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Marilda", null, null, null, null)));
    }

    private UUID corte(UUID tenant) {
        return TenantContext.obter(tenant, () -> cadastrarServico.executar("Corte", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 30)))).id();
    }

    private UUID mechas(UUID tenant) {
        return TenantContext.obter(tenant, () -> cadastrarServico.executar("Mechas", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 30),
                        new EtapaBruta(TipoDeBloco.PAUSA, 40),
                        new EtapaBruta(TipoDeBloco.ATIVO, 30)))).id();
    }

    private static Instant as(int hora, int minuto) {
        return DIA.atTime(hora, minuto).atZone(SAO_PAULO).toInstant();
    }

    private static String quando(String inicio, String fim) {
        return DateTimeFormatter.ofPattern("EEEE, dd/MM", Locale.forLanguageTag("pt-BR"))
                .format(DIA) + ", das " + inicio + " às " + fim;
    }

    private ObjectNode marcacao(UUID cliente, UUID servico, UUID profissional, String inicio) {
        return json.createObjectNode()
                .put("cliente_id", cliente.toString())
                .put("servico_id", servico.toString())
                .put("profissional_id", profissional.toString())
                .put("inicio", inicio);
    }

    private void marcarNoBalcao(UUID tenant, UUID cliente, UUID profissional, UUID servico,
                                Instant inicio) {
        TenantContext.executar(tenant, () -> criar.executar(new NovoAgendamento(cliente,
                profissional, servico, inicio, OrigemDoAgendamento.PAINEL, null, null, false,
                false, null, null)));
    }

    private void marcarNoBalcaoForcando(UUID tenant, UUID cliente, UUID profissional,
                                        UUID servico, Instant inicio) {
        TenantContext.executar(tenant, () -> criar.executar(new NovoAgendamento(cliente,
                profissional, servico, inicio, OrigemDoAgendamento.PAINEL, null, null, true,
                false, "encaixe combinado no balcão", null)));
    }

    private String trilhaDe(UUID agendamentoId) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "select depois::text from auditoria where entidade = 'agendamento' "
                        + "and entidade_id = ?")) {
            ps.setObject(1, agendamentoId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private int agendamentosDo(UUID tenant) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "select count(*) from agendamento where estabelecimento_id = ?")) {
            ps.setObject(1, tenant);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private ErroDeDominio recusa(Runnable chamada) {
        Throwable erro = catchThrowable(chamada::run);
        assertThat(erro).isInstanceOf(ErroDeDominio.class);
        return (ErroDeDominio) erro;
    }

    // ------------------------------------------------------------------ consultar_disponibilidade

    @Test
    @DisplayName("consultar_disponibilidade devolve data e hora do salão, nunca UTC")
    void disponibilidade_em_hora_do_salao() {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID corte = corte(tenant);

        JsonNode saida = TenantContext.obter(tenant, () -> disponibilidade.executar(json
                .createObjectNode().put("data", DIA.toString()).put("servico_id", corte.toString())));

        JsonNode primeiro = saida.path("horarios").get(0);
        assertThat(primeiro.path("inicio").asString()).isEqualTo(DIA + "T09:00");
        assertThat(primeiro.path("fim").asString()).isEqualTo(DIA + "T09:30");
        assertThat(primeiro.path("profissional").asString()).isEqualTo("Ana");
        assertThat(primeiro.path("profissional_id").asString()).isEqualTo(ana.toString());
        assertThat(saida.toString()).doesNotContain("Z\"");
    }

    @Test
    @DisplayName("a lista tem teto, com o total real e o aviso para pedir por profissional")
    void disponibilidade_com_teto() {
        UUID tenant = salao();
        profissional(tenant, "Ana", 0);
        profissional(tenant, "Bruna", 1);
        UUID corte = corte(tenant);

        JsonNode saida = TenantContext.obter(tenant, () -> disponibilidade.executar(json
                .createObjectNode().put("data", DIA.toString()).put("servico_id", corte.toString())));

        assertThat(saida.path("total").asInt())
                .isGreaterThan(FerramentaConsultarDisponibilidade.MAXIMO_DE_HORARIOS);
        assertThat(saida.path("horarios").size())
                .isEqualTo(FerramentaConsultarDisponibilidade.MAXIMO_DE_HORARIOS);
        assertThat(saida.path("aviso").asString()).contains("profissional_id");
    }

    @Test
    @DisplayName("argumento fora do formato volta com o nome do campo, sem o valor")
    void disponibilidade_argumento_invalido() {
        UUID tenant = salao();

        var erro = recusa(() -> TenantContext.executar(tenant, () -> disponibilidade.executar(
                json.createObjectNode().put("data", "17/09").put("servico_id", "corte"))));

        assertThat(erro.codigo()).isEqualTo(ErrosDaInfra.DADOS_INVALIDOS);
        assertThat(erro.getMessage()).contains("data").doesNotContain("17/09");
    }

    // ------------------------------------------------------------------ agendar: simular

    @Test
    @DisplayName("simular não grava nada, e o resumo sai do cadastro e do horário do salão")
    void simular_nao_grava() throws SQLException {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = marilda(tenant);
        UUID corte = corte(tenant);

        Simulacao simulacao = TenantContext.obter(tenant, () -> agendar.simular(
                marcacao(marilda, corte, ana, DIA + "T13:00")));

        assertThat(simulacao.resumo())
                .isEqualTo("Corte para Marilda com Ana, " + quando("13:00", "13:30"));
        assertThat(simulacao.comando().path("inicio").asString()).isEqualTo(as(13, 0).toString());
        assertThat(simulacao.comando().path("cliente_id").asString())
                .isEqualTo(marilda.toString());
        assertThat(agendamentosDo(tenant)).isZero();
    }

    @Test
    @DisplayName("simular recusa horário já tomado por quem vai atender, em texto de gente")
    void simular_conflito() {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = marilda(tenant);
        UUID corte = corte(tenant);
        marcarNoBalcao(tenant, marilda, ana, corte, as(13, 0));

        var erro = recusa(() -> TenantContext.executar(tenant, () -> agendar.simular(
                marcacao(marilda, corte, ana, DIA + "T13:15"))));

        assertThat(erro.codigo()).isEqualTo(ErrosDaAgenda.CONFLITO_HORARIO);
        assertThat(erro.getMessage())
                .isEqualTo("quem vai atender já tem atendimento nesse horário.");
    }

    @Test
    @DisplayName("simular oferece o encaixe na pausa de outro atendimento, como a gravação aceita")
    void simular_encaixe_na_pausa() {
        // A pergunta a mais da simulação olha só o que ocupa o profissional: a química das mechas
        // não ocupa, e recusar o encaixe ali seria a simulação mais rígida que a gravação.
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = marilda(tenant);
        UUID mechas = mechas(tenant);
        UUID corte = corte(tenant);
        marcarNoBalcao(tenant, marilda, ana, mechas, as(13, 0));

        Simulacao simulacao = TenantContext.obter(tenant, () -> agendar.simular(
                marcacao(marilda, corte, ana, DIA + "T13:40")));

        assertThat(simulacao.resumo()).endsWith("das 13:40 às 14:10");
    }

    @Test
    @DisplayName("simular recusa passado e fora da jornada — o agente não força nada")
    void simular_nao_forca() {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = marilda(tenant);
        UUID corte = corte(tenant);

        var passado = recusa(() -> TenantContext.executar(tenant, () -> agendar.simular(
                marcacao(marilda, corte, ana, LocalDate.now(SAO_PAULO).minusDays(1) + "T10:00"))));
        var foraDaJornada = recusa(() -> TenantContext.executar(tenant, () -> agendar.simular(
                marcacao(marilda, corte, ana, DIA + "T19:00"))));

        assertThat(passado.codigo()).isEqualTo(ErrosDaAgenda.DATA_PASSADA);
        assertThat(passado.getMessage()).isEqualTo("o horário já passou.");
        assertThat(foraDaJornada.codigo()).isEqualTo(ErrosDaAgenda.FORA_DA_JORNADA);
    }

    @Test
    @DisplayName("inicio com fuso é recusado: o modelo não converte para UTC")
    void simular_com_fuso() {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = marilda(tenant);
        UUID corte = corte(tenant);

        var erro = recusa(() -> TenantContext.executar(tenant, () -> agendar.simular(
                marcacao(marilda, corte, ana, DIA + "T16:00Z"))));

        assertThat(erro.codigo()).isEqualTo(ErrosDaInfra.DADOS_INVALIDOS);
        assertThat(erro.getMessage()).startsWith("inicio");
    }

    @Test
    @DisplayName("sem permissão de escrita na agenda, simular é negado pelo caso de uso")
    void simular_sem_permissao() {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = marilda(tenant);
        UUID corte = corte(tenant);

        var anterior = SecurityContextHolder.getContext();
        var contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("painel", null,
                List.of(new SimpleGrantedAuthority(Permissao.AGENDA_LER_TODAS))));
        SecurityContextHolder.setContext(contexto);
        try {
            Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                    () -> agendar.simular(marcacao(marilda, corte, ana, DIA + "T13:00"))));
            assertThat(erro).isInstanceOf(AccessDeniedException.class);
        } finally {
            SecurityContextHolder.setContext(anterior);
        }
    }

    // ------------------------------------------------------------------ agendar: confirmar

    @Test
    @DisplayName("confirmar grava com origem AGENTE, nasce confirmado, e responde com o resumo")
    void confirmar_grava() throws SQLException {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = marilda(tenant);
        UUID corte = corte(tenant);
        Simulacao simulacao = TenantContext.obter(tenant, () -> agendar.simular(
                marcacao(marilda, corte, ana, DIA + "T13:00")));

        Efetivacao efetivacao = TenantContext.obter(tenant,
                () -> agendar.confirmar(UUID.randomUUID(), simulacao.comando(), Optional.empty()));

        assertThat(efetivacao.mensagem())
                .isEqualTo("Marcado: Corte para Marilda com Ana, " + quando("13:00", "13:30") + ".");
        assertThat(efetivacao.registro().path("status").asString()).isEqualTo("CONFIRMADO");
        try (var ps = comoOwner().prepareStatement(
                "select origem, status from agendamento where id = ?")) {
            ps.setObject(1, UUID.fromString(efetivacao.registro().path("agendamento_id").asString()));
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("origem")).isEqualTo("AGENTE");
                assertThat(rs.getString("status")).isEqualTo("CONFIRMADO");
            }
        }
    }

    @Test
    @DisplayName("confirmar confere tudo de novo: o horário tomado depois do resumo é recusado")
    void confirmar_confere_de_novo() throws SQLException {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = marilda(tenant);
        UUID corte = corte(tenant);
        Simulacao simulacao = TenantContext.obter(tenant, () -> agendar.simular(
                marcacao(marilda, corte, ana, DIA + "T13:00")));
        marcarNoBalcao(tenant, marilda, ana, corte, as(13, 0));

        var erro = recusa(() -> TenantContext.executar(tenant,
                () -> agendar.confirmar(UUID.randomUUID(), simulacao.comando(), Optional.empty())));

        assertThat(erro.codigo()).isEqualTo(ErrosDaAgenda.CONFLITO_HORARIO);
        assertThat(erro.getMessage())
                .isEqualTo("quem vai atender já tem atendimento nesse horário.");
        assertThat(agendamentosDo(tenant)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ RT-CNV-006: fora da jornada

    @Test
    @DisplayName("fora da jornada, a simulação pede o aceite de quem vai atender, com o telefone normalizado")
    void simular_fora_da_jornada_pede_aceite() throws SQLException {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0, "(11) 98765-4321");
        UUID marilda = marilda(tenant);
        UUID corte = corte(tenant);

        Simulacao simulacao = TenantContext.obter(tenant, () -> agendar.simular(
                marcacao(marilda, corte, ana, DIA + "T19:00")));

        assertThat(simulacao.aceite())
                .isEqualTo(new Aceite("Ana", "5511987654321", "fora da jornada"));
        assertThat(simulacao.comando().path("fora_da_jornada").asBoolean()).isTrue();
        assertThat(simulacao.resumo())
                .isEqualTo("Corte para Marilda com Ana, " + quando("19:00", "19:30"));
        assertThat(agendamentosDo(tenant)).isZero();
    }

    @Test
    @DisplayName("fora da jornada dentro de uma transação somente leitura: a simulação não a envenena")
    void simular_fora_da_jornada_dentro_de_transacao() {
        // É como o agente simula (TransacaoSomenteLeitura). A primeira versão lançava a recusa da
        // jornada e simulava de novo: a exceção marcava a transação para rollback, e o commit do fim
        // estourava com UnexpectedRollbackException. Sem a transação por fora, o teste acima passava.
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0, "(11) 98765-4321");
        UUID marilda = marilda(tenant);
        UUID corte = corte(tenant);
        var somenteLeitura = new TransactionTemplate(transacoes);
        somenteLeitura.setReadOnly(true);

        Simulacao simulacao = TenantContext.obter(tenant, () -> somenteLeitura.execute(
                transacao -> agendar.simular(marcacao(marilda, corte, ana, DIA + "T19:00"))));

        assertThat(simulacao.aceite()).isNotNull();
    }

    @Test
    @DisplayName("dentro da jornada ninguém precisa aceitar")
    void simular_dentro_da_jornada_sem_aceite() {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0, "(11) 98765-4321");
        UUID marilda = marilda(tenant);
        UUID corte = corte(tenant);

        Simulacao simulacao = TenantContext.obter(tenant, () -> agendar.simular(
                marcacao(marilda, corte, ana, DIA + "T13:00")));

        assertThat(simulacao.aceite()).isNull();
        assertThat(simulacao.comando().path("fora_da_jornada").asBoolean(true)).isFalse();
    }

    @Test
    @DisplayName("fora da jornada sem telefone no cadastro: recusa dizendo por quê")
    void simular_fora_da_jornada_sem_telefone() {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = marilda(tenant);
        UUID corte = corte(tenant);

        var erro = recusa(() -> TenantContext.executar(tenant, () -> agendar.simular(
                marcacao(marilda, corte, ana, DIA + "T19:00"))));

        assertThat(erro.codigo()).isEqualTo(ErrosDaAgenda.FORA_DA_JORNADA);
        assertThat(erro.getMessage()).contains("não há telefone válido");
    }

    @Test
    @DisplayName("fora da jornada não perdoa o resto: horário tomado continua recusado")
    void simular_fora_da_jornada_confere_o_resto() {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0, "(11) 98765-4321");
        UUID marilda = marilda(tenant);
        UUID corte = corte(tenant);
        marcarNoBalcaoForcando(tenant, marilda, ana, corte, as(19, 0));

        var erro = recusa(() -> TenantContext.executar(tenant, () -> agendar.simular(
                marcacao(marilda, corte, ana, DIA + "T19:15"))));

        assertThat(erro.codigo()).isEqualTo(ErrosDaAgenda.CONFLITO_HORARIO);
    }

    @Test
    @DisplayName("confirmar fora da jornada sem a prova do aceite é recusado")
    void confirmar_fora_da_jornada_sem_aceite() throws SQLException {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0, "(11) 98765-4321");
        UUID marilda = marilda(tenant);
        UUID corte = corte(tenant);
        Simulacao simulacao = TenantContext.obter(tenant, () -> agendar.simular(
                marcacao(marilda, corte, ana, DIA + "T19:00")));

        var erro = recusa(() -> TenantContext.executar(tenant,
                () -> agendar.confirmar(UUID.randomUUID(), simulacao.comando(), Optional.empty())));

        assertThat(erro.codigo()).isEqualTo(ErrosDaAgenda.FORA_DA_JORNADA);
        assertThat(erro.getMessage()).contains("o aceite de quem vai atender não chegou");
        assertThat(agendamentosDo(tenant)).isZero();
    }

    @Test
    @DisplayName("confirmar com o aceite força a jornada e deixa o aceite na trilha")
    void confirmar_com_aceite() throws SQLException {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0, "(11) 98765-4321");
        UUID marilda = marilda(tenant);
        UUID corte = corte(tenant);
        Simulacao simulacao = TenantContext.obter(tenant, () -> agendar.simular(
                marcacao(marilda, corte, ana, DIA + "T19:00")));

        Efetivacao efetivacao = TenantContext.obter(tenant, () -> agendar.confirmar(
                UUID.randomUUID(), simulacao.comando(), Optional.of(new AceiteRegistrado("Ana",
                        "5511987654321", Instant.now()))));

        UUID agendamento = UUID.fromString(
                efetivacao.registro().path("agendamento_id").asString());
        assertThat(trilhaDe(agendamento)).contains("forcouForaJornada")
                .contains("aceito por Ana pelo WhatsApp (final 4321)");
    }
}
