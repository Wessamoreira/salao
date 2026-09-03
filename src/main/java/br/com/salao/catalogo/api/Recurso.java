package br.com.salao.catalogo.api;

import java.util.UUID;

/**
 * RT-CAT-003 — uma coisa contável do salão: lavatório, cadeira, sala.
 *
 * <h2>Por que não tem tipo</h2>
 *
 * <p>A rotina se chama "lavatório, cadeira, sala", e a tentação é transformar isso numa coluna
 * {@code tipo}. Mas lavatório e cadeira não se comportam de forma diferente em lugar nenhum: o que
 * distingue os dois é <strong>qual bloco os exige</strong>, e isso já está dito no vínculo. Um
 * campo que só serviria para agrupar numa tela que ainda não existe é dado que nasce especulativo
 * — e dado especulativo envelhece errado.
 *
 * <h2>Por que quantidade e não uma linha por unidade</h2>
 *
 * <p>O salão tem "três lavatórios", não "Lavatório A, B e C". As unidades são intercambiáveis:
 * ninguém escolhe qual, só importa se sobra um. Nomeá-las criaria uma escolha que a recepção não
 * sabe fazer e um dado que ninguém consulta.
 *
 * @param quantidade sempre ≥ 1. Zero e {@code ativo = false} diriam a mesma coisa de duas formas,
 *                   e duas formas de dizer a mesma coisa é o começo de um relatório que não fecha
 */
public record Recurso(UUID id, String nome, int quantidade, boolean ativo, int versao) {

    public Recurso {
        if (nome == null || nome.isBlank()) {
            throw new IllegalArgumentException("o nome do recurso é obrigatório");
        }
        if (quantidade < 1) {
            throw new IllegalArgumentException(
                    "quantidade precisa ser ao menos 1: " + quantidade
                            + " — recurso que o salão não tem é recurso inativo, não recurso zero");
        }
    }
}
