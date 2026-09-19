package br.com.salao.financeiro.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.financeiro.internal.application.ImportarExtratoUseCase.ResultadoDaImportacao;
import br.com.salao.financeiro.internal.application.PreverImportacaoUseCase.PreviaDaImportacao;
import br.com.salao.financeiro.internal.domain.CampoDoExtrato;
import br.com.salao.financeiro.internal.domain.FormatoDeNumero;
import br.com.salao.financeiro.internal.domain.MapeamentoDoExtrato;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * RT-FIN-009 — importar o relatório de vendas da Stone, com Postgres de verdade.
 *
 * <p>Cada teste faz o que a tela faz: prévia, e a importação com o mapeamento que a prévia sugeriu.
 * O arquivo é fictício, no formato de uma lista de vendas — nomes de coluna em caixa alta, ponto e
 * vírgula, vírgula decimal.
 */
@WithMockUser(authorities = Permissao.FINANCEIRO_CONCILIAR)
class ImportarExtratoIT extends AbstractPostgresIT {

    private static final String CABECALHO = "STONE ID;DATA DA VENDA;VALOR BRUTO;VALOR LÍQUIDO;"
            + "PRODUTO;BANDEIRA;Nº DE PARCELAS;NSU;CÓDIGO DE AUTORIZAÇÃO;STATUS";

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private PreverImportacaoUseCase prever;
    @Autowired private ImportarExtratoUseCase importar;
    @Autowired private ListarImportacoesUseCase listar;

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

    private static byte[] csv(String... linhas) {
        return (CABECALHO + "\n" + String.join("\n", linhas) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String venda(String id, String data, String valor, String status) {
        return id + ";" + data + ";" + valor + ";;Crédito;Mastercard;1;123456;AB12CD;" + status;
    }

    private PreviaDaImportacao previa(UUID salao, byte[] arquivo) {
        return TenantContext.obter(salao, () -> prever.executar(arquivo, null));
    }

    /** O que a tela faz: prévia, e importar com o que ela sugeriu. */
    private ResultadoDaImportacao importar(UUID salao, byte[] arquivo) {
        var sugerida = previa(salao, arquivo);
        return TenantContext.obter(salao, () -> importar.executar(arquivo, "vendas.csv",
                new MapeamentoDoExtrato(sugerida.mapeamento(), sugerida.formato())));
    }

    private List<Map<String, Object>> transacoes(UUID salao) throws SQLException {
        var linhas = new ArrayList<Map<String, Object>>();
        try (var ps = comoOwner().prepareStatement("""
                select id_na_adquirente, valor_bruto, situacao, cancelada_em, capturada_em,
                       hora_informada, parcelas, bandeira, nsu
                  from transacao_do_adquirente where estabelecimento_id = ?
                 order by id_na_adquirente""")) {
            ps.setObject(1, salao);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    var linha = new HashMap<String, Object>();
                    linha.put("id", rs.getString("id_na_adquirente"));
                    linha.put("valor", rs.getBigDecimal("valor_bruto"));
                    linha.put("situacao", rs.getString("situacao"));
                    linha.put("cancelada_em", rs.getObject("cancelada_em", OffsetDateTime.class));
                    linha.put("capturada_em", rs.getObject("capturada_em", OffsetDateTime.class));
                    linha.put("hora_informada", rs.getBoolean("hora_informada"));
                    linha.put("parcelas", rs.getInt("parcelas"));
                    linha.put("bandeira", rs.getString("bandeira"));
                    linha.put("nsu", rs.getString("nsu"));
                    linhas.add(linha);
                }
            }
        }
        return linhas;
    }

    // ------------------------------------------------------------------ a prévia

