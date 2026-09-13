package br.com.salao.arquivos.internal.application;

import br.com.salao.arquivos.api.Armazenamento;
import br.com.salao.arquivos.api.ArquivoResumo;
import br.com.salao.arquivos.api.ConteudoDeArquivo;
import br.com.salao.arquivos.api.EscritaDeConteudo;
import br.com.salao.arquivos.api.IngestaoDeArquivoApi;
import br.com.salao.arquivos.api.StatusDoArquivo;
import br.com.salao.arquivos.api.TipoDeArquivo;
import br.com.salao.arquivos.internal.domain.MagicBytes;
import br.com.salao.arquivos.internal.domain.TiposPorDono;
import br.com.salao.arquivos.internal.infra.ArquivosJdbc;
import br.com.salao.shared.tenant.TenantContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CNV-003 — ingestão pelo servidor. Ver {@link IngestaoDeArquivoApi}.
 *
 * <p>Não se chama {@code ...UseCase}: não há usuário para um {@code @PreAuthorize} conferir — quem
 * chama é o processador de áudios. A garantia de que nenhuma tela alcance esta classe está no teste
 * de arquitetura, e não na convenção de nome.
 *
 * <h2>A linha antes dos bytes</h2>
 *
 * <p>Registrar e depois gravar: se gravar falhar, a exceção desfaz o registro e não sobra linha
 * apontando para um arquivo que não existe. A ordem contrária deixaria bytes órfãos no disco a cada
 * falha de banco — que ninguém encontraria, porque nenhuma linha diz que eles existem.
 */
public class IngestaoDeArquivo implements IngestaoDeArquivoApi {

    private static final Logger log = LoggerFactory.getLogger(IngestaoDeArquivo.class);

    /** O mesmo teto do upload: 20 MB. A nota de voz mais longa do WhatsApp fica muito abaixo. */
    static final long TAMANHO_MAXIMO = 20L * 1024 * 1024;

    private final ArquivosJdbc arquivos;
    private final Armazenamento armazenamento;
    private final EscritaDeConteudo escrita;

    public IngestaoDeArquivo(ArquivosJdbc arquivos, Armazenamento armazenamento,
                             EscritaDeConteudo escrita) {
        this.arquivos = arquivos;
        this.armazenamento = armazenamento;
        this.escrita = escrita;
    }

    @Override
    @Transactional
    public ArquivoResumo guardar(String donoTipo, UUID donoId, String nomeOriginal,
                                byte[] conteudo) {
        UUID tenant = TenantContext.obrigatorio();
        // A chave é gerada, como no upload (RN-ARQ-002): o nome não compõe caminho.
        String chave = tenant + "/" + donoTipo.toLowerCase() + "/" + UUID.randomUUID();
        String nome = recortar(nomeOriginal);

        Optional<TipoDeArquivo> tipo = conteudo == null || conteudo.length == 0
                ? Optional.empty()
                : MagicBytes.detectar(Arrays.copyOf(conteudo,
                        Math.min(conteudo.length, MagicBytes.BYTES_NECESSARIOS)));
        String motivo = motivoDeRecusa(donoTipo, conteudo, tipo);

        if (motivo != null) {
            // Recusa vira registro, sem lançar (RN-ARQ-003). Nenhum byte é gravado.
            UUID id = arquivos.registrar(tenant, donoTipo, donoId, chave, nome, null);
            arquivos.recusar(id, motivo);
            log.warn("Ingestão recusada para {} {}: {}", donoTipo, donoId, motivo);
            return arquivos.porId(id).orElseThrow();
        }

        UUID id = arquivos.registrarValidado(tenant, donoTipo, donoId, chave, nome, tipo.get(),
                conteudo.length);
        try {
            escrita.guardar(chave, conteudo);
        } catch (IOException e) {
            throw new UncheckedIOException("não foi possível gravar o arquivo " + id, e);
        }
        return arquivos.porId(id).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConteudoDeArquivo> conteudo(UUID arquivoId) {
        return arquivos.porId(arquivoId)
                .filter(a -> a.status() == StatusDoArquivo.VALIDADO)
                .flatMap(a -> armazenamento.ler(a.chave(), TAMANHO_MAXIMO)
                        .map(bytes -> new ConteudoDeArquivo(a.tipo(), bytes)));
    }

    private static String motivoDeRecusa(String donoTipo, byte[] conteudo,
                                         Optional<TipoDeArquivo> tipo) {
        if (conteudo == null || conteudo.length == 0) {
            return "nenhum byte chegou";
        }
        if (conteudo.length > TAMANHO_MAXIMO) {
            return "arquivo com " + conteudo.length + " bytes, acima do limite";
        }
        if (tipo.isEmpty()) {
            return "o conteúdo não é de um formato aceito";
        }
        if (!TiposPorDono.aceita(donoTipo, tipo.get())) {
            return "um arquivo " + tipo.get() + " não pode pertencer a " + donoTipo.toLowerCase();
        }
        return null;
    }

    private static String recortar(String nome) {
        if (nome == null) {
            return null;
        }
        String limpo = nome.strip();
        return limpo.substring(0, Math.min(limpo.length(), 200));
    }
}
