package br.com.salao.conversacional.internal.infra;

import br.com.salao.conversacional.internal.application.Transcritor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CNV-003 — a fila de transcrição, dentro do escopo do salão.
 *
 * <p>Toda escrita de estado é CONDICIONADA ao estado de onde se sai ({@code where status = ...}). É o
 * que torna a transição segura sem lock longo: se outra instância chegou antes, a atualização afeta
 * zero linhas e nada é sobrescrito — inclusive uma transcrição concluída, que não é reescrita.
 */
public class TranscricoesJdbc {

    private final JdbcClient jdbc;

    public TranscricoesJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Transcricao(UUID id, UUID mensagemId, String status, UUID arquivoId,
                              String texto, int tentativas) {
    }

    /** @return verdadeiro se entrou agora; falso se a mensagem já estava na fila */
    @Transactional
    public boolean enfileirar(UUID estabelecimentoId, UUID mensagemId, Instant agora) {
        return jdbc.sql("""
                insert into transcricao_de_audio
                    (estabelecimento_id, mensagem_id, proxima_tentativa_em)
                values (:estabelecimento, :mensagem, :agora)
                on conflict (mensagem_id) do nothing
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("mensagem", mensagemId)
                .param("agora", Timestamp.from(agora))
                .update() == 1;
    }

    @Transactional(readOnly = true)
    public Optional<Transcricao> porMensagem(UUID mensagemId) {
        return jdbc.sql("""
                select id, mensagem_id, status, arquivo_id, texto, tentativas
                  from transcricao_de_audio where mensagem_id = :mensagem
                """)
                .param("mensagem", mensagemId)
                .query((rs, n) -> new Transcricao(rs.getObject("id", UUID.class),
                        rs.getObject("mensagem_id", UUID.class), rs.getString("status"),
                        rs.getObject("arquivo_id", UUID.class), rs.getString("texto"),
                        rs.getInt("tentativas")))
                .optional();
    }

    /**
     * Trava a linha, se ela ainda estiver esperando o download.
     *
     * <p>{@code MANDATORY} de propósito: o lock só vale dentro da transação de quem vai guardar o
     * áudio. Chamado solto, soltaria o lock no fim do próprio SELECT — o defeito que a reserva
     * atômica existe para evitar.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean travarPendente(UUID id) {
        return jdbc.sql("""
                select id from transcricao_de_audio
                 where id = :id and status = 'PENDENTE'
                   for update
                """)
                .param("id", id)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    @Transactional
    public int marcarBaixada(UUID id, UUID arquivoId, Instant agora) {
        // Tentativas voltam a zero: a etapa de transcrever tem o seu próprio orçamento. Uma rede ruim
        // no download não pode ter gasto as tentativas do provedor de STT.
        return jdbc.sql("""
                update transcricao_de_audio
                   set status = 'BAIXADA', arquivo_id = :arquivo, tentativas = 0,
                       proxima_tentativa_em = :agora, reservada_ate = null, ultimo_erro = null
                 where id = :id and status = 'PENDENTE'
                """)
                .param("id", id)
                .param("arquivo", arquivoId)
                .param("agora", Timestamp.from(agora))
                .update();
    }

    @Transactional
    public int concluir(UUID id, Transcritor.TextoTranscrito resultado, Instant agora) {
        return jdbc.sql("""
                update transcricao_de_audio
                   set status = 'CONCLUIDA', texto = :texto, idioma = :idioma,
                       duracao_segundos = :duracao, provedor = :provedor,
                       concluida_em = :agora, reservada_ate = null, ultimo_erro = null
                 where id = :id and status = 'BAIXADA'
                """)
                .param("id", id)
                .param("texto", resultado.texto() == null ? "" : resultado.texto())
                .param("idioma", resultado.idioma())
                .param("duracao", resultado.duracaoSegundos())
                .param("provedor", resultado.provedor())
                .param("agora", Timestamp.from(agora))
                .update();
    }

    /** Volta para a fila, com a próxima tentativa no futuro. */
    @Transactional
    public int adiar(UUID id, String erro, Instant proxima) {
        return jdbc.sql("""
                update transcricao_de_audio
                   set reservada_ate = null, proxima_tentativa_em = :proxima, ultimo_erro = :erro
                 where id = :id and status in ('PENDENTE', 'BAIXADA')
                """)
                .param("id", id)
                .param("proxima", Timestamp.from(proxima))
                .param("erro", erro)
                .update();
    }

    @Transactional
    public int falhar(UUID id, String erro) {
        return jdbc.sql("""
                update transcricao_de_audio
                   set status = 'FALHOU', reservada_ate = null, ultimo_erro = :erro
                 where id = :id and status in ('PENDENTE', 'BAIXADA')
                """)
                .param("id", id)
                .param("erro", erro)
                .update();
    }
}
