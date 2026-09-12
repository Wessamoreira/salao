package br.com.salao.conversacional.internal.domain;

import br.com.salao.conversacional.api.TipoDeMensagem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * RT-CNV-001 — leitura do payload da Cloud API.
 *
 * <h2>A armadilha: um POST NÃO é de um salão</h2>
 *
 * <p>A Meta agrupa. Um único POST traz {@code entry[]}, cada entry traz {@code changes[]}, e cada
 * change carrega o seu próprio {@code metadata.phone_number_id}. Com um app servindo vários
 * salões, <strong>um POST pode conter mensagens de estabelecimentos diferentes</strong>.
 *
 * <p>Resolver o tenant uma vez, a partir do primeiro entry, e usá-lo para o lote inteiro é o erro
 * natural aqui — e ele grava a mensagem de uma cliente na caixa de outro salão. Por isso cada
 * mensagem sai daqui carregando o {@code phoneNumberId} de onde veio, e o roteamento é por
 * mensagem.
 *
 * <h2>Tolerante por obrigação</h2>
 *
 * <p>A forma do payload é da Meta e muda sem aviso. Campo que falta faz a mensagem ser pulada, não
 * a requisição falhar: um lote de cinco em que uma veio estranha tem de entregar as outras quatro.
 * Recusar o lote inteiro faria a Meta reenviar as cinco para sempre.
 *
 * <p>Esta classe é pura — recebe {@link JsonNode}, devolve registros, não toca banco nem log. É o
 * que a torna testável com um payload real colado no teste.
 */
public final class CargaDoWhatsApp {

    private CargaDoWhatsApp() {
    }

    /**
     * Uma mensagem, já traduzida, ainda sem tenant.
     *
     * @param tipoOriginal o {@code type} literal da Meta. Serve para o log dizer <em>qual</em>
     *                     tipo novo apareceu; não é persistido, porque a coluna guarda o tipo
     *                     traduzido e um texto livre de terceiro na tabela seria dado sem contrato
     */
    public record Entrante(
            String phoneNumberId,
            String provedorId,
            String deTelefone,
            TipoDeMensagem tipo,
            String tipoOriginal,
            String texto,
            String midiaId,
            String midiaMime,
            Instant enviadaEm) {
    }

    /**
     * @param raiz o corpo já desserializado
     * @return as mensagens do lote, na ordem em que vieram; vazio quando o payload não traz
     *         mensagem nenhuma — o que é o caso comum, porque a maior parte dos webhooks é recibo
     *         de entrega (ver {@code statuses})
     */
    public static List<Entrante> mensagens(JsonNode raiz) {
        var encontradas = new ArrayList<Entrante>();
        if (raiz == null || !raiz.isObject()) {
            return encontradas;
        }
        for (JsonNode entry : raiz.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                // `field` diz o assunto. Hoje só `messages` interessa; `message_template_status`
                // e outros chegam no mesmo webhook e não são mensagem de ninguém.
                if (!"messages".equals(texto(change.path("field")))) {
                    continue;
                }
                JsonNode valor = change.path("value");
                String numero = texto(valor.path("metadata").path("phone_number_id"));
                if (numero == null) {
                    // Sem o número não há como saber de quem é. Pular é a única saída honesta.
                    continue;
                }
                for (JsonNode mensagem : valor.path("messages")) {
                    Entrante entrante = traduzir(numero, mensagem);
                    if (entrante != null) {
                        encontradas.add(entrante);
                    }
                }
            }
        }
        return encontradas;
    }

    private static Entrante traduzir(String phoneNumberId, JsonNode mensagem) {
        String id = texto(mensagem.path("id"));
        String de = texto(mensagem.path("from"));
        // Sem id não há idempotência, e sem remetente não há a quem responder. Os dois são
        // obrigatórios na Cloud API; faltar um significa payload que não entendemos.
        if (id == null || de == null) {
            return null;
        }
        String tipoOriginal = texto(mensagem.path("type"));
        Instant enviadaEm = instante(texto(mensagem.path("timestamp")));

        return switch (tipoOriginal == null ? "" : tipoOriginal) {
            case "text" -> comTexto(phoneNumberId, id, de, tipoOriginal, enviadaEm,
                    texto(mensagem.path("text").path("body")));
            case "audio" -> comMidia(phoneNumberId, id, de, TipoDeMensagem.AUDIO, tipoOriginal,
                    enviadaEm, mensagem.path("audio"));
            case "image" -> comMidia(phoneNumberId, id, de, TipoDeMensagem.IMAGEM, tipoOriginal,
                    enviadaEm, mensagem.path("image"));
            case "document" -> comMidia(phoneNumberId, id, de, TipoDeMensagem.DOCUMENTO,
                    tipoOriginal, enviadaEm, mensagem.path("document"));
            default -> naoSuportada(phoneNumberId, id, de, tipoOriginal, enviadaEm);
        };
    }

    private static Entrante comTexto(String numero, String id, String de, String tipoOriginal,
                                     Instant enviadaEm, String corpo) {
        // Texto vazio não existe no WhatsApp, mas um payload sem `body` existiria. Sem corpo a
        // mensagem não é texto — e a constraint do banco recusaria TEXTO com `texto` nulo.
        if (corpo == null || corpo.isBlank()) {
            return naoSuportada(numero, id, de, tipoOriginal, enviadaEm);
        }
        return new Entrante(numero, id, de, TipoDeMensagem.TEXTO, tipoOriginal,
                corpo, null, null, enviadaEm);
    }

    private static Entrante comMidia(String numero, String id, String de, TipoDeMensagem tipo,
                                     String tipoOriginal, Instant enviadaEm, JsonNode midia) {
        String midiaId = texto(midia.path("id"));
        if (midiaId == null) {
            return naoSuportada(numero, id, de, tipoOriginal, enviadaEm);
        }
        return new Entrante(numero, id, de, tipo, tipoOriginal,
                null, midiaId, texto(midia.path("mime_type")), enviadaEm);
    }

    private static Entrante naoSuportada(String numero, String id, String de, String tipoOriginal,
                                         Instant enviadaEm) {
        return new Entrante(numero, id, de, TipoDeMensagem.NAO_SUPORTADO, tipoOriginal,
                null, null, null, enviadaEm);
    }

    /** Texto de um nó, ou {@code null} — que é diferente de string vazia e de nó ausente. */
    private static String texto(JsonNode no) {
        if (no == null || !no.isValueNode()) {
            return null;
        }
        String valor = no.asString();
        return valor == null || valor.isBlank() ? null : valor;
    }

    /**
     * O {@code timestamp} da Meta vem em segundos do epoch, como STRING.
     *
     * <p>Ausente ou ilegível vira o instante zero do epoch, e não "agora": "agora" mentiria que a
     * mensagem acabou de ser enviada, e a coluna existe exatamente para dizer quando ela foi. Um
     * 1970 na tela é evidentemente um defeito de origem; um "agora" errado não é evidente nenhum.
     */
    private static Instant instante(String segundos) {
        if (segundos == null) {
            return Instant.EPOCH;
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(segundos.trim()));
        } catch (NumberFormatException e) {
            return Instant.EPOCH;
        }
    }
}
