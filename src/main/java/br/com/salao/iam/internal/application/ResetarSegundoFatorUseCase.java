package br.com.salao.iam.internal.application;

import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.infra.MfaJdbc;
import br.com.salao.iam.internal.infra.RefreshTokensJdbc;
import br.com.salao.shared.tempo.Relogio;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * RT-IAM-007 — administrador remove o segundo fator de alguém.
 *
 * <p>Fecha a pendência de RT-IAM-005: quem perdia o celular <em>e</em> os códigos de recuperação
 * ficava sem acesso, sem caminho de volta que não fosse mexer no banco.
 *
 * <p><strong>É uma operação perigosa, e o desenho reconhece isso.</strong> Ela remove uma camada
 * de segurança de outra pessoa, então: exige {@code usuario:manage}, encerra todas as sessões do
 * alvo, e registra em log com nível de aviso. Quem administra é responsável por confirmar a
 * identidade de quem pediu — nenhum sistema resolve isso sozinho, e fingir que resolve seria pior.
 *
 * <p>As sessões do alvo caem porque, se o pedido veio de um impostor, deixá-las abertas manteria
 * o acesso que se está tentando cortar.
 */
public class ResetarSegundoFatorUseCase {

    private static final Logger log = LoggerFactory.getLogger(ResetarSegundoFatorUseCase.class);

    private final MfaJdbc mfa;
    private final RefreshTokensJdbc tokens;
    private final Relogio relogio;

    public ResetarSegundoFatorUseCase(MfaJdbc mfa, RefreshTokensJdbc tokens, Relogio relogio) {
        this.mfa = mfa;
        this.tokens = tokens;
        this.relogio = relogio;
    }

    @PreAuthorize("hasAuthority('" + Permissao.USUARIO_GERENCIAR + "')")
    public void executar(UUID usuarioId, UUID quemPede) {
        mfa.desativar(usuarioId);
        tokens.revogarTodasDoUsuario(usuarioId, relogio.agora(), "segundo fator resetado");
        log.warn("Segundo fator do usuário {} REMOVIDO pelo administrador {}. "
                + "Se o pedido não partiu do próprio usuário, isto é um incidente.",
                usuarioId, quemPede);
    }
}
