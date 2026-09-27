package br.com.salao.conversacional.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.conversacional.internal.infra.TranscricoesJdbc;
import br.com.salao.iam.api.Perfil;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * RT-CNV-009 — o agente espera a pessoa terminar de falar.
 *
 * <p>Contexto próprio, e não o das outras ITs do agente: aqui a janela é REAL. É a única classe da
 * suíte que precisa disso, e a espera é justamente o que ela prova — as demais rodam com janela zero
 * (ver {@link AbstractAgenteIT}).
 *
 * <h2>Como o tempo passa aqui</h2>
 *
 * <p>Não passa: o silêncio é fabricado empurrando {@code recebida_em} para trás, que é exatamente o
 * que a janela lê. Dormir trinta segundos por teste seria a mesma prova, nove vezes mais devagar, e
 * frágil na máquina lenta.
 */
class JanelaDeAgregacaoIT extends AbstractAgenteIT {

    private static final Duration JANELA = Duration.ofSeconds(30);
    private static final Duration TETO = Duration.ofMinutes(3);

    @DynamicPropertySource
    static void janelaReal(DynamicPropertyRegistry registro) {
        registro.add("app.agente.janela-de-agregacao", JANELA::toString);
        registro.add("app.agente.teto-de-espera", TETO::toString);
    }

    @Autowired private ConcluirTranscricao concluirTranscricao;
    @Autowired private TranscricoesJdbc transcricoes;
    @Autowired private Relogio relogio;

    // ------------------------------------------------------------------ o que a rotina promete

    @Test
    @DisplayName("três mensagens em rajada viram UMA volta do modelo, na ordem em que chegaram")
    void rajada_vira_uma_volta_do_modelo() throws SQLException {
        Salao salao = salao();
        String bia = pessoa(salao, Perfil.RECEPCAO);
        naFila(salao, bia, "wamid.1", "oi");
        naFila(salao, bia, "wamid.2", "queria marcar com a Ana");
        naFila(salao, bia, "wamid.3", "pode ser quinta de tarde");

        processador.executar();
        assertThat(modelo.pedidos).as("dentro da janela o agente não fala").isEmpty();

        calar(salao, bia);
        processador.executar();

        assertThat(modelo.pedidos).singleElement().satisfies(pedido ->
                assertThat(entradaDe(pedido))
                        .isEqualTo("oi\nqueria marcar com a Ana\npode ser quinta de tarde"));
        assertThat(execucao(salao.tenant(), "wamid.3")).containsEntry("status", "CONCLUIDA");
        assertThat(execucao(salao.tenant(), "wamid.1")).containsEntry("status", "JUNTADA");
        assertThat(execucao(salao.tenant(), "wamid.2")).containsEntry("status", "JUNTADA");
    }

    @Test
    @DisplayName("responde a última, e as absorvidas apontam para ela — com uma ação só na trilha")
    void absorvidas_apontam_para_quem_respondeu() throws SQLException {
        Salao salao = salao();
        String bia = pessoa(salao, Perfil.RECEPCAO);
        naFila(salao, bia, "wamid.1", "oi");
        naFila(salao, bia, "wamid.2", "tem horário hoje?");
        calar(salao, bia);

        processador.executar();

        UUID respondeu = (UUID) execucao(salao.tenant(), "wamid.2").get("id");
        assertThat(quemRespondeu(salao.tenant(), "wamid.1")).isEqualTo(respondeu);
        assertThat(quemRespondeu(salao.tenant(), "wamid.2")).as("quem responde não é absorvida")
                .isNull();
        assertThat(execucao(salao.tenant(), "wamid.1")).as("a resposta é da rajada, não de cada uma")
                .containsEntry("resposta", null);
        assertThat(trilha(salao.tenant())).as("uma ação por rajada, com o pedido inteiro")
                .singleElement()
                .satisfies(acao -> assertThat(acao)
                        .containsEntry("pedido", "oi\ntem horário hoje?"));
    }

