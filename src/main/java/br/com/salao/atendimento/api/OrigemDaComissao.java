package br.com.salao.atendimento.api;

/**
 * RT-ATD-002 — qual regra decidiu o percentual deste executante.
 *
 * <p>Existe para responder <em>"por que a minha comissão foi 35% e não 40%"</em> — a pergunta que
 * o profissional faz três meses depois, quando a regra já mudou. Sem o registro de qual nível
 * venceu, a única resposta possível é recalcular com a regra de hoje, que é justamente a que não
 * vale mais.
 *
 * <p>A cascata — padrão do salão, por serviço, e por profissional × serviço — é RT-FIN-002, e é lá
 * que este enum ganha os níveis que faltam. Declarar agora os que ninguém escreve seria documentar
 * no código uma regra que não existe.
 */
public enum OrigemDaComissao {

    /** O percentual cadastrado na ficha do profissional. */
    PROFISSIONAL,

    /**
     * RT-ATD-005 — alguém digitou o número na hora.
     *
     * <p>É o caso do assistente, e é honesto que apareça como origem própria: o percentual dele
     * não sai de cadastro nenhum, porque a pergunta 6 de {@code 13-perguntas-em-aberto} —
     * <em>qual é o percentual usual do assistente?</em> — continua sem resposta do dono.
     *
     * <p>Enquanto ela não tiver, quem responde é quem está no balcão. Gravar isso como
     * {@code PROFISSIONAL} seria dizer que veio da ficha, e três meses depois ninguém saberia por
     * que aquele número era aquele.
     */
    INFORMADA_NO_ITEM
}