    @Test
    @DisplayName("a prévia sugere as colunas e mostra as vendas já lidas, sem gravar nada")
    void previa() throws SQLException {
        byte[] arquivo = csv(venda("ST1", "18/09/2026 14:32", "1.234,56", "Aprovada"),
                venda("ST2", "18/09/2026 15:00", "80,00", "Aprovada"));

        var previa = previa(tenant, arquivo);

        assertThat(previa.faltando()).isEmpty();
        assertThat(previa.formato()).isEqualTo(FormatoDeNumero.BRASILEIRO);
        assertThat(previa.mapeamento()).containsEntry(CampoDoExtrato.ID_NA_ADQUIRENTE, "STONE ID")
                .containsEntry(CampoDoExtrato.VALOR_BRUTO, "VALOR BRUTO")
                .containsEntry(CampoDoExtrato.PARCELAS, "Nº DE PARCELAS");
        assertThat(previa.linhas()).isEqualTo(2);
        assertThat(previa.lidas()).isEqualTo(2);
        assertThat(previa.amostra().getFirst()).satisfies(linha -> {
            assertThat(linha.estado()).isEqualTo("LIDA");
            assertThat(linha.valorBruto()).isEqualByComparingTo("1234.56");
            assertThat(linha.bandeira()).isEqualTo("MASTERCARD");
        });
        assertThat(previa.jaImportadoEm()).isNull();
        assertThat(transacoes(tenant)).as("a prévia não grava").isEmpty();
    }

    // ------------------------------------------------------------------ importar e reimportar

    @Test
    @DisplayName("importa; o mesmo arquivo de novo não cria nada, e a prévia avisa que ele já entrou")
    void reimportar_nao_duplica() throws SQLException {
        byte[] arquivo = csv(venda("ST1", "18/09/2026 14:32", "1.234,56", "Aprovada"),
                venda("ST2", "18/09/2026 15:00", "80,00", "Aprovada"));

        var primeira = importar(tenant, arquivo);
        var segunda = importar(tenant, arquivo);

        assertThat(primeira.novas()).isEqualTo(2);
        assertThat(segunda.novas()).isZero();
        assertThat(segunda.repetidas()).isEqualTo(2);
        assertThat(transacoes(tenant)).hasSize(2);
        assertThat(previa(tenant, arquivo).jaImportadoEm()).isNotNull();
    }

    @Test
    @DisplayName("a venda que volta cancelada muda de situação, e fica registrado quando")
    void cancelada_depois() throws SQLException {
        importar(tenant, csv(venda("ST1", "18/09/2026 14:32", "100,00", "Aprovada")));

        var resultado = importar(tenant, csv(venda("ST1", "18/09/2026 14:32", "100,00", "Cancelada")));

        assertThat(resultado.canceladas()).isEqualTo(1);
        assertThat(transacoes(tenant)).singleElement().satisfies(t -> {
            assertThat(t).containsEntry("situacao", "CANCELADA");
            assertThat(t.get("cancelada_em")).isNotNull();
        });
    }

    @Test
    @DisplayName("o mesmo Stone ID com outro valor é recusado, e o valor gravado não muda")
    void valor_diferente_recusado() throws SQLException {
        importar(tenant, csv(venda("ST1", "18/09/2026 14:32", "100,00", "Aprovada")));

        var resultado = importar(tenant, csv(venda("ST1", "18/09/2026 14:32", "90,00", "Aprovada")));

        assertThat(resultado.recusadas()).isEqualTo(1);
        assertThat(resultado.recusas()).singleElement().satisfies(r -> {
            assertThat(r.linha()).isEqualTo(2);
            assertThat(r.motivo()).contains("ST1").contains("R$ 100,00").contains("R$ 90,00");
        });
        assertThat(transacoes(tenant)).singleElement()
                .satisfies(t -> assertThat((java.math.BigDecimal) t.get("valor")).isEqualByComparingTo("100"));
    }

    @Test
    @DisplayName("cancelada não volta a ser aprovada sem alguém olhar")
    void cancelada_nao_volta() throws SQLException {
        importar(tenant, csv(venda("ST1", "18/09/2026 14:32", "100,00", "Cancelada")));

        var resultado = importar(tenant, csv(venda("ST1", "18/09/2026 14:32", "100,00", "Aprovada")));

        assertThat(resultado.recusadas()).isEqualTo(1);
        assertThat(transacoes(tenant)).singleElement()
                .satisfies(t -> assertThat(t).containsEntry("situacao", "CANCELADA"));
    }

