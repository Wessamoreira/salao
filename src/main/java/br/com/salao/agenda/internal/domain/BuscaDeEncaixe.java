package br.com.salao.agenda.internal.domain;

import br.com.salao.agenda.api.CapacidadeDeRecurso;
import br.com.salao.agenda.api.Janela;
import br.com.salao.catalogo.api.BlocoDoServico;
import br.com.salao.catalogo.api.SequenciaDeBlocos;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RT-AGD-001 — onde a receita cabe.
 *
 * <p>Domínio puro: recebe as janelas livres já calculadas e devolve os instantes em que o serviço
 * inteiro se encaixa. Não sabe de banco, de tenant nem de fuso.
 *
 * <h2>Cada bloco pergunta uma coisa diferente</h2>
 *
 * <p>É aqui que o modelo de blocos se paga, e é a razão de esta busca não ser
 * "cabe um intervalo de N minutos?".
 *
 * <ul>
 *   <li>Bloco <strong>ATIVO</strong>: o profissional precisa estar livre. O bloco tem de caber
 *       inteiro dentro de <em>uma</em> janela livre — não adianta a soma dar certo se ele
 *       atravessa o almoço.</li>
 *   <li>Bloco <strong>PAUSA</strong>: o profissional <em>não</em> precisa estar livre. Durante a
 *       química ele pode estar atendendo outra cliente, e é exatamente isso que faz a agenda
 *       render. Exigir que ele estivesse livre jogaria fora 40 minutos por coloração.</li>
 *   <li>Qualquer bloco que <strong>exija recurso</strong>: precisa sobrar unidade, inclusive na
 *       pausa. A cliente segue ocupando a cadeira enquanto a química age.</li>
 * </ul>
 *
 * <h2>A pausa pode atravessar o fim do expediente?</h2>
 *
 * <p>Não. Só os blocos ATIVO exigem o profissional, mas o serviço inteiro precisa caber dentro do
 * limite do dia: uma pausa que passe do fechamento deixaria a cliente sozinha no salão trancado.
 * O limite é dado por quem chama, e no dia a dia é o horário de funcionamento.
 */
public final class BuscaDeEncaixe {

    private BuscaDeEncaixe() {
    }

    /**
     * Os instantes em que a receita cabe, em ordem.
     *
     * @param livres        janelas em que o profissional pode trabalhar, já sem bloqueios e sem o
     *                      que ele já tem marcado
     * @param receita       os blocos do serviço, já com a duração deste profissional (RN-CAT-013)
     * @param recursos      capacidade e ocupação de cada recurso citado pela receita
     * @param granularidade de quantos em quantos minutos o salão oferece horário
     * @param limite        o dia utilizável: nenhum serviço pode começar nem terminar fora dele
     */
    public static List<Instant> horarios(List<Janela> livres, SequenciaDeBlocos receita,
                                         Map<UUID, CapacidadeDeRecurso> recursos,
                                         Duration granularidade, Janela limite) {
        if (livres.isEmpty()) {
            return List.of();
        }
        if (granularidade.isZero() || granularidade.isNegative()) {
            throw new IllegalArgumentException("granularidade precisa ser positiva");
        }
        Duration total = Duration.ofMinutes(receita.duracaoTotal());
        var encontrados = new ArrayList<Instant>();

        // A grade é alinhada ao início do limite — não ao início da jornada de cada um. Com o
        // alinhamento por profissional, duas colunas da grade da recepção mostrariam horários
        // desencontrados (9h05 numa, 9h00 na outra) e o dia inteiro ficaria ilegível.
        for (Instant candidato = limite.inicio();
                !candidato.plus(total).isAfter(limite.fim());
                candidato = candidato.plus(granularidade)) {
            if (cabe(candidato, livres, receita, recursos)) {
                encontrados.add(candidato);
            }
        }
        return List.copyOf(encontrados);
    }

    private static boolean cabe(Instant inicio, List<Janela> livres, SequenciaDeBlocos receita,
                                Map<UUID, CapacidadeDeRecurso> recursos) {
        Instant cursor = inicio;
        for (BlocoDoServico bloco : receita.blocos()) {
            Janela janela = Janela.de(cursor, Duration.ofMinutes(bloco.duracaoMinutos()));

            if (bloco.tipo().ocupaOProfissional() && !dentroDeUmaJanela(janela, livres)) {
                return false;
            }
            for (UUID recursoId : bloco.recursosExigidos()) {
                CapacidadeDeRecurso capacidade = recursos.get(recursoId);
                // Recurso exigido e desconhecido: recusa. Devolver o horário e descobrir na
                // gravação seria oferecer à cliente um horário que não existe.
                if (capacidade == null || !capacidade.cabeEm(janela)) {
                    return false;
                }
            }
            cursor = janela.fim();
        }
        return true;
    }

    /**
     * O bloco precisa caber inteiro em <strong>uma</strong> janela, não na soma delas.
     *
     * <p>Duas janelas encostadas (9h–12h e 12h–18h, se o almoço fosse removido) somariam nove
     * horas, mas um bloco que atravessa a fronteira atravessa um intervalo que não existe. Como as
     * janelas livres já vêm fundidas por {@code SubtracaoDeIntervalos}, o que resta entre duas é
     * ausência de verdade.
     */
    private static boolean dentroDeUmaJanela(Janela bloco, List<Janela> livres) {
        return livres.stream().anyMatch(bloco::cabeEm);
    }
}
