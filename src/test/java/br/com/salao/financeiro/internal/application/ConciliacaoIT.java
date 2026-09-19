package br.com.salao.financeiro.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.financeiro.internal.application.ImportarExtratoUseCase.ResultadoDaImportacao;
import br.com.salao.financeiro.internal.domain.MapeamentoDoExtrato;
import br.com.salao.financeiro.internal.application.ConsultarConciliacaoUseCase.Recorte;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * RT-FIN-010 — casar as vendas da Stone com os pagamentos do salão, com Postgres de verdade.
 *
 * <p>As vendas entram pela importação, como na tela; os pagamentos, direto na tabela — o caminho da
 * comanda tem teste próprio, e aqui o que importa é a hora e a forma de cada um.
 */
@WithMockUser(authorities = Permissao.FINANCEIRO_CONCILIAR)
class ConciliacaoIT extends AbstractPostgresIT {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate DIA = LocalDate.of(2026, 9, 18);
    private static final String CABECALHO = "STONE ID;DATA DA VENDA;VALOR BRUTO;PRODUTO;NSU;STATUS";

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private PreverImportacaoUseCase prever;
    @Autowired private ImportarExtratoUseCase importar;
    @Autowired private ConciliarUseCase conciliar;
    @Autowired private ConsultarConciliacaoUseCase consultar;

    private UUID tenant;

    @BeforeEach
    void salao() {
        tenant = novoSalao();
    }

