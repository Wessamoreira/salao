package br.com.salao.catalogo.api;

import java.util.List;

/**
 * RT-CAT-001 — a receita do serviço, em blocos.
 *
 * <h2>Duas invariantes, e as duas são de negócio</h2>
 *
 * <p><strong>Começa e termina em ATIVO.</strong> Um serviço que começa em pausa faria a cliente
 * chegar e esperar sem ninguém ter feito nada — não é serviço, é atraso. Um que termina em pausa
 * encerraria o atendimento com a química ainda agindo e ninguém escalado para lavar: a agenda
 * marcaria a próxima cliente e a primeira ficaria sentada com tinta no cabelo.
 *
 * <p><strong>ATIVO e PAUSA se alternam.</strong> Dois ATIVO seguidos são um ATIVO com a soma das
 * durações, e duas PAUSA seguidas idem. Permitir isso deixaria a mesma receita ser escrita de
 * várias formas — e duas fichas do mesmo serviço com blocos diferentes é o começo de um relatório
 * que não fecha.
 *
 * <p>Juntas, elas garantem que a sequência é sempre {@code A}, {@code APA}, {@code APAPA} — número
 * ímpar de blocos, começando e terminando em ATIVO.
 */
public record SequenciaDeBlocos(List<BlocoDoServico> blocos) {

    public SequenciaDeBlocos {
        if (blocos == null || blocos.isEmpty()) {
            throw new IllegalArgumentException("o serviço precisa de ao menos um bloco");
        }
        if (blocos.getFirst().tipo() != TipoDeBloco.ATIVO
                || blocos.getLast().tipo() != TipoDeBloco.ATIVO) {
            throw new IllegalArgumentException(
                    "o serviço precisa começar e terminar com um bloco ATIVO");
        }
        for (int i = 1; i < blocos.size(); i++) {
            if (blocos.get(i).tipo() == blocos.get(i - 1).tipo()) {
                throw new IllegalArgumentException(
                        "blocos do mesmo tipo em sequência na posição " + i
                                + ": junte-os num só");
            }
        }
        blocos = List.copyOf(blocos);
    }

    public static SequenciaDeBlocos simples(int minutos) {
        return new SequenciaDeBlocos(List.of(BlocoDoServico.ativo(minutos)));
    }

    /** Quanto tempo a cliente fica no salão. É o que a agenda reserva de ponta a ponta. */
    public int duracaoTotal() {
        return blocos.stream().mapToInt(BlocoDoServico::duracaoMinutos).sum();
    }

    /**
     * Quanto tempo do profissional o serviço consome.
     *
     * <p>Numa coloração de 100 minutos, são 60. A diferença é o que a agenda pode vender para
     * outra cliente — e é a razão de o modelo existir.
     */
    public int minutosDoProfissional() {
        return blocos.stream()
                .filter(bloco -> bloco.tipo().ocupaOProfissional())
                .mapToInt(BlocoDoServico::duracaoMinutos)
                .sum();
    }

    /** Quanto tempo o profissional fica livre no meio do atendimento. */
    public int minutosLivresNoMeio() {
        return duracaoTotal() - minutosDoProfissional();
    }

    /** Se o serviço tem pausa — o que muda como a agenda o encaixa. */
    public boolean temPausa() {
        return blocos.stream().anyMatch(bloco -> bloco.tipo() == TipoDeBloco.PAUSA);
    }
}
