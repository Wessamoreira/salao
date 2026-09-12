package br.com.salao.cliente.api;

/**
 * RT-CLI-004 — para que o dado da cliente é usado.
 *
 * <p>Cada finalidade carrega sua base legal, e é por isso que a base <strong>não</strong> é coluna
 * no banco: ela é a mesma para toda cliente do salão. Guardá-la por linha permitiria que duas
 * linhas discordassem sobre um fato único — e a errada seria descoberta num processo.
 *
 * <p>Toda finalidade nova precisa entrar também no {@code check} da migração; há teste provando
 * que os dois lados concordam, porque a divergência só apareceria como erro de escrita em
 * produção, no momento em que alguém tentasse registrar a decisão da cliente.
 */
public enum Finalidade {

    /** "Seu horário é amanhã às 14h." Faz parte do serviço que ela contratou. */
    LEMBRETE_DE_AGENDAMENTO(BaseLegal.EXECUCAO_DE_CONTRATO),

    /** Promoção, campanha, felicitação de aniversário. Nada disso ela pediu. */
    MARKETING(BaseLegal.CONSENTIMENTO),

    /**
     * "Como foi seu atendimento?"
     *
     * <p>Poderia ser sustentada por legítimo interesse (art. 7, IX), e não é — de propósito.
     * Legítimo interesse exige um teste documentado de necessidade e balanceamento que este
     * projeto não fez, e invocá-lo sem fazê-lo é uma base legal de fachada. Consentimento é a
     * escolha conservadora e defensável enquanto esse trabalho não existir.
     */
    PESQUISA_DE_SATISFACAO(BaseLegal.CONSENTIMENTO),

    /**
     * "Seu comprovante do atendimento de hoje." (RT-FIN-008)
     *
     * <p>Execução de contrato, e não consentimento: a cliente <strong>pediu</strong> o
     * comprovante do serviço que acabou de pagar. Exigir opt-in para entregar o recibo do que ela
     * comprou seria transformar a LGPD em obstáculo ao próprio contrato — e o efeito prático
     * seria a recepção mandando pelo WhatsApp pessoal, fora de qualquer registro.
     *
     * <p>O que continua valendo é o resto: sem telefone não sai, e a revogação total da cliente
     * (RT-CLI-005) alcança isto como alcança tudo.
     */
    COMPROVANTE_DE_ATENDIMENTO(BaseLegal.EXECUCAO_DE_CONTRATO);

    private final BaseLegal baseLegal;

    Finalidade(BaseLegal baseLegal) {
        this.baseLegal = baseLegal;
    }

    public BaseLegal baseLegal() {
        return baseLegal;
    }

    /** O que vale quando a cliente nunca se manifestou sobre esta finalidade. */
    public boolean permitidoSemRegistro() {
        return baseLegal.permitidoSemRegistro();
    }
}
