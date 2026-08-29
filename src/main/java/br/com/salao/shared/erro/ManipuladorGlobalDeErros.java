package br.com.salao.shared.erro;

import br.com.salao.shared.tenant.TenantNaoDefinidoException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RT-INF-003 — tradução única de exceção para HTTP, no formato RFC 9457 (Problem Details),
 * acrescido de {@code codigo} e {@code traceId}.
 *
 * <p>Existe um só lugar que monta erro. Controller que monta o seu próprio acaba divergindo do
 * contrato, e o front descobre em produção.
 */
@RestControllerAdvice
public class ManipuladorGlobalDeErros {

    private static final Logger log = LoggerFactory.getLogger(ManipuladorGlobalDeErros.class);

    @ExceptionHandler(ErroDeDominio.class)
    public ProblemDetail tratarErroDeDominio(ErroDeDominio erro) {
        return montar(erro.codigo(), erro.getMessage(), erro.campos(), erro.parametros());
    }

    /**
     * RN-INF-003 — transação sem tenant. Isto é <strong>bug</strong>, não erro de usuário: um caso
     * de uso rodou fora do escopo de uma requisição autenticada. Vira 500 e log de erro, nunca uma
     * mensagem amigável que faça alguém achar que é comportamento esperado.
     */
    @ExceptionHandler(TenantNaoDefinidoException.class)
    public ProblemDetail tratarTenantAusente(TenantNaoDefinidoException erro) {
        log.error("Transação iniciada sem tenant no escopo", erro);
        return montar(ErrosDaInfra.ERRO_INTERNO, "Erro interno.", List.of(), java.util.Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail tratarValidacao(MethodArgumentNotValidException erro) {
        List<CampoInvalido> campos = erro.getBindingResult().getFieldErrors().stream()
                .map(f -> new CampoInvalido(f.getField(),
                        ErrosDaInfra.DADOS_INVALIDOS.codigo(),
                        f.getDefaultMessage()))
                .toList();
        return montar(ErrosDaInfra.DADOS_INVALIDOS, "Verifique os campos informados.",
                campos, java.util.Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail tratarInesperado(Exception erro) {
        log.error("Erro não tratado", erro);
        // Nunca devolva a mensagem original: stack trace e detalhe de infraestrutura viram
        // informação para quem estiver sondando a API.
        return montar(ErrosDaInfra.ERRO_INTERNO, "Erro interno.", List.of(), java.util.Map.of());
    }

    private ProblemDetail montar(CodigoDeErro codigo, String detalhe,
                                 List<CampoInvalido> campos,
                                 java.util.Map<String, Object> parametros) {
        var problema = ProblemDetail.forStatusAndDetail(codigo.status(), detalhe);
        problema.setTitle(codigo.titulo());
        problema.setType(java.net.URI.create(
                "https://api.salao.app/erros/" + codigo.codigo().toLowerCase()));
        problema.setProperty("codigo", codigo.codigo());
        problema.setProperty("traceId", MDC.get("traceId"));
        if (!campos.isEmpty()) {
            problema.setProperty("campos", campos);
        }
        if (!parametros.isEmpty()) {
            problema.setProperty("parametros", parametros);
        }
        return problema;
    }
}
