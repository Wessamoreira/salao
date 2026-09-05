package br.com.salao.arquivos.internal.infra;

import br.com.salao.arquivos.api.Armazenamento;
import br.com.salao.arquivos.api.EscritaDeConteudo;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RT-ARQ-001 — armazenamento em disco, para enquanto não há bucket.
 *
 * <p>Honesto sobre o que é: nenhum provedor de objeto está contratado, e o módulo inteiro —
 * registro, validação, recusa, expurgo — funciona e é testável sem isso. Trocar por S3 ou MinIO é
 * implementar {@link Armazenamento} e registrar o bean como {@code @Primary}.
 *
 * <p><strong>A "URL assinada" daqui não é assinada.</strong> É um caminho da própria aplicação, e
 * está dito no nome do método que a produz. Um presigned URL de verdade tira a aplicação do caminho
 * dos bytes; este não tira — o que muda é só quem carrega o arquivo, não a validação que vem
 * depois. É a diferença que a implementação real fecha.
 *
 * <h2>A chave nunca vem do nome enviado</h2>
 *
 * <p>Nome de arquivo vem do cliente e aceita {@code ../}. Derivar a chave dele faria um upload
 * chamado {@code ../../outro-salao/foto.jpg} escrever fora do prefixo do salão — travessia de
 * caminho, e num sistema multi-tenant isso é vazamento entre clientes. A chave é gerada; o nome
 * original só é guardado para exibir.
 */
public class ArmazenamentoLocal implements Armazenamento, EscritaDeConteudo {

    private static final Logger log = LoggerFactory.getLogger(ArmazenamentoLocal.class);

    private final Path raiz;

    public ArmazenamentoLocal(Path raiz) {
        this.raiz = raiz.toAbsolutePath().normalize();
    }

    @Override
    public String urlDeUpload(String chave, Duration validade) {
        return "/api/v1/arquivos/conteudo/" + chave;
    }

    @Override
    public String urlDeLeitura(String chave, Duration validade) {
        return "/api/v1/arquivos/conteudo/" + chave;
    }

    @Override
    public Optional<byte[]> primeirosBytes(String chave, int quantidade) {
        Path arquivo = caminhoDe(chave);
        if (!Files.isRegularFile(arquivo)) {
            return Optional.empty();
        }
        try (InputStream entrada = Files.newInputStream(arquivo)) {
            return Optional.of(entrada.readNBytes(quantidade));
        } catch (IOException e) {
            log.warn("Não foi possível ler o começo de {}: {}", chave, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<Long> tamanho(String chave) {
        Path arquivo = caminhoDe(chave);
        if (!Files.isRegularFile(arquivo)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.size(arquivo));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public void apagar(String chave) {
        try {
            Files.deleteIfExists(caminhoDe(chave));
        } catch (IOException e) {
            // Não relançar: a recusa já foi registrada no banco, e o arquivo órfão é problema do
            // expurgo. Falhar aqui transformaria "recusei o upload" em erro para quem enviou.
            log.warn("Não foi possível apagar {}: {}", chave, e.getMessage());
        }
    }

    /** Escreve os bytes. Só existe nesta implementação; num bucket, quem escreve é o navegador. */
    @Override
    public void guardar(String chave, byte[] conteudo) throws IOException {
        Path destino = caminhoDe(chave);
        Files.createDirectories(destino.getParent());
        Files.write(destino, conteudo);
    }

    /**
     * Resolve a chave para um caminho, <strong>confinado à raiz</strong>.
     *
     * <p>A checagem depois do {@code normalize} é o que impede travessia: mesmo que uma chave
     * malformada chegue até aqui, um caminho que saia da raiz é recusado antes de tocar o disco.
     * É defesa em profundidade — a chave já é gerada, não recebida.
     */
    private Path caminhoDe(String chave) {
        Path resolvido = raiz.resolve(chave).normalize();
        if (!resolvido.startsWith(raiz)) {
            throw new IllegalArgumentException("chave fora da raiz do armazenamento: " + chave);
        }
        return resolvido;
    }
}
