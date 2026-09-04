package br.com.salao.agenda.internal.infra;

import br.com.salao.agenda.api.Janela;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-AGD-001 — o que já está ocupado.
 *
 * <p>Uma consulta para o dia inteiro, não uma por profissional: a grade da recepção pergunta por
 * dez pessoas de uma vez, e o agrupamento sai de graça em memória sobre algumas dezenas de linhas.
 */
public class AgendaJdbc {

    /**
     * Os blocos que ocupam alguém ou algo no período.
     *
     * <p>O filtro de status é o que faz um agendamento cancelado devolver o horário — é o mesmo
     * conjunto das constraints de exclusão, e precisa continuar sendo: se divergissem, a aplicação
     * ofereceria horário que o banco recusa, ou esconderia horário que ele aceitaria.
     */
    static final String OCUPACOES = """
            select profissional_id, recurso_id, unidade,
                   lower(periodo) as inicio, upper(periodo) as fim
              from agendamento_bloco
             where periodo && tstzrange(:inicio, :fim)
               and status in ('SOLICITADO', 'CONFIRMADO', 'EM_ATENDIMENTO')
            """;

    private final JdbcClient jdbc;

    public AgendaJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** O que cada profissional e cada recurso têm tomado no período. */
    @Transactional(readOnly = true)
    public Ocupacoes ocupacoesEm(Janela periodo) {
        record Linha(UUID profissionalId, UUID recursoId, Janela janela) {
        }
        List<Linha> linhas = jdbc.sql(OCUPACOES)
                .param("inicio", OffsetDateTime.ofInstant(periodo.inicio(),
                        java.time.ZoneOffset.UTC))
                .param("fim", OffsetDateTime.ofInstant(periodo.fim(), java.time.ZoneOffset.UTC))
                .query((rs, n) -> new Linha(
                        rs.getObject("profissional_id", UUID.class),
                        rs.getObject("recurso_id", UUID.class),
                        new Janela(instante(rs.getObject("inicio", OffsetDateTime.class)),
                                instante(rs.getObject("fim", OffsetDateTime.class)))))
                .list();

        var porProfissional = new LinkedHashMap<UUID, List<Janela>>();
        var porRecurso = new LinkedHashMap<UUID, List<Janela>>();
        for (Linha linha : linhas) {
            if (linha.profissionalId() != null) {
                porProfissional.computeIfAbsent(linha.profissionalId(), id -> new ArrayList<>())
                        .add(linha.janela());
            }
            // Cada linha com recurso é UMA unidade tomada. A contagem de cruzamentos contra a
            // quantidade é o que responde "ainda cabe mais um?" (CapacidadeDeRecurso).
            if (linha.recursoId() != null) {
                porRecurso.computeIfAbsent(linha.recursoId(), id -> new ArrayList<>())
                        .add(linha.janela());
            }
        }
        return new Ocupacoes(porProfissional, porRecurso);
    }

    private static Instant instante(OffsetDateTime valor) {
        return valor.toInstant();
    }

    public record Ocupacoes(Map<UUID, List<Janela>> porProfissional,
                            Map<UUID, List<Janela>> porRecurso) {

        public List<Janela> doProfissional(UUID profissionalId) {
            return porProfissional.getOrDefault(profissionalId, List.of());
        }

        public List<Janela> doRecurso(UUID recursoId) {
            return porRecurso.getOrDefault(recursoId, List.of());
        }
    }
}
