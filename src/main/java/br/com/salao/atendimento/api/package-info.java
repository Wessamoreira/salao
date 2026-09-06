/**
 * Contrato público do módulo {@code atendimento}.
 *
 * <p>{@code @NamedInterface} é o que <strong>de fato</strong> torna este pacote visível aos outros
 * módulos: no Spring Modulith, só o pacote-raiz do módulo é exposto por padrão, e um subpacote
 * chamado {@code api} não passa de convenção de nome até ser declarado aqui.
 */
@org.springframework.modulith.NamedInterface("api")
package br.com.salao.atendimento.api;