    @Test
    @DisplayName("linha ruim não derruba o arquivo: as boas entram, e cada outra diz por quê")
    void linhas_ruins_nao_derrubam() throws SQLException {
        var resultado = importar(tenant, csv(
                venda("ST1", "18/09/2026 14:32", "100,00", "Aprovada"),
                venda("ST2", "31/02/2026 10:00", "50,00", "Aprovada"),
                "ST3;18/09/2026 16:00;30,00;;Pix;;1;;;Aprovada",
                venda("ST4", "18/09/2026 17:00", "70,00", "Negada"),
                venda("ST5", "18/09/2026 18:00", "40,00", "Aprovada")));

        assertThat(resultado.linhas()).isEqualTo(5);
        assertThat(resultado.novas()).isEqualTo(2);
        assertThat(resultado.ignoradas()).isEqualTo(2);
        assertThat(resultado.recusas()).singleElement().satisfies(r -> {
            assertThat(r.linha()).isEqualTo(3);
            assertThat(r.motivo()).contains("31/02/2026");
        });
        assertThat(transacoes(tenant)).extracting(t -> t.get("id")).containsExactly("ST1", "ST5");
    }

    @Test
    @DisplayName("a hora do arquivo é a do salão: 23h30 em São Paulo é 2h30 UTC do dia seguinte")
    void hora_do_salao() throws SQLException {
        importar(tenant, csv(venda("ST1", "18/09/2026 23:30", "100,00", "Aprovada"),
                venda("ST2", "18/09/2026", "50,00", "Aprovada")));

        var transacoes = transacoes(tenant);
        assertThat(((OffsetDateTime) transacoes.get(0).get("capturada_em")).toInstant())
                .isEqualTo(Instant.parse("2026-09-19T02:30:00Z"));
        assertThat(transacoes.get(1)).containsEntry("hora_informada", false);
    }

    // ------------------------------------------------------------------ o mapeamento

    @Test
    @DisplayName("o mapeamento confirmado fica salvo, e a próxima prévia já vem com ele")
    void mapeamento_salvo() {
        byte[] arquivo = ("ID;QUANDO;TOTAL\nST1;18/09/2026 10:00;100,00\n")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(previa(tenant, arquivo).faltando()).as("nenhum apelido casa")
                .containsExactly(CampoDoExtrato.ID_NA_ADQUIRENTE, CampoDoExtrato.DATA,
                        CampoDoExtrato.VALOR_BRUTO);

        var escolhido = new MapeamentoDoExtrato(Map.of(CampoDoExtrato.ID_NA_ADQUIRENTE, "ID",
                CampoDoExtrato.DATA, "QUANDO", CampoDoExtrato.VALOR_BRUTO, "TOTAL"),
                FormatoDeNumero.BRASILEIRO);
        var resultado = TenantContext.obter(tenant,
                () -> importar.executar(arquivo, "vendas.csv", escolhido));

        assertThat(resultado.novas()).isEqualTo(1);
        assertThat(previa(tenant, arquivo).mapeamento())
                .containsEntry(CampoDoExtrato.VALOR_BRUTO, "TOTAL")
                .containsEntry(CampoDoExtrato.DATA, "QUANDO");
    }

    @Test
    @DisplayName("mapeamento sem a data é recusado, e nada é gravado")
    void mapeamento_incompleto() throws SQLException {
        byte[] arquivo = csv(venda("ST1", "18/09/2026 10:00", "100,00", "Aprovada"));
        var semData = new MapeamentoDoExtrato(Map.of(CampoDoExtrato.ID_NA_ADQUIRENTE, "STONE ID",
                CampoDoExtrato.VALOR_BRUTO, "VALOR BRUTO"), FormatoDeNumero.BRASILEIRO);

        Throwable erro = catchThrowable(() -> TenantContext.obter(tenant,
                () -> importar.executar(arquivo, "vendas.csv", semData)));

        assertThat(erro).isInstanceOf(ErroDeDominio.class).hasMessageContaining("Data da venda");
        assertThat(((ErroDeDominio) erro).codigo().codigo()).isEqualTo("ER-FIN-MAPEAMENTO_INCOMPLETO");
        assertThat(transacoes(tenant)).isEmpty();
    }

