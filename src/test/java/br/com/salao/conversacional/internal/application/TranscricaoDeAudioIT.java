package br.com.salao.conversacional.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.arquivos.api.TipoDeArquivo;
import br.com.salao.conversacional.internal.infra.ProcessadorDeAudios;
import br.com.salao.conversacional.internal.infra.TranscricoesJdbc;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * RT-CNV-003 — do webhook à transcrição, com a Meta e o provedor de STT falsos.
 *
 * <p>O adaptador HTTP tem o seu próprio teste, contra servidores de verdade. Aqui o assunto é a
 * esteira: outbox, fila, reserva, etapas, espera, desistência e a cópia guardada.
 */
@Import(TranscricaoDeAudioIT.Falsos.class)
@WithMockUser(authorities = { Permissao.CONFIG_GERENCIAR })
class TranscricaoDeAudioIT extends AbstractPostgresIT {

    private static final String NUMERO = "3333333333";
    private static final String TEXTO = "quero marcar quarta às dez";

    @DynamicPropertySource
    static void semAgendamento(DynamicPropertyRegistry registro) {
        // O @Scheduled rodaria sozinho no meio do teste e processaria a linha que o teste ainda
        // está preparando. Aqui quem aciona o processador é o teste.
        registro.add("app.whatsapp.audio.intervalo", () -> "PT10H");
        registro.add("app.whatsapp.audio.atraso-inicial", () -> "PT10H");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Falsos {
        @Bean
        @Primary
        MidiaFalsa midiaFalsa() {
            return new MidiaFalsa();
        }

        @Bean
        @Primary
        TranscritorFalso transcritorFalso() {
            return new TranscritorFalso();
        }
    }

    static class MidiaFalsa implements MidiaDoWhatsApp {
        volatile boolean configurado = true;
        volatile String modo = "OK";
        final AtomicInteger chamadas = new AtomicInteger();

        @Override
        public boolean configurado() {
            return configurado;
        }

        @Override
        public MidiaBaixada baixar(String midiaId) {
            chamadas.incrementAndGet();
            return switch (modo) {
                case "TRANSITORIA" -> throw new FalhaTransitoria("Meta fora do ar");
                case "PERMANENTE" -> throw new FalhaPermanente("metadado respondeu 404");
                case "PNG" -> new MidiaBaixada(png(), "audio/ogg");
                default -> new MidiaBaixada(ogg(), "audio/ogg; codecs=opus");
            };
        }
    }

    static class TranscritorFalso implements Transcritor {
        volatile boolean configurado = true;
        volatile String modo = "OK";
        final AtomicInteger chamadas = new AtomicInteger();
        volatile byte[] recebido;
        volatile TipoDeArquivo tipoRecebido;

        @Override
        public boolean configurado() {
            return configurado;
        }

        @Override
        public TextoTranscrito transcrever(byte[] audio, TipoDeArquivo tipo) {
            chamadas.incrementAndGet();
            recebido = audio;
            tipoRecebido = tipo;
            if ("TRANSITORIA".equals(modo)) {
                throw new FalhaTransitoria("provedor ocupado");
            }
            return new TextoTranscrito(TEXTO, "pt-BR", new BigDecimal("3.20"), "falso");
        }
    }

    static byte[] ogg() {
        byte[] b = new byte[256];
        System.arraycopy("OggS".getBytes(StandardCharsets.US_ASCII), 0, b, 0, 4);
        for (int i = 4; i < b.length; i++) {
            b[i] = (byte) i;
        }
        return b;
    }

    static byte[] png() {
        byte[] b = new byte[64];
        int[] assinatura = { 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
        for (int i = 0; i < assinatura.length; i++) {
            b[i] = (byte) assinatura[i];
        }
        return b;
    }

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private RegistrarCanalDoWhatsAppUseCase registrarCanal;
    @Autowired private ReceberLoteDoWhatsAppUseCase receber;
    @Autowired private ProcessadorDeAudios processador;
    @Autowired private TranscricoesJdbc transcricoes;
    @Autowired private MidiaFalsa midia;
    @Autowired private TranscritorFalso transcritor;
    @Autowired private Relogio relogio;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void zerarFalsos() {
        midia.configurado = true;
        midia.modo = "OK";
        midia.chamadas.set(0);
        transcritor.configurado = true;
        transcritor.modo = "OK";
        transcritor.chamadas.set(0);
        transcritor.recebido = null;
        transcritor.tipoRecebido = null;
    }

    private UUID salao(String numero) {
        UUID tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        TenantContext.executar(tenant,
                () -> registrarCanal.executar(numero, "5511999990000", true));
        return tenant;
    }

    private void entregar(String numero, String tipo, String wamid) {
        String mensagem = "audio".equals(tipo)
                ? """
                  {"from":"5511988887777","id":"%s","timestamp":"1757000000","type":"audio",
                   "audio":{"id":"MIDIA-%s","mime_type":"audio/ogg; codecs=opus"}}"""
                        .formatted(wamid, wamid.replace('.', '-'))
                : """
                  {"from":"5511988887777","id":"%s","timestamp":"1757000000","type":"text",
                   "text":{"body":"oi"}}""".formatted(wamid);
        receber.executar(json.readTree("""
                {"entry":[{"changes":[{"field":"messages","value":{
                  "metadata":{"phone_number_id":"%s"},"messages":[%s]}}]}]}
                """.formatted(numero, mensagem)));
    }

    private UUID mensagem(UUID tenant, String wamid) throws SQLException {
        var conexao = comoApp(tenant);
        try (var ps = conexao.prepareStatement(
                "select id from mensagem_recebida where provedor_id = ?")) {
            ps.setString(1, wamid);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1, UUID.class) : null;
            }
        } finally {
            conexao.rollback();
        }
    }

