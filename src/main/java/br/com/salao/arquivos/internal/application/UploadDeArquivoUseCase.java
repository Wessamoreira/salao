package br.com.salao.arquivos.internal.application;

import br.com.salao.arquivos.api.Armazenamento;
import br.com.salao.arquivos.api.ArquivoResumo;
import br.com.salao.arquivos.api.StatusDoArquivo;
import br.com.salao.arquivos.api.TipoDeArquivo;
import br.com.salao.arquivos.internal.domain.MagicBytes;
import br.com.salao.arquivos.internal.infra.ArquivosJdbc;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.erro.CodigoDeErro;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tenant.TenantContext;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-ARQ-001 — subir arquivo em dois passos.
 *
 * <h2>Por que dois passos</h2>
 *
 * <p>Com presigned URL os bytes vão do navegador direto para o armazenamento, e a aplicação nunca
 * os vê passar. É o ponto: uma foto de cabelo tem alguns megabytes, e proxiá-la queima memória e
 * prende uma thread por upload.
 *
 * <p>O preço é perder o controle do momento da escrita — e é a confirmação que o devolve. Ela lê os
 * primeiros bytes do que chegou e decide se aquilo fica.
 *
 * <h2>A validação é depois, e é a única que vale</h2>
 *
 * <p>Extensão e {@code Content-Type} são escolhidos por quem envia, e a exigência do projeto é
 * explícita: "não confiar em extensão nem em Content-Type". O que se confere são os bytes.
 *
 * <p>Recusado, o arquivo é <strong>apagado</strong> do armazenamento. Deixá-lo lá seria manter no
 * bucket do salão um arquivo que o sistema já sabe que não deveria existir — e a URL dele
 * continuaria funcionando.
 */
public class UploadDeArquivoUseCase {

    private static final Logger log = LoggerFactory.getLogger(UploadDeArquivoUseCase.class);

    /**
     * Tempo de vida do link de upload.
     *
     * <p>Quinze minutos: o suficiente para uma foto grande numa rede de salão, e curto o bastante
     * para um link vazado não valer nada amanhã.
     */
    private static final Duration VALIDADE = Duration.ofMinutes(15);

    /** 20 MB. Foto de celular passa longe; PDF de contrato também. */
    private static final long TAMANHO_MAXIMO = 20L * 1024 * 1024;

    public enum Erros implements CodigoDeErro {
        ARQUIVO_NAO_ENCONTRADO("ER-ARQ-ARQUIVO_NAO_ENCONTRADO", HttpStatus.NOT_FOUND,
                "Arquivo não encontrado"),
        NADA_FOI_ENVIADO("ER-ARQ-NADA_FOI_ENVIADO", HttpStatus.UNPROCESSABLE_ENTITY,
                "Nenhum conteúdo chegou nesta chave"),
        JA_CONFIRMADO("ER-ARQ-JA_CONFIRMADO", HttpStatus.CONFLICT,
                "Este upload já foi confirmado");

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

    /** O que o front precisa para enviar: para onde mandar, e o id para confirmar depois. */
    public record Autorizacao(UUID arquivoId, String urlDeUpload, Duration validade) {
    }

    private final ArquivosJdbc arquivos;
    private final Armazenamento armazenamento;

    public UploadDeArquivoUseCase(ArquivosJdbc arquivos, Armazenamento armazenamento) {
        this.arquivos = arquivos;
        this.armazenamento = armazenamento;
    }

    /**
     * Passo 1: registra e autoriza.
     *
     * <p>A permissão é a de escrever no dono: subir foto de cliente exige poder mexer na ficha
     * dela. Uma permissão própria de "upload" separaria o arquivo do dado a que ele pertence — e
     * foto de química é dado de saúde (RT-CLI-004), não anexo genérico.
     */
    @PreAuthorize("hasAuthority('" + Permissao.CLIENTE_ESCREVER + "')"
            + " or hasAuthority('" + Permissao.USUARIO_GERENCIAR + "')")
    @Transactional
    public Autorizacao autorizar(String donoTipo, UUID donoId, String nomeOriginal) {
        UUID tenant = TenantContext.obrigatorio();

        // A chave é GERADA, nunca derivada do nome enviado: nome de arquivo vem do cliente e
        // aceita `../`, o que faria a chave apontar para fora do prefixo do salão.
        String chave = tenant + "/" + donoTipo.toLowerCase() + "/" + UUID.randomUUID();

        UUID id = arquivos.registrar(tenant, donoTipo, donoId, chave,
                recortar(nomeOriginal), UsuarioAtual.idOuNulo());
        return new Autorizacao(id, armazenamento.urlDeUpload(chave, VALIDADE), VALIDADE);
    }