    @Test
    @DisplayName("o áudio é esperado: ele chegou antes, e entra na entrada antes")
    void espera_o_audio_virar_texto() throws SQLException {
        Salao salao = salao();
        String bia = pessoa(salao, Perfil.RECEPCAO);
        comAudio(salao, bia, "wamid.A");
        naFila(salao, bia, "wamid.T", "é pra quinta");
        calar(salao, bia);

        processador.executar();
        assertThat(modelo.pedidos).as("o texto não é respondido enquanto o áudio vira texto")
                .isEmpty();

        transcrever(salao, "wamid.A", "oi, queria marcar com a Ana");
        calar(salao, bia);
        processador.executar();

        assertThat(modelo.pedidos).singleElement().satisfies(pedido ->
                assertThat(entradaDe(pedido)).as("a ordem é a de chegada, não a da fila")
                        .isEqualTo("oi, queria marcar com a Ana\né pra quinta"));
        assertThat(trilha(salao.tenant())).singleElement().satisfies(acao -> assertThat(acao)
                .as("basta um áudio na rajada para a ação ter vindo de áudio")
                .containsEntry("veio_de_audio", true)
                .containsEntry("pedido", "oi, queria marcar com a Ana\né pra quinta"));
    }

    @Test
    @DisplayName("falha passageira devolve a rajada INTEIRA para a fila, e ela se reforma igual")
    void falha_devolve_a_rajada_inteira() throws SQLException {
        Salao salao = salao();
        String bia = pessoa(salao, Perfil.RECEPCAO);
        naFila(salao, bia, "wamid.1", "oi");
        naFila(salao, bia, "wamid.2", "tem horário hoje?");
        calar(salao, bia);
        modelo.roteiro.add(new IllegalStateException("provedor fora do ar"));

        processador.executar();

        assertThat(execucao(salao.tenant(), "wamid.1")).as("a absorvida volta junto")
                .containsEntry("status", "PENDENTE");
        assertThat(execucao(salao.tenant(), "wamid.2")).containsEntry("status", "PENDENTE");
        assertThat(reservadas(salao.tenant(), bia)).as("nenhuma fica presa pela reserva").isZero();

        adiantarFila(salao, bia);
        calar(salao, bia);
        processador.executar();

        assertThat(modelo.pedidos).as("uma tentativa falha, outra inteira")
                .hasSize(2);
        assertThat(entradaDe(modelo.pedidos.get(1))).isEqualTo("oi\ntem horário hoje?");
    }

    @Test
    @DisplayName("mensagem sozinha não espera nada além da janela")
    void sozinha_nao_espera_alem_da_janela() throws SQLException {
        Salao salao = salao();
        String bia = pessoa(salao, Perfil.RECEPCAO);
        naFila(salao, bia, "wamid.1", "tem horário hoje?");

        processador.executar();
        assertThat(modelo.pedidos).isEmpty();

        calar(salao, bia);
        processador.executar();

        assertThat(modelo.pedidos).singleElement().satisfies(pedido ->
                assertThat(entradaDe(pedido)).isEqualTo("tem horário hoje?"));
    }

    // ------------------------------------------------------------------ nada espera para sempre

    @Test
    @DisplayName("transcrição travada não segura a rajada além do teto")
    void transcricao_travada_nao_segura_alem_do_teto() throws SQLException {
        Salao salao = salao();
        String bia = pessoa(salao, Perfil.RECEPCAO);
        comAudio(salao, bia, "wamid.A");
        naFila(salao, bia, "wamid.T", "oi, tem horário?");
        calar(salao, bia);

        processador.executar();
        assertThat(modelo.pedidos).as("dentro do teto, o áudio ainda é esperado").isEmpty();

        envelhecer(salao, bia, TETO.plusMinutes(1));
        processador.executar();

        assertThat(modelo.pedidos).as("passado o teto, responde com o que tem")
                .singleElement()
                .satisfies(pedido -> assertThat(entradaDe(pedido)).isEqualTo("oi, tem horário?"));
    }

    @Test
    @DisplayName("quem não para de escrever recebe resposta no teto")
    void quem_nao_para_de_escrever_recebe_no_teto() throws SQLException {
        Salao salao = salao();
        String bia = pessoa(salao, Perfil.RECEPCAO);
        naFila(salao, bia, "wamid.1", "oi");
        // A primeira está esperando desde antes do teto; a segunda acabou de chegar, então o número
        // NÃO está em silêncio. É o caso de quem digita sem parar.
        esperandoDesde(salao, "wamid.1", TETO.plusMinutes(1));
        naFila(salao, bia, "wamid.2", "e aí?");

        processador.executar();

        assertThat(modelo.pedidos).singleElement().satisfies(pedido ->
                assertThat(entradaDe(pedido)).isEqualTo("oi\ne aí?"));
    }

