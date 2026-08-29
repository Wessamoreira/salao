package br.com.salao.shared.tenant;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

/**
 * RT-INF-002 — resolvedor de <strong>desenvolvimento</strong>: lê {@code X-Estabelecimento-Id}.
 *
 * <p><strong>Nunca deve ser registrado em produção.</strong> Um cabeçalho controlado pelo cliente
 * escolhendo o tenant é troca de identidade por HTTP. O registro é condicionado aos perfis
 * {@code dev} e {@code test} em {@link br.com.salao.shared.tenant.TenantConfig}, e
 * {@code VerificadorDeResolvedorDeTenant} derruba a aplicação se ele aparecer em prod.
 */
public class ResolvedorDeTenantPorCabecalho implements ResolvedorDeTenant {

    public static final String CABECALHO = "X-Estabelecimento-Id";

    @Override
    public UUID resolver(HttpServletRequest requisicao) {
        String valor = requisicao.getHeader(CABECALHO);
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(valor.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
