package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.EsperaNaFila;

/**
 * RT-AGD-010 — a espera com o que a tela escreve.
 *
 * @param cliente      o nome — ou o rótulo de anonimizada, como em qualquer leitura
 * @param servico      nulo se o serviço deixou de existir no catálogo
 * @param profissional nulo quando ela aceita qualquer uma
 */
public record EsperaComNomes(EsperaNaFila espera, String cliente, String servico,
                             String profissional) {
}
