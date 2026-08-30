package br.com.salao.cliente.internal.domain;

import java.util.Objects;

/**
 * RT-CLI-001 / R-UX-15 — telefone normalizado.
 *
 * <h2>Por que isto merece um tipo próprio</h2>
 *
 * <p>Telefone é a maior fonte de erro de cadastro num salão, e o motivo é simples: quem digita
 * está com o telefone na orelha e a cliente na frente. O mesmo número chega como
 * {@code (11) 98765-4321}, {@code 11987654321}, {@code +55 11 98765-4321} e {@code 98765-4321} —
 * e se cada um virar uma linha diferente, a busca por telefone (R-UX-03) deixa de funcionar
 * exatamente para quem mais precisa dela.
 *
 * <p>Guardado em E.164 sem o {@code +}: só dígitos. Formatar na gravação faria a busca por sufixo
 * depender de como cada pessoa digitou a máscara.
 *
 * <h2>O que é aceito, e por quê</h2>
 *
 * <ul>
 *   <li><b>Sem DDD</b> — quem atende anota "98765-4321"; o DDD é óbvio para quem está ali.
 *       Completa-se com o do estabelecimento. Exigi-lo é fricção que faz inventar dado;
 *   <li><b>Com máscara, espaço, ponto ou traço</b> — colar de uma agenda é o caminho normal;
 *   <li><b>Com {@code +55} ou {@code 0055}</b> — o mesmo número, escrito por quem já sabe.
 * </ul>
 *
 * <p>O nono dígito NÃO é adicionado automaticamente. Um fixo de oito dígitos é válido, e adivinhar
 * transformaria um número certo em errado sem ninguém perceber — pior que recusar.
 */
public record Telefone(String e164) {

    /** Fixo com DDD: 10 dígitos. Celular com DDD: 11. */
    private static final int MINIMO_COM_DDD = 10;
    private static final int MAXIMO_COM_DDD = 11;
    /** Sem DDD: 8 (fixo) ou 9 (celular). */
    private static final int MINIMO_LOCAL = 8;
    private static final int MAXIMO_LOCAL = 9;

    public Telefone {
        Objects.requireNonNull(e164, "telefone não pode ser nulo");
    }

    /**
     * @param informado  o que a pessoa digitou, em qualquer formato
     * @param paisPadrao código do país do estabelecimento, sem {@code +} (ex.: {@code 55})
     * @param dddPadrao  DDD do estabelecimento, ou {@code null} se não configurado
     */
    public static Telefone de(String informado, String paisPadrao, String dddPadrao) {
        if (informado == null || informado.isBlank()) {
            throw new TelefoneInvalidoException("telefone vazio");
        }

        String digitos = informado.replaceAll("\\D", "");
        if (digitos.isEmpty()) {
            throw new TelefoneInvalidoException("nenhum dígito em \"" + informado + "\"");
        }

        // "0055 11 ..." é como algumas agendas antigas gravam o discagem internacional.
        if (digitos.startsWith("00")) {
            digitos = digitos.substring(2);
        }

        String pais = paisPadrao == null || paisPadrao.isBlank() ? "55" : paisPadrao;

        // Já vem com o código do país e sobra número suficiente para ser DDD + assinante.
        if (digitos.startsWith(pais) && digitos.length() - pais.length() >= MINIMO_COM_DDD) {
            return validado(digitos, pais);
        }

        if (digitos.length() >= MINIMO_COM_DDD && digitos.length() <= MAXIMO_COM_DDD) {
            return validado(pais + digitos, pais);
        }

        if (digitos.length() >= MINIMO_LOCAL && digitos.length() <= MAXIMO_LOCAL) {
            if (dddPadrao == null || dddPadrao.isBlank()) {
                throw new TelefoneInvalidoException(
                        "número sem DDD e o estabelecimento não tem DDD padrão configurado");
            }
            return validado(pais + dddPadrao + digitos, pais);
        }

        throw new TelefoneInvalidoException(
                "número com " + digitos.length() + " dígitos não é um telefone válido");
    }

    private static Telefone validado(String completo, String pais) {
        int nacionais = completo.length() - pais.length();
        if (nacionais < MINIMO_COM_DDD || nacionais > MAXIMO_COM_DDD) {
            throw new TelefoneInvalidoException(
                    "número com " + nacionais + " dígitos após o país não é válido");
        }
        return new Telefone(completo);
    }

    /** Os últimos dígitos, que é como a recepção procura quando o telefone toca (R-UX-03). */
    public String ultimosQuatro() {
        return e164.substring(e164.length() - 4);
    }

    /** Formatação para exibição. Nunca para gravar — o banco guarda só dígitos. */
    public String formatado() {
        String nacional = e164.substring(2);
        if (nacional.length() == 11) {
            return "(" + nacional.substring(0, 2) + ") " + nacional.substring(2, 7)
                    + "-" + nacional.substring(7);
        }
        if (nacional.length() == 10) {
            return "(" + nacional.substring(0, 2) + ") " + nacional.substring(2, 6)
                    + "-" + nacional.substring(6);
        }
        return e164;
    }
}
