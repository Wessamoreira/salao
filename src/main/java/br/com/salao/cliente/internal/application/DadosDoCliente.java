package br.com.salao.cliente.internal.application;

/**
 * RT-CLI-001 — o que se sabe da pessoa no momento do cadastro.
 *
 * <p>Só {@code nome} é obrigatório. Todo o resto é opcional <strong>de propósito</strong>: o
 * cadastro nasce no meio de um agendamento, com a cliente esperando (R-UX-04). Exigir e-mail para
 * marcar um corte é a razão nº 1 de sistema de salão virar caderno na recepção.
 *
 * <p>O telefone chega como a pessoa digitou — com máscara, sem DDD, colado do WhatsApp. Normalizar
 * é trabalho do domínio, não de quem preenche.
 */
public record DadosDoCliente(
        String nome,
        String apelido,
        String telefone,
        String email,
        String observacao) {

    /** O cadastro rápido de dois campos, que é como a maioria dos clientes entra no sistema. */
    public static DadosDoCliente rapido(String nome, String telefone) {
        return new DadosDoCliente(nome, null, telefone, null, null);
    }
}
