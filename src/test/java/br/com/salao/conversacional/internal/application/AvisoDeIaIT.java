package br.com.salao.conversacional.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.conversacional.internal.infra.DespachanteParaEquipe;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.AtualizarConfiguracaoUseCase;
import br.com.salao.iam.internal.application.ConfiguracaoCommand;
import br.com.salao.shared.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * RT-CNV-008 — o aviso a quem não é da equipe: pelo caminho real do WhatsApp até a fila de saída, com
 * o listener assíncrono de verdade.
 *
 * <p>Para afirmar que alguém <strong>não</strong> foi avisado, o teste espera o listener terminar
 * aquela mensagem — a publicação concluída no outbox — e só então olha a fila. Olhar antes provaria
 * apenas que ele ainda não tinha rodado.
 */
class AvisoDeIaIT extends AbstractAgenteIT {

    @Autowired private DespachanteParaEquipe despachante;
    @Autowired private AtualizarConfiguracaoUseCase atualizarConfiguracao;
    @Autowired private EstabelecimentoApi estabelecimentos;

    private long processadas(UUID tenant) {
        try (var ps = comoOwner().prepareStatement("""
                select (select count(*) from event_publication_archive
                         where listener_id like '%AvisarQuemNaoEDaEquipe%'
                           and serialized_event like ?)
                     + (select count(*) from event_publication
                         where listener_id like '%AvisarQuemNaoEDaEquipe%'
                           and completion_date is not null and serialized_event like ?)""")) {
            ps.setString(1, "%" + tenant + "%");
            ps.setString(2, "%" + tenant + "%");
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Entrega e espera o listener do aviso concluir essas mensagens. */
    private void entregarEEsperar(Salao salao, int quantas, Runnable entrega) {
        long antes = processadas(salao.tenant());
        entrega.run();
        aguardar(() -> processadas(salao.tenant()) >= antes + quantas,
                "o aviso não processou a mensagem", 15);
    }

    private List<String[]> avisos(UUID tenant) throws SQLException {
        var linhas = new ArrayList<String[]>();
        try (var ps = comoOwner().prepareStatement("""
                select destino, texto from mensagem_para_equipe
                 where estabelecimento_id = ? and motivo = 'AVISO_DE_IA' order by sequencia""")) {
            ps.setObject(1, tenant);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    linhas.add(new String[] { rs.getString(1), rs.getString(2) });
                }
            }
        }
        return linhas;
    }

    private void contato(Salao salao, String contato) {
        TenantContext.executar(salao.tenant(), () -> com(() -> {
            var atual = estabelecimentos.configuracao(salao.tenant()).orElseThrow();
            return atualizarConfiguracao.executar(new ConfiguracaoCommand(atual.nome(), null,
                    atual.fuso().getId(), atual.moeda(), atual.baseComissao(),
                    atual.descontoAfetaComissao(), atual.periodicidadeDeFechamento(),
                    atual.dddPadrao(), contato));
        }, Permissao.CONFIG_GERENCIAR));
    }

    // ------------------------------------------------------------------ quem recebe

    @Test
    @DisplayName("quem não é da equipe é avisado uma vez — mesmo sem modelo configurado")
    void avisado_uma_vez_sem_modelo() throws SQLException {
        Salao salao = salao();
        modelo.configurado = false;
        String estranho = telefoneNovo();

        entregarEEsperar(salao, 2, () -> {
            entregar(salao.numero(), estranho, "wamid.1", "text", "oi, tem horário amanhã?");
            entregar(salao.numero(), estranho, "wamid.2", "text", "é pra corte");
        });

        assertThat(avisos(salao.tenant())).singleElement().satisfies(aviso -> {
            assertThat(aviso[0]).isEqualTo(estranho);
            assertThat(aviso[1]).startsWith("Mensagem automática do Studio Bertoldo:")
                    .contains("inteligência artificial").contains("ninguém lê")
                    .doesNotContain("Para falar com o salão");
        });
        assertThat(modelo.pedidos).as("o aviso não passa pelo modelo").isEmpty();
    }

    @Test
    @DisplayName("áudio também avisa: a primeira mensagem de muita cliente é um áudio")
    void audio_avisa() throws SQLException {
        Salao salao = salao();
        String estranho = telefoneNovo();

        entregarEEsperar(salao, 1,
                () -> entregar(salao.numero(), estranho, "wamid.A", "audio", null));

        assertThat(avisos(salao.tenant())).singleElement()
                .satisfies(aviso -> assertThat(aviso[0]).isEqualTo(estranho));
    }

    @Test
    @DisplayName("quem é da equipe não recebe — nem a conta que o canal recusa por exigir segundo fator")
    void equipe_nao_recebe() throws SQLException {
        Salao salao = salao();
        String bia = pessoa(salao, Perfil.RECEPCAO);
        String dona = telefoneNovo();
        TenantContext.executar(salao.tenant(), () -> com(() -> {
            definirTelefones.executar(salao.adminId(), List.of(dona));
            return null;
        }, Permissao.USUARIO_GERENCIAR));

        entregarEEsperar(salao, 2, () -> {
            entregar(salao.numero(), bia, "wamid.1", "text", "oi");
            entregar(salao.numero(), dona, "wamid.2", "text", "quanto faturei?");
        });

        assertThat(avisos(salao.tenant())).isEmpty();
    }