    // ------------------------------------------------------------------ o que NÃO se junta

    @Test
    @DisplayName("o toque num botão não espera janela nenhuma")
    void botao_nao_espera() throws SQLException {
        Salao salao = salao();
        String bia = pessoa(salao, Perfil.RECEPCAO);
        naFilaBotao(salao, bia, "wamid.B", "aceite:token-que-nao-existe");

        processador.executar();

        assertThat(execucao(salao.tenant(), "wamid.B")).containsEntry("status", "CONCLUIDA");
        assertThat(modelo.pedidos).as("o botão não passa pelo modelo").isEmpty();
    }

    @Test
    @DisplayName("rajadas de telefones diferentes não se misturam")
    void telefones_diferentes_nao_se_misturam() throws SQLException {
        Salao salao = salao();
        String bia = pessoa(salao, Perfil.RECEPCAO);
        String carla = pessoa(salao, Perfil.RECEPCAO);
        naFila(salao, bia, "wamid.B1", "aqui é a Bia");
        naFila(salao, bia, "wamid.B2", "tem horário?");
        naFila(salao, carla, "wamid.C1", "aqui é a Carla");
        naFila(salao, carla, "wamid.C2", "e amanhã?");
        calar(salao, bia);
        calar(salao, carla);

        processador.executar();

        assertThat(modelo.pedidos).hasSize(2).extracting(JanelaDeAgregacaoIT::entradaDe)
                .containsExactlyInAnyOrder("aqui é a Bia\ntem horário?",
                        "aqui é a Carla\ne amanhã?");
    }

    @Test
    @DisplayName("enxurrada tem teto de tamanho: o que passa de 20 vira a rajada seguinte")
    void enxurrada_tem_teto_de_tamanho() throws SQLException {
        Salao salao = salao();
        String bia = pessoa(salao, Perfil.RECEPCAO);
        for (int i = 1; i <= 22; i++) {
            naFila(salao, bia, "wamid." + i, "mensagem " + i);
        }
        calar(salao, bia);

        processador.executar();

        assertThat(modelo.pedidos).singleElement().satisfies(pedido -> {
            assertThat(entradaDe(pedido)).startsWith("mensagem 1\n").endsWith("\nmensagem 20");
            assertThat(entradaDe(pedido).lines()).hasSize(20);
        });
        assertThat(execucao(salao.tenant(), "wamid.21")).containsEntry("status", "PENDENTE");

        calar(salao, bia);
        processador.executar();

        assertThat(modelo.pedidos).hasSize(2);
        assertThat(entradaDe(modelo.pedidos.get(1))).isEqualTo("mensagem 21\nmensagem 22");
    }

    // ------------------------------------------------------------------ apoio

    /**
     * O que o modelo LEU nesta volta: a última fala do usuário do pedido.
     *
     * <p>É a asserção que importa — a rajada existe para que o modelo veja tudo de uma vez, e o que
     * ele viu está aqui, não no banco.
     */
    private static String entradaDe(ModeloDeLinguagem.Pedido pedido) {
        var ultima = pedido.mensagens().getLast();
        return ((ModeloDeLinguagem.Texto) ultima.blocos().getFirst()).texto();
    }

    /** Traz a espera entre tentativas para o passado: a rajada pode ser tentada de novo. */
    private void adiantarFila(Salao salao, String telefone) throws SQLException {
        try (var ps = comoOwner().prepareStatement("""
                update execucao_do_agente set proxima_tentativa_em = now() - interval '1 hour'
                 where estabelecimento_id = ? and de_telefone = ? and status = 'PENDENTE'""")) {
            ps.setObject(1, salao.tenant());
            ps.setString(2, telefone);
            ps.executeUpdate();
        }
    }

