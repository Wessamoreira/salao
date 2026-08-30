package br.com.salao.iam.internal.infra;

import br.com.salao.shared.tenant.ResolvedorDeTenant;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * RT-IAM-002 — o tenant sai do token, não de um cabeçalho.
 *
 * <p>Substitui, em produção, o {@code ResolvedorDeTenantPorCabecalho}, que sempre foi restrito a
 * dev e test justamente porque um cabeçalho escolhido pelo cliente decidindo o estabelecimento é
 * troca de identidade por HTTP.
 *
 * <p>Aqui o valor vem de uma claim assinada: alterá-la invalida a assinatura, e o token é rejeitado
 * antes de este resolvedor ser consultado.
 *
 * <p>{@code @Order} baixo para ganhar do resolvedor de cabeçalho quando os dois existirem — em
 * dev, um login real deve prevalecer sobre o atalho.
 */
@Order(0)
public class ResolvedorDeTenantPorJwt implements ResolvedorDeTenant {

    @Override
    public UUID resolver(HttpServletRequest requisicao) {
        var autenticacao = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacao == null || !(autenticacao.getPrincipal() instanceof Jwt jwt)) {
            return null;
        }
        String valor = jwt.getClaimAsString(EmissorDeTokenJwt.CLAIM_ESTABELECIMENTO);
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(valor);
        } catch (IllegalArgumentException e) {
            // Token assinado por nós com claim inválida seria bug de emissão, não ataque —
            // mas tratar como "sem tenant" faz a transação falhar de forma barulhenta.
            return null;
        }
    }
}
