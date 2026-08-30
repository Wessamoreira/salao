package br.com.salao.iam.internal.application;

import br.com.salao.iam.api.SessaoIniciada;
import br.com.salao.iam.internal.domain.CredencialDeAcesso;
import br.com.salao.iam.internal.domain.SegredoOpaco;
import br.com.salao.iam.internal.infra.EmissorDeTokenJwt;
import br.com.salao.iam.internal.infra.RefreshTokensJdbc;
import br.com.salao.shared.tempo.Relogio;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * RT-IAM-005 — abre a sessão de verdade: zera falhas, emite refresh e access.
 *
 * <p>Extraído porque agora há <strong>dois caminhos</strong> que terminam numa sessão: login sem
 * MFA, e login com MFA depois do segundo fator conferido. Duplicar essa sequência abriria a porta
 * para um dos caminhos esquecer de zerar as falhas ou de abrir família nova — e o esquecimento
 * seria silencioso.
 */
public class AbridorDeSessao {

    private final br.com.salao.iam.internal.infra.CredenciaisJdbc credenciais;
    private final EmissorDeTokenJwt emissor;
    private final RefreshTokensJdbc refreshTokens;
    private final Relogio relogio;
    private final Duration validadeDoRefresh;

    public AbridorDeSessao(br.com.salao.iam.internal.infra.CredenciaisJdbc credenciais,
                           EmissorDeTokenJwt emissor, RefreshTokensJdbc refreshTokens,
                           Relogio relogio, Duration validadeDoRefresh) {
        this.credenciais = credenciais;
        this.emissor = emissor;
        this.refreshTokens = refreshTokens;
        this.relogio = relogio;
        this.validadeDoRefresh = validadeDoRefresh;
    }

    public SessaoIniciada abrir(CredencialDeAcesso credencial) {
        Instant agora = relogio.agora();
        credenciais.registrarSucesso(credencial.usuarioId());

        // Cada login abre uma FAMÍLIA nova de refresh: entrar de novo não derruba a sessão
        // do celular, porque as cadeias são independentes.
        String segredo = SegredoOpaco.gerar();
        Instant expiraEm = agora.plus(validadeDoRefresh);
        refreshTokens.emitirNovaFamilia(credencial.estabelecimentoId(), credencial.usuarioId(),
                UUID.randomUUID(), SegredoOpaco.hashDe(segredo), expiraEm, null, null);

        var acesso = emissor.emitir(credencial.usuarioId(), credencial.estabelecimentoId(),
                credencial.perfil());
        return new SessaoIniciada(acesso, segredo, expiraEm);
    }
}
