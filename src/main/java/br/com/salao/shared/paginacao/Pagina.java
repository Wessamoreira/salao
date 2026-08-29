package br.com.salao.shared.paginacao;

import java.util.List;
import java.util.function.Function;

/**
 * RT-INF-004 — página de resultados por keyset.
 *
 * <p>Não existe {@code totalDeItens}: em listagem grande o {@code count(*)} custa mais que a
 * própria página. Onde o total for realmente necessário, vira endpoint separado e cacheado.
 *
 * <p>{@code OFFSET} é proibido no projeto — {@code OFFSET 50000} faz o Postgres varrer 50 mil
 * linhas só para descartá-las.
 */
public record Pagina<T>(List<T> itens, String proximoCursor, boolean temMais) {

    public static <T> Pagina<T> vazia() {
        return new Pagina<>(List.of(), null, false);
    }

    /**
     * Monta a página a partir de {@code limite + 1} itens lidos do banco.
     *
     * <p>Ler um a mais é como se descobre que existe próxima página sem pagar um {@code count}.
     */
    public static <T> Pagina<T> de(List<T> lidos, int limite, Function<T, Cursor> extrairCursor) {
        boolean temMais = lidos.size() > limite;
        List<T> itens = temMais ? List.copyOf(lidos.subList(0, limite)) : List.copyOf(lidos);
        String proximo = temMais && !itens.isEmpty()
                ? extrairCursor.apply(itens.getLast()).codificar()
                : null;
        return new Pagina<>(itens, proximo, temMais);
    }

    public <R> Pagina<R> mapear(Function<T, R> conversao) {
        return new Pagina<>(itens.stream().map(conversao).toList(), proximoCursor, temMais);
    }
}
