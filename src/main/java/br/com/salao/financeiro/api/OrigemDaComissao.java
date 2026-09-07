package br.com.salao.financeiro.api;

/**
 * RT-FIN-002 — qual nível decidiu o percentual.
 *
 * <p>Existe para responder <em>"por que a minha comissão foi 30% e não 40%"</em> — a pergunta que o
 * profissional faz três meses depois, quando a regra já mudou. Recalcular com a regra de hoje é
 * justamente o que não responde.
 *
 * <p>Nasceu em {@code atendimento} (RT-ATD-002) com dois valores, quando a cascata ainda não
 * existia. Mudou de módulo junto com a regra: quem decide o percentual é o {@code financeiro}, e o
 * enum pertence a quem decide.
 */
public enum OrigemDaComissao {

    /** Regra do par profissional × serviço — a exceção mais específica. */
    PROFISSIONAL_SERVICO,

    /** Regra do serviço, valendo para quem o executar. */
    SERVICO,

    /** O percentual da ficha do profissional. */
    PROFISSIONAL,

    /** O padrão da casa. */
    PADRAO_DO_SALAO,

    /**
     * Ninguém configurou nada, em nenhum nível.
     *
     * <p>O percentual é zero, e essa é a resposta honesta — mas é <strong>diferente</strong> de um
     * zero configurado, que teria origem {@code PROFISSIONAL} ou {@code PADRAO_DO_SALAO}. Sem esta
     * distinção, "por que não recebi comissão?" teria a mesma resposta para a decisão do salão e
     * para o cadastro esquecido.
     */
    SEM_REGRA,

    /**
     * RT-ATD-005 — alguém digitou o número na hora.
     *
     * <p>É o caso do assistente: o percentual dele não sai de cadastro nenhum, porque a pergunta 6
     * de {@code 13-perguntas-em-aberto} — <em>qual é o percentual usual?</em> — continua sem
     * resposta do dono.
     */
    INFORMADA_NO_ITEM
}
