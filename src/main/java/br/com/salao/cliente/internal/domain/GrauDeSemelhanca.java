package br.com.salao.cliente.internal.domain;

/**
 * RT-CLI-003 — o quanto duas fichas parecem ser a mesma pessoa.
 *
 * <p>Três graus, e não um booleano, porque as três situações pedem ações diferentes da recepção.
 */
public enum GrauDeSemelhanca {

    /** Mesmo telefone e mesmo primeiro nome. Quase certamente a mesma pessoa cadastrada duas vezes. */
    PROVAVEL,

    /** Nomes muito parecidos, sem telefone que confirme ou desminta. Vale conferir. */
    POSSIVEL,

    /**
     * Mesmo telefone, primeiro nome diferente.
     *
     * <p><strong>Não é duplicado</strong> — é quase sempre família, e mãe e filha dividindo o
     * número é rotina no salão (RN-CLI-005). Aparece como contexto útil, nunca como aviso de
     * duplicidade: tratar isto como erro treinaria a recepção a ignorar o aviso, e aí o aviso
     * deixaria de servir para o caso em que ele está certo.
     */
    MESMO_TELEFONE
}