    /**
     * Passo 2: confere o que chegou.
     *
     * <p>É aqui que o controle perdido no upload direto volta. Nada é aceito por afirmação: o tipo
     * sai dos bytes, e o tamanho sai do armazenamento.
     */
    @PreAuthorize("hasAuthority('" + Permissao.CLIENTE_ESCREVER + "')"
            + " or hasAuthority('" + Permissao.USUARIO_GERENCIAR + "')")
    @Transactional
    public ArquivoResumo confirmar(UUID arquivoId) {
        ArquivoResumo arquivo = arquivos.porId(arquivoId)
                .orElseThrow(() -> new ErroDeDominio(Erros.ARQUIVO_NAO_ENCONTRADO,
                        "arquivo " + arquivoId + " não encontrado"));
        if (arquivo.status() != StatusDoArquivo.AGUARDANDO) {
            throw new ErroDeDominio(Erros.JA_CONFIRMADO,
                    "este upload já foi confirmado como " + arquivo.status());
        }

        Optional<byte[]> inicio = armazenamento.primeirosBytes(arquivo.chave(),
                MagicBytes.BYTES_NECESSARIOS);
        if (inicio.isEmpty()) {
            // O link foi pedido e ninguém enviou nada. Não é recusa — é upload que não aconteceu,
            // e a linha continua aguardando para o expurgo levar.
            throw new ErroDeDominio(Erros.NADA_FOI_ENVIADO,
                    "nada chegou nesta chave; envie o arquivo antes de confirmar");
        }

        long tamanho = armazenamento.tamanho(arquivo.chave()).orElse(0L);
        if (tamanho > TAMANHO_MAXIMO) {
            return recusar(arquivo, "arquivo com " + tamanho + " bytes, acima do limite");
        }

        Optional<TipoDeArquivo> tipo = MagicBytes.detectar(inicio.get());
        if (tipo.isEmpty()) {
            // O nome dizia .jpg e os bytes dizem outra coisa. É o ataque que a validação existe
            // para impedir, e também o engano honesto de quem renomeou um arquivo.
            return recusar(arquivo, "o conteúdo não é uma imagem nem um PDF");
        }

        arquivos.validar(arquivoId, tipo.get(), tamanho);
        log.info("Arquivo {} validado como {} ({} bytes)", arquivoId, tipo.get(), tamanho);
        return arquivos.porId(arquivoId).orElseThrow();
    }

    /**
     * Registra a recusa e devolve o arquivo recusado — sem lançar.
     *
     * <p>Lançar daqui desfaria o próprio registro: o {@code @Transactional} de {@code confirmar}
     * faz rollback em exceção, e a linha voltaria a {@code AGUARDANDO} com os bytes já apagados do
     * disco. Nada explicaria o que aconteceu, que é o oposto do que a coluna de motivo existe para
     * fazer.
     */
    private ArquivoResumo recusar(ArquivoResumo arquivo, String motivo) {
        arquivos.recusar(arquivo.id(), motivo);
        // Apagado, não abandonado: deixá-lo no bucket manteria um arquivo que o sistema já sabe
        // que não deveria existir, com a URL dele funcionando.
        armazenamento.apagar(arquivo.chave());
        log.warn("Arquivo {} recusado: {}", arquivo.id(), motivo);
        return arquivos.porId(arquivo.id()).orElseThrow();
    }

    /** Nome só para exibir; comprido demais é ruído de tela, não erro. */
    private String recortar(String nome) {
        if (nome == null) {
            return null;
        }
        String limpo = nome.strip();
        return limpo.substring(0, Math.min(limpo.length(), 200));
    }
}