    @Test
    @DisplayName("toque de botão não avisa: botão é resposta a mensagem que o salão mandou")
    void botao_nao_avisa() throws SQLException {
        Salao salao = salao();
        String profissionalSemConta = telefoneNovo();

        entregarEEsperar(salao, 1, () -> entregar(salao.numero(), profissionalSemConta, "wamid.B",
                "botao", "aceite:" + UUID.randomUUID() + ":" + "A".repeat(24) + ":sim"));

        assertThat(avisos(salao.tenant())).isEmpty();
    }

    // ------------------------------------------------------------------ o texto e o salão

    @Test
    @DisplayName("o contato é de cada salão, e quem escreve para outro salão é avisado de novo")
    void contato_por_salao() throws SQLException {
        Salao primeiro = salao();
        Salao segundo = salao("6666666666");
        contato(primeiro, "(11) 3456-7890");
        contato(segundo, "https://studio.exemplo/agendar");
        String estranho = telefoneNovo();

        entregarEEsperar(primeiro, 1,
                () -> entregar(primeiro.numero(), estranho, "wamid.1", "text", "oi"));
        entregarEEsperar(segundo, 1,
                () -> entregar(segundo.numero(), estranho, "wamid.2", "text", "oi"));

        assertThat(avisos(primeiro.tenant())).singleElement().satisfies(aviso ->
                assertThat(aviso[1]).endsWith("\n\nPara falar com o salão: (11) 3456-7890"));
        assertThat(avisos(segundo.tenant())).singleElement().satisfies(aviso ->
                assertThat(aviso[1]).endsWith("Para falar com o salão: https://studio.exemplo/agendar"));
        assertThat(impressaoDe(primeiro.tenant()))
                .as("a mesma pessoa não deixa o mesmo rastro em dois salões")
                .isNotEqualTo(impressaoDe(segundo.tenant()));
    }

    private byte[] impressaoDe(UUID tenant) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "select impressao from aviso_de_ia where estabelecimento_id = ?")) {
            ps.setObject(1, tenant);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getBytes(1);
            }
        }
    }

    // ------------------------------------------------------------------ o que fica guardado

    @Test
    @DisplayName("fica só a impressão com chave: nem o número, nem um hash que se desfaça")
    void guarda_so_a_impressao() throws Exception {
        Salao salao = salao();
        String estranho = telefoneNovo();

        entregarEEsperar(salao, 1, () -> entregar(salao.numero(), estranho, "wamid.1", "text", "oi"));

        try (var ps = comoOwner().prepareStatement("""
                select impressao, row_to_json(a)::text from aviso_de_ia a
                 where estabelecimento_id = ?""")) {
            ps.setObject(1, salao.tenant());
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                byte[] impressao = rs.getBytes(1);
                var sha = MessageDigest.getInstance("SHA-256");
                assertThat(impressao).hasSize(32)
                        .isNotEqualTo(sha.digest(estranho.getBytes(StandardCharsets.UTF_8)))
                        .isNotEqualTo(sha.digest((salao.tenant() + ":" + estranho)
                                .getBytes(StandardCharsets.UTF_8)));
                assertThat(rs.getString(2)).doesNotContain(estranho)
                        .doesNotContain(estranho.substring(estranho.length() - 8));
                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test
    @DisplayName("a aplicação não apaga nem reescreve quem já foi avisado")
    void aplicacao_nao_apaga() throws SQLException {
        Salao salao = salao();
        entregarEEsperar(salao, 1,
                () -> entregar(salao.numero(), telefoneNovo(), "wamid.1", "text", "oi"));

        for (String sql : List.of("delete from aviso_de_ia",
                "update aviso_de_ia set avisado_em = now()")) {
            var conexao = comoApp(salao.tenant());
            try (var st = conexao.createStatement()) {
                Throwable erro = catchThrowable(() -> st.executeUpdate(sql));
                assertThat(erro).as(sql).isNotNull();
                assertThat(erro.getMessage()).containsIgnoringCase("permission denied");
            } finally {
                conexao.rollback();
            }
        }
    }

    // ------------------------------------------------------------------ a saída

    @Test
    @DisplayName("sai como resposta: a cliente acabou de escrever, e a janela da Meta está aberta")
    void sai_como_resposta() throws SQLException {
        Salao salao = salao();
        String estranho = telefoneNovo();
        entregarEEsperar(salao, 1, () -> entregar(salao.numero(), estranho, "wamid.1", "text", "oi"));

        despachante.executar();

        assertThat(enviador.envios).filteredOn(envio -> estranho.equals(envio.destino()))
                .singleElement().satisfies(envio -> {
                    assertThat(envio.iniciadaPeloSalao()).isFalse();
                    assertThat(envio.botoes()).isEmpty();
                    assertThat(envio.texto()).contains("inteligência artificial");
                });
    }
}
