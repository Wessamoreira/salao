package br.com.salao.conversacional.internal.web;

import br.com.salao.conversacional.internal.application.ReceberLoteDoWhatsAppUseCase;
import br.com.salao.conversacional.internal.domain.AssinaturaDoWebhook;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * RT-CNV-001 — a única porta pública de escrita do sistema.
 *
 * <h2>Fora de {@code /api}, e isso é decisão</h2>
 *
 * <p>{@code /webhooks/whatsapp} não é API do produto: não tem versão negociada com front nenhum,
 * não recebe CORS (nenhum navegador a chama) e não aparece na superfície que o cliente consome.
 * Misturá-la em {@code /api/v1} a colocaria sob o limite de taxa por IP de RT-INF-011 — que conta
 * por origem, e a Meta é <strong>uma</strong> origem para todos os salões. O limite pensado para
 * conter <em>password spraying</em> passaria a descartar mensagem de cliente em horário de pico.
 *
 * <h2>A ordem das verificações é o desenho</h2>
 *
 * <ol>
 *   <li>segredo configurado? senão 503 — falha fechada (RN-CNV-001);</li>
 *   <li>corpo dentro do teto? senão 413, <strong>sem</strong> calcular HMAC;</li>
 *   <li>assinatura confere? senão 403, <strong>sem</strong> interpretar o JSON;</li>
 *   <li>só então o payload é lido.</li>
 * </ol>
 *
 * <p>Nada é parseado antes da assinatura conferir: o parser é a maior superfície de ataque que
 * esta rota tem, e ele só deve ver bytes que a Meta assinou.
 *
 * <h2>Quando responder 200 e quando não</h2>
 *
 * <p>A Meta reenvia o que não recebeu 200. Então 200 é a resposta para tudo o que
 * <strong>não vai melhorar</strong> numa próxima tentativa — inclusive JSON ilegível e mensagem
 * que a constraint recusa. 500 fica reservado para falha de infraestrutura, que é exatamente o
 * caso em que o reenvio ajuda.
 *
 * <p>Assinatura inválida responde 403, e não 200: um 200 ali tornaria requisição forjada
 * indistinguível de legítima para qualquer um olhando o log de acesso.
 */
@RestController
@RequestMapping("/webhooks/whatsapp")
public class WebhookDoWhatsAppController {

    private static final Logger log = LoggerFactory.getLogger(WebhookDoWhatsAppController.class);

    private static final String CABECALHO_ASSINATURA = "X-Hub-Signature-256";

    /**
     * Teto do corpo: 512 KB.
     *
     * <p>Webhook da Cloud API tem alguns kilobytes — a mídia não vem no corpo, vem como id para
     * baixar depois. O teto existe porque esta rota é pública e o HMAC percorre o corpo inteiro:
     * sem limite, um POST de 2 GB é CPU e memória de graça para quem nem tem o segredo.
     *
     * <p>Checado pelo {@code Content-Length} <em>e</em> pela leitura, porque uma requisição
     * {@code chunked} não declara tamanho — e aí o único limite real é o da leitura.
     */
    private static final int TETO_DO_CORPO = 512 * 1024;

    private final AssinaturaDoWebhook assinatura;
    private final String tokenDeVerificacao;
    private final ReceberLoteDoWhatsAppUseCase receber;
    private final ObjectMapper json;

    public WebhookDoWhatsAppController(
            AssinaturaDoWebhook assinatura,
            // Injetado por `@Value` e não como bean: um bean de tipo String seria resolvido por
            // tipo e colidiria com qualquer outra String publicada no contexto.
            @Value("${app.whatsapp.token-de-verificacao:}") String tokenDeVerificacao,
            ReceberLoteDoWhatsAppUseCase receber, ObjectMapper json) {
        this.assinatura = assinatura;
        this.tokenDeVerificacao = tokenDeVerificacao;
        this.receber = receber;
        this.json = json;
    }

    /**
     * O handshake que a Meta faz UMA vez, ao cadastrar a URL do webhook.
     *
     * <p>Ela chama com um {@code hub.challenge} aleatório e espera recebê-lo de volta em texto
     * puro. Devolver JSON aqui faz a verificação falhar sem dizer por quê — o corpo tem de ser
     * exatamente o desafio.
     *
     * <p>Responde 403 quando o token não bate. A comparação é em tempo constante: esta rota é
     * pública, e um {@code equals} entregaria o token pelo tempo de resposta.
     */
    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verificar(
            @RequestParam(name = "hub.mode", required = false) String modo,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String desafio) {
        if (!"subscribe".equals(modo) || desafio == null
                || !AssinaturaDoWebhook.tokenConfere(tokenDeVerificacao, token)) {
            log.warn("Handshake do webhook recusado");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        log.info("Handshake do webhook do WhatsApp concluído");
        return ResponseEntity.ok(desafio);
    }

    @PostMapping
    public ResponseEntity<Void> receber(HttpServletRequest requisicao) throws IOException {
        if (!assinatura.configurado()) {
            // Sem segredo não há como distinguir a Meta de qualquer um. Recusar tudo é a única
            // resposta segura — e 503 diz "não estou pronta", que é a verdade.
            log.error("Webhook do WhatsApp chamado sem app.whatsapp.app-secret configurado");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        byte[] corpo = corpoLimitado(requisicao);
        if (corpo == null) {
            log.warn("Corpo do webhook acima do teto de {} bytes", TETO_DO_CORPO);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        if (!assinatura.confere(corpo, requisicao.getHeader(CABECALHO_ASSINATURA))) {
            // Sem o corpo e sem a assinatura no log: um deles é dado pessoal, o outro é material
            // para quem está sondando.
            log.warn("Webhook com assinatura inválida recusado");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        JsonNode raiz;
        try {
            raiz = json.readTree(corpo);
        } catch (JacksonException e) {
            // Assinado pela Meta e ilegível: é defeito do provedor, e reenviar não conserta.
            log.error("Webhook assinado com JSON ilegível: {}", e.getOriginalMessage());
            return ResponseEntity.ok().build();
        }

        var resumo = receber.executar(raiz);
        if (resumo.vazio()) {
            // A maioria dos webhooks é recibo de entrega, não mensagem. INFO em cada um afogaria
            // o log justamente onde ele precisa ser legível.
            log.debug("Webhook sem mensagens (recibo de status ou assunto não tratado)");
        } else {
            log.info("Webhook do WhatsApp: {} gravadas, {} repetidas, {} sem dono, {} recusadas",
                    resumo.gravadas(), resumo.repetidas(), resumo.semDono(), resumo.recusadas());
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Lê o corpo com teto, sem confiar no {@code Content-Length}.
     *
     * @return os bytes, ou {@code null} quando passam do teto
     */
    private byte[] corpoLimitado(HttpServletRequest requisicao) throws IOException {
        if (requisicao.getContentLengthLong() > TETO_DO_CORPO) {
            return null;
        }
        try (InputStream entrada = requisicao.getInputStream()) {
            // Um byte além do teto: se vier, já sabemos que passou, sem ter lido o resto.
            byte[] lidos = entrada.readNBytes(TETO_DO_CORPO + 1);
            return lidos.length > TETO_DO_CORPO ? null : lidos;
        }
    }
}
