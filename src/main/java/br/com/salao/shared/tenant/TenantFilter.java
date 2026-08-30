package br.com.salao.shared.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * RT-INF-002 — abre o escopo de {@link TenantContext} para a requisição.
 *
 * <p>Delega a identificação a uma lista ordenada de {@link ResolvedorDeTenant}: o do JWT primeiro
 * (RT-IAM-002) e, só em dev e test, o de cabeçalho como atalho.
 *
 * <p><strong>Roda depois da cadeia do Spring Security</strong>, e a ordem importa: o resolvedor do
 * JWT lê o {@code SecurityContext}, que só existe depois da autenticação. Antes dela, ele
 * encontraria sempre {@code null} e toda requisição autenticada cairia em
 * {@link TenantNaoDefinidoException}.
 *
 * <p>Requisição sem tenant resolvido não é rejeitada aqui — segue sem escopo, e qualquer
 * transação que toque tabela de negócio falha com {@link TenantNaoDefinidoException}. Rejeitar
 * aqui é trabalho da autenticação, que ainda não existe.
 */
public class TenantFilter extends OncePerRequestFilter {

    public static final String CAMPO_DE_LOG = "tenantId";

    private final List<ResolvedorDeTenant> resolvedores;

    public TenantFilter(List<ResolvedorDeTenant> resolvedores) {
        this.resolvedores = List.copyOf(resolvedores);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta,
                                    FilterChain cadeia) throws ServletException, IOException {
        UUID tenant = resolver(requisicao);
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

    /**
     * Primeiro resolvedor que souber responder ganha. Em produção há só o do JWT; em dev o de
     * cabeçalho entra depois dele, como atalho — nunca no lugar de um login real.
     */
    private UUID resolver(HttpServletRequest requisicao) {
        for (ResolvedorDeTenant resolvedor : resolvedores) {
            UUID tenant = resolvedor.resolver(requisicao);
            if (tenant != null) {
                return tenant;
            }
        }
        return null;
    }

    private static final class FalhaNaCadeiaDeFiltros extends RuntimeException {
        FalhaNaCadeiaDeFiltros(Exception causa) {
            super(causa);
        }
    }
}
