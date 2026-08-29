/**
 * Módulo compartilhado: contexto de tenant, relógio, dinheiro, erros e paginação.
 *
 * <p>Todos os módulos podem depender daqui, e este não depende de nenhum. Declarado em
 * {@code @Modulithic(sharedModules = "shared")}. Nenhuma regra de negócio mora neste pacote —
 * se aparecer uma, ela pertence a um módulo.
 */
package br.com.salao.shared;
