package br.com.salao.agenda.internal.infra;

import br.com.salao.agenda.api.EsperaNaFila;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-AGD-010 — persistência da fila de espera.
 *
 * <p>A consulta de candidatos filtra no banco o que dá para filtrar por índice — serviço,
 * profissional e intervalo de dias — e deixa a faixa de hora para o domínio
 * ({@code EsperaNaFila.serve}). A hora é comparada em relógio de parede no fuso do salão, e um
 * {@code where} sobre isso exigiria converter o instante dentro do SQL, com o fuso vindo de outra
 * tabela: mais fácil de errar e impossível de testar sem banco.
 */
public class FilaDeEsperaJdbc {

    private static final String CAMPOS = """
            id, cliente_id, servico_id, profissional_id, aceita_de, aceita_ate,
            hora_de, hora_ate, observacao, criado_em
            """;

    private static final String NA_FILA = " atendida_em is null and desistiu_em is null ";

    private final JdbcClient jdbc;

    public FilaDeEsperaJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public UUID entrar(UUID estabelecimentoId, EsperaNaFila espera, UUID criadoPor) {
        return jdbc.sql("""
                insert into fila_espera (estabelecimento_id, cliente_id, servico_id,
                                         profissional_id, aceita_de, aceita_ate, hora_de,
                                         hora_ate, observacao, criado_por)
                values (:estabelecimento, :cliente, :servico, :profissional, :de, :ate, :horaDe,
                        :horaAte, :observacao, :criadoPor)
                returning id
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("cliente", espera.clienteId())
                .param("servico", espera.servicoId())
                .param("profissional", espera.profissionalId())
                .param("de", espera.aceitaDe())
                .param("ate", espera.aceitaAte())
                .param("horaDe", espera.horaDe())
                .param("horaAte", espera.horaAte())
                .param("observacao", espera.observacao())
                .param("criadoPor", criadoPor)
                .query(UUID.class)
                .single();
    }

    @Transactional(readOnly = true)
    public Optional<EsperaNaFila> porId(UUID esperaId) {
        return jdbc.sql("select " + CAMPOS + " from fila_espera where id = :id")
                .param("id", esperaId)
                .query(FilaDeEsperaJdbc::mapear)
                .optional();
    }

    /** Quem está esperando, do mais antigo para o mais novo — a ordem de quem chegou primeiro. */
    @Transactional(readOnly = true)
    public List<EsperaNaFila> naFila() {
        return jdbc.sql("select " + CAMPOS + " from fila_espera where" + NA_FILA
                        + " order by criado_em")
                .query(FilaDeEsperaJdbc::mapear)
                .list();
    }

    /**
     * Os candidatos a uma vaga, já filtrados por serviço, profissional e dia.
     *
     * <p>A faixa de hora fica para o domínio: ver o javadoc da classe.
     *
     * <p>Ordem de chegada. Sem isso, "quem avisamos primeiro" viraria a ordem em que o banco
     * devolveu — e a cliente que espera há duas semanas ficaria atrás da que entrou ontem.
     */
    @Transactional(readOnly = true)
    public List<EsperaNaFila> candidatosA(UUID servicoId, UUID profissionalId, LocalDate dia) {
        return jdbc.sql("select " + CAMPOS + """
                  from fila_espera
                 where servico_id = :servico
                   and (profissional_id is null or profissional_id = :profissional)
                   and :dia between aceita_de and aceita_ate
                   and""" + NA_FILA + """
                 order by criado_em
                """)
                .param("servico", servicoId)
                .param("profissional", profissionalId)
                .param("dia", dia)
                .query(FilaDeEsperaJdbc::mapear)
                .list();
    }

    /** Saiu da fila porque conseguiu horário. */
    @Transactional
    public int marcarAtendida(UUID esperaId) {
        return jdbc.sql("update fila_espera set atendida_em = now() where id = :id and" + NA_FILA)
                .param("id", esperaId)
                .update();
    }

    /** Saiu da fila porque cansou de esperar. A diferença importa no relatório. */
    @Transactional
    public int marcarDesistencia(UUID esperaId) {
        return jdbc.sql("update fila_espera set desistiu_em = now() where id = :id and" + NA_FILA)
                .param("id", esperaId)
                .update();
    }

    private static EsperaNaFila mapear(ResultSet rs, int linha) throws SQLException {
        return new EsperaNaFila(
                rs.getObject("id", UUID.class),
                rs.getObject("cliente_id", UUID.class),
                rs.getObject("servico_id", UUID.class),
                rs.getObject("profissional_id", UUID.class),
                rs.getObject("aceita_de", LocalDate.class),
                rs.getObject("aceita_ate", LocalDate.class),
                rs.getObject("hora_de", LocalTime.class),
                rs.getObject("hora_ate", LocalTime.class),
                rs.getString("observacao"),
                rs.getObject("criado_em", OffsetDateTime.class).toInstant());
    }
}
