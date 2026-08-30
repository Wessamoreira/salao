/**
 * Contrato público do módulo {@code catalogo}.
 *
 * <p>{@code @NamedInterface} é o que <strong>de fato</strong> torna este pacote visível aos outros
 * módulos: no Spring Modulith, só o pacote-raiz do módulo é exposto por padrão, e um subpacote
 * chamado {@code api} não passa de convenção de nome até ser declarado aqui.
 *
 * <p>Sem esta declaração, {@code internal} e {@code api} seriam igualmente privados — a fronteira
 * existiria na documentação e não no build, que é o mesmo que não existir.
 */
@org.springframework.modulith.NamedInterface("api")
package br.com.salao.catalogo.api;
