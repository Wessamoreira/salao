package br.com.salao.iam.internal.application;

import br.com.salao.iam.api.ErrosDoIam;
import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.api.SessaoIniciada;
import br.com.salao.iam.internal.domain.RefreshTokenArmazenado;
import br.com.salao.iam.internal.domain.SegredoOpaco;
import br.com.salao.iam.internal.infra.CredenciaisJdbc;
import br.com.salao.iam.internal.infra.EmissorDeTokenJwt;
import br.com.salao.iam.internal.infra.RefreshTokensJdbc;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RT-IAM-003 — troca o refresh por um par novo, e detecta vazamento.
 *
 * <h2>A rotação é o que torna o vazamento detectável</h2>
 *
 * <p>Um refresh de uso único deixa um rastro: depois de trocado, ele nunca deveria voltar. Quando
 * volta, só há uma explicação — <strong>duas partes têm o mesmo token</strong>. Não dá para saber
 * qual delas é a legítima, e é por isso que a resposta é derrubar a <em>família</em> inteira e
 * exigir login: o atacante perde o acesso, e o usuário legítimo perde a sessão. Entre errar para
 * um lado e para o outro, este é o lado certo.
 *
 * <p>Sem rotação, um refresh roubado funcionaria por trinta dias em silêncio, e nada no sistema
 * teria como notar.
 *
 * <h2>A janela de tolerância, e por que ela existe</h2>
 *
 * <p>Cliente com rede instável reenvia. Duas requisições com o mesmo refresh chegam quase juntas:
 * uma rotaciona, a outra encontra o token já usado — e seria classificada como vazamento,
 * derrubando a sessão de quem só teve internet ruim.
 *
 * <p>Dentro da janela, a segunda é <strong>recusada sem revogar a família</strong>. Recusar já
 * basta: o cliente que fez a primeira já recebeu o par novo. Fora da janela, aí sim é vazamento.
 */
public class RenovarAcessoUseCase {

    private static final Logger log = LoggerFactory.getLogger(RenovarAcessoUseCase.class);

    private final RefreshTokensJdbc tokens;
    private final CredenciaisJdbc credenciais;
    private final EmissorDeTokenJwt emissor;
    private final Relogio relogio;
    private final Duration validade;
    private final Duration toleranciaDeReenvio;
    private final Counter reusoDetectado;

    public RenovarAcessoUseCase(RefreshTokensJdbc tokens, CredenciaisJdbc credenciais,
                                EmissorDeTokenJwt emissor, Relogio relogio,
                                Duration validade, Duration toleranciaDeReenvio,
                                MeterRegistry registro) {
        this.tokens = tokens;
        this.credenciais = credenciais;
        this.emissor = emissor;
        this.relogio = relogio;
        this.validade = validade;
        this.toleranciaDeReenvio = toleranciaDeReenvio;
        // Qualquer valor aqui merece investigação: significa que um refresh existia em dois
        // lugares. É sinal de segurança, não métrica de capacidade.
        this.reusoDetectado = Counter.builder("auth.refresh.reuso.detectado")
                .description("Refresh já rotacionado reapresentado; indica vazamento de token")
                .register(registro);
    }

    public SessaoIniciada executar(String segredo, String ip, String userAgent) {
        if (segredo == null || segredo.isBlank()) {
            throw refreshInvalido();
        }
        var encontrado = tokens.porHash(SegredoOpaco.hashDe(segredo));
        if (encontrado.isEmpty()) {
            log.info("Renovação recusada: refresh desconhecido");
            throw refreshInvalido();
        }
        RefreshTokenArmazenado atual = encontrado.get();
        return TenantContext.obter(atual.estabelecimentoId(),
                () -> concluir(atual, ip, userAgent));
    }

    private SessaoIniciada concluir(RefreshTokenArmazenado atual, String ip, String userAgent) {
        Instant agora = relogio.agora();

        if (atual.revogado()) {
            log.warn("Renovação recusada: refresh de família revogada (usuário {})",
                    atual.usuarioId());
            throw refreshInvalido();
        }

        if (atual.jaUsado()) {
            tratarReapresentacao(atual, agora);
            throw refreshInvalido();
        }

        if (atual.expirado(agora)) {
            log.info("Renovação recusada: refresh expirado (usuário {})", atual.usuarioId());
            throw refreshInvalido();
        }

        String novoSegredo = SegredoOpaco.gerar();
        var rotacionado = tokens.rotacionar(atual, SegredoOpaco.hashDe(novoSegredo), agora,
                agora.plus(validade), ip, userAgent);

        if (rotacionado.isEmpty()) {
            // Perdeu a corrida para outra requisição simultânea. Quem venceu já entregou o par
            // novo ao cliente; recusar aqui é o suficiente, e revogar seria punir uma corrida.
            log.info("Renovação recusada: corrida perdida na rotação (usuário {})",
                    atual.usuarioId());
            throw refreshInvalido();
        }

        var credencial = credenciais.porId(atual.usuarioId())
                .orElseThrow(this::refreshInvalido);
        if (!credencial.ativo()) {
            // Desativar um usuário não invalida o access token dele (ele não é revogável), mas
            // corta aqui a renovação: em no máximo 15 minutos o acesso acaba de fato.
            tokens.revogarFamilia(atual.familiaId(), agora, "usuário desativado");
            log.info("Renovação recusada: usuário {} inativo", atual.usuarioId());
            throw refreshInvalido();
        }

        var acesso = emitirAcesso(credencial.usuarioId(), atual.estabelecimentoId(),
                credencial.perfil());
        return new SessaoIniciada(acesso, novoSegredo, agora.plus(validade));
    }

    private void tratarReapresentacao(RefreshTokenArmazenado atual, Instant agora) {
        boolean dentroDaTolerancia =
                atual.usadoEm().plus(toleranciaDeReenvio).isAfter(agora);

        if (dentroDaTolerancia) {
            log.info("Renovação recusada: refresh reapresentado dentro da janela de tolerância "
                    + "(usuário {}); provável reenvio do cliente, família preservada",
                    atual.usuarioId());
            return;
        }

        int revogados = tokens.revogarFamilia(atual.familiaId(), agora,
                "reuso de refresh já rotacionado");
        reusoDetectado.increment();
        log.error("REUSO DE REFRESH detectado: usuário {}, família {} revogada ({} tokens). "
                        + "O token existia em dois lugares — trate como vazamento.",
                atual.usuarioId(), atual.familiaId(), revogados);
    }

    private br.com.salao.iam.api.TokenDeAcesso emitirAcesso(java.util.UUID usuarioId,
                                                            java.util.UUID tenant, Perfil perfil) {
        return emissor.emitir(usuarioId, tenant, perfil);
    }

    private ErroDeDominio refreshInvalido() {
        // Um código só para desconhecido, expirado, revogado e reusado: distinguir diria a quem
        // testa tokens o que aconteceu com cada um.
        return new ErroDeDominio(ErrosDoIam.SESSAO_EXPIRADA,
                "Sessão expirada. Entre novamente.");
    }
}
