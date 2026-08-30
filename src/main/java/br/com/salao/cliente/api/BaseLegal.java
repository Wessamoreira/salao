package br.com.salao.cliente.api;

/**
 * RT-CLI-004 — o fundamento legal para tratar o dado (LGPD, art. 7).
 *
 * <p>Define o <strong>padrão na ausência de registro</strong>, que é a parte que o código precisa
 * saber: sem nenhuma decisão da cliente, pode-se mandar o lembrete do horário que ela marcou, e
 * não se pode mandar promoção.
 */
public enum BaseLegal {

    /**
     * Art. 7, V — execução de contrato. Padrão: <strong>pode</strong>.
     *
     * <p>A cliente marcou horário; avisar sobre esse horário faz parte do que ela contratou. Pedir
     * consentimento aqui seria pior que desnecessário: sugeriria que ela pode recusar e ainda ser
     * atendida do mesmo jeito, e criaria uma revogação que o salão não poderia honrar sem deixar
     * de prestar o serviço.
     *
     * <p>Ainda assim aceita um "não" explícito — direito de oposição (art. 18 §2). Na prática,
     * "não me manda mais mensagem" precisa ser honrado, e um sistema que não sabe registrar isso
     * empurra o salão para a decisão de ignorar a cliente ou anotar num papel.
     */
    EXECUCAO_DE_CONTRATO(true),

    /**
     * Art. 7, I — consentimento. Padrão: <strong>não pode</strong>.
     *
     * <p>Silêncio não é consentimento, e ficha antiga importada sem registro também não. O padrão
     * negativo é o que impede que uma migração de dados vire disparo de campanha para gente que
     * nunca autorizou nada.
     */
    CONSENTIMENTO(false);

    private final boolean permitidoSemRegistro;

    BaseLegal(boolean permitidoSemRegistro) {
        this.permitidoSemRegistro = permitidoSemRegistro;
    }

    public boolean permitidoSemRegistro() {
        return permitidoSemRegistro;
    }
}