    private UUID novoSalao() {
        return provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    private static Instant as(String hora) {
        return LocalDateTime.of(DIA, java.time.LocalTime.parse(hora)).atZone(SP).toInstant();
    }

    private UUID pagamento(UUID salao, String forma, String valor, Instant quando, String nsu)
            throws SQLException {
        try (var ps = comoOwner().prepareStatement("""
                insert into pagamento (estabelecimento_id, comanda_id, forma, valor, parcelas, nsu,
                                       status_conciliacao, criado_em)
                values (?, gen_random_uuid(), ?, ?, 1, ?, 'PENDENTE', ?) returning id""")) {
            ps.setObject(1, salao);
            ps.setString(2, forma);
            ps.setBigDecimal(3, new BigDecimal(valor));
            ps.setString(4, nsu);
            ps.setObject(5, java.time.OffsetDateTime.ofInstant(quando, SP));
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private UUID pagamento(String forma, String valor, String hora) throws SQLException {
        return pagamento(tenant, forma, valor, as(hora), null);
    }

    private static String venda(String id, String hora, String valor, String produto) {
        return venda(id, "18/09/2026 " + hora, valor, produto, "", "Aprovada");
    }

    private static String venda(String id, String data, String valor, String produto, String nsu,
                                String status) {
        return String.join(";", id, data, valor, produto, nsu, status);
    }

    private ResultadoDaImportacao importar(UUID salao, String... linhas) {
        byte[] arquivo = (CABECALHO + "\n" + String.join("\n", linhas) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        var previa = TenantContext.obter(salao, () -> prever.executar(arquivo, null));
        return TenantContext.obter(salao, () -> importar.executar(arquivo, "vendas.csv",
                new MapeamentoDoExtrato(previa.mapeamento(), previa.formato())));
    }

    private ResultadoDaImportacao importar(String... linhas) {
        return importar(tenant, linhas);
    }

    private String estado(UUID pagamento) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "select status_conciliacao from pagamento where id = ?")) {
            ps.setObject(1, pagamento);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private ConsultarConciliacaoUseCase.Resumo resumo() {
        return TenantContext.obter(tenant, () -> consultar.resumir(DIA, DIA));
    }

    private List<ConsultarConciliacaoUseCase.Item> itens(Recorte lado) {
        return TenantContext.obter(tenant, () -> consultar.listar(lado, DIA, DIA, null, null)).itens();
    }

    // ------------------------------------------------------------------ casar

    @Test
    @DisplayName("importar já casa: o pagamento das 14h com a venda das 14h05, e o pagamento fica conciliado")
    void casa_na_importacao() throws SQLException {
        UUID p = pagamento("CREDITO", "100.00", "14:00");

        var resultado = importar(venda("ST1", "14:05", "100,00", "Crédito"));

        assertThat(resultado.casadas()).isEqualTo(1);
        assertThat(resultado.divergentes()).isZero();
        assertThat(estado(p)).isEqualTo("CONCILIADO");
        assertThat(resumo().conciliadas().quantidade()).isEqualTo(1);
        assertThat(resumo().conciliadas().valor()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("a janela é de duas horas: dentro casa; fora, cada um fica no seu lado")
    void janela_de_duas_horas() throws SQLException {
        UUID dentro = pagamento("CREDITO", "70.00", "14:00");
        UUID fora = pagamento("CREDITO", "50.00", "14:00");

        importar(venda("ST1", "15:59", "70,00", "Crédito"), venda("ST2", "16:01", "50,00", "Crédito"));

        assertThat(estado(dentro)).isEqualTo("CONCILIADO");
        assertThat(estado(fora)).isEqualTo("PENDENTE");
        var resumo = resumo();
        assertThat(resumo.soNoSistema().quantidade()).isEqualTo(1);
        assertThat(resumo.soNaMaquininha().quantidade()).isEqualTo(1);
        assertThat(itens(Recorte.SO_NA_MAQUININHA)).singleElement()
                .satisfies(i -> assertThat(i.idNaAdquirente()).isEqualTo("ST2"));
    }

    @Test
    @DisplayName("empate de valor casa pela ordem de horário")
    void empate() throws SQLException {
        UUID cedo = pagamento("CREDITO", "50.00", "14:00");
        UUID tarde = pagamento("CREDITO", "50.00", "14:30");

        importar(venda("ST-TARDE", "14:31", "50,00", "Crédito"),
                venda("ST-CEDO", "14:02", "50,00", "Crédito"));

        assertThat(itens(Recorte.CONCILIADAS)).hasSize(2).allSatisfy(item ->
                assertThat(item.idNaAdquirente()).isEqualTo(
                        item.pagamentoId().equals(cedo) ? "ST-CEDO" : "ST-TARDE"));
        assertThat(List.of(estado(cedo), estado(tarde))).containsOnly("CONCILIADO");
    }

    @Test
    @DisplayName("débito no sistema, crédito na Stone: casa, e fica divergente pela forma")
    void forma_trocada() throws SQLException {
        UUID p = pagamento("DEBITO", "80.00", "10:00");

        var resultado = importar(venda("ST1", "10:02", "80,00", "Crédito"));

        assertThat(resultado.divergentes()).isEqualTo(1);
        assertThat(estado(p)).isEqualTo("DIVERGENTE");
        assertThat(itens(Recorte.DIVERGENTES)).singleElement().satisfies(item -> {
            assertThat(item.divergencias()).containsExactly("FORMA");
            assertThat(item.forma()).isEqualTo("DEBITO");
            assertThat(item.produto()).isEqualTo("CREDITO");
        });
    }

    @Test
    @DisplayName("NSU informado casa mesmo fora da janela, e diz que o valor difere")
    void nsu() throws SQLException {
        UUID p = pagamento(tenant, "CREDITO", "100.00", as("09:00"), "000123");

        importar(venda("ST1", "18/09/2026 18:00", "90,00", "Crédito", "123", "Aprovada"));

        assertThat(itens(Recorte.DIVERGENTES)).singleElement().satisfies(item -> {
            assertThat(item.pagamentoId()).isEqualTo(p);
            assertThat(item.criterio()).isEqualTo("NSU");
            assertThat(item.divergencias()).containsExactly("VALOR");
        });
    }

    // ------------------------------------------------------------------ cancelamento

    @Test
    @DisplayName("a venda cancelada na Stone depois de casada: o par fica divergente, e o pagamento também")
    void cancelada_depois() throws SQLException {
        UUID p = pagamento("CREDITO", "100.00", "14:00");
        importar(venda("ST1", "14:05", "100,00", "Crédito"));

        importar(venda("ST1", "18/09/2026 14:05", "100,00", "Crédito", "", "Cancelada"));

        assertThat(estado(p)).isEqualTo("DIVERGENTE");
        assertThat(itens(Recorte.DIVERGENTES)).singleElement().satisfies(item ->
                assertThat(item.divergencias()).containsExactly("CANCELADA_NA_ADQUIRENTE"));
        assertThat(resumo().conciliadas().quantidade()).isZero();
    }

    @Test
    @DisplayName("venda já cancelada não casa: não é dinheiro")
    void cancelada_nao_casa() throws SQLException {
        UUID p = pagamento("CREDITO", "100.00", "14:00");

        var resultado = importar(venda("ST1", "18/09/2026 14:05", "100,00", "Crédito", "", "Cancelada"));

        assertThat(resultado.casadas()).isZero();
        assertThat(estado(p)).isEqualTo("PENDENTE");
        assertThat(resumo().soNoSistema().quantidade()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ rodadas

    @Test
    @DisplayName("pagamento registrado depois da importação casa no \"Conciliar agora\"")
    void conciliar_agora() throws SQLException {
        assertThat(importar(venda("ST1", "14:05", "100,00", "Crédito")).casadas()).isZero();
        UUID p = pagamento("CREDITO", "100.00", "14:00");

        var rodada = TenantContext.obter(tenant, () -> conciliar.executar());

        assertThat(rodada.casadas()).isEqualTo(1);
        assertThat(estado(p)).isEqualTo("CONCILIADO");
    }

    @Test
    @DisplayName("rodar de novo não casa nada duas vezes")
    void rodar_de_novo() throws SQLException {
        pagamento("CREDITO", "100.00", "14:00");
        importar(venda("ST1", "14:05", "100,00", "Crédito"));

        var segunda = TenantContext.obter(tenant, () -> conciliar.executar());

        assertThat(segunda.casadas()).isZero();
        try (var st = comoOwner().createStatement();
             var rs = st.executeQuery("select count(*) from conciliacao_de_venda where estabelecimento_id = '"
                     + tenant + "'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("o estorno apaga o pagamento, e a venda volta a \"só na maquininha\"")
    void pagamento_apagado() throws SQLException {
        UUID p = pagamento("CREDITO", "100.00", "14:00");
        importar(venda("ST1", "14:05", "100,00", "Crédito"));

        var conexao = comoApp(tenant);
        try (var ps = conexao.prepareStatement("delete from pagamento where id = ?")) {
            ps.setObject(1, p);
            ps.executeUpdate();
            conexao.commit();
        }

        assertThat(resumo().conciliadas().quantidade()).isZero();
        assertThat(resumo().soNaMaquininha().quantidade()).isEqualTo(1);
    }

    @Test
    @DisplayName("pagamento num mês sem extrato não é \"só no sistema\": é falta de extrato")
    void sem_extrato() throws SQLException {
        importar(venda("ST1", "14:05", "100,00", "Crédito"));
        pagamento(tenant, "CREDITO", "40.00",
                LocalDateTime.of(2026, 8, 10, 10, 0).atZone(SP).toInstant(), null);

        var agosto = TenantContext.obter(tenant,
                () -> consultar.resumir(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)));

        assertThat(agosto.soNoSistema().quantidade()).isZero();
        assertThat(agosto.semExtrato().quantidade()).isEqualTo(1);
        assertThat(agosto.semExtrato().valor()).isEqualByComparingTo("40");
    }

    // ------------------------------------------------------------------ fronteiras

    @Test
    @DisplayName("o pagamento de um salão não casa com a venda de outro")
    void isolamento() throws SQLException {
        UUID outro = novoSalao();
        pagamento(outro, "CREDITO", "100.00", as("14:00"), null);

        assertThat(importar(venda("ST1", "14:05", "100,00", "Crédito")).casadas()).isZero();
    }

    @Test
    @DisplayName("sem financeiro:reconcile, nem a conferência")
    @WithMockUser(authorities = Permissao.FINANCEIRO_LER_TODOS)
    void sem_permissao() {
        assertThat(catchThrowable(this::resumo)).isInstanceOf(AccessDeniedException.class);
        assertThat(catchThrowable(() -> TenantContext.obter(tenant, () -> conciliar.executar())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a aplicação não desfaz nem reescreve o par")
    void imutavel() throws SQLException {
        pagamento("CREDITO", "100.00", "14:00");
        importar(venda("ST1", "14:05", "100,00", "Crédito"));

        for (String sql : List.of("delete from conciliacao_de_venda",
                "update conciliacao_de_venda set criterio = 'NSU'")) {
            var conexao = comoApp(tenant);
            try (var st = conexao.createStatement()) {
                Throwable erro = catchThrowable(() -> st.executeUpdate(sql));
                assertThat(erro).as(sql).isNotNull();
                assertThat(erro.getMessage()).containsIgnoringCase("permission denied");
            } finally {
                conexao.rollback();
            }
        }
    }

    @Test
    @DisplayName("a lista anda pelo cursor, das mais recentes para trás")
    void cursor() {
        importar(venda("ST1", "10:00", "10,00", "Débito"), venda("ST2", "11:00", "20,00", "Débito"),
                venda("ST3", "12:00", "30,00", "Débito"));

        var primeira = TenantContext.obter(tenant,
                () -> consultar.listar(Recorte.SO_NA_MAQUININHA, DIA, DIA, null, 2));
        var segunda = TenantContext.obter(tenant,
                () -> consultar.listar(Recorte.SO_NA_MAQUININHA, DIA, DIA, primeira.proximoCursor(), 2));

        assertThat(primeira.itens()).extracting(i -> i.idNaAdquirente()).containsExactly("ST3", "ST2");
        assertThat(segunda.itens()).extracting(i -> i.idNaAdquirente()).containsExactly("ST1");
        assertThat(segunda.temMais()).isFalse();
    }

    @Test
    @DisplayName("período invertido ou longo demais é recusado")
    void periodo_invalido() {
        Throwable invertido = catchThrowable(() -> TenantContext.obter(tenant,
                () -> consultar.resumir(DIA, DIA.minusDays(1))));
        Throwable longo = catchThrowable(() -> TenantContext.obter(tenant,
                () -> consultar.resumir(DIA.minusDays(92), DIA)));

        assertThat(invertido).isInstanceOf(ErroDeDominio.class);
        assertThat(((ErroDeDominio) longo).codigo().codigo()).isEqualTo("ER-FIN-FILTRO_INVALIDO");
    }
}
