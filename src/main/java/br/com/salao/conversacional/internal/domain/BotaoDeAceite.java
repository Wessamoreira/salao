package br.com.salao.conversacional.internal.domain;

import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RT-CNV-006 — o id do botão "Aceitar" / "Recusar", e como ele se reconhece na volta.
 *
 * <h2>O id carrega a intenção, um token e a decisão</h2>
 *
 * <p>{@code aceite:<intenção>:<token>:sim}. O botão é mandado por nós e volta no webhook, assinado
 * pela Meta — mas o id é lido como entrada não confiável mesmo assim: formato conferido, tamanho com
 * teto, token comparado em tempo constante.
 *
 * <p>O token não é o que autentica: quem autentica é o NÚMERO de onde veio o toque, que precisa ser o
 * número para onde o pedido foi mandado (RN-CNV-019). O token existe para que um id de botão
 * copiado — de um print, de um encaminhamento — não sirva sozinho, e para que a intenção de um pedido
 * nunca seja adivinhável pelo id.
 *
 * <h2>Reconhecido, nunca interpretado</h2>
 *
 * <p>Como o código de confirmação (RN-CNV-015): o toque não passa pelo modelo, e nada no texto de
 * quem tocou é lido — o botão não tem texto.
 */
public record BotaoDeAceite(UUID intencaoId, String token, boolean aceita) {

    public static final String ACEITAR = "Aceitar";
    public static final String RECUSAR = "Recusar";

    private static final Pattern FORMATO = Pattern.compile(
            "aceite:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}):"
                    + "([A-Za-z0-9_-]{16,64}):(sim|nao)");

    /** 18 bytes aleatórios: 144 bits, em 24 caracteres. */
    private static final int BYTES_DO_TOKEN = 18;

    public BotaoDeAceite {
        if (intencaoId == null || token == null) {
            throw new IllegalArgumentException("botão de aceite precisa de intenção e token");
        }
    }

    /** O botão, quando o id é um botão de aceite bem formado. */
    public static Optional<BotaoDeAceite> de(String idDoBotao) {
        if (idDoBotao == null) {
            return Optional.empty();
        }
        Matcher encontrado = FORMATO.matcher(idDoBotao);
        if (!encontrado.matches()) {
            return Optional.empty();
        }
        return Optional.of(new BotaoDeAceite(UUID.fromString(encontrado.group(1)),
                encontrado.group(2), "sim".equals(encontrado.group(3))));
    }

    public static String novoToken(RandomGenerator aleatorio) {
        byte[] bytes = new byte[BYTES_DO_TOKEN];
        aleatorio.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String idDoBotao() {
        return "aceite:" + intencaoId + ":" + token + ":" + (aceita ? "sim" : "nao");
    }
}
