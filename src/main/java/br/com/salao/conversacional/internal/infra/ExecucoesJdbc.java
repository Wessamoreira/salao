package br.com.salao.conversacional.internal.infra;

import br.com.salao.conversacional.internal.application.ConcluirExecucao;
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

    /**
     * RT-CNV-009 — entre as mensagens de uma rajada. Uma quebra de linha, e não " " nem "; ": é como
     * a pessoa escreveu.
     *
     * <p>Uma constante, e não um literal em cada consulta, porque o texto que o modelo LEU e o que a
     * trilha REMONTA precisam ser o mesmo texto — separadores diferentes fariam a trilha mentir por
     * um caractere, que é o tipo de divergência que ninguém encontra.
     */
    public static final String SEPARADOR_DA_RAJADA = "\n";

    /**
     * O que a pessoa disse por inteiro: a entrada desta execução mais a das que ela absorveu, na
     * ordem em que chegaram — {@code mensagem_recebida.sequencia}, e não a ordem da fila, que o áudio
     * inverte (a transcrição termina depois do texto que veio depois dele).
     */
    private static final String ENTRADA_DA_RAJADA = """
            (select string_agg(j.entrada, :separador order by jm.sequencia)
               from execucao_do_agente j
               join mensagem_recebida jm on jm.id = j.mensagem_id
              where j.id = e.id or j.respondida_por = e.id)""";

    /**
     * RT-CNV-006 — de onde veio a execução.
     *
     * <p>{@code BOTAO} é o toque num botão de aceite: não passa pelo modelo, não entra no histórico de
     * conversa (o id do botão carrega o token do aceite) e pode vir de quem não tem conta.
     */
    public enum Tipo {
        CONVERSA,
        BOTAO
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ExecucoesJdbc(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** @return verdadeiro se entrou agora; o aviso repetido do outbox encontra a linha e sai */
    @Transactional
    public boolean enfileirar(UUID estabelecimentoId, UUID mensagemId, String telefone,
                              String entrada, Tipo tipo, Instant agora) {
        return jdbc.sql("""
                insert into execucao_do_agente
                    (estabelecimento_id, mensagem_id, de_telefone, entrada, tipo, proxima_tentativa_em)
                values (:estabelecimento, :mensagem, :telefone, :entrada, :tipo, :agora)
                on conflict (mensagem_id) do nothing
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("mensagem", mensagemId)
                .param("telefone", telefone)
                .param("entrada", entrada)
                .param("tipo", tipo.name())
                .param("agora", Timestamp.from(agora))
                .update() == 1;
    }

    /** As últimas trocas concluídas deste telefone, da mais antiga para a mais recente. */
    @Transactional(readOnly = true)
    public List<Orquestrador.Troca> historico(String telefone, Instant desde, int limite) {
        var trocas = new ArrayList<>(jdbc.sql("""
                select %s as entrada, e.resposta from execucao_do_agente e
                 where e.de_telefone = :telefone and e.status = 'CONCLUIDA'
                   and e.concluida_em >= :desde and e.tipo = 'CONVERSA'
                 order by e.concluida_em desc
                 limit :limite
                """.formatted(ENTRADA_DA_RAJADA))
                .param("telefone", telefone)
                .param("separador", SEPARADOR_DA_RAJADA)
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
     *
     * @param usuarioEfetivoId nulo só no toque de botão de quem não é usuário (RT-CNV-006)
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

    /**
     * RT-CNV-009 — a rajada INTEIRA volta para a fila, e volta junta.
     *
     * <p>Adiar só quem responderia deixaria as absorvidas reservadas por dez minutos: a volta
     * seguinte leria metade do que a pessoa disse, responderia com isso, e o resto viraria uma
     * segunda resposta. Soltar a reserva das duas pontas é o que faz a rajada se reformar igual.
     */
    @Transactional
    public int adiar(ConcluirExecucao.Alvo alvo, String erro, Instant proxima) {
        var ids = new ArrayList<UUID>(alvo.absorvidas());
        ids.add(alvo.id());
        return jdbc.sql("""
                update execucao_do_agente
                   set reservada_ate = null, proxima_tentativa_em = :proxima, ultimo_erro = :erro
                 where id in (:ids) and status = 'PENDENTE'
                """)
                .param("ids", ids)
                .param("proxima", Timestamp.from(proxima)).param("erro", erro)
                .update();
    }

    /**
     * RN-CNV-030 — as mensagens que foram respondidas junto com a seguinte.
     *
     * <p>Não some com a linha: a trilha responde "o que o agente fez com esta mensagem?", e "foi
     * respondida junto com a seguinte" é uma resposta. Condicionada a {@code PENDENTE} como todas as
     * transições daqui, e no mesmo commit do encerramento de quem respondeu.
     */
    @Transactional
    public int juntar(List<UUID> absorvidas, UUID quemRespondeu) {
        if (absorvidas.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                update execucao_do_agente
                   set status = 'JUNTADA', respondida_por = :respondeu, reservada_ate = null
                 where id in (:ids) and status = 'PENDENTE'
                """)
                .param("respondeu", quemRespondeu)
                .param("ids", absorvidas)
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
