package br.com.salao.shared.rede;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * RT-INF-011 — limite de requisições por IP.
 *
 * <h2>O que isto pega que o bloqueio por usuário não pega</h2>
 *
 * <p>O bloqueio progressivo de RT-IAM-002 conta falhas <em>por conta</em>. Um atacante que
 * tente uma senha comum contra mil e-mails diferentes acumula <strong>uma</strong> falha em cada
 * conta e nunca dispara bloqueio nenhum. É o <em>password spraying</em>, e é justamente o ataque
 * que só um limite por origem detém.
 *
 * <h2>Duas faixas, porque os riscos são diferentes</h2>
 *
 * <p>As rotas de autenticação são estreitas: quem entra no salão erra a senha duas ou três vezes,
 * não vinte. O resto da API é largo — uma tela de agenda dispara várias chamadas ao abrir, e
 * apertar ali transformaria uso normal em erro.
 *
 * <h2>Contagem local, e a consequência assumida</h2>
 *
 * <p>Os buckets vivem em memória, por instância. Com N instâncias atrás de um balanceador, o
 * limite efetivo é N vezes maior — o atacante distribuído passa mais do que o número diz. É o
 * gatilho de Redis já registrado em ADR-0004 ("rate limit distribuído preciso"), e até lá o
 * número é um teto aproximado, não uma garantia. Preferível a um teto exato que custa um
 * contêiner novo numa VM pequena.
 *
 * <p>O cache tem teto de tamanho e expiração: um mapa sem limite indexado por IP é, ele próprio,
 * um caminho para esgotar memória — o ataque que o limitador deveria conter.
 */
public class LimitadorDeTaxa extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LimitadorDeTaxa.class);

    private static final String PREFIXO_AUTENTICACAO = "/api/v1/auth/";

    private final Cache<String, Bucket> baldes;
    private final EnderecoDoCliente endereco;
    private final int limiteDeAutenticacao;
    private final int limiteGeral;
    private final Counter recusas;

    public LimitadorDeTaxa(EnderecoDoCliente endereco, int limiteDeAutenticacao, int limiteGeral,
                           MeterRegistry registro) {
        this.endereco = endereco;
        this.limiteDeAutenticacao = limiteDeAutenticacao;
        this.limiteGeral = limiteGeral;
        this.baldes = Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfterAccess(Duration.ofMinutes(10))
                .build();
        this.recusas = Counter.builder("rede.limite.recusas")
                .description("Requisições recusadas por limite de taxa por IP")
                .register(registro);
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta,
                                    FilterChain cadeia) throws ServletException, IOException {
        String caminho = requisicao.getRequestURI();
        if (!caminho.startsWith("/api/")) {
            cadeia.doFilter(requisicao, resposta);
            return;
        }

        boolean autenticacao = caminho.startsWith(PREFIXO_AUTENTICACAO);
        String chave = (autenticacao ? "auth|" : "geral|") + endereco.de(requisicao);
        Bucket balde = baldes.get(chave, k -> criar(autenticacao));

        ConsumptionProbe tentativa = balde.tryConsumeAndReturnRemaining(1);
        if (tentativa.isConsumed()) {
            cadeia.doFilter(requisicao, resposta);
            return;
        }

        recusas.increment();
        long segundos = Math.max(1, tentativa.getNanosToWaitForRefill() / 1_000_000_000L);
        // Sem o IP no log: ele é dado pessoal sob a LGPD, e a contagem já está na métrica.
        log.warn("Requisição recusada por limite de taxa em {}", autenticacao ? "auth" : "api");

        resposta.setStatus(429);
        resposta.setHeader("Retry-After", String.valueOf(segundos));
        resposta.setContentType("application/problem+json");
        resposta.getWriter().write("""
                {"type":"https://api.salao.app/erros/er-inf-limite_de_requisicoes",\
                "title":"Muitas requisições","status":429,\
                "detail":"Muitas tentativas. Aguarde alguns instantes.",\
                "codigo":"ER-INF-LIMITE_DE_REQUISICOES"}""");
    }

    private Bucket criar(boolean autenticacao) {
        int limite = autenticacao ? limiteDeAutenticacao : limiteGeral;
        return Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(limite)
                        .refillGreedy(limite, Duration.ofMinutes(1)).build())
                .build();
    }
}
