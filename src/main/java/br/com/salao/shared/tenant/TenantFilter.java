package br.com.salao.shared.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * RT-INF-002 — abre o escopo de {@link TenantContext} para a requisição.
 *
 * <p>Delega a identificação do tenant a um {@link ResolvedorDeTenant}. Hoje existe apenas o
 * resolvedor de desenvolvimento (cabeçalho HTTP); em {@code RT-IAM-002} entra o resolvedor
 * baseado no JWT, e o de cabeçalho deixa de ser registrado fora de dev/test.
 *
 * <p>Requisição sem tenant resolvido não é rejeitada aqui — segue sem escopo, e qualquer
 * transação que toque tabela de negócio falha com {@link TenantNaoDefinidoException}. Rejeitar
 * aqui é trabalho da autenticação, que ainda não existe.
 */
public class TenantFilter extends OncePerRequestFilter {

    public static final String CAMPO_DE_LOG = "tenantId";

    private final ResolvedorDeTenant resolvedor;

    public TenantFilter(ResolvedorDeTenant resolvedor) {
        this.resolvedor = resolvedor;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta,
                                    FilterChain cadeia) throws ServletException, IOException {
        UUID tenant = resolvedor.resolver(requisicao);
        if (tenant == null) {
            cadeia.doFilter(requisicao, resposta);
            return;
        }
        // RT-INF-008: o tenant vai para o MDC junto com o escopo. Todo log da requisição passa a
        // dizer de qual estabelecimento é — sem isso, investigar um incidente em produção
        // multi-tenant vira adivinhação. Nunca telefone, nome ou conteúdo: só o identificador.
        MDC.put(CAMPO_DE_LOG, tenant.toString());
        try {
            TenantContext.executar(tenant, () -> {
                try {
                    cadeia.doFilter(requisicao, resposta);
                } catch (IOException | ServletException e) {
                    throw new FalhaNaCadeiaDeFiltros(e);
                }
            });
        } catch (FalhaNaCadeiaDeFiltros e) {
            switch (e.getCause()) {
                case IOException io -> throw io;
                case ServletException se -> throw se;
                default -> throw e;
            }
        } finally {
            MDC.remove(CAMPO_DE_LOG);
        }
    }

    private static final class FalhaNaCadeiaDeFiltros extends RuntimeException {
        FalhaNaCadeiaDeFiltros(Exception causa) {
            super(causa);
        }
    }
}
