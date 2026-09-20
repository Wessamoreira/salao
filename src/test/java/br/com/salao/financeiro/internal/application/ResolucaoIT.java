package br.com.salao.financeiro.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.financeiro.api.FormaDePagamento;
import br.com.salao.financeiro.internal.application.ConsultarConciliacaoUseCase.Recorte;
import br.com.salao.financeiro.internal.application.ImportarExtratoUseCase.ResultadoDaImportacao;
import br.com.salao.financeiro.internal.application.ResolverDivergenciaUseCase.Comando;
import br.com.salao.financeiro.internal.domain.AcaoDaResolucao;
import br.com.salao.financeiro.internal.domain.AlvoDaResolucao;
import br.com.salao.financeiro.internal.domain.MapeamentoDoExtrato;
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
import java.time.LocalTime;
import java.time.OffsetDateTime;
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
 * RT-FIN-011 — resolver cada linha da conferência à mão, com Postgres de verdade.
 *
 * <p>As vendas entram pela importação, como na tela; os pagamentos, direto na tabela — o caminho da
 * comanda tem teste próprio, e aqui o que importa é o que sobra para alguém decidir.
 */
@WithMockUser(authorities = Permissao.FINANCEIRO_CONCILIAR)
class ResolucaoIT extends AbstractPostgresIT {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate DIA = LocalDate.of(2026, 9, 18);
    private static final String CABECALHO = "STONE ID;DATA DA VENDA;VALOR BRUTO;PRODUTO;NSU;STATUS";

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private PreverImportacaoUseCase prever;
    @Autowired private ImportarExtratoUseCase importar;
    @Autowired private ConsultarConciliacaoUseCase consultar;
    @Autowired private ResolverDivergenciaUseCase resolver;

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

    // ------------------------------------------------------------------ montar a cena

    private static Instant as(String hora) {
        return LocalDateTime.of(DIA, LocalTime.parse(hora)).atZone(SP).toInstant();
    }