    private int reservadas(UUID tenant, String telefone) throws SQLException {
        try (var ps = comoOwner().prepareStatement("""
                select count(*) from execucao_do_agente
                 where estabelecimento_id = ? and de_telefone = ? and reservada_ate is not null""")) {
            ps.setObject(1, tenant);
            ps.setString(2, telefone);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Fabrica o silêncio: empurra o que chegou deste número para fora da janela. */
    private void calar(Salao salao, String telefone) throws SQLException {
        envelhecer(salao, telefone, JANELA.plusSeconds(30));
    }

    private void envelhecer(Salao salao, String telefone, Duration quanto) throws SQLException {
        try (var ps = comoOwner().prepareStatement("""
                update mensagem_recebida set recebida_em = recebida_em - ?::interval
                 where estabelecimento_id = ? and de_telefone = ?""")) {
            ps.setString(1, quanto.toSeconds() + " seconds");
            ps.setObject(2, salao.tenant());
            ps.setString(3, telefone);
            ps.executeUpdate();
        }
    }

    /** Põe a execução desta mensagem esperando desde antes do teto, sem mexer no silêncio. */
    private void esperandoDesde(Salao salao, String wamid, Duration quanto) throws SQLException {
        try (var ps = comoOwner().prepareStatement("""
                update execucao_do_agente e
                   set proxima_tentativa_em = e.proxima_tentativa_em - ?::interval
                  from mensagem_recebida m
                 where m.id = e.mensagem_id and m.estabelecimento_id = ? and m.provedor_id = ?""")) {
            ps.setString(1, quanto.toSeconds() + " seconds");
            ps.setObject(2, salao.tenant());
            ps.setString(3, wamid);
            ps.executeUpdate();
        }
    }

    /** Um áudio que chegou e ainda não virou texto: é o que a janela tem de esperar. */
    private void comAudio(Salao salao, String telefone, String wamid) {
        entregar(salao.numero(), telefone, wamid, "audio", null);
        aguardar(() -> transcricaoDe(salao, wamid) != null,
                "o áudio não entrou na fila de transcrição", 15);
    }

    /** O áudio vira texto — e só então vira conversa na fila (RT-CNV-003). */
    private void transcrever(Salao salao, String wamid, String texto) throws SQLException {
        UUID mensagem = mensagem(salao.tenant(), wamid);
        UUID transcricao = transcricaoDe(salao, wamid);
        try (var ps = comoOwner().prepareStatement("""
                update transcricao_de_audio set status = 'BAIXADA', arquivo_id = gen_random_uuid()
                 where id = ?""")) {
            ps.setObject(1, transcricao);
            ps.executeUpdate();
        }
        TenantContext.executar(salao.tenant(), () -> concluirTranscricao.executar(transcricao,
                mensagem, salao.tenant(),
                new Transcritor.TextoTranscrito(texto, "pt-BR", null, "falso"), relogio.agora()));
        aguardar(() -> {
            try {
                return !execucao(salao.tenant(), wamid).isEmpty();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }, "a transcrição não virou conversa na fila", 15);
    }

    private UUID transcricaoDe(Salao salao, String wamid) {
        try {
            UUID mensagem = mensagem(salao.tenant(), wamid);
            return mensagem == null ? null : TenantContext.obter(salao.tenant(),
                    () -> transcricoes.porMensagem(mensagem)).map(t -> t.id()).orElse(null);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID quemRespondeu(UUID tenant, String wamid) throws SQLException {
        try (var ps = comoOwner().prepareStatement("""
                select e.respondida_por from execucao_do_agente e
                  join mensagem_recebida m on m.id = e.mensagem_id
                 where m.estabelecimento_id = ? and m.provedor_id = ?""")) {
            ps.setObject(1, tenant);
            ps.setString(2, wamid);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1, UUID.class) : null;
            }
        }
    }

    private List<Map<String, Object>> trilha(UUID tenant) throws SQLException {
        var linhas = new ArrayList<Map<String, Object>>();
        try (var ps = comoOwner().prepareStatement("""
                select pedido, resposta, veio_de_audio from acao_ia
                 where estabelecimento_id = ? order by ocorrida_em""")) {
            ps.setObject(1, tenant);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    var linha = new HashMap<String, Object>();
                    linha.put("pedido", rs.getString("pedido"));
                    linha.put("resposta", rs.getString("resposta"));
                    linha.put("veio_de_audio", rs.getBoolean("veio_de_audio"));
                    linhas.add(linha);
                }
            }
        }
        return linhas;
    }
}
