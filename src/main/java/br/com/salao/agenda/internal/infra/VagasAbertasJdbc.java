package br.com.salao.agenda.internal.infra;

import br.com.salao.agenda.internal.domain.ChamadaDaFila;
import br.com.salao.agenda.internal.domain.OrigemDaVaga;
import br.com.salao.agenda.internal.domain.ResultadoDaChamada;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-AGD-010 — as vagas que abriram e as chamadas feitas para elas.
 *
 * <p>As duas tabelas só aceitam {@code insert} (V66): a vaga é um fato consumado, e a chamada é a
 * trilha do que a recepção fez. Nada aqui atualiza nada.
 */
public class VagasAbertasJdbc {

    public record Vaga(UUID id, UUID agendamentoId, OrigemDaVaga origem, UUID profissionalId,
                       Instant inicio, Instant fim, Instant abertaEm) {
    }

    private static final String CAMPOS_DA_VAGA =
            "id, agendamento_id, origem, profissional_id, inicio, fim, aberta_em";

    private static final String CAMPOS_DA_CHAMADA = """
            id, vaga_id, espera_id, resultado, agendamento_id, motivo_do_pulo, registrada_em,
            registrada_por
            """;

    private final JdbcClient jdbc;

    public VagasAbertasJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Grava a vaga, a não ser que ela já tenha passado ou que o tempo esteja inteiro dentro de uma
     * vaga aberta da mesma profissional (RN-AGD-009).
     *
     * <p>O segundo caso acontece quando a vaga é preenchida pela fila e a nova cliente também
     * cancela: a vaga que já existe anuncia aquele tempo, e uma segunda listaria as mesmas pessoas
     * duas vezes.
     *
     * @return o id da vaga gravada, ou vazio quando não havia o que gravar
     */
    @Transactional
    public Optional<UUID> abrir(UUID estabelecimentoId, UUID agendamentoId, OrigemDaVaga origem,
                                UUID profissionalId, Instant inicio, Instant fim, Instant agora) {
        return jdbc.sql("""
                insert into vaga_aberta (estabelecimento_id, agendamento_id, origem,
                                         profissional_id, inicio, fim, aberta_em)
                select :estabelecimento, :agendamento, :origem, :profissional, :inicio, :fim,
                       :agora
                 where :fim > :agora
                   and not exists (select 1
                                     from vaga_aberta v
                                    where v.profissional_id = :profissional
                                      and v.inicio <= :inicio
                                      and v.fim >= :fim
                                      and v.fim > :agora)
                returning id
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("agendamento", agendamentoId)
                .param("origem", origem.name())
                .param("profissional", profissionalId)
                .param("inicio", utc(inicio))
                .param("fim", utc(fim))
                .param("agora", utc(agora))
                .query(UUID.class)
                .optional();
    }

    /** As vagas cujo horário ainda não acabou, da mais próxima para a mais distante. */
    @Transactional(readOnly = true)
    public List<Vaga> abertas(Instant agora) {
        return jdbc.sql("select " + CAMPOS_DA_VAGA + """
                  from vaga_aberta
                 where fim > :agora
                 order by inicio, id
                """)
                .param("agora", utc(agora))
                .query(VagasAbertasJdbc::vaga)
                .list();
    }

    @Transactional(readOnly = true)
    public Optional<Vaga> porId(UUID vagaId) {
        return jdbc.sql("select " + CAMPOS_DA_VAGA + " from vaga_aberta where id = :id")
                .param("id", vagaId)
                .query(VagasAbertasJdbc::vaga)
                .optional();
    }

    @Transactional
    public UUID registrarChamada(UUID estabelecimentoId, UUID vagaId, UUID esperaId,
                                 ResultadoDaChamada resultado, UUID agendamentoId,
                                 String motivoDoPulo, Instant agora, UUID quem) {
        return jdbc.sql("""
                insert into chamada_da_fila (estabelecimento_id, vaga_id, espera_id, resultado,
                                             agendamento_id, motivo_do_pulo, registrada_em,
                                             registrada_por)
                values (:estabelecimento, :vaga, :espera, :resultado, :agendamento, :motivo,
                        :agora, :quem)
                returning id
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("vaga", vagaId)
                .param("espera", esperaId)
                .param("resultado", resultado.name())
                .param("agendamento", agendamentoId)
                .param("motivo", motivoDoPulo)
                .param("agora", utc(agora))
                .param("quem", quem)
                .query(UUID.class)
                .single();
    }

    @Transactional(readOnly = true)
    public Optional<ChamadaDaFila> chamadaPorId(UUID chamadaId) {
        return jdbc.sql("select " + CAMPOS_DA_CHAMADA + " from chamada_da_fila where id = :id")
                .param("id", chamadaId)
                .query(VagasAbertasJdbc::chamada)
                .optional();
    }

    /** As chamadas destas vagas, na ordem em que foram feitas. */
    @Transactional(readOnly = true)
    public List<ChamadaDaFila> chamadasDas(Collection<UUID> vagaIds) {
        if (vagaIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select " + CAMPOS_DA_CHAMADA + """
                  from chamada_da_fila
                 where vaga_id in (:vagas)
                 order by registrada_em, id
                """)
                .param("vagas", vagaIds)
                .query(VagasAbertasJdbc::chamada)
                .list();
    }

    /** Quem já teve alguma chamada nesta vaga — "não atendeu" inclusive (RN-AGD-026). */
    @Transactional(readOnly = true)
    public Set<UUID> esperasJaChamadas(UUID vagaId) {
        return chamadasDas(List.of(vagaId)).stream()
                .map(ChamadaDaFila::esperaId)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static OffsetDateTime utc(Instant instante) {
        return OffsetDateTime.ofInstant(instante, ZoneOffset.UTC);
    }

    private static Vaga vaga(ResultSet rs, int linha) throws SQLException {
        return new Vaga(
                rs.getObject("id", UUID.class),
                rs.getObject("agendamento_id", UUID.class),
                OrigemDaVaga.valueOf(rs.getString("origem")),
                rs.getObject("profissional_id", UUID.class),
                rs.getObject("inicio", OffsetDateTime.class).toInstant(),
                rs.getObject("fim", OffsetDateTime.class).toInstant(),
                rs.getObject("aberta_em", OffsetDateTime.class).toInstant());
    }

    private static ChamadaDaFila chamada(ResultSet rs, int linha) throws SQLException {
        return new ChamadaDaFila(
                rs.getObject("id", UUID.class),
                rs.getObject("vaga_id", UUID.class),
                rs.getObject("espera_id", UUID.class),
                ResultadoDaChamada.valueOf(rs.getString("resultado")),
                rs.getObject("agendamento_id", UUID.class),
                rs.getString("motivo_do_pulo"),
                rs.getObject("registrada_em", OffsetDateTime.class).toInstant(),
                rs.getObject("registrada_por", UUID.class));
    }
}