    private UUID pagamento(UUID salao, String forma, String valor, String hora, String nsu)
            throws SQLException {
        try (var ps = comoOwner().prepareStatement("""
                insert into pagamento (estabelecimento_id, comanda_id, forma, valor, parcelas, nsu,
                                       bandeira, status_conciliacao, criado_em)
                values (?, gen_random_uuid(), ?, ?, 1, ?, 'VISA', 'PENDENTE', ?) returning id""")) {
            ps.setObject(1, salao);
            ps.setString(2, forma);
            ps.setBigDecimal(3, new BigDecimal(valor));
            ps.setString(4, nsu);
            ps.setObject(5, OffsetDateTime.ofInstant(as(hora), SP));
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private UUID pagamento(String forma, String valor, String hora) throws SQLException {
        return pagamento(tenant, forma, valor, hora, null);
    }

    private static String venda(String id, String hora, String valor, String produto) {
        return venda(id, "18/09/2026 " + hora, valor, produto, "", "Aprovada");
    }

    private static String venda(String id, String data, String valor, String produto, String nsu,
                                String status) {
        return String.join(";", id, data, valor, produto, nsu, status);
    }

    private ResultadoDaImportacao importar(String... linhas) {
        byte[] arquivo = (CABECALHO + "\n" + String.join("\n", linhas) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        var previa = TenantContext.obter(tenant, () -> prever.executar(arquivo, null));
        return TenantContext.obter(tenant, () -> importar.executar(arquivo, "vendas.csv",
                new MapeamentoDoExtrato(previa.mapeamento(), previa.formato())));
    }

    // ------------------------------------------------------------------ ler o estado

    private ConsultarConciliacaoUseCase.Resumo resumo() {
        return TenantContext.obter(tenant, () -> consultar.resumir(DIA, DIA));
    }

    private List<ConsultarConciliacaoUseCase.Item> itens(Recorte lado) {
        return TenantContext.obter(tenant, () -> consultar.listar(lado, DIA, DIA, null, null))
                .itens();
    }

    private List<ConsultarConciliacaoUseCase.ItemResolvido> trilha() {
        return TenantContext.obter(tenant,
                () -> consultar.listarResolvidas(DIA, DIA, null, null)).itens();
    }

    private ResolverDivergenciaUseCase.Resultado resolver(AlvoDaResolucao alvo, UUID id,
                                                          AcaoDaResolucao acao, String motivo) {
        return resolver(alvo, id, acao, motivo, null);
    }

    private ResolverDivergenciaUseCase.Resultado resolver(AlvoDaResolucao alvo, UUID id,
                                                          AcaoDaResolucao acao, String motivo,
                                                          FormaDePagamento forma) {
        return TenantContext.obter(tenant,
                () -> resolver.executar(new Comando(alvo, id, acao, motivo, forma)));
    }

    private String[] pagamentoAgora(UUID id) throws SQLException {
        try (var ps = comoOwner().prepareStatement("""
                select forma, bandeira, parcelas, status_conciliacao from pagamento where id = ?""")) {
            ps.setObject(1, id);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return new String[] {rs.getString(1), rs.getString(2),
                        String.valueOf(rs.getInt(3)), rs.getString(4)};
            }
        }
    }

    /** As linhas que a conciliação escreveu no razão, com sinal. */
    private List<BigDecimal> lancamentos() throws SQLException {
        try (var ps = comoOwner().prepareStatement("""
                select valor from lancamento where estabelecimento_id = ?
                   and origem_tipo = 'CONCILIACAO' order by criado_em, id""")) {
            ps.setObject(1, tenant);
            try (var rs = ps.executeQuery()) {
                var valores = new java.util.ArrayList<BigDecimal>();
                while (rs.next()) {
                    valores.add(rs.getBigDecimal(1));
                }
                return valores;
            }
        }
    }

    /** O id da venda pelo Stone ID — cancelada não aparece em recorte nenhum da conferência. */
    private UUID vendaPorStoneId(String stoneId) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "select id from transacao_do_adquirente where id_na_adquirente = ?")) {
            ps.setString(1, stoneId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private static String codigo(Throwable erro) {
        return ((ErroDeDominio) erro).codigo().codigo();
    }

    // ------------------------------------------------------------------ par divergente

    @Test
    @DisplayName("forma trocada: corrigir troca a forma do pagamento e o par sai de divergentes")
    void corrigir_forma() throws SQLException {
        UUID p = pagamento("DEBITO", "80.00", "10:00");
        importar(venda("ST1", "10:02", "80,00", "Crédito"));
        UUID par = itens(Recorte.DIVERGENTES).getFirst().id();

        var resultado = resolver(AlvoDaResolucao.PAR, par, AcaoDaResolucao.CORRIGIR,
                "recepção registrou débito; foi crédito");

        assertThat(resultado.formaDe()).isEqualTo("DEBITO");
        assertThat(resultado.formaPara()).isEqualTo("CREDITO");
        assertThat(pagamentoAgora(p)).containsExactly("CREDITO", "VISA", "1", "CONCILIADO");
        assertThat(itens(Recorte.DIVERGENTES)).isEmpty();
        assertThat(resumo().divergentes().quantidade()).isZero();
        assertThat(resumo().resolvidas().quantidade()).isEqualTo(1);
        assertThat(resumo().resolvidas().valor()).isEqualByComparingTo("80");
        assertThat(lancamentos()).isEmpty();
    }

    @Test
    @DisplayName("valor diferente: a diferença vira ajuste no razão, e o pagamento não é reescrito")
    void corrigir_valor_lanca_a_diferenca() throws SQLException {
        UUID p = pagamento(tenant, "CREDITO", "80.00", "09:00", "000123");
        importar(venda("ST1", "18/09/2026 18:00", "90,00", "Crédito", "123", "Aprovada"));
        UUID par = itens(Recorte.DIVERGENTES).getFirst().id();

        var resultado = resolver(AlvoDaResolucao.PAR, par, AcaoDaResolucao.CORRIGIR,
                "Stone cobrou o valor certo; a recepção digitou menos");

        assertThat(resultado.lancado()).isEqualByComparingTo("10.00");
        assertThat(lancamentos()).singleElement()
                .satisfies(v -> assertThat(v).isEqualByComparingTo("10.00"));
        // O valor recebido não se reescreve: a comanda já foi apurada em cima dele.
        try (var ps = comoOwner().prepareStatement("select valor from pagamento where id = ?")) {
            ps.setObject(1, p);
            try (var rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("80.00");
            }
        }
        assertThat(itens(Recorte.DIVERGENTES)).isEmpty();
    }

    @Test
    @DisplayName("venda cancelada depois: aceitar como está tira da conferência sem mexer em nada")
    void aceitar_como_esta() throws SQLException {
        UUID p = pagamento("CREDITO", "100.00", "14:00");
        importar(venda("ST1", "14:05", "100,00", "Crédito"));
        importar(venda("ST1", "18/09/2026 14:05", "100,00", "Crédito", "", "Cancelada"));
        UUID par = itens(Recorte.DIVERGENTES).getFirst().id();

        resolver(AlvoDaResolucao.PAR, par, AcaoDaResolucao.ACEITAR,
                "cliente passou de novo; a segunda venda é a boa");

        assertThat(itens(Recorte.DIVERGENTES)).isEmpty();
        assertThat(pagamentoAgora(p)).containsExactly("CREDITO", "VISA", "1", "DIVERGENTE");
        assertThat(lancamentos()).isEmpty();
        assertThat(trilha()).singleElement().satisfies(linha -> {
            assertThat(linha.acao()).isEqualTo("ACEITAR");
            assertThat(linha.divergencias()).containsExactly("CANCELADA_NA_ADQUIRENTE");
        });
    }

    @Test
    @DisplayName("forma trocada e cancelada: corrige a forma, e o par segue divergente pelo que sobrou")
    void corrigir_o_que_da_deixa_o_resto() throws SQLException {
        UUID p = pagamento("DEBITO", "80.00", "10:00");
        importar(venda("ST1", "10:02", "80,00", "Crédito"));
        importar(venda("ST1", "18/09/2026 10:02", "80,00", "Crédito", "", "Cancelada"));
        UUID par = itens(Recorte.DIVERGENTES).getFirst().id();

        resolver(AlvoDaResolucao.PAR, par, AcaoDaResolucao.CORRIGIR, "foi crédito; a Stone cancelou");

        // A forma foi acertada, mas o dinheiro continua não vindo: conciliado seria mentira.
        assertThat(pagamentoAgora(p)).containsExactly("CREDITO", "VISA", "1", "DIVERGENTE");
        assertThat(itens(Recorte.DIVERGENTES)).isEmpty();
    }

    @Test
    @DisplayName("par só com cancelamento não tem o que corrigir: a saída é aceitar")
    void corrigir_par_sem_o_que_corrigir() {
        assertThat(catchThrowable(() -> {
            pagamento("CREDITO", "100.00", "14:00");
            importar(venda("ST1", "14:05", "100,00", "Crédito"));
            importar(venda("ST1", "18/09/2026 14:05", "100,00", "Crédito", "", "Cancelada"));
            resolver(AlvoDaResolucao.PAR, itens(Recorte.DIVERGENTES).getFirst().id(),
                    AcaoDaResolucao.CORRIGIR, "quero corrigir");
        })).satisfies(erro ->
                assertThat(codigo(erro)).isEqualTo("ER-FIN-RESOLUCAO_NAO_APLICAVEL"));
    }

    // ------------------------------------------------------------------ só na maquininha

    @Test
    @DisplayName("venda sem comanda: lançar no caixa escreve o ajuste e tira da conferência")
    void lancar_venda_no_caixa() throws SQLException {
        importar(venda("ST1", "10:00", "150,00", "Débito"));
        UUID venda = itens(Recorte.SO_NA_MAQUININHA).getFirst().vendaId();

        var resultado = resolver(AlvoDaResolucao.VENDA, venda, AcaoDaResolucao.LANCAR,
                "venda de shampoo sem comanda");

        assertThat(resultado.lancado()).isEqualByComparingTo("150.00");
        assertThat(lancamentos()).singleElement()
                .satisfies(v -> assertThat(v).isEqualByComparingTo("150.00"));
        assertThat(itens(Recorte.SO_NA_MAQUININHA)).isEmpty();
        assertThat(resumo().soNaMaquininha().quantidade()).isZero();
        assertThat(resumo().resolvidas().valor()).isEqualByComparingTo("150");
    }

    @Test
    @DisplayName("venda que já casou não se lança no caixa: o dinheiro já está na comanda")
    void lancar_venda_que_ja_casou() throws SQLException {
        pagamento("CREDITO", "100.00", "14:00");
        importar(venda("ST1", "14:05", "100,00", "Crédito"));
        UUID casada = itens(Recorte.CONCILIADAS).getFirst().vendaId();

        Throwable erro = catchThrowable(() -> resolver(AlvoDaResolucao.VENDA, casada,
                AcaoDaResolucao.LANCAR, "lançando assim mesmo"));

        assertThat(codigo(erro)).isEqualTo("ER-FIN-RESOLUCAO_NAO_APLICAVEL");
        assertThat(lancamentos()).isEmpty();
    }

    @Test
    @DisplayName("venda cancelada na Stone não se lança no caixa: não é dinheiro que entrou")
    void lancar_venda_cancelada() throws SQLException {
        importar(venda("ST1", "18/09/2026 10:00", "150,00", "Débito", "", "Cancelada"));

        Throwable erro = catchThrowable(() -> resolver(AlvoDaResolucao.VENDA,
                vendaPorStoneId("ST1"), AcaoDaResolucao.LANCAR, "entrou dinheiro"));

        assertThat(codigo(erro)).isEqualTo("ER-FIN-RESOLUCAO_NAO_APLICAVEL");
        assertThat(lancamentos()).isEmpty();
    }

    @Test
    @DisplayName("cancelada de outro valor não gera aviso: não é a mesma venda passada de novo")
    void sem_aviso_quando_o_valor_difere() throws SQLException {
        pagamento("CREDITO", "200.00", "14:00");
        importar(venda("ST1", "14:05", "200,00", "Crédito"));
        // A cancelada casou e é do mesmo dia — mas de outro valor, e portanto de outra venda.
        importar(venda("ST1", "18/09/2026 14:05", "200,00", "Crédito", "", "Cancelada"),
                venda("ST2", "14:09", "150,00", "Crédito"));

        assertThat(itens(Recorte.SO_NA_MAQUININHA)).singleElement().satisfies(item -> {
            assertThat(item.idNaAdquirente()).isEqualTo("ST2");
            assertThat(item.possivelRefeita()).isFalse();
        });
    }

    @Test
    @DisplayName("cancelada que nunca casou não gera aviso: não há o que duplicar")
    void sem_aviso_quando_a_cancelada_nao_casou() {
        // A cancelada já entra cancelada: nunca teve pagamento, e o dinheiro nunca foi ao caixa.
        importar(venda("ST1", "18/09/2026 10:00", "150,00", "Débito", "", "Cancelada"),
                venda("ST2", "10:09", "150,00", "Débito"));

        assertThat(itens(Recorte.SO_NA_MAQUININHA)).singleElement().satisfies(item -> {
            assertThat(item.idNaAdquirente()).isEqualTo("ST2");
            assertThat(item.possivelRefeita()).isFalse();
        });
    }

    @Test
    @DisplayName("venda refeita depois de um cancelamento vem marcada: o valor já pode estar no caixa")
    void aviso_de_venda_refeita() throws SQLException {
        pagamento("CREDITO", "100.00", "14:00");
        importar(venda("ST1", "14:05", "100,00", "Crédito"));
        // A primeira é cancelada e a mesma venda é passada de novo, no mesmo dia e valor.
        importar(venda("ST1", "18/09/2026 14:05", "100,00", "Crédito", "", "Cancelada"),
                venda("ST2", "14:09", "100,00", "Crédito"));

        assertThat(itens(Recorte.SO_NA_MAQUININHA)).singleElement().satisfies(item -> {
            assertThat(item.idNaAdquirente()).isEqualTo("ST2");
            assertThat(item.possivelRefeita()).isTrue();
        });
    }

    @Test
    @DisplayName("sem cancelada de mesmo valor no dia, não há aviso")
    void sem_aviso_quando_nao_ha_cancelada() {
        importar(venda("ST1", "10:00", "150,00", "Débito"));

        assertThat(itens(Recorte.SO_NA_MAQUININHA)).singleElement()
                .satisfies(item -> assertThat(item.possivelRefeita()).isFalse());
    }

    // ------------------------------------------------------------------ só no sistema

    @Test
    @DisplayName("pagamento que a maquininha não tem: corrigir a forma tira da conferência")
    void corrigir_forma_do_pagamento() throws SQLException {
        UUID p = pagamento(tenant, "CREDITO", "60.00", "11:00", "999");
        importar(venda("ST1", "10:00", "150,00", "Débito"));

        var resultado = resolver(AlvoDaResolucao.PAGAMENTO, p, AcaoDaResolucao.CORRIGIR,
                "foi Pix; a recepção marcou crédito", FormaDePagamento.PIX);

        assertThat(resultado.formaPara()).isEqualTo("PIX");
        assertThat(pagamentoAgora(p)).containsExactly("PIX", null, "1", "NAO_APLICAVEL");
        assertThat(itens(Recorte.SO_NO_SISTEMA)).isEmpty();
        assertThat(lancamentos()).isEmpty();
    }

    @Test
    @DisplayName("trocar cartão por cartão não resolve nada, e é recusado")
    void forma_de_cartao_recusada() throws SQLException {
        UUID p = pagamento("CREDITO", "60.00", "11:00");
        importar(venda("ST1", "10:00", "150,00", "Débito"));

        for (FormaDePagamento forma : List.of(FormaDePagamento.DEBITO, FormaDePagamento.CORTESIA)) {
            Throwable erro = catchThrowable(() -> resolver(AlvoDaResolucao.PAGAMENTO, p,
                    AcaoDaResolucao.CORRIGIR, "trocando", forma));
            assertThat(codigo(erro)).as(forma.name()).isEqualTo("ER-FIN-DADOS_INVALIDOS");
        }
    }

    // ------------------------------------------------------------------ as travas

    @Test
    @DisplayName("sem motivo escrito não resolve")
    void motivo_obrigatorio() throws SQLException {
        importar(venda("ST1", "10:00", "150,00", "Débito"));
        UUID venda = itens(Recorte.SO_NA_MAQUININHA).getFirst().vendaId();

        for (String motivo : List.of("", "   ", "ab")) {
            Throwable erro = catchThrowable(() -> resolver(AlvoDaResolucao.VENDA, venda,
                    AcaoDaResolucao.LANCAR, motivo));
            assertThat(codigo(erro)).as("[" + motivo + "]").isEqualTo("ER-FIN-DADOS_INVALIDOS");
        }
        assertThat(lancamentos()).isEmpty();
    }

    @Test
    @DisplayName("resolver a mesma linha duas vezes é conflito, e não lança dobrado")
    void ja_resolvida() throws SQLException {
        importar(venda("ST1", "10:00", "150,00", "Débito"));
        UUID venda = itens(Recorte.SO_NA_MAQUININHA).getFirst().vendaId();
        resolver(AlvoDaResolucao.VENDA, venda, AcaoDaResolucao.LANCAR, "venda sem comanda");

        Throwable erro = catchThrowable(() -> resolver(AlvoDaResolucao.VENDA, venda,
                AcaoDaResolucao.LANCAR, "de novo"));

        assertThat(codigo(erro)).isEqualTo("ER-FIN-JA_RESOLVIDA");
        assertThat(lancamentos()).hasSize(1);
    }

    @Test
    @DisplayName("a ação que não cabe no alvo é recusada")
    void acao_que_nao_cabe() throws SQLException {
        UUID p = pagamento("CREDITO", "60.00", "11:00");
        importar(venda("ST1", "10:00", "150,00", "Débito"));
        UUID venda = itens(Recorte.SO_NA_MAQUININHA).getFirst().vendaId();

        assertThat(codigo(catchThrowable(() -> resolver(AlvoDaResolucao.VENDA, venda,
                AcaoDaResolucao.ACEITAR, "aceito assim"))))
                .isEqualTo("ER-FIN-RESOLUCAO_NAO_APLICAVEL");
        assertThat(codigo(catchThrowable(() -> resolver(AlvoDaResolucao.PAGAMENTO, p,
                AcaoDaResolucao.LANCAR, "lança aí"))))
                .isEqualTo("ER-FIN-RESOLUCAO_NAO_APLICAVEL");
    }

    @Test
    @DisplayName("sem financeiro:reconcile, ninguém resolve nem lê a trilha")
    @WithMockUser(authorities = Permissao.FINANCEIRO_LER_TODOS)
    void sem_permissao() {
        assertThat(catchThrowable(() -> resolver(AlvoDaResolucao.VENDA, UUID.randomUUID(),
                AcaoDaResolucao.LANCAR, "sem permissão")))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(catchThrowable(this::trilha)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a linha de outro salão não existe para este")
    void isolamento() throws SQLException {
        UUID outro = novoSalao();
        UUID pagamentoDoOutro = pagamento(outro, "CREDITO", "60.00", "11:00", null);

        Throwable erro = catchThrowable(() -> resolver(AlvoDaResolucao.PAGAMENTO, pagamentoDoOutro,
                AcaoDaResolucao.CORRIGIR, "não é meu", FormaDePagamento.PIX));

        assertThat(codigo(erro)).isEqualTo("ER-FIN-RESOLUCAO_NAO_ENCONTRADA");
    }

    @Test
    @DisplayName("a aplicação não apaga nem reescreve uma resolução")
    void imutavel() throws SQLException {
        importar(venda("ST1", "10:00", "150,00", "Débito"));
        resolver(AlvoDaResolucao.VENDA, itens(Recorte.SO_NA_MAQUININHA).getFirst().vendaId(),
                AcaoDaResolucao.LANCAR, "venda sem comanda");

        for (String sql : List.of("delete from resolucao_de_conciliacao",
                "update resolucao_de_conciliacao set motivo = 'outro'",
                "update resolucao_de_conciliacao set acao = 'ACEITAR'")) {
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

    // ------------------------------------------------------------------ reabrir

    @Test
    @DisplayName("reabrir devolve a forma do pagamento e a linha volta para a conferência")
    void reabrir_desfaz_a_correcao() throws SQLException {
        UUID p = pagamento("DEBITO", "80.00", "10:00");
        importar(venda("ST1", "10:02", "80,00", "Crédito"));
        UUID par = itens(Recorte.DIVERGENTES).getFirst().id();
        UUID resolucao = resolver(AlvoDaResolucao.PAR, par, AcaoDaResolucao.CORRIGIR,
                "achei que era crédito").id();

        TenantContext.executar(tenant, () -> resolver.reabrir(resolucao, "cliente mostrou o comprovante"));

        assertThat(pagamentoAgora(p)).containsExactly("DEBITO", "VISA", "1", "DIVERGENTE");
        assertThat(itens(Recorte.DIVERGENTES)).singleElement()
                .satisfies(item -> assertThat(item.id()).isEqualTo(par));
        assertThat(trilha()).isEmpty();
    }

    @Test
    @DisplayName("reabrir um lançamento o estorna no razão: a soma volta a zero")
    void reabrir_estorna_o_lancamento() throws SQLException {
        importar(venda("ST1", "10:00", "150,00", "Débito"));
        UUID venda = itens(Recorte.SO_NA_MAQUININHA).getFirst().vendaId();
        UUID resolucao = resolver(AlvoDaResolucao.VENDA, venda, AcaoDaResolucao.LANCAR,
                "venda sem comanda").id();

        TenantContext.executar(tenant, () -> resolver.reabrir(resolucao, "era estorno do dia anterior"));

        assertThat(lancamentos()).containsExactly(new BigDecimal("150.0000"),
                new BigDecimal("-150.0000"));
        assertThat(itens(Recorte.SO_NA_MAQUININHA)).singleElement()
                .satisfies(item -> assertThat(item.vendaId()).isEqualTo(venda));
    }

    @Test
    @DisplayName("reaberta, a linha pode ser resolvida de novo")
    void resolver_de_novo_depois_de_reabrir() throws SQLException {
        importar(venda("ST1", "10:00", "150,00", "Débito"));
        UUID venda = itens(Recorte.SO_NA_MAQUININHA).getFirst().vendaId();
        UUID primeira = resolver(AlvoDaResolucao.VENDA, venda, AcaoDaResolucao.LANCAR,
                "venda sem comanda").id();
        TenantContext.executar(tenant, () -> resolver.reabrir(primeira, "motivo errado"));

        resolver(AlvoDaResolucao.VENDA, venda, AcaoDaResolucao.LANCAR, "venda de produto, sem comanda");

        assertThat(itens(Recorte.SO_NA_MAQUININHA)).isEmpty();
        assertThat(trilha()).singleElement()
                .satisfies(linha -> assertThat(linha.motivo())
                        .isEqualTo("venda de produto, sem comanda"));
    }

    @Test
    @DisplayName("reabrir duas vezes é conflito, e não estorna duas vezes")
    void reabrir_duas_vezes() throws SQLException {
        importar(venda("ST1", "10:00", "150,00", "Débito"));
        UUID resolucao = resolver(AlvoDaResolucao.VENDA,
                itens(Recorte.SO_NA_MAQUININHA).getFirst().vendaId(), AcaoDaResolucao.LANCAR,
                "venda sem comanda").id();
        TenantContext.executar(tenant, () -> resolver.reabrir(resolucao, "enganei-me"));

        Throwable erro = catchThrowable(() ->
                TenantContext.executar(tenant, () -> resolver.reabrir(resolucao, "de novo")));

        assertThat(codigo(erro)).isEqualTo("ER-FIN-JA_RESOLVIDA");
        assertThat(lancamentos()).hasSize(2);
    }

    @Test
    @DisplayName("cancelada na Stone depois de resolvida: a pergunta volta, e nada é desfeito")
    void cancelada_depois_reabre_a_resolucao() throws SQLException {
        UUID p = pagamento("DEBITO", "80.00", "10:00");
        importar(venda("ST1", "10:02", "80,00", "Crédito"));
        UUID par = itens(Recorte.DIVERGENTES).getFirst().id();
        resolver(AlvoDaResolucao.PAR, par, AcaoDaResolucao.CORRIGIR, "foi crédito mesmo");
        assertThat(itens(Recorte.DIVERGENTES)).isEmpty();

        importar(venda("ST1", "18/09/2026 10:02", "80,00", "Crédito", "", "Cancelada"));

        assertThat(itens(Recorte.DIVERGENTES)).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(par);
            assertThat(item.divergencias()).contains("CANCELADA_NA_ADQUIRENTE");
        });
        // A correção continua de pé: a forma estava certa, e só o cancelamento é novidade.
        assertThat(pagamentoAgora(p)).containsExactly("CREDITO", "VISA", "1", "DIVERGENTE");
        assertThat(trilha()).isEmpty();
    }

    // ------------------------------------------------------------------ a trilha

    @Test
    @DisplayName("a trilha diz o que foi feito, por quê e o que mudou")
    void trilha_conta_a_historia() throws SQLException {
        pagamento("DEBITO", "80.00", "10:00");
        importar(venda("ST1", "10:02", "80,00", "Crédito"), venda("ST2", "11:00", "40,00", "Débito"));
        UUID par = itens(Recorte.DIVERGENTES).getFirst().id();
        UUID venda = itens(Recorte.SO_NA_MAQUININHA).getFirst().vendaId();

        resolver(AlvoDaResolucao.PAR, par, AcaoDaResolucao.CORRIGIR, "foi crédito");
        resolver(AlvoDaResolucao.VENDA, venda, AcaoDaResolucao.LANCAR, "venda sem comanda");

        assertThat(trilha()).hasSize(2);
        assertThat(trilha()).filteredOn(l -> "PAR".equals(l.alvo())).singleElement()
                .satisfies(linha -> {
                    assertThat(linha.acao()).isEqualTo("CORRIGIR");
                    assertThat(linha.motivo()).isEqualTo("foi crédito");
                    assertThat(linha.formaDe()).isEqualTo("DEBITO");
                    assertThat(linha.formaPara()).isEqualTo("CREDITO");
                    assertThat(linha.idNaAdquirente()).isEqualTo("ST1");
                    assertThat(linha.valor()).isEqualByComparingTo("80");
                    assertThat(linha.lancado()).isNull();
                });
        assertThat(trilha()).filteredOn(l -> "VENDA".equals(l.alvo())).singleElement()
                .satisfies(linha -> {
                    assertThat(linha.acao()).isEqualTo("LANCAR");
                    assertThat(linha.lancado()).isEqualByComparingTo("40.00");
                    assertThat(linha.idNaAdquirente()).isEqualTo("ST2");
                });
    }
}
