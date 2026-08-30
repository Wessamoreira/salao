package br.com.salao.cliente.internal.domain;

/** RT-CLI-001 — normalização de nome digitado às pressas. */
public final class Nomes {

    private Nomes() {
    }

    /**
     * Remove espaços das pontas e colapsa os do meio.
     *
     * <p>{@code "Maria  Silva "} e {@code "Maria Silva"} são a mesma pessoa, e quem digita com a
     * cliente na frente não olha para o espaço a mais. Guardar as duas formas faria a busca
     * (RT-CLI-002) e a detecção de duplicado (RT-CLI-003) tratarem-nas como pessoas diferentes.
     */
    public static String normalizar(String bruto) {
        return bruto == null ? null : bruto.strip().replaceAll("\\s{2,}", " ");
    }

    /** Como {@link #normalizar}, mas devolve {@code null} para o que ficou vazio. */
    public static String opcional(String bruto) {
        String limpo = normalizar(bruto);
        return limpo == null || limpo.isEmpty() ? null : limpo;
    }
}
