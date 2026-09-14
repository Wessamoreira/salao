package br.com.salao.conversacional.internal.domain;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Optional;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RT-CNV-005 — como a pessoa confirma o que o agente propôs, e como isso se reconhece sem o modelo.
 *
 * <h2>Reconhecido, nunca interpretado</h2>
 *
 * <p>"Confirmação humana explícita" não pode ser o modelo concluindo que a pessoa disse sim. O modelo
 * é tratado como comprometido (RN-CNV-010), e um "sim" deduzido por ele é exatamente o que um ataque
 * produziria. A mensagem de confirmação é reconhecida por esta classe — uma expressão regular sobre o
 * texto INTEIRO — antes de o modelo ser chamado, e nunca chega a ele.
 *
 * <h2>Por que um código, e não só "sim"</h2>
 *
 * <ul>
 *   <li>"sim" responde a qualquer pergunta. Se o agente perguntou "quer ver outro horário?", um "sim"
 *       não pode virar marcação;
 *   <li>o código amarra a resposta a UM resumo. "SIM 4821" confirma aquele texto, e não o que estiver
 *       pendente quando a mensagem for processada;
 *   <li>a mensagem inteira precisa ser a confirmação: "sim 4821 e cancela o resto" não é — vai para o
 *       modelo, que não tem como gravar nada.
 * </ul>
 *
 * <p>O código não é segredo: quem manda mensagem daquele telefone é a pessoa, atestada pela Meta
 * (RN-CNV-001, RN-IAM-019). Ele é vínculo e intenção, e quatro dígitos bastam.
 */
public final class CodigoDeConfirmacao {

    /** "SIM 4821", "sim, 4821.", "Sim4821" — e nada antes nem depois. */
    private static final Pattern CONFIRMACAO = Pattern.compile(
            "\\s*sim[\\s,.:;!-]*(\\d{4})[\\s.!]*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

    private CodigoDeConfirmacao() {
    }

    /** O código, quando a mensagem inteira é uma confirmação. */
    public static Optional<String> codigoEm(String mensagem) {
        if (mensagem == null) {
            return Optional.empty();
        }
        Matcher encontrado = CONFIRMACAO.matcher(mensagem);
        return encontrado.matches() ? Optional.of(encontrado.group(1)) : Optional.empty();
    }

    /**
     * Um código de quatro dígitos que não esteja em {@code evitar}.
     *
     * <p>{@code evitar} são os códigos recentes do mesmo telefone. Sem isso, a proposta nova poderia
     * sair com o código de uma antiga, e um "SIM" digitado olhando a mensagem velha confirmaria a nova.
     */
    public static String sortear(RandomGenerator aleatorio, Collection<String> evitar) {
        for (int tentativa = 0; tentativa < 1_000; tentativa++) {
            String codigo = "%04d".formatted(aleatorio.nextInt(10_000));
            if (!evitar.contains(codigo)) {
                return codigo;
            }
        }
        throw new IllegalStateException("nenhum código livre em mil sorteios");
    }

    /** O texto que a pessoa recebe. É do servidor — o modelo não escreve uma letra dele. */
    public static String pedido(String resumo, String codigo, LocalTime valeAte) {
        return pedido(resumo, codigo, valeAte, null, null);
    }

    /**
     * RT-CNV-006 — o mesmo pedido, dizendo que depois do SIM ainda falta alguém aceitar. Sem isso a
     * pessoa confirmaria achando que marcou.
     */
    public static String pedido(String resumo, String codigo, LocalTime valeAte, String aceiteDe,
                                String motivoDoAceite) {
        String pedido = """
                Confirma?
                %s
                Para confirmar, responda SIM %s até %s."""
                .formatted(resumo, codigo, HORA.format(valeAte));
        if (aceiteDe == null) {
            return pedido;
        }
        return pedido + "\nDepois do seu SIM, %s ainda precisa aceitar (%s)."
                .formatted(aceiteDe, motivoDoAceite);
    }
}
