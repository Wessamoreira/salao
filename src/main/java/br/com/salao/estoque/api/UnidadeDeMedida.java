package br.com.salao.estoque.api;

/**
 * RT-EST-001 — em que se conta o produto.
 *
 * <p>Lista fechada porque {@code quantidade} é numérica em toda parte: {@code "un"}, {@code "und"}
 * e {@code "unid"} digitados à mão viram três unidades diferentes, e a soma do saldo passa a
 * depender de como cada pessoa escreveu.
 *
 * <p>Tinta se mede em gramas, ox em mililitros, escova em unidade. Sem isso, a ficha técnica
 * (RT-EST-007) não tem como dizer "40 g de tinta".
 *
 * <p>Não há conversão entre elas de propósito. Um produto é cadastrado na unidade em que o salão o
 * usa, e converter grama em quilo automaticamente é o tipo de esperteza que erra por mil quando
 * alguém cadastra errado.
 */
public enum UnidadeDeMedida {
    UN, ML, L, G, KG
}
