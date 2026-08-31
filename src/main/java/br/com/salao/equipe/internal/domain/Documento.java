package br.com.salao.equipe.internal.domain;

/**
 * RT-EQP-001 — CPF ou CNPJ, com dígito verificador conferido.
 *
 * <h2>Por que conferir o dígito, e não só o tamanho</h2>
 *
 * <p>Documento errado só aparece quando alguém precisa dele: na hora de emitir a nota, de pagar a
 * comissão, de responder a uma fiscalização. Aí já está no cadastro há meses, o profissional não
 * lembra de ter digitado, e ninguém sabe qual dos dois números é o certo.
 *
 * <p>Conferir na entrada custa vinte linhas e pega o erro enquanto a pessoa ainda está na frente.
 *
 * <h2>O caso que o algoritmo sozinho não pega</h2>
 *
 * <p>{@code 111.111.111-11} <strong>passa</strong> no cálculo do dígito verificador — e em todos
 * os outros repetidos. É o valor que aparece quando alguém precisa preencher o campo e não tem o
 * documento em mãos, e é justamente por isso que precisa ser recusado explicitamente.
 *
 * <p>Guardado só com dígitos, como o telefone (RN-CLI-001): máscara é assunto de quem exibe.
 */
public record Documento(String digitos, Tipo tipo) {

    public enum Tipo {
        CPF(11),
        CNPJ(14);

        private final int tamanho;

        Tipo(int tamanho) {
            this.tamanho = tamanho;
        }

        public int tamanho() {
            return tamanho;
        }
    }

    /** Aceita com ou sem máscara; decide o tipo pelo tamanho. */
    public static Documento de(String informado) {
        if (informado == null || informado.isBlank()) {
            throw new DocumentoInvalidoException("documento vazio");
        }
        String digitos = informado.replaceAll("\\D", "");

        Tipo tipo = switch (digitos.length()) {
            case 11 -> Tipo.CPF;
            case 14 -> Tipo.CNPJ;
            default -> throw new DocumentoInvalidoException(
                    "documento com " + digitos.length() + " dígitos não é CPF nem CNPJ");
        };

        if (todosIguais(digitos)) {
            // Passa no cálculo do dígito e não existe. É o que se digita quando não se tem o
            // documento em mãos, e aceitar isso enche o cadastro de números que ninguém pode usar.
            throw new DocumentoInvalidoException("documento com todos os dígitos iguais");
        }
        if (!digitosConferem(digitos, tipo)) {
            throw new DocumentoInvalidoException(
                    tipo + " com dígito verificador inválido");
        }
        return new Documento(digitos, tipo);
    }

    private static boolean todosIguais(String digitos) {
        return digitos.chars().distinct().count() == 1;
    }

    /**
     * Módulo 11 sobre os dois dígitos finais.
     *
     * <p>Os dois documentos percorrem os dígitos <strong>da direita para a esquerda</strong> com
     * peso começando em 2. A única diferença: o CPF deixa o peso crescer (2, 3, … 11), e o CNPJ
     * volta a 2 depois do 9.
     *
     * <p>A primeira versão aplicava o peso do CPF na direção contrária — 10 no dígito mais à
     * direita em vez do mais à esquerda. Nenhum CPF válido passava, e foi o teste com números
     * sabidamente válidos que mostrou. Um teste que só verificasse "recusa lixo" teria passado
     * com o algoritmo invertido.
     */
    private static boolean digitosConferem(String digitos, Tipo tipo) {
        int base = tipo.tamanho() - 2;
        for (int posicao = base; posicao < tipo.tamanho(); posicao++) {
            if (Character.getNumericValue(digitos.charAt(posicao))
                    != digitoVerificador(digitos, posicao, tipo)) {
                return false;
            }
        }
        return true;
    }

    private static int digitoVerificador(String digitos, int posicao, Tipo tipo) {
        int soma = 0;
        int peso = 2;
        for (int i = posicao - 1; i >= 0; i--) {
            soma += Character.getNumericValue(digitos.charAt(i)) * peso;
            peso = tipo == Tipo.CNPJ && peso == 9 ? 2 : peso + 1;
        }
        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }

    public boolean ehPessoaJuridica() {
        return tipo == Tipo.CNPJ;
    }

    /** Máscara para exibição. Nunca para gravar. */
    public String formatado() {
        if (tipo == Tipo.CPF) {
            return digitos.substring(0, 3) + "." + digitos.substring(3, 6) + "."
                    + digitos.substring(6, 9) + "-" + digitos.substring(9);
        }
        return digitos.substring(0, 2) + "." + digitos.substring(2, 5) + "."
                + digitos.substring(5, 8) + "/" + digitos.substring(8, 12) + "-"
                + digitos.substring(12);
    }
}
