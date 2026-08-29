package br.com.salao.shared.idempotencia;

import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.erro.ErrosDaInfra;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * RT-INF-005 — implementação sobre a unique constraint do Postgres.
 *
 * <h2>Por que uma transação só</h2>
 *
 * <p>O desenho usual é em três fases: reservar a chave numa transação própria, executar o negócio
 * em outra, gravar a resposta numa terceira. Ele dá visibilidade de "em andamento", mas abre uma
 * janela real: se o processo morre entre o commit do negócio e a gravação da resposta, a chave
 * volta a parecer livre e a repetição <strong>executa o efeito de novo</strong>. Em criação de
 * agendamento isso é uma segunda reserva; em pagamento seria uma segunda cobrança.
 *
 * <p>Aqui tudo acontece na <strong>mesma transação</strong>: o registro de idempotência e o efeito
 * de negócio commitam juntos ou não commitam. Não existe estado intermediário para ninguém
 * observar, e por isso também não existe a janela.
 *
 * <h2>Quem arbitra a concorrência é o banco</h2>
 *
 * <p>Duas requisições simultâneas com a mesma chave: a segunda <em>bloqueia</em> no índice único
 * até a primeira commitar, e então lê a resposta já gravada. Sem lock de aplicação, que não
 * sobrevive a duas instâncias — mesma decisão da exclusion constraint da agenda.
 *
 * <p>O custo assumido: a segunda requisição espera o tempo da primeira. Para o volume de um salão
 * é irrelevante, e {@code lock_timeout} limita o pior caso. A alternativa — responder 409 "em
 * andamento" na hora — exigiria a reserva em transação separada, com a janela descrita acima.
 *
 * <h2>Dependência de nível de isolamento</h2>
 *
 * <p>Funciona em {@code READ COMMITTED} (o padrão): depois que o {@code ON CONFLICT DO NOTHING}
 * devolve zero linhas por causa de um insert concorrente já commitado, o {@code SELECT} seguinte
 * enxerga a linha porque cada comando toma um snapshot novo. Em {@code REPEATABLE READ} o select
 * não a veria e o método falharia. Se algum caso de uso precisar de isolamento mais forte, esta
 * classe precisa ser revista junto.
 */
public class IdempotenciaJdbc implements Idempotencia {

    private static final String RESERVAR = """
            insert into idempotencia
                (estabelecimento_id, escopo, chave, hash_payload, expira_em)
            values (:tenant, :escopo, :chave, :hash, :expira)
            on conflict on constraint idempotencia_unica do nothing
            returning id
            """;

    private static final String BUSCAR = """
            select hash_payload, tipo_resposta, corpo_resposta::text as corpo
              from idempotencia
             where estabelecimento_id = :tenant and escopo = :escopo and chave = :chave
            """;

    private static final String GRAVAR_RESPOSTA = """
            update idempotencia
               set tipo_resposta = :tipo, corpo_resposta = cast(:corpo as jsonb)
             where estabelecimento_id = :tenant and escopo = :escopo and chave = :chave
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Relogio relogio;
    private final Duration retencao;

    public IdempotenciaJdbc(JdbcClient jdbc, ObjectMapper json, Relogio relogio,
                            Duration retencao) {
        this.jdbc = jdbc;
        this.json = json;
        this.relogio = relogio;
        this.retencao = retencao;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public <T> ResultadoIdempotente<T> executar(ChaveDeIdempotencia chave, Object payload,
                                                Class<T> tipoResposta, Supplier<T> acao) {
        var tenant = TenantContext.obrigatorio();
        String hash = hashDe(payload);

        Optional<?> reservada = jdbc.sql(RESERVAR)
                .param("tenant", tenant)
                .param("escopo", chave.escopo())
                .param("chave", chave.valor())
                .param("hash", hash)
                .param("expira", java.sql.Timestamp.from(relogio.agora().plus(retencao)))
                .query(Object.class)
                .optional();

        if (reservada.isEmpty()) {
            return repetir(chave, tenant, hash, tipoResposta);
        }

        T resultado = acao.get();

        jdbc.sql(GRAVAR_RESPOSTA)
                .param("tipo", tipoResposta.getName())
                .param("corpo", json.writeValueAsString(resultado))
                .param("tenant", tenant)
                .param("escopo", chave.escopo())
                .param("chave", chave.valor())
                .update();

        return ResultadoIdempotente.novo(resultado);
    }

    private <T> ResultadoIdempotente<T> repetir(ChaveDeIdempotencia chave, java.util.UUID tenant,
                                                String hash, Class<T> tipoResposta) {
        var registro = jdbc.sql(BUSCAR)
                .param("tenant", tenant)
                .param("escopo", chave.escopo())
                .param("chave", chave.valor())
                .query((rs, n) -> new Registro(
                        rs.getString("hash_payload"),
                        rs.getString("tipo_resposta"),
                        rs.getString("corpo")))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Chave reservada por outra transação sumiu: " + chave));

        if (!registro.hashPayload().equals(hash)) {
            // Reuso de chave com conteúdo diferente é erro do cliente, não repetição.
            // Reexecutar seria criar um segundo agendamento sob a chave do primeiro.
            throw new ErroDeDominio(ErrosDaInfra.IDEMPOTENCIA_CONFLITO,
                    "A chave de idempotência já foi usada com outro conteúdo.");
        }

        if (registro.corpoResposta() == null) {
            // A primeira transação reservou e ainda não gravou a resposta. Só acontece se ela
            // tiver falhado depois do commit da reserva — impossível no desenho de transação
            // única, mas tratado para não devolver null silenciosamente.
            throw new IllegalStateException(
                    "Registro de idempotência sem resposta gravada: " + chave);
        }

        return ResultadoIdempotente.repetido(json.readValue(registro.corpoResposta(), tipoResposta));
    }

    /**
     * SHA-256 do JSON canônico do payload.
     *
     * <p>O hash existe para detectar reuso de chave com conteúdo diferente, não para segurança —
     * mas é SHA-256 e não {@code hashCode()} porque colisão aqui significa devolver a resposta de
     * uma operação para outra.
     */
    private String hashDe(Object payload) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = json.writeValueAsBytes(payload);
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    private record Registro(String hashPayload, String tipoResposta, String corpoResposta) {
    }
}
