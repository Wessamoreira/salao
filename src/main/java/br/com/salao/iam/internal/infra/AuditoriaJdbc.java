package br.com.salao.iam.internal.infra;

import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

/** RT-IAM-008 — gravação da trilha. */
public class AuditoriaJdbc implements AuditoriaApi {

    private static final String INSERIR = """
            insert into auditoria
                (estabelecimento_id, usuario_id, ator, acao, entidade, entidade_id,
                 antes, depois, ip, user_agent, trace_id)
            values (:tenant, :usuario, :ator, :acao, :entidade, :entidadeId,
                    cast(:antes as jsonb), cast(:depois as jsonb),
                    cast(:ip as inet), :userAgent, :traceId)
            """;

    /**
     * Chaves cuja presença é sempre erro de quem chamou.
     *
     * <p>A trilha é retida por anos e lida por gente. Um hash de senha ou um segredo TOTP dentro
     * de {@code antes}/{@code depois} viraria dado sensível de longa duração — e ninguém revisaria
     * isso depois. A rede de segurança substitui o valor em vez de recusar o registro: perder a
     * trilha seria pior que guardá-la sem o campo.
     */
    private static final Set<String> CHAVES_PROIBIDAS = Set.of(
            "senha", "senhahash", "senha_hash", "segredo", "segredo_cifrado", "token",
            "tokenhash", "token_hash", "codigo_hash");

    private static final String OMITIDO = "[omitido]";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AuditoriaJdbc(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * {@code MANDATORY}: exige uma transação já aberta.
     *
     * <p>Auditoria precisa commitar junto com o fato que descreve. Se abrisse transação própria,
     * a trilha registraria alterações que o rollback desfez. Falhar aqui é melhor que gravar uma
     * mentira — e quem esquecer de chamar de dentro de uma transação descobre na hora.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(RegistroDeAuditoria registro) {
        jdbc.sql(INSERIR)
                .param("tenant", TenantContext.obrigatorio())
                .param("usuario", usuarioAtual())
                .param("ator", ator())
                .param("acao", registro.acao())
                .param("entidade", registro.entidade())
                .param("entidadeId", registro.entidadeId())
                .param("antes", serializar(registro.antes()))
                .param("depois", serializar(registro.depois()))
                .param("ip", ipDaRequisicao())
                .param("userAgent", cabecalho("User-Agent"))
                .param("traceId", MDC.get("traceId"))
                .update();
    }

    private String serializar(Map<String, Object> valores) {
        if (valores == null) {
            return null;
        }
        var limpo = new LinkedHashMap<String, Object>();
        valores.forEach((chave, valor) -> limpo.put(chave,
                CHAVES_PROIBIDAS.contains(chave.toLowerCase(java.util.Locale.ROOT))
                        ? OMITIDO : valor));
        return json.writeValueAsString(limpo);
    }

    private UUID usuarioAtual() {
        var autenticacao = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacao != null && autenticacao.getPrincipal() instanceof Jwt jwt) {
            try {
                return UUID.fromString(jwt.getSubject());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Sem usuário no contexto, o ator é {@code SISTEMA} — job agendado, migração, consumidor de
     * evento. O {@code BOT} entra na Fase 4, quando ele passar a agir em nome de alguém: ali o
     * usuário efetivo estará no contexto e o ator precisará dizer que a ação veio da IA.
     */
    private String ator() {
        return usuarioAtual() == null ? "SISTEMA" : "USUARIO";
    }

    private String ipDaRequisicao() {
        var requisicao = requisicaoAtual();
        return requisicao == null ? null : requisicao.getRemoteAddr();
    }

    private String cabecalho(String nome) {
        var requisicao = requisicaoAtual();
        return requisicao == null ? null : requisicao.getHeader(nome);
    }

    private HttpServletRequest requisicaoAtual() {
        var atributos = RequestContextHolder.getRequestAttributes();
        return atributos instanceof ServletRequestAttributes servlet
                ? servlet.getRequest() : null;
    }
}
