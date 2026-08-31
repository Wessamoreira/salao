package br.com.salao.shared.erro;

import br.com.salao.shared.tenant.TenantNaoDefinidoException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /**
     * Erros do próprio Spring MVC: rota inexistente, método errado, corpo ilegível.
     *
     * <p>Sem estes tratadores, o {@code @ExceptionHandler(Exception.class)} abaixo os engoliria e
     * <strong>toda URL digitada errada viraria 500</strong> — enchendo o log de erro interno por
     * causa de um cliente que errou o caminho, e escondendo os 500 de verdade no meio do ruído.
     * O tratador mais específico ganha, e é por isso que estes precisam existir explicitamente.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ProblemDetail tratarRotaInexistente(Exception erro) {
        return montar(ErrosDaInfra.NAO_ENCONTRADO, "Recurso não encontrado.",
                List.of(), java.util.Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail tratarMetodoNaoPermitido(HttpRequestMethodNotSupportedException erro) {
        return montar(ErrosDaInfra.METODO_NAO_PERMITIDO,
                "Método não permitido para este recurso.", List.of(), java.util.Map.of());
    }

    /**
     * Parâmetro que não converte para o tipo esperado: {@code /clientes/nao-e-uuid},
     * {@code /consentimentos/FINALIDADE_QUE_NAO_EXISTE}, {@code ?limite=muitos}.
     *
     * <p>Faltava, e o sintoma era <strong>500 em qualquer URL com id malformado</strong> — o
     * mesmo defeito que os tratadores acima corrigiram para rota inexistente, num caso que passou
     * despercebido porque as rotas com id só eram exercitadas com ids válidos.
     *
     * <p>O nome do parâmetro vai no campo, para a tela apontar onde está o erro; o valor
     * <strong>não</strong> vai — ele veio de fora e atravessaria log e telemetria.
     */
    /**
     * Negação de {@code @PreAuthorize} dentro de um caso de uso.
     *
     * <p>Sem este tratador, o {@code @ExceptionHandler(Exception.class)} engolia a
     * {@code AccessDeniedException} e <strong>toda negação de permissão virava 500</strong> —
     * em todos os endpoints do sistema, não só nos de cliente.
     *
     * <p>Passou despercebido porque a negação que os testes exercitavam era a da cadeia de
     * filtros (segundo fator, token ausente), que o Spring Security responde antes de chegar ao
     * controller. A do caso de uso acontece depois, já dentro do {@code DispatcherServlet}, e
     * caía aqui.
     *
     * <p>Não é só cosmético: 500 diz "algo quebrou aqui dentro" sobre o que é um desfecho
     * normal de autorização, e enche o log de erro interno — escondendo os 500 de verdade, que é
     * exatamente o que se quer enxergar.
     *
     * <p>O 401 de quem não está autenticado continua vindo da cadeia de filtros: nenhuma rota de
     * {@code /api} é anônima, então quem chega até aqui já se identificou e o que falta é
     * permissão.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ProblemDetail tratarAcessoNegado(
            org.springframework.security.access.AccessDeniedException erro) {
        log.info("Acesso negado por falta de permissão");
        return montar(ErrosDaInfra.ACESSO_NEGADO,
                "Você não tem permissão para esta operação.", List.of(), java.util.Map.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail tratarParametroDeTipoErrado(MethodArgumentTypeMismatchException erro) {
        return montar(ErrosDaInfra.DADOS_INVALIDOS, "Parâmetro inválido.",
                List.of(new CampoInvalido(erro.getName(), "TIPO_INVALIDO",
                        "Valor inválido para este parâmetro")),
                java.util.Map.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail tratarCorpoIlegivel(HttpMessageNotReadableException erro) {
        // Sem detalhe do parser: ele expõe nomes de classe e estrutura interna.
        return montar(ErrosDaInfra.DADOS_INVALIDOS, "Corpo da requisição inválido.",
                List.of(), java.util.Map.of());
    }

    /**
     * Espera por bloqueio que estourou o teto (`lock_timeout`, RT-INF-012).
     *
     * <p>Acontece quando duas requisições disputam a mesma linha — o caso desenhado em
     * RT-INF-005, com dois pedidos idempotentes simultâneos. Não é falha: é a espera tendo um
     * teto, que é justamente o que se queria. Vira 409 com orientação de repetir, e não 500,
     * que diria "algo quebrou" quando nada quebrou.
     */
    @ExceptionHandler(org.springframework.dao.CannotAcquireLockException.class)
    public ProblemDetail tratarEsperaPorBloqueio(
            org.springframework.dao.CannotAcquireLockException erro) {
        log.info("Espera por bloqueio estourou o limite", erro);
        return montar(ErrosDaInfra.OPERACAO_EM_ANDAMENTO,
                "Esta operação está sendo processada. Tente de novo em instantes.",
                List.of(), java.util.Map.of());
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
