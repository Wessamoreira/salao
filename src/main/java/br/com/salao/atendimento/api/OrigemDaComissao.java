package br.com.salao.atendimento.api;

/**
 * RT-ATD-002 — qual regra decidiu o percentual deste executante.
 *
 * <p>Existe para responder <em>"por que a minha comissão foi 35% e não 40%"</em> — a pergunta que
 * o profissional faz três meses depois, quando a regra já mudou. Sem o registro de qual nível
 * venceu, a única resposta possível é recalcular com a regra de hoje, que é justamente a que não
 * vale mais.
 *
 * <p>Hoje só há um nível. A cascata — padrão do salão, por serviço, e por profissional × serviço —
 * é RT-FIN-002, e é lá que este enum ganha os outros valores. Declarar agora os que ninguém
 * escreve seria documentar no código uma regra que não existe.
 */
public enum OrigemDaComissao {

    /** O percentual cadastrado na ficha do profissional. */
    PROFISSIONAL
}
