package br.com.salao.conversacional.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.conversacional.api.TipoDeMensagem;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** RT-CNV-001 — leitura do payload da Cloud API, com payloads reais. */
class CargaDoWhatsAppTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    @DisplayName("mensagem de texto sai com id, remetente, corpo e instante")
    void texto() {
        var carga = ler("""
                {"object":"whatsapp_business_account","entry":[{"id":"WABA1","changes":[{
                  "field":"messages","value":{
                    "messaging_product":"whatsapp",
                    "metadata":{"display_phone_number":"5511999990000",
                                "phone_number_id":"1234567890"},
                    "contacts":[{"profile":{"name":"Maria"},"wa_id":"5511988887777"}],
                    "messages":[{"from":"5511988887777","id":"wamid.AAA","timestamp":"1757000000",
                                 "type":"text","text":{"body":"quero marcar quarta 10h"}}]}}]}]}
                """);

        assertThat(carga).singleElement().satisfies(m -> {
            assertThat(m.phoneNumberId()).isEqualTo("1234567890");
            assertThat(m.provedorId()).isEqualTo("wamid.AAA");
            assertThat(m.deTelefone()).isEqualTo("5511988887777");
            assertThat(m.tipo()).isEqualTo(TipoDeMensagem.TEXTO);
            assertThat(m.texto()).isEqualTo("quero marcar quarta 10h");
            assertThat(m.midiaId()).isNull();
            assertThat(m.enviadaEm()).isEqualTo(Instant.ofEpochSecond(1757000000L));
        });
    }

    @Test
    @DisplayName("um POST pode trazer mensagens de SALÕES DIFERENTES")
    void lote_multi_tenant() {
        /*
         * A armadilha da rotina. A Meta agrupa por app, não por número: um app que serve dez
         * salões pode entregar, no mesmo POST, mensagem de três deles.
         *
         * Resolver o tenant uma vez a partir do primeiro entry — o jeito natural de escrever
         * isso — grava a mensagem de uma cliente na caixa de outro salão.
         */
        var carga = ler("""
                {"object":"whatsapp_business_account","entry":[
                  {"id":"WABA1","changes":[{"field":"messages","value":{
                    "metadata":{"phone_number_id":"NUMERO_DO_SALAO_A"},
                    "messages":[{"from":"5511900000001","id":"wamid.A","timestamp":"1757000000",
                                 "type":"text","text":{"body":"oi do salão A"}}]}}]},
                  {"id":"WABA2","changes":[{"field":"messages","value":{
                    "metadata":{"phone_number_id":"NUMERO_DO_SALAO_B"},
                    "messages":[{"from":"5511900000002","id":"wamid.B","timestamp":"1757000001",
                                 "type":"text","text":{"body":"oi do salão B"}}]}}]}]}
                """);

        assertThat(carga).hasSize(2);
        assertThat(carga).extracting(CargaDoWhatsApp.Entrante::phoneNumberId)
                .containsExactly("NUMERO_DO_SALAO_A", "NUMERO_DO_SALAO_B");
    }

    @Test
    @DisplayName("áudio vira o bilhete da mídia, não o áudio")
    void audio() {
        // Baixar aqui estouraria a janela de resposta da Meta. Guarda-se o id; o download é
        // RT-CNV-003.
        var carga = ler("""
                {"entry":[{"changes":[{"field":"messages","value":{
                  "metadata":{"phone_number_id":"N1"},
                  "messages":[{"from":"5511988887777","id":"wamid.AUD","timestamp":"1757000000",
                               "type":"audio","audio":{"id":"MIDIA123","voice":true,
                                                       "mime_type":"audio/ogg; codecs=opus"}}]}}]}]}
                """);

        assertThat(carga).singleElement().satisfies(m -> {
            assertThat(m.tipo()).isEqualTo(TipoDeMensagem.AUDIO);
            assertThat(m.midiaId()).isEqualTo("MIDIA123");
            assertThat(m.midiaMime()).isEqualTo("audio/ogg; codecs=opus");
            assertThat(m.texto()).isNull();
        });
    }

    @Test
    @DisplayName("recibo de entrega não é mensagem de ninguém")
    void statuses() {
        // A maioria dos webhooks é isto. Tratá-los como mensagem encheria a caixa de entrada de
        // linhas sem remetente.
        var carga = ler("""
                {"entry":[{"changes":[{"field":"messages","value":{
                  "metadata":{"phone_number_id":"N1"},
                  "statuses":[{"id":"wamid.OUT","status":"delivered","timestamp":"1757000000",
                               "recipient_id":"5511988887777"}]}}]}]}
                """);

        assertThat(carga).isEmpty();
    }

    @Test
    @DisplayName("assunto que não é `messages` é ignorado")
    void outro_field() {
        var carga = ler("""
                {"entry":[{"changes":[{"field":"message_template_status_update","value":{
                  "event":"APPROVED","message_template_name":"lembrete"}}]}]}
                """);

        assertThat(carga).isEmpty();
    }

    @Test
    @DisplayName("tipo não tratado é GUARDADO sem conteúdo, não descartado")
    void tipo_desconhecido() {
        // Descartar tornaria "a cliente mandou e ninguém respondeu" indistinguível de "a cliente
        // não mandou nada" — que é a primeira pergunta do suporte.
        var carga = ler("""
                {"entry":[{"changes":[{"field":"messages","value":{
                  "metadata":{"phone_number_id":"N1"},
                  "messages":[{"from":"5511988887777","id":"wamid.REACT","timestamp":"1757000000",
                               "type":"reaction","reaction":{"message_id":"wamid.X","emoji":"👍"}}]
                  }}]}]}
                """);

        assertThat(carga).singleElement().satisfies(m -> {
            assertThat(m.tipo()).isEqualTo(TipoDeMensagem.NAO_SUPORTADO);
            assertThat(m.tipoOriginal()).as("o log precisa dizer QUAL tipo novo apareceu")
                    .isEqualTo("reaction");
            assertThat(m.texto()).isNull();
            assertThat(m.midiaId()).isNull();
        });
    }

    @Test
    @DisplayName("uma mensagem estranha no lote não derruba as outras")
    void tolerancia() {
        // Recusar o lote inteiro faria a Meta reenviar as cinco para sempre por causa de uma.
        var carga = ler("""
                {"entry":[{"changes":[{"field":"messages","value":{
                  "metadata":{"phone_number_id":"N1"},
                  "messages":[
                    {"id":"wamid.SEM_FROM","timestamp":"1757000000","type":"text",
                     "text":{"body":"sem remetente"}},
                    {"from":"5511988887777","timestamp":"1757000000","type":"text",
                     "text":{"body":"sem id"}},
                    {"from":"5511988887777","id":"wamid.BOA","timestamp":"1757000000",
                     "type":"text","text":{"body":"esta presta"}}]}}]}]}
                """);

        assertThat(carga).extracting(CargaDoWhatsApp.Entrante::provedorId)
                .containsExactly("wamid.BOA");
    }

    @Test
    @DisplayName("texto sem corpo e mídia sem id caem para NAO_SUPORTADO, não passam pela metade")
    void incompletos() {
        // A constraint do banco recusa TEXTO com `texto` nulo e AUDIO sem `midia_id`. Traduzir
        // para NAO_SUPORTADO aqui é o que impede a falha de aparecer como violação de integridade.
        var carga = ler("""
                {"entry":[{"changes":[{"field":"messages","value":{
                  "metadata":{"phone_number_id":"N1"},
                  "messages":[
                    {"from":"5511900000001","id":"wamid.T","timestamp":"1","type":"text",
                     "text":{}},
                    {"from":"5511900000002","id":"wamid.A","timestamp":"1","type":"audio",
                     "audio":{"mime_type":"audio/ogg"}}]}}]}]}
                """);

        assertThat(carga).hasSize(2)
                .allSatisfy(m -> assertThat(m.tipo()).isEqualTo(TipoDeMensagem.NAO_SUPORTADO));
    }

    @Test
    @DisplayName("timestamp ilegível vira 1970, e não `agora`")
    void timestamp_torto() {
        // "Agora" mentiria que a mensagem acabou de ser enviada — e a coluna existe exatamente
        // para dizer quando ela foi. Um 1970 na tela é evidentemente defeito de origem.
        var carga = ler("""
                {"entry":[{"changes":[{"field":"messages","value":{
                  "metadata":{"phone_number_id":"N1"},
                  "messages":[{"from":"5511988887777","id":"wamid.X","timestamp":"ontem",
                               "type":"text","text":{"body":"oi"}}]}}]}]}
                """);

        assertThat(carga).singleElement()
                .extracting(CargaDoWhatsApp.Entrante::enviadaEm).isEqualTo(Instant.EPOCH);
    }

    @Test
    @DisplayName("payload sem número de destino é pulado: não há como saber de quem é")
    void sem_metadata() {
        var carga = ler("""
                {"entry":[{"changes":[{"field":"messages","value":{
                  "messages":[{"from":"5511988887777","id":"wamid.X","timestamp":"1",
                               "type":"text","text":{"body":"oi"}}]}}]}]}
                """);

        assertThat(carga).isEmpty();
    }

    @Test
    @DisplayName("payload vazio, nulo ou de outra forma não estoura")
    void payloads_degenerados() {
        assertThat(CargaDoWhatsApp.mensagens(null)).isEmpty();
        assertThat(ler("{}")).isEmpty();
        assertThat(ler("""
                {"entry":"nao-e-lista"}""")).isEmpty();
        assertThat(ler("[]")).isEmpty();
    }

    private List<CargaDoWhatsApp.Entrante> ler(String payload) {
        JsonNode raiz = json.readTree(payload);
        return CargaDoWhatsApp.mensagens(raiz);
    }
}
