package br.com.salao.agenda.internal.infra;

import br.com.salao.agenda.api.AgendamentoResumo;
import br.com.salao.agenda.api.BlocoDoAgendamento;
import br.com.salao.agenda.api.Janela;
import br.com.salao.agenda.api.OrigemDoAgendamento;
import br.com.salao.agenda.api.StatusAgendamento;
import br.com.salao.catalogo.api.TipoDeBloco;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Optional;
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

    // ------------------------------------------------------------------ RT-AGD-002

    /**
     * Quais unidades de quais recursos estão tomadas no período.
     *
     * <p>Uma consulta para todos os recursos da receita: um serviço pode exigir lavatório e
     * cadeira, e duas idas ao banco para alocar duas unidades seria o começo de uma por bloco.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<UnidadeOcupada>> unidadesOcupadas(Collection<UUID> recursos,
                                                            Janela periodo) {
        if (recursos.isEmpty()) {
            return Map.of();
        }
        var porRecurso = new LinkedHashMap<UUID, List<UnidadeOcupada>>();
        jdbc.sql("""
                select recurso_id, unidade, lower(periodo) as inicio, upper(periodo) as fim
                  from agendamento_bloco
                 where recurso_id in (:recursos)
                   and periodo && tstzrange(:inicio, :fim)
                   and status in ('SOLICITADO', 'CONFIRMADO', 'EM_ATENDIMENTO')
                """)
                .param("recursos", List.copyOf(recursos))
                .param("inicio", OffsetDateTime.ofInstant(periodo.inicio(), ZoneOffset.UTC))
                .param("fim", OffsetDateTime.ofInstant(periodo.fim(), ZoneOffset.UTC))
                .query((rs, n) -> Map.entry(rs.getObject("recurso_id", UUID.class),
                        new UnidadeOcupada(rs.getInt("unidade"),
                                new Janela(instante(rs.getObject("inicio", OffsetDateTime.class)),
                                        instante(rs.getObject("fim", OffsetDateTime.class))))))
                .list()
                .forEach(entrada -> porRecurso
                        .computeIfAbsent(entrada.getKey(), id -> new ArrayList<>())
                        .add(entrada.getValue()));
        return porRecurso;
    }

    public record UnidadeOcupada(int unidade, Janela janela) {
    }

    public UUID inserirCabeca(UUID estabelecimentoId, UUID clienteId, UUID profissionalId,
                              UUID servicoId, StatusAgendamento status, Janela periodo,
                              OrigemDoAgendamento origem, String observacao, UUID criadoPor) {
        return jdbc.sql("""
                insert into agendamento (estabelecimento_id, cliente_id, profissional_id,
                                         servico_id, status, inicio, fim, origem, observacao,
                                         criado_por)
                values (:estabelecimento, :cliente, :profissional, :servico, :status, :inicio,
                        :fim, :origem, :observacao, :criadoPor)
                returning id
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("cliente", clienteId)
                .param("profissional", profissionalId)
                .param("servico", servicoId)
                .param("status", status.name())
                .param("inicio", OffsetDateTime.ofInstant(periodo.inicio(), ZoneOffset.UTC))
                .param("fim", OffsetDateTime.ofInstant(periodo.fim(), ZoneOffset.UTC))
                .param("origem", origem.name())
                .param("observacao", observacao)
                .param("criadoPor", criadoPor)
                .query(UUID.class)
                .single();
    }

    /** Os blocos, um a um. São três ou cinco — lote aqui seria otimizar o que não dói. */
    public void inserirBlocos(UUID estabelecimentoId, UUID agendamentoId,
                              List<BlocoDoAgendamento> blocos, StatusAgendamento status) {
        for (BlocoDoAgendamento bloco : blocos) {
            jdbc.sql("""
                    insert into agendamento_bloco (estabelecimento_id, agendamento_id, ordem,
                                                   tipo, profissional_id, recurso_id, unidade,
                                                   periodo, status)
                    values (:estabelecimento, :agendamento, :ordem, :tipo, :profissional,
                            :recurso, :unidade, tstzrange(:inicio, :fim), :status)
                    """)
                    .param("estabelecimento", estabelecimentoId)
                    .param("agendamento", agendamentoId)
                    .param("ordem", bloco.ordem())
                    .param("tipo", bloco.tipo().name())
                    .param("profissional", bloco.profissionalId())
                    .param("recurso", bloco.recursoId())
                    .param("unidade", bloco.unidade())
                    .param("inicio", OffsetDateTime.ofInstant(bloco.janela().inicio(),
                            ZoneOffset.UTC))
                    .param("fim", OffsetDateTime.ofInstant(bloco.janela().fim(), ZoneOffset.UTC))
                    .param("status", status.name())
                    .update();
        }
    }

    @Transactional(readOnly = true)
    public Optional<AgendamentoResumo> porId(UUID agendamentoId) {
        record Cabeca(UUID id, UUID clienteId, UUID profissionalId, UUID servicoId,
                      StatusAgendamento status, Instant inicio, Instant fim,
                      OrigemDoAgendamento origem, String observacao, int versao) {
        }
        Optional<Cabeca> cabeca = jdbc.sql("""
                select id, cliente_id, profissional_id, servico_id, status, inicio, fim,
                       origem, observacao, versao
                  from agendamento where id = :id
                """)
                .param("id", agendamentoId)
                .query((rs, n) -> new Cabeca(rs.getObject("id", UUID.class),
                        rs.getObject("cliente_id", UUID.class),
                        rs.getObject("profissional_id", UUID.class),
                        rs.getObject("servico_id", UUID.class),
                        StatusAgendamento.valueOf(rs.getString("status")),
                        rs.getObject("inicio", OffsetDateTime.class).toInstant(),
                        rs.getObject("fim", OffsetDateTime.class).toInstant(),
                        OrigemDoAgendamento.valueOf(rs.getString("origem")),
                        rs.getString("observacao"), rs.getInt("versao")))
                .optional();

        return cabeca.map(c -> new AgendamentoResumo(c.id(), c.clienteId(), c.profissionalId(),
                c.servicoId(), c.status(), c.inicio(), c.fim(), c.origem(), c.observacao(),
                blocosDe(agendamentoId), c.versao()));
    }

    private List<BlocoDoAgendamento> blocosDe(UUID agendamentoId) {
        return jdbc.sql("""
                select ordem, tipo, profissional_id, recurso_id, unidade,
                       lower(periodo) as inicio, upper(periodo) as fim
                  from agendamento_bloco
                 where agendamento_id = :id
                 order by ordem, recurso_id nulls first
                """)
                .param("id", agendamentoId)
                .query((rs, n) -> {
                    int unidade = rs.getInt("unidade");
                    Integer qual = rs.wasNull() ? null : unidade;
                    return new BlocoDoAgendamento(rs.getInt("ordem"),
                            TipoDeBloco.valueOf(rs.getString("tipo")),
                            new Janela(instante(rs.getObject("inicio", OffsetDateTime.class)),
                                    instante(rs.getObject("fim", OffsetDateTime.class))),
                            rs.getObject("profissional_id", UUID.class),
                            rs.getObject("recurso_id", UUID.class), qual);
                })
                .list();
    }

    // ------------------------------------------------------------------ RT-AGD-003

    /**
     * Apaga os blocos do agendamento.
     *
     * <p>Chamada <strong>antes</strong> de gravar os novos, na mesma transação, e é isso que faz
     * mover 13h→13h15 funcionar: sem apagar primeiro, o agendamento colidiria <em>consigo mesmo</em>
     * na exclusion constraint. É o defeito mais fácil de escrever nesta rotina, e o que só aparece
     * quando alguém arrasta um bloco alguns minutos.
     */
    public int apagarBlocos(UUID agendamentoId) {
        return jdbc.sql("delete from agendamento_bloco where agendamento_id = :id")
                .param("id", agendamentoId)
                .update();
    }

    /** Move a cabeça, conferindo a versão. Zero linhas significa que alguém salvou antes. */
    public int moverCabeca(UUID agendamentoId, int versao, UUID profissionalId, Janela periodo) {
        return jdbc.sql("""
                update agendamento
                   set profissional_id = :profissional, inicio = :inicio, fim = :fim,
                       versao = versao + 1
                 where id = :id and versao = :versao
                """)
                .param("id", agendamentoId)
                .param("versao", versao)
                .param("profissional", profissionalId)
                .param("inicio", OffsetDateTime.ofInstant(periodo.inicio(), ZoneOffset.UTC))
                .param("fim", OffsetDateTime.ofInstant(periodo.fim(), ZoneOffset.UTC))
                .update();
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
