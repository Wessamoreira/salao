package br.com.salao.notificacao.internal.infra;

import br.com.salao.cliente.api.Finalidade;
import br.com.salao.notificacao.api.CanalDeEnvio;
import br.com.salao.notificacao.api.StatusDaMensagem;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** RT-NOT-001 — a fila de mensagens. */
public class MensagensJdbc {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public MensagensJdbc(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Enfileira, ou devolve a que já existe.
     *
     * <p>{@code on conflict do nothing} sobre a referência: pedir duas vezes o lembrete do mesmo
     * agendamento não manda duas mensagens. É o que impede o job de varredura de mandar seis
     * lembretes do mesmo horário — e sem isso, a proteção teria de estar em cada chamador.
     */
    @Transactional
    public UUID enfileirar(UUID estabelecimentoId, UUID clienteId, Finalidade finalidade,
                           CanalDeEnvio canal, String modelo, String destino,
                           Map<String, String> parametros, UUID referencia,
                           StatusDaMensagem status, java.time.Instant agora) {
        Optional<UUID> id = jdbc.sql("""
                insert into mensagem (estabelecimento_id, cliente_id, finalidade, canal, modelo,
                                      destino, parametros, referencia, status,
                                      proxima_tentativa_em)
                values (:estabelecimento, :cliente, :finalidade, :canal, :modelo, :destino,
                        cast(:parametros as jsonb), :referencia, :status, :agora)
                on conflict (estabelecimento_id, modelo, referencia)
                     where referencia is not null do nothing
                returning id
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("cliente", clienteId)
                .param("finalidade", finalidade.name())
                .param("canal", canal.name())
                .param("modelo", modelo)
                .param("destino", destino)
                .param("parametros", json.writeValueAsString(parametros))
                .param("referencia", referencia)
                .param("status", status.name())
                .param("agora", java.time.OffsetDateTime.ofInstant(agora,
                        java.time.ZoneOffset.UTC))
                .query(UUID.class)
                .optional();

        return id.orElseGet(() -> jaExistente(modelo, referencia));
    }

    private UUID jaExistente(String modelo, UUID referencia) {
        return jdbc.sql("""
                select id from mensagem where modelo = :modelo and referencia = :referencia
                """)
                .param("modelo", modelo)
                .param("referencia", referencia)
                .query(UUID.class)
                .single();
    }

    @Transactional(readOnly = true)
    public Optional<StatusDaMensagem> statusDe(UUID id) {
        return jdbc.sql("select status from mensagem where id = :id")
                .param("id", id)
                .query(String.class)
                .optional()
                .map(StatusDaMensagem::valueOf);
    }
}
