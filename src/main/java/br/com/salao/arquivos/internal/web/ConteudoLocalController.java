package br.com.salao.arquivos.internal.web;

import br.com.salao.arquivos.api.EscritaDeConteudo;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.erro.CodigoDeErro;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-ARQ-001 — o lugar onde os bytes caem <strong>enquanto não há bucket</strong>.
 *
 * <p>Num armazenamento de objeto, esta porta não existe: o navegador escreve direto no provedor com
 * a URL assinada, e é justamente isso que tira a aplicação do caminho dos bytes. Aqui ela está no
 * caminho, e o endereço que {@code ArmazenamentoLocal.urlDeUpload} devolve aponta para cá.
 *
 * <p>Por isso ela sai com uma propriedade: {@code app.arquivos.modo=objeto} apaga a porta no dia em
 * que houver provedor. É a única coisa que precisa mudar junto com o bean de armazenamento — e
 * esquecer disso deixaria uma rota de escrita aberta que nada mais usa.
 *
 * <h2>A chave precisa ser do salão de quem está pedindo</h2>
 *
 * <p>A chave começa pelo tenant (RN-ARQ-002), e é isso que se confere aqui. Sem a conferência,
 * quem descobrisse a chave de outro salão poderia sobrescrever o arquivo dele — a autenticação
 * sozinha não impede, porque o usuário é legítimo; o que ele não tem é aquele salão.
 *
 * <p>Ela é barata porque a chave é gerada com UUID e ninguém a adivinha. É defesa em profundidade:
 * o dia em que a chave passar a ser previsível, esta linha é a que continua valendo.
 */
@RestController
@ConditionalOnProperty(name = "app.arquivos.modo", havingValue = "local", matchIfMissing = true)
public class ConteudoLocalController {

    private static final Logger log = LoggerFactory.getLogger(ConteudoLocalController.class);

    /** O mesmo teto da confirmação. Aqui ele para a escrita antes de o disco crescer. */
    private static final long TAMANHO_MAXIMO = 20L * 1024 * 1024;

    public enum Erros implements CodigoDeErro {
        CHAVE_DE_OUTRO_SALAO("ER-ARQ-CHAVE_DE_OUTRO_SALAO", HttpStatus.FORBIDDEN,
                "Esta chave não é deste estabelecimento"),
        GRANDE_DEMAIS("ER-ARQ-GRANDE_DEMAIS", HttpStatus.PAYLOAD_TOO_LARGE,
                "Arquivo acima do tamanho permitido"),
        FALHA_AO_GRAVAR("ER-ARQ-FALHA_AO_GRAVAR", HttpStatus.INTERNAL_SERVER_ERROR,
                "Não foi possível guardar o arquivo");

        private final String codigo;
        private final HttpStatus status;
        private final String titulo;

        Erros(String codigo, HttpStatus status, String titulo) {
            this.codigo = codigo;
            this.status = status;
            this.titulo = titulo;
        }

        @Override public String codigo() { return codigo; }
        @Override public HttpStatus status() { return status; }
        @Override public String titulo() { return titulo; }
    }

    private final EscritaDeConteudo armazenamento;

    public ConteudoLocalController(EscritaDeConteudo armazenamento) {
        this.armazenamento = armazenamento;
    }

    /**
     * Escreve os bytes na chave autorizada.
     *
     * <p>Nada é validado aqui de propósito: o que decide se o arquivo fica é a confirmação, que lê
     * os bytes que realmente chegaram. Validar na escrita seria conferir o que o cliente mandou
     * <em>agora</em> e confiar que é o mesmo depois — e com bucket de verdade esta escrita nem
     * passa pela aplicação para poder ser conferida.
     */
    @PreAuthorize("hasAuthority('" + Permissao.CLIENTE_ESCREVER + "')"
            + " or hasAuthority('" + Permissao.USUARIO_GERENCIAR + "')")
    @PutMapping("/api/v1/arquivos/conteudo/{*chave}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void guardar(@PathVariable String chave, HttpServletRequest requisicao) {
        String limpa = chave.startsWith("/") ? chave.substring(1) : chave;
        String prefixo = TenantContext.obrigatorio() + "/";
        if (!limpa.startsWith(prefixo)) {
            log.warn("Escrita recusada: chave {} fora do prefixo do salão", limpa);
            throw new ErroDeDominio(Erros.CHAVE_DE_OUTRO_SALAO,
                    "a chave não pertence a este estabelecimento");
        }

        byte[] conteudo;
        try (InputStream entrada = requisicao.getInputStream()) {
            // Um a mais que o teto: se vier o byte extra, é porque passou — e o corpo inteiro
            // nunca chega a ser materializado.
            conteudo = entrada.readNBytes((int) TAMANHO_MAXIMO + 1);
        } catch (IOException e) {
            throw new ErroDeDominio(Erros.FALHA_AO_GRAVAR, "falha ao ler o corpo: " + e.getMessage());
        }
        if (conteudo.length > TAMANHO_MAXIMO) {
            throw new ErroDeDominio(Erros.GRANDE_DEMAIS,
                    "arquivo acima de " + TAMANHO_MAXIMO + " bytes");
        }

        try {
            armazenamento.guardar(limpa, conteudo);
        } catch (IOException e) {
            throw new ErroDeDominio(Erros.FALHA_AO_GRAVAR, e.getMessage());
        }
    }
}
