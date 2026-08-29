/**
 * Módulo compartilhado: contexto de tenant, relógio, dinheiro, erros, paginação, cache, eventos,
 * idempotência, manutenção e observabilidade.
 *
 * <p><strong>Declarado {@code OPEN} de propósito.</strong> O Spring Modulith só considera exposto
 * o pacote-raiz de um módulo; subpacotes são internos. Como {@code shared} é organizado por
 * assunto ({@code shared.erro}, {@code shared.dinheiro}, {@code shared.tenant}…), fechá-lo
 * obrigaria a declarar um {@code @NamedInterface} por subpacote — cerimônia sem ganho, porque a
 * intenção é justamente que todos os módulos usem tudo o que está aqui.
 *
 * <p>O que sustenta a fronteira não é esta anotação: é {@code shared} <strong>não depender de
 * nenhum módulo</strong> e não conter regra de negócio. Se aparecer uma regra aqui, ela pertence
 * a um módulo, e a decisão de manter isto aberto deixa de se justificar.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package br.com.salao.shared;
