package br.com.salao.conversacional.internal.infra;

import br.com.salao.conversacional.internal.application.Orquestrador;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * RT-CNV-004 — a fila de conversas, dentro do escopo do salão.
 *
 * <p>Toda transição de estado é condicionada a {@code status = 'PENDENTE'}: a execução que outra
 * instância já encerrou não é sobrescrita.
 */
public class ExecucoesJdbc {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ExecucoesJdbc(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** @return verdadeiro se entrou agora; o aviso repetido do outbox encontra a linha e sai */
    @Transactional
    public boolean enfileirar(UUID estabelecimentoId, UUID mensagemId, String telefone,
                              String entrada, Instant agora) {
        return jdbc.sql("""
                insert into execucao_do_agente
                    (estabelecimento_id, mensagem_id, de_telefone, entrada, proxima_tentativa_em)
                values (:estabelecimento, :mensagem, :telefone, :entrada, :agora)
                on conflict (mensagem_id) do nothing
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("mensagem", mensagemId)
                .param("telefone", telefone)
                .param("entrada", entrada)
                .param("agora", Timestamp.from(agora))
                .update() == 1;
    }

    /** As últimas trocas concluídas deste telefone, da mais antiga para a mais recente. */
    @Transactional(readOnly = true)
    public List<Orquestrador.Troca> historico(String telefone, Instant desde, int limite) {
        var trocas = new ArrayList<>(jdbc.sql("""
                select entrada, resposta from execucao_do_agente
                 where de_telefone = :telefone and status = 'CONCLUIDA' and concluida_em >= :desde
                 order by concluida_em desc
                 limit :limite
                """)
                .param("telefone", telefone)
                .param("desde", Timestamp.from(desde))
                .param("limite", limite)
                .query((rs, n) -> new Orquestrador.Troca(rs.getString("entrada"),
                        rs.getString("resposta")))
                .list());
        Collections.reverse(trocas);
        return trocas;
    }

    @Transactional
    public int concluir(UUID id, UUID usuarioEfetivoId, Orquestrador.Resultado resultado,
                        Instant agora) {
        return jdbc.sql("""
                update execucao_do_agente
                   set status = 'CONCLUIDA', usuario_efetivo_id = :usuario, resposta = :resposta,
                       ferramentas = cast(:ferramentas as jsonb), iteracoes = :iteracoes,
                       modelo = :modelo, tokens_entrada = :entrada, tokens_saida = :saida,
                       custo_estimado_usd = :custo, concluida_em = :agora,
                       reservada_ate = null, ultimo_erro = null
                 where id = :id and status = 'PENDENTE'
                """)
                .param("id", id)
                .param("usuario", usuarioEfetivoId)
                .param("resposta", resultado.resposta())
                .param("ferramentas", json.writeValueAsString(resultado.chamadas()))
                .param("iteracoes", resultado.iteracoes())
                .param("modelo", resultado.modelo())
                .param("entrada", resultado.tokensEntrada())
                .param("saida", resultado.tokensSaida())
                .param("custo", resultado.custo())
                .param("agora", Timestamp.from(agora))
                .update();
    }

    /**
     * RT-CNV-005 — encerrada sem o modelo: a resposta a uma confirmação é do servidor, e não houve
     * ferramenta, token nem custo.
     */
    @Transactional
    public int concluirSemModelo(UUID id, UUID usuarioEfetivoId, String resposta, Instant agora) {
        return jdbc.sql("""
                update execucao_do_agente
                   set status = 'CONCLUIDA', usuario_efetivo_id = :usuario, resposta = :resposta,
                       concluida_em = :agora, reservada_ate = null, ultimo_erro = null
                 where id = :id and status = 'PENDENTE'
                """)
                .param("id", id)
                .param("usuario", usuarioEfetivoId)
                .param("resposta", resposta)
                .param("agora", Timestamp.from(agora))
                .update();
    }

    /**
     * Encerra sem conversa. {@code parcial} é o que chegou a acontecer antes do corte — o teto pode
     * ser atingido depois de algumas chamadas, e o custo delas precisa ficar registrado.
     */
    @Transactional
    public int recusar(UUID id, String motivo, UUID usuarioEfetivoId,
                       Orquestrador.Resultado parcial, Instant agora) {
        return jdbc.sql("""
                update execucao_do_agente
                   set status = 'RECUSADA', motivo_recusa = :motivo, usuario_efetivo_id = :usuario,
                       ferramentas = cast(:ferramentas as jsonb), iteracoes = :iteracoes,
                       modelo = :modelo, tokens_entrada = :entrada, tokens_saida = :saida,
                       custo_estimado_usd = :custo, concluida_em = :agora,
                       reservada_ate = null, ultimo_erro = null
                 where id = :id and status = 'PENDENTE'
                """)
                .param("id", id)
                .param("motivo", motivo)
                .param("usuario", usuarioEfetivoId)
                .param("ferramentas", parcial == null ? "[]" : json.writeValueAsString(parcial.chamadas()))
                .param("iteracoes", parcial == null ? 0 : parcial.iteracoes())
                .param("modelo", parcial == null ? null : parcial.modelo())
                .param("entrada", parcial == null ? 0 : parcial.tokensEntrada())
                .param("saida", parcial == null ? 0 : parcial.tokensSaida())
                .param("custo", parcial == null ? java.math.BigDecimal.ZERO : parcial.custo())
                .param("agora", Timestamp.from(agora))
                .update();
    }

    @Transactional
    public int adiar(UUID id, String erro, Instant proxima) {
        return jdbc.sql("""
                update execucao_do_agente
                   set reservada_ate = null, proxima_tentativa_em = :proxima, ultimo_erro = :erro
                 where id = :id and status = 'PENDENTE'
                """)
                .param("id", id).param("proxima", Timestamp.from(proxima)).param("erro", erro)
                .update();
    }

    @Transactional
    public int falhar(UUID id, String erro) {
        return jdbc.sql("""
                update execucao_do_agente
                   set status = 'FALHOU', reservada_ate = null, ultimo_erro = :erro
                 where id = :id and status = 'PENDENTE'
                """)
                .param("id", id).param("erro", erro)
                .update();
    }
}
