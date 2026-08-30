package br.com.salao.cliente.internal.domain;

/**
 * RT-CLI-002 / R-UX-03 — o que a recepção digitou, interpretado.
 *
 * <h2>Por que a interpretação é do domínio</h2>
 *
 * <p>O caso mais comum do balcão é o telefone tocando: buscar por nome falha (oito Marias,
 * apelidos, grafia), e os quatro últimos dígitos resolvem na primeira tentativa. Mas ninguém vai
 * escolher num seletor se está buscando por nome ou por telefone — a pessoa só digita, e o sistema
 * precisa entender.
 *
 * <p>A regra é simples e não tem exceção prática: <strong>nome não tem dígito</strong>. Se veio
 * dígito, é telefone.
 */
public sealed interface TermoDeBusca {

    int MINIMO_DE_LETRAS = 2;
    int MINIMO_DE_DIGITOS = 3;

    /** Busca por parte do nome ou do apelido, sem acento e sem caixa. */
    record PorNome(String termo) implements TermoDeBusca {
    }

    /**
     * Busca pelo <strong>fim</strong> do telefone.
     *
     * <p>Sufixo, e não prefixo: o começo do número é o país e o DDD, iguais para quase todo mundo
     * do salão. Quem olha o identificador de chamadas lê os últimos dígitos.
     */
    record PorTelefone(String digitos) implements TermoDeBusca {
    }

    /**
     * Curto demais para valer a busca.
     *
     * <p>Não é erro: é o estado normal enquanto a pessoa ainda está digitando a primeira letra.
     * Devolver 422 aqui faria a tela piscar um erro a cada tecla. Quem chama devolve lista vazia,
     * e a tela explica o que falta.
     */
    record Insuficiente(String motivo) implements TermoDeBusca {
    }

    static TermoDeBusca de(String digitado) {
        if (digitado == null || digitado.isBlank()) {
            return new Insuficiente("digite para buscar");
        }

        String limpo = digitado.strip();
        String digitos = limpo.replaceAll("\\D", "");

        if (!digitos.isEmpty()) {
            // Qualquer dígito no termo significa telefone. "Maria 11" não é um nome.
            if (digitos.length() < MINIMO_DE_DIGITOS) {
                return new Insuficiente("informe ao menos " + MINIMO_DE_DIGITOS + " dígitos");
            }
            return new PorTelefone(digitos);
        }

        if (limpo.length() < MINIMO_DE_LETRAS) {
            return new Insuficiente("informe ao menos " + MINIMO_DE_LETRAS + " letras");
        }
        return new PorNome(limpo);
    }
}
