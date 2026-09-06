package br.com.salao.atendimento.api;

/**
 * RT-ATD-001 — como a comanda começou.
 *
 * <p>É informação de relatório, não vínculo: a comanda aberta pelo agendamento das 9h recebe também
 * o serviço marcado para as 14h, porque é uma conta só. Qual item veio de qual agendamento é do
 * item (RT-ATD-002).
 *
 * <p>A pergunta que ela responde é do dono: <em>quanto do faturamento entra sem hora marcada?</em>
 */
public enum OrigemDaComanda {

    /** Alguém chegou com horário marcado. */
    AGENDAMENTO,

    /** Alguém chegou sem horário — ou comprou um produto no balcão. */
    AVULSA
}