    /** A linha da fila, lida como a aplicação a vê. Vazia se não há linha. */
    private Map<String, Object> fila(UUID tenant, UUID mensagemId) throws SQLException {
        var linha = new HashMap<String, Object>();
        var conexao = comoApp(tenant);
        try (var ps = conexao.prepareStatement("""
                select status, tentativas, arquivo_id, texto, ultimo_erro, reservada_ate,
                       proxima_tentativa_em > now() as no_futuro
                  from transcricao_de_audio where mensagem_id = ?
                """)) {
            ps.setObject(1, mensagemId);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    linha.put("status", rs.getString("status"));
                    linha.put("tentativas", rs.getInt("tentativas"));
                    linha.put("arquivo_id", rs.getObject("arquivo_id", UUID.class));
                    linha.put("texto", rs.getString("texto"));
                    linha.put("ultimo_erro", rs.getString("ultimo_erro"));
                    linha.put("reservada_ate", rs.getTimestamp("reservada_ate"));
                    linha.put("no_futuro", rs.getBoolean("no_futuro"));
                }
            }
        } finally {
            conexao.rollback();
        }
        return linha;
    }

    /** Entrega um áudio e espera o listener assíncrono enfileirá-lo. */
    private UUID audioNaFila(UUID tenant, String wamid) throws SQLException {
        entregar(NUMERO, "audio", wamid);
        aguardar(() -> {
            try {
                UUID m = mensagem(tenant, wamid);
                return m != null && !fila(tenant, m).isEmpty();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }, "o áudio não entrou na fila", 15);
        return mensagem(tenant, wamid);
    }

    private void comoOwnerSql(String sql, UUID mensagemId) throws SQLException {
        try (var ps = comoOwner().prepareStatement(sql)) {
            ps.setObject(1, mensagemId);
            ps.executeUpdate();
        }
    }

    private long publicacoesConcluidasDoEnfileirador() throws SQLException {
        try (var st = comoOwner().createStatement();
             var rs = st.executeQuery("""
                     select (select count(*) from event_publication_archive
                              where listener_id like '%EnfileiradorDeAudios%')
                          + (select count(*) from event_publication
                              where listener_id like '%EnfileiradorDeAudios%'
                                and completion_date is not null)
                     """)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ------------------------------------------------------------------ outbox e fila

    @Test
    @DisplayName("o áudio entra na fila PELO OUTBOX — a pendência de RT-CNV-001 fecha aqui")
    void audio_entra_pelo_outbox() throws SQLException {
        /*
         * RT-CNV-001 registrou que `MensagemDeClienteRecebida` não estava no outbox: o Modulith grava
         * uma publicação por LISTENER, e não havia nenhum. Com o enfileirador, a publicação existe —
         * e é ela que o ReenviadorDeEventos repete se o listener falhar.
         */
        UUID tenant = salao(NUMERO);
        long antes = publicacoesConcluidasDoEnfileirador();

        UUID mensagem = audioNaFila(tenant, "wamid.A1");

        assertThat(fila(tenant, mensagem))
                .containsEntry("status", "PENDENTE")
                .containsEntry("tentativas", 0);
        aguardar(() -> {
            try {
                return publicacoesConcluidasDoEnfileirador() > antes;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }, "a publicação do enfileirador não chegou ao outbox", 15);
    }

    @Test
    @DisplayName("texto não entra na fila de áudio")
    void texto_nao_entra() throws SQLException {
        UUID tenant = salao(NUMERO);
        long antes = publicacoesConcluidasDoEnfileirador();

        entregar(NUMERO, "text", "wamid.T1");
        UUID audio = audioNaFila(tenant, "wamid.A1");
        // Espera as DUAS publicações terminarem, para a ausência abaixo não ser só atraso.
        aguardar(() -> {
            try {
                return publicacoesConcluidasDoEnfileirador() >= antes + 2;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }, "o enfileirador não consumiu os dois avisos", 15);

        assertThat(fila(tenant, mensagem(tenant, "wamid.T1"))).isEmpty();
        assertThat(fila(tenant, audio)).containsEntry("status", "PENDENTE");
    }

    @Test
    @DisplayName("aviso repetido não duplica a fila: ao menos uma vez é inofensivo")
    void aviso_repetido() throws SQLException {
        UUID tenant = salao(NUMERO);
        UUID mensagem = audioNaFila(tenant, "wamid.A1");

        boolean entrouDeNovo = TenantContext.obter(tenant,
                () -> transcricoes.enfileirar(tenant, mensagem, relogio.agora()));

        assertThat(entrouDeNovo).isFalse();
    }

    // ------------------------------------------------------------------ as duas etapas

    @Test
    @DisplayName("baixa, guarda como arquivo validado, e só depois transcreve")
    void caminho_completo() throws SQLException {
        UUID tenant = salao(NUMERO);
        UUID mensagem = audioNaFila(tenant, "wamid.A1");

        transcritor.configurado = false;
        processador.executar();

        var baixada = fila(tenant, mensagem);
        assertThat(baixada).containsEntry("status", "BAIXADA").containsEntry("tentativas", 0);
        UUID arquivo = (UUID) baixada.get("arquivo_id");
        assertThat(arquivo).isNotNull();
        try (var ps = comoOwner().prepareStatement(
                "select status, tipo, dono_tipo, dono_id from arquivo where id = ?")) {
            ps.setObject(1, arquivo);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("VALIDADO");
                assertThat(rs.getString("tipo")).as("detectado nos bytes").isEqualTo("OGG");
                assertThat(rs.getString("dono_tipo")).isEqualTo("MENSAGEM");
                assertThat(rs.getObject("dono_id", UUID.class)).isEqualTo(mensagem);
            }
        }

        transcritor.configurado = true;
        processador.executar();

        assertThat(fila(tenant, mensagem))
                .containsEntry("status", "CONCLUIDA")
                .containsEntry("texto", TEXTO);
        assertThat(transcritor.recebido).isEqualTo(ogg());
        assertThat(transcritor.tipoRecebido).isEqualTo(TipoDeArquivo.OGG);
    }

    @Test
    @DisplayName("sem token da Meta, o áudio espera — sem gastar tentativa")
    void sem_token_espera() throws SQLException {
        UUID tenant = salao(NUMERO);
        UUID mensagem = audioNaFila(tenant, "wamid.A1");
        midia.configurado = false;

        processador.executar();

        assertThat(fila(tenant, mensagem))
                .containsEntry("status", "PENDENTE")
                .containsEntry("tentativas", 0);
        assertThat(midia.chamadas).hasValue(0);
    }

    // ------------------------------------------------------------------ falhas

    @Test
    @DisplayName("falha transitória volta para a fila com espera, e solta a reserva")
    void transitoria_adia() throws SQLException {
        UUID tenant = salao(NUMERO);
        UUID mensagem = audioNaFila(tenant, "wamid.A1");
        midia.modo = "TRANSITORIA";

        processador.executar();

        assertThat(fila(tenant, mensagem))
                .containsEntry("status", "PENDENTE")
                .containsEntry("tentativas", 1)
                .containsEntry("ultimo_erro", "Meta fora do ar")
                .containsEntry("no_futuro", true)
                .containsEntry("reservada_ate", null);
    }

    @Test
    @DisplayName("na quinta tentativa desiste: insistir não é resiliência, é custo")
    void desiste_na_quinta() throws SQLException {
        UUID tenant = salao(NUMERO);
        UUID mensagem = audioNaFila(tenant, "wamid.A1");
        comoOwnerSql("update transcricao_de_audio set tentativas = 4 where mensagem_id = ?",
                mensagem);
        midia.modo = "TRANSITORIA";

        processador.executar();

        assertThat(fila(tenant, mensagem))
                .containsEntry("status", "FALHOU")
                .containsEntry("tentativas", 5);
    }

    @Test
    @DisplayName("mídia que não existe mais falha de vez, na primeira")
    void permanente_falha_de_vez() throws SQLException {
        UUID tenant = salao(NUMERO);
        UUID mensagem = audioNaFila(tenant, "wamid.A1");
        midia.modo = "PERMANENTE";

        processador.executar();

        assertThat(fila(tenant, mensagem))
                .containsEntry("status", "FALHOU")
                .containsEntry("tentativas", 1);
    }

    @Test
    @DisplayName("bytes que não são áudio: recusados E registrados — a recusa não é desfeita")
    void bytes_que_nao_sao_audio() throws SQLException {
        /*
         * A Meta declarou audio/ogg e os bytes são PNG. Quem decide são os bytes. E a lição de
         * RN-ARQ-003: lançar de dentro da transação desfaria o registro da recusa — então a linha
         * FALHOU e o arquivo RECUSADO saem no mesmo commit, sem exceção.
         */
        UUID tenant = salao(NUMERO);
        UUID mensagem = audioNaFila(tenant, "wamid.A1");
        midia.modo = "PNG";

        processador.executar();

        var linha = fila(tenant, mensagem);
        assertThat(linha).containsEntry("status", "FALHOU");
        assertThat((String) linha.get("ultimo_erro")).contains("recusado");
        try (var ps = comoOwner().prepareStatement(
                "select status, count(*) from arquivo where dono_id = ? group by status")) {
            ps.setObject(1, mensagem);
            var porStatus = new HashMap<String, Long>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    porStatus.put(rs.getString(1), rs.getLong(2));
                }
            }
            assertThat(porStatus).containsEntry("RECUSADO", 1L).doesNotContainKey("VALIDADO");
        }
    }

    @Test
    @DisplayName("a retentativa do STT usa a cópia guardada: a Meta não é chamada de novo")
    void retentativa_usa_a_copia() throws SQLException {
        UUID tenant = salao(NUMERO);
        UUID mensagem = audioNaFila(tenant, "wamid.A1");
        transcritor.modo = "TRANSITORIA";

        processador.executar();
        assertThat(fila(tenant, mensagem))
                .containsEntry("status", "BAIXADA")
                .containsEntry("tentativas", 1);

        comoOwnerSql("update transcricao_de_audio set proxima_tentativa_em = now() - interval "
                + "'1 minute' where mensagem_id = ?", mensagem);
        transcritor.modo = "OK";
        processador.executar();

        assertThat(fila(tenant, mensagem)).containsEntry("status", "CONCLUIDA");
        assertThat(midia.chamadas).as("baixou uma vez só").hasValue(1);
    }

    // ------------------------------------------------------------------ reserva

    @Test
    @DisplayName("linha reservada por outra instância não é tocada; reserva vencida é retomada")
    void reserva() throws SQLException {
        UUID tenant = salao(NUMERO);
        UUID mensagem = audioNaFila(tenant, "wamid.A1");
        transcritor.configurado = false;

        comoOwnerSql("update transcricao_de_audio set reservada_ate = now() + interval '5 minutes' "
                + "where mensagem_id = ?", mensagem);
        processador.executar();
        assertThat(midia.chamadas).as("outra instância está com ela").hasValue(0);
        assertThat(fila(tenant, mensagem)).containsEntry("tentativas", 0);

        // A instância morreu: a reserva vence e a linha volta sozinha.
        comoOwnerSql("update transcricao_de_audio set reservada_ate = now() - interval '1 minute' "
                + "where mensagem_id = ?", mensagem);
        processador.executar();
        assertThat(midia.chamadas).hasValue(1);
        assertThat(fila(tenant, mensagem)).containsEntry("status", "BAIXADA");
    }

    // ------------------------------------------------------------------ o banco garante

    @Test
    @DisplayName("transcrição concluída não é reescrita")
    void concluida_nao_e_reescrita() throws SQLException {
        UUID tenant = salao(NUMERO);
        UUID mensagem = audioNaFila(tenant, "wamid.A1");
        processador.executar();
        assertThat(fila(tenant, mensagem)).containsEntry("status", "CONCLUIDA");
        UUID id = TenantContext.obter(tenant, () -> transcricoes.porMensagem(mensagem))
                .orElseThrow().id();

        int alteradas = TenantContext.obter(tenant, () -> transcricoes.concluir(id,
                new Transcritor.TextoTranscrito("quero marcar QUINTA", "pt-BR", null, "outro"),
                relogio.agora()));

        assertThat(alteradas).isZero();
        assertThat(fila(tenant, mensagem)).containsEntry("texto", TEXTO);
    }

    @Test
    @DisplayName("a fila é isolada por salão")
    void isolamento() throws SQLException {
        UUID meu = salao(NUMERO);
        UUID outro = salao("4444444444");
        audioNaFila(meu, "wamid.A1");

        var conexao = comoApp(outro);
        try (var st = conexao.createStatement();
             var rs = st.executeQuery("select count(*) from transcricao_de_audio")) {
            rs.next();
            assertThat(rs.getLong(1)).isZero();
        } finally {
            conexao.rollback();
        }
    }

    @Test
    @DisplayName("a aplicação não apaga transcrição")
    void nao_apaga() throws SQLException {
        UUID tenant = salao(NUMERO);
        audioNaFila(tenant, "wamid.A1");

        var conexao = comoApp(tenant);
        try (var st = conexao.createStatement()) {
            Throwable erro = catchThrowable(() -> st.executeUpdate(
                    "delete from transcricao_de_audio"));
            assertThat(erro).isNotNull();
            assertThat(erro.getMessage()).containsIgnoringCase("permission denied");
        } finally {
            conexao.rollback();
        }
    }
}
