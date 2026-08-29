package br.com.salao.shared.tenant;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

/**
 * RT-INF-002 — porta de saída para descobrir o tenant de uma requisição.
 *
 * <p>Existe para que o {@code TenantFilter} não conheça nem cabeçalho nem JWT. Em
 * {@code RT-IAM-002} a implementação passa a ler o {@code estabelecimentoId} do token.
 */
public interface ResolvedorDeTenant {

    /** O tenant da requisição, ou {@code null} se não for possível determiná-lo. */
    UUID resolver(HttpServletRequest requisicao);
}
