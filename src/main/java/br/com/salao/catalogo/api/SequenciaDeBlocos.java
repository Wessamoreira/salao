package br.com.salao.catalogo.api;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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

    /** Quanto do total é pausa — o tempo que não depende de quem executa. */
    public int minutosDePausa() {
        return duracaoTotal() - minutosDoProfissional();
    }

    /** Quantos blocos ocupam o profissional. É o mínimo absoluto de minutos ativos. */
    public int quantidadeDeBlocosAtivos() {
        return (int) blocos.stream().filter(bloco -> bloco.tipo().ocupaOProfissional()).count();
    }

    /**
     * RT-CAT-004 — a mesma receita, executada por quem leva {@code minutos} no total.
     *
     * <h2>A pausa não encolhe</h2>
     *
     * <p>É a decisão da rotina. Se a Ana faz coloração em 90 minutos em vez de 100, ela é mais
     * rápida <strong>aplicando e finalizando</strong> — a química continua levando os mesmos 40
     * minutos, porque é reação, não habilidade. Encolher a pausa junto mandaria lavar o cabelo
     * antes da hora, e o resultado quem paga é a cliente.
     *
     * <p>Então os 90 viram: 40 de pausa, intocados, e 50 distribuídos entre os blocos ativos na
     * proporção original.
     *
     * <h2>A distribuição é determinística</h2>
     *
     * <p>Cada bloco ativo recebe 1 minuto de base — nenhum pode desaparecer — e o resto é
     * repartido proporcionalmente, com os minutos que sobram do arredondamento indo para os blocos
     * de maior fração e, em empate, para o primeiro. Sem uma regra fixa, a mesma entrada produziria
     * receitas diferentes conforme a ordem de iteração, e a agenda mostraria horários que mudam
     * sozinhos.
     *
     * @throws IllegalArgumentException se {@code minutos} não deixar ao menos um minuto para cada
     *                                  bloco ativo depois de descontadas as pausas
     */
    public SequenciaDeBlocos comDuracaoTotal(int minutos) {
        int pausa = minutosDePausa();
        int ativos = quantidadeDeBlocosAtivos();
        int aDistribuir = minutos - pausa;
        if (aDistribuir < ativos) {
            throw new IllegalArgumentException(
                    "duração de " + minutos + " min não cabe: " + pausa
                            + " min são de pausa, que não encolhe, e sobram menos de "
                            + ativos + " min para o trabalho");
        }
        if (minutos == duracaoTotal()) {
            return this;
        }
        return new SequenciaDeBlocos(redistribuir(aDistribuir));
    }

    private List<BlocoDoServico> redistribuir(int aDistribuir) {
        int somaAtivaOriginal = minutosDoProfissional();
        int ativos = quantidadeDeBlocosAtivos();
        int sobra = aDistribuir - ativos;

        // Piso proporcional sobre a sobra, guardando a fração de cada um para o desempate.
        var minutosPorBloco = new int[blocos.size()];
        var fracao = new long[blocos.size()];
        int distribuido = 0;
        for (int i = 0; i < blocos.size(); i++) {
            BlocoDoServico bloco = blocos.get(i);
            if (!bloco.tipo().ocupaOProfissional()) {
                minutosPorBloco[i] = bloco.duracaoMinutos();
                continue;
            }
            long numerador = (long) sobra * bloco.duracaoMinutos();
            int piso = (int) (numerador / somaAtivaOriginal);
            fracao[i] = numerador % somaAtivaOriginal;
            minutosPorBloco[i] = 1 + piso;
            distribuido += piso;
        }

        // Os minutos que o arredondamento deixou de fora vão para as maiores frações.
        int restante = sobra - distribuido;
        var ordemPorFracao = new java.util.ArrayList<Integer>();
        for (int i = 0; i < blocos.size(); i++) {
            if (blocos.get(i).tipo().ocupaOProfissional()) {
                ordemPorFracao.add(i);
            }
        }
        ordemPorFracao.sort((a, b) -> fracao[a] == fracao[b]
                ? Integer.compare(a, b)
                : Long.compare(fracao[b], fracao[a]));
        for (int k = 0; k < restante; k++) {
            minutosPorBloco[ordemPorFracao.get(k % ordemPorFracao.size())]++;
        }

        var novos = new java.util.ArrayList<BlocoDoServico>(blocos.size());
        for (int i = 0; i < blocos.size(); i++) {
            BlocoDoServico bloco = blocos.get(i);
            novos.add(new BlocoDoServico(bloco.tipo(), minutosPorBloco[i],
                    bloco.recursosExigidos()));
        }
        return novos;
    }

    /**
     * RT-CAT-003 — todos os recursos que a receita toca, em qualquer bloco.
     *
     * <p>Serve para a pergunta grossa ("este serviço depende do lavatório?"). A agenda não usa
     * esta: para saber <em>quando</em> o lavatório fica ocupado ela precisa do bloco, porque a
     * coloração o ocupa em 30 dos 100 minutos.
     */
    public Set<UUID> recursosExigidos() {
        var uniao = new LinkedHashSet<UUID>();
        blocos.forEach(bloco -> uniao.addAll(bloco.recursosExigidos()));
        return Set.copyOf(uniao);
    }
}
