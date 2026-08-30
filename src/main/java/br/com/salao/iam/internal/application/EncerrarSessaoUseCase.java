package br.com.salao.iam.internal.application;

import br.com.salao.iam.internal.domain.SegredoOpaco;
import br.com.salao.iam.internal.infra.RefreshTokensJdbc;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RT-IAM-004 — encerrar sessão.
 *
 * <h2>Logout nunca falha</h2>
 *
 * <p>Refresh desconhecido, já revogado ou ausente: todos terminam em sucesso. Devolver erro num
 * logout é o pior dos dois mundos — não há nada que o usuário possa fazer a respeito, e a tela
 * ficaria dizendo "não foi possível sair" enquanto o cookie já foi apagado. Do ponto de vista de
 * quem clicou, sair de uma sessão que já não existe <em>é</em> o resultado desejado.
 *
 * <p>Também não revela se o token existia: um logout que respondesse diferente para token válido
 * e inválido viraria um oráculo para testar tokens.
 *
 * <h2>O que o logout NÃO faz</h2>
 *
 * <p>Ele encerra a capacidade de <strong>renovar</strong>. O access token já emitido continua
 * válido até expirar — no máximo 15 minutos —, porque um JWT não é revogável sem consultar estado
 * a cada requisição. Ver a seção "O access token sobrevive ao logout" em
 * {@code docs/modulos/iam/RT-IAM-004-logout.md}: é decisão consciente, com o gatilho escrito para
 * quando deixar de ser aceitável.
 */
public class EncerrarSessaoUseCase {

    private static final Logger log = LoggerFactory.getLogger(EncerrarSessaoUseCase.class);

    private final RefreshTokensJdbc tokens;
    private final Relogio relogio;

    public EncerrarSessaoUseCase(RefreshTokensJdbc tokens, Relogio relogio) {
        this.tokens = tokens;
        this.relogio = relogio;
    }

    /** Encerra apenas a sessão deste dispositivo. */
    public void encerrar(String segredo) {
        if (segredo == null || segredo.isBlank()) {
            return;
        }
        var encontrado = tokens.porHash(SegredoOpaco.hashDe(segredo));
        if (encontrado.isEmpty()) {
            return;
        }
        var token = encontrado.get();
        TenantContext.executar(token.estabelecimentoId(), () -> {
            int revogados = tokens.revogarFamilia(token.familiaId(), relogio.agora(),
                    "logout");
            log.info("Sessão encerrada: usuário {}, {} token(s) revogado(s)",
                    token.usuarioId(), revogados);
        });
    }

    /**
     * Encerra todas as sessões do usuário.
     *
     * <p>É a ação de quem suspeita que alguém tem acesso — e por isso ela não pode depender do
     * cookie do dispositivo atual, que pode ser justamente o que foi perdido. Exige access token
     * válido: quem chama já provou ser o dono da conta.
     *
     * <p>Chamado dentro do escopo do tenant pela camada web, então a RLS já confere que o usuário
     * pertence ao estabelecimento de quem pediu.
     */
    public int encerrarTodas(UUID usuarioId) {
        int revogados = tokens.revogarTodasDoUsuario(usuarioId, relogio.agora(),
                "logout de todos os dispositivos");
        log.info("Todas as sessões encerradas: usuário {}, {} token(s) revogado(s)",
                usuarioId, revogados);
        return revogados;
    }
}
