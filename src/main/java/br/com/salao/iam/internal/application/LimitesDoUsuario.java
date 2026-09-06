package br.com.salao.iam.internal.application;

import br.com.salao.iam.api.LimitesDoUsuarioApi;
import br.com.salao.iam.internal.domain.MapaDePermissoes;
import br.com.salao.iam.internal.infra.CredenciaisJdbc;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-ATD-004 — o teto de desconto de quem está agindo.
 *
 * <h2>O perfil vem do banco, não do token</h2>
 *
 * <p>O token carrega o perfil, e usá-lo seria mais barato. Mas ele é <strong>autocontido</strong>:
 * emitido, vale até expirar, e o próprio {@code EmissorDeTokenJwt} avisa que um perfil rebaixado no
 * meio do expediente continua valendo no token antigo.
 *
 * <p>Para desenhar menu isso é aceitável. Para autorizar dinheiro, não: seria uma janela em que a
 * recepcionista que acabou de perder a alçada continua dando cem por cento de desconto, e a trilha
 * mostraria a permissão que ela já não tinha.
 */
public class LimitesDoUsuario implements LimitesDoUsuarioApi {

    private final CredenciaisJdbc credenciais;

    public LimitesDoUsuario(CredenciaisJdbc credenciais) {
        this.credenciais = credenciais;
    }

    @Override
    @Transactional(readOnly = true)
    public int descontoMaximoPercentual(UUID usuarioId) {
        if (usuarioId == null) {
            return 0;
        }
        return credenciais.porId(usuarioId)
                .map(usuario -> MapaDePermissoes.descontoMaximoPercentual(usuario.perfil()))
                .orElse(0);
    }
}
