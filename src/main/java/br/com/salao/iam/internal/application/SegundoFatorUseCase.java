package br.com.salao.iam.internal.application;

import br.com.salao.iam.api.ErrosDoIam;
import br.com.salao.iam.api.SessaoIniciada;
import br.com.salao.iam.internal.domain.SegredoOpaco;
import br.com.salao.iam.internal.domain.Totp;
import br.com.salao.iam.internal.infra.CredenciaisJdbc;
import br.com.salao.iam.internal.infra.EmissorDeTokenJwt;
import br.com.salao.iam.internal.infra.MfaJdbc;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/** RT-IAM-005 — inscrição, confirmação, verificação e desativação do segundo fator. */
public class SegundoFatorUseCase {

    private static final Logger log = LoggerFactory.getLogger(SegundoFatorUseCase.class);

    /** ±1 janela: cobre relógio dessincronizado e o tempo entre ler e digitar. */
    private static final int TOLERANCIA_DE_JANELAS = 1;

    private static final int QUANTIDADE_DE_CODIGOS = 10;

    private final MfaJdbc mfa;
    private final CredenciaisJdbc credenciais;
    private final br.com.salao.iam.api.EstabelecimentoApi estabelecimentos;
    private final AbridorDeSessao abridor;
    private final JwtDecoder decodificador;
    private final Relogio relogio;

    public SegundoFatorUseCase(MfaJdbc mfa, CredenciaisJdbc credenciais,
                               br.com.salao.iam.api.EstabelecimentoApi estabelecimentos,
                               AbridorDeSessao abridor, JwtDecoder decodificador,
                               Relogio relogio) {
        this.mfa = mfa;
        this.credenciais = credenciais;
        this.estabelecimentos = estabelecimentos;
        this.abridor = abridor;
        this.decodificador = decodificador;
        this.relogio = relogio;
    }

    /**
     * Gera o segredo e devolve a URI que o autenticador lê.
     *
     * <p><strong>Não ativa o MFA.</strong> Ativar aqui trancaria para fora quem digitasse o
     * segredo errado no aplicativo — e o único jeito de sair seria um administrador. A ativação
     * só acontece em {@link #confirmar}, depois de a pessoa provar que consegue gerar um código.
     */
    public Inscricao inscrever(UUID tenant, UUID usuarioId) {
        // O rótulo do autenticador é resolvido aqui, e não pelo controller: buscar e-mail e nome
        // do salão é orquestração, e web não alcança infraestrutura (ArquiteturaTest reprova).
        String email = credenciais.porId(usuarioId).map(c -> c.email()).orElse("usuario");
        String nomeDoSalao = estabelecimentos.configuracao(tenant)
                .map(c -> c.nome()).orElse("Salão");

        String segredo = Totp.novoSegredo();
        mfa.salvarSegredo(tenant, usuarioId, segredo);

        String rotulo = URLEncoder.encode(nomeDoSalao + ":" + email, StandardCharsets.UTF_8);
        String emissor = URLEncoder.encode(nomeDoSalao, StandardCharsets.UTF_8);
        String uri = "otpauth://totp/" + rotulo + "?secret=" + segredo + "&issuer=" + emissor
                + "&digits=" + Totp.DIGITOS + "&period=" + Totp.JANELA.toSeconds();

        log.info("Segundo fator inscrito (pendente de confirmação) para o usuário {}", usuarioId);
        return new Inscricao(segredo, uri);
    }

    /** Confirma a inscrição e devolve os códigos de recuperação — mostrados uma única vez. */
    public Confirmacao confirmar(UUID tenant, UUID usuarioId, String codigo) {
        var credencial = mfa.porUsuario(usuarioId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoIam.MFA_NAO_INSCRITO,
                        "Nenhuma inscrição de segundo fator em andamento."));

        conferirCodigoTotp(usuarioId, credencial.segredoBase32(), codigo);
        mfa.definirAtivo(usuarioId, true);

        var codigos = gerarCodigosDeRecuperacao();
        mfa.gravarCodigosDeRecuperacao(tenant, usuarioId,
                codigos.stream().map(SegredoOpaco::hashDe).toList());

        log.info("Segundo fator ativado para o usuário {}", usuarioId);