    @Test
    @DisplayName("planilha do Excel é recusada dizendo para exportar em CSV")
    void excel_recusado() {
        Throwable erro = catchThrowable(() -> previa(tenant, new byte[] { 'P', 'K', 3, 4 }));

        // "Excel", e não só "CSV": a mensagem genérica de arquivo sem separador também diz "CSV".
        assertThat(erro).isInstanceOf(ErroDeDominio.class).hasMessageContaining("Excel")
                .hasMessageContaining("CSV");
        assertThat(((ErroDeDominio) erro).codigo().codigo()).isEqualTo("ER-FIN-ARQUIVO_INVALIDO");
    }

    // ------------------------------------------------------------------ fronteiras

    @Test
    @DisplayName("o mesmo Stone ID em dois salões são duas vendas, e um não vê a do outro")
    void isolamento() throws SQLException {
        UUID outro = novoSalao();
        byte[] arquivo = csv(venda("ST1", "18/09/2026 10:00", "100,00", "Aprovada"));

        assertThat(importar(tenant, arquivo).novas()).isEqualTo(1);
        assertThat(importar(outro, arquivo).novas()).isEqualTo(1);
        assertThat(TenantContext.obter(outro, () -> listar.listar(null, null)).itens()).hasSize(1);
        assertThat(transacoes(tenant)).hasSize(1);
    }

    @Test
    @DisplayName("sem financeiro:reconcile, nem a prévia")
    @WithMockUser(authorities = Permissao.FINANCEIRO_LER_TODOS)
    void sem_permissao() {
        byte[] arquivo = csv(venda("ST1", "18/09/2026 10:00", "100,00", "Aprovada"));

        assertThat(catchThrowable(() -> previa(tenant, arquivo)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a aplicação não reescreve valor nem apaga venda ou importação")
    void imutavel() throws SQLException {
        importar(tenant, csv(venda("ST1", "18/09/2026 10:00", "100,00", "Aprovada")));

        for (String sql : List.of("update transacao_do_adquirente set valor_bruto = 1",
                "delete from transacao_do_adquirente", "update importacao_de_extrato set novas = 0",
                "delete from importacao_de_extrato")) {
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
    @DisplayName("o histórico, das mais recentes para trás, com cursor e as contas de cada uma")
    void historico() {
        importar(tenant, csv(venda("ST1", "18/09/2026 10:00", "100,00", "Aprovada")));
        importar(tenant, csv(venda("ST1", "18/09/2026 10:00", "100,00", "Aprovada"),
                venda("ST2", "18/09/2026 11:00", "50,00", "Aprovada")));

        var primeira = TenantContext.obter(tenant, () -> listar.listar(null, 1));
        var segunda = TenantContext.obter(tenant, () -> listar.listar(primeira.proximoCursor(), 1));

        assertThat(primeira.itens()).singleElement().satisfies(i -> {
            assertThat(i.linhas()).isEqualTo(2);
            assertThat(i.novas()).isEqualTo(1);
            assertThat(i.repetidas()).isEqualTo(1);
            assertThat(i.nomeDoArquivo()).isEqualTo("vendas.csv");
        });
        assertThat(segunda.itens()).singleElement().satisfies(i -> assertThat(i.novas()).isEqualTo(1));
        assertThat(segunda.temMais()).isFalse();
    }

    @Test
    @DisplayName("a auditoria guarda as contas da importação, e nenhum dado da venda")
    void auditoria() throws SQLException {
        var resultado = importar(tenant, csv(venda("ST1", "18/09/2026 10:00", "100,00", "Aprovada")));

        try (var ps = comoOwner().prepareStatement("""
                select depois::text from auditoria
                 where estabelecimento_id = ? and entidade_id = ?""")) {
            ps.setObject(1, tenant);
            ps.setObject(2, resultado.id());
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).contains("\"novas\": 1").doesNotContain("ST1")
                        .doesNotContain("123456");
            }
        }
    }
}
