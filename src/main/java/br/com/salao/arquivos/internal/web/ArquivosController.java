package br.com.salao.arquivos.internal.web;

import br.com.salao.arquivos.api.ArquivoResumo;
import br.com.salao.arquivos.api.StatusDoArquivo;
import br.com.salao.arquivos.api.TipoDeArquivo;
import br.com.salao.arquivos.internal.application.UploadDeArquivoUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-ARQ-001 — a porta do upload em dois passos.
 *
 * <p>O front pede a autorização, escreve os bytes onde ela mandar, e volta para confirmar. São dois
 * pedidos de propósito: entre eles os bytes não passam por aqui.
 *
 * <h2>Recusa responde 200</h2>
 *
 * <p>Um arquivo recusado não é um pedido malfeito — o pedido estava certo, e a resposta é que o
 * conteúdo não serve. Vem com {@code status: RECUSADO} e o motivo, no mesmo corpo de sempre.
 *
 * <p>É a consequência direta de RN-ARQ-003: transformar a recusa em erro HTTP exigiria lançar, e
 * lançar de dentro da transação desfaria o registro da própria recusa. A tela mostra o motivo
 * porque ele veio no corpo, não porque interpretou um código de erro.
 */
@RestController
@RequestMapping("/api/v1/arquivos")
public class ArquivosController {

    private final UploadDeArquivoUseCase upload;

    public ArquivosController(UploadDeArquivoUseCase upload) {
        this.upload = upload;
    }

    /**
     * O que a tela precisa mandar para começar.
     *
     * <p>{@code nomeOriginal} é só para exibir depois — não vira caminho e não decide tipo
     * (RN-ARQ-002). O {@code Pattern} do dono é a lista fechada do próprio banco: valor fora dela
     * quebraria o {@code check} lá na frente, e a mensagem de lá não explica nada para quem chamou.
     */
    public record AutorizarRequest(
            @NotNull @Pattern(regexp = "CLIENTE|PROFISSIONAL") String donoTipo,
            @NotNull UUID donoId,
            @Size(max = 200) String nomeOriginal) {
    }

    @PostMapping
    public ResponseEntity<UploadDeArquivoUseCase.Autorizacao> autorizar(
            @Valid @RequestBody AutorizarRequest req) {
        var autorizacao = upload.autorizar(req.donoTipo(), req.donoId(), req.nomeOriginal());
        return ResponseEntity
                .created(URI.create("/api/v1/arquivos/" + autorizacao.arquivoId()))
                .body(autorizacao);
    }

    /**
     * O arquivo como a tela o vê.
     *
     * <p>Sem a {@code chave}: ela é endereço de armazenamento, e o que a tela precisa para chegar
     * ao arquivo é uma URL de leitura com validade — não o caminho cru, que não expira.
     */
    public record ArquivoResponse(
            UUID id,
            StatusDoArquivo status,
            TipoDeArquivo tipo,
            Long tamanhoBytes,
            String nomeOriginal,
            String motivoRecusa) {

        static ArquivoResponse de(ArquivoResumo a) {
            return new ArquivoResponse(a.id(), a.status(), a.tipo(), a.tamanhoBytes(),
                    a.nomeOriginal(), a.motivoRecusa());
        }
    }

    /** Passo 2. Aceito ou recusado, a resposta é 200 — ver o javadoc da classe. */
    @PostMapping("/{id}/confirmacao")
    public ArquivoResponse confirmar(@PathVariable UUID id) {
        return ArquivoResponse.de(upload.confirmar(id));
    }
}