        // Sessão nova junto: o token em uso ainda diz mfa=false e, com a imposição ligada
        // (RN-IAM-014), o usuário ficaria bloqueado logo depois de fazer exatamente o que se
        // pediu dele. Devolver o par novo aqui evita esse beco.
        var acesso = credenciais.porId(usuarioId).orElseThrow(this::codigoInvalido);
        return new Confirmacao(codigos, abridor.abrir(acesso));
    }

    /** Conclui o login: confere o desafio e o código, e só então abre a sessão. */
    public SessaoIniciada concluirLogin(String desafio, String codigo) {
        var jwt = decodificarDesafio(desafio);
        UUID usuarioId = UUID.fromString(jwt.getSubject());
        UUID tenant = UUID.fromString(
                jwt.getClaimAsString(EmissorDeTokenJwt.CLAIM_ESTABELECIMENTO));

        return TenantContext.obter(tenant, () -> {
            var credencial = mfa.porUsuario(usuarioId)
                    .filter(c -> c.confirmada())
                    .orElseThrow(this::codigoInvalido);

            if (!consumirTotpOuRecuperacao(usuarioId, credencial.segredoBase32(), codigo)) {
                throw codigoInvalido();
            }

            var acesso = credenciais.porId(usuarioId).orElseThrow(this::codigoInvalido);
            if (!acesso.ativo()) {
                throw codigoInvalido();
            }
            log.info("Segundo fator conferido; login concluído para o usuário {}", usuarioId);
            return abridor.abrir(acesso);
        });
    }

    /** Desativar exige um código válido: senão bastaria uma sessão aberta para remover o MFA. */
    public void desativar(UUID usuarioId, String codigo) {
        var credencial = mfa.porUsuario(usuarioId)
                .filter(c -> c.confirmada())
                .orElseThrow(() -> new ErroDeDominio(ErrosDoIam.MFA_NAO_INSCRITO,
                        "Segundo fator não está ativo."));

        if (!consumirTotpOuRecuperacao(usuarioId, credencial.segredoBase32(), codigo)) {
            throw codigoInvalido();
        }
        mfa.desativar(usuarioId);
        log.info("Segundo fator desativado para o usuário {}", usuarioId);
    }

    private boolean consumirTotpOuRecuperacao(UUID usuarioId, String segredo, String codigo) {
        var agora = relogio.agora();
        long contador = Totp.conferir(segredo, codigo, Totp.contadorDe(agora),
                TOLERANCIA_DE_JANELAS);

        if (contador >= 0) {
            // O contador só é aceito se for maior que o último usado: um código TOTP vale trinta
            // segundos, e sem isso quem o interceptasse poderia reapresentá-lo dentro da janela.
            return mfa.consumirContador(usuarioId, contador, agora);
        }
        // Não era TOTP: pode ser código de recuperação, que é de uso único.
        return mfa.consumirCodigoDeRecuperacao(usuarioId, SegredoOpaco.hashDe(codigo), agora);
    }

    private void conferirCodigoTotp(UUID usuarioId, String segredo, String codigo) {
        var agora = relogio.agora();
        long contador = Totp.conferir(segredo, codigo, Totp.contadorDe(agora),
                TOLERANCIA_DE_JANELAS);
        if (contador < 0 || !mfa.consumirContador(usuarioId, contador, agora)) {
            throw codigoInvalido();
        }
    }

    private org.springframework.security.oauth2.jwt.Jwt decodificarDesafio(String desafio) {
        if (desafio == null || desafio.isBlank()) {
            throw codigoInvalido();
        }
        try {
            var jwt = decodificador.decode(desafio);
            // Um access token normal NÃO pode servir de desafio: aceitar qualquer JWT aqui
            // permitiria pular a senha usando uma sessão antiga ainda válida.
            if (!EmissorDeTokenJwt.ESCOPO_SEGUNDO_FATOR.equals(
                    jwt.getClaimAsString(EmissorDeTokenJwt.CLAIM_ESCOPO))) {
                throw codigoInvalido();
            }
            return jwt;
        } catch (JwtException e) {
            throw codigoInvalido();
        }
    }

    private List<String> gerarCodigosDeRecuperacao() {
        var codigos = new ArrayList<String>(QUANTIDADE_DE_CODIGOS);
        for (int i = 0; i < QUANTIDADE_DE_CODIGOS; i++) {
            // Sem separador nem maiúsculas ambíguas: quem digita isso costuma estar sem o
            // celular e com pressa.
            codigos.add(SegredoOpaco.gerar().substring(0, 12).toLowerCase(java.util.Locale.ROOT));
        }
        return List.copyOf(codigos);
    }

    private ErroDeDominio codigoInvalido() {
        // Um código só para desafio inválido, TOTP errado, reapresentado e recuperação já usada:
        // distinguir diria a quem tenta o que aconteceu com cada tentativa.
        return new ErroDeDominio(ErrosDoIam.SEGUNDO_FATOR_INVALIDO,
                "Código inválido ou expirado.");
    }

    /** O segredo é devolvido uma única vez, no momento da inscrição. */
    public record Inscricao(String segredo, String uriOtpauth) {
    }

    /** Códigos de recuperação (exibidos uma vez) e a sessão já renovada com {@code mfa=true}. */
    public record Confirmacao(List<String> codigos, SessaoIniciada sessao) {
    }
}
