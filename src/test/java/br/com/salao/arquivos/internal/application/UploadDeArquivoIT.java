package br.com.salao.arquivos.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.arquivos.api.Armazenamento;
import br.com.salao.arquivos.api.StatusDoArquivo;
import br.com.salao.arquivos.api.TipoDeArquivo;
import br.com.salao.arquivos.internal.infra.ArmazenamentoLocal;
import br.com.salao.arquivos.internal.infra.ArquivosJdbc;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-ARQ-001 — upload em dois passos, com validação por magic bytes. */
@WithMockUser(authorities = { Permissao.CLIENTE_ESCREVER, Permissao.USUARIO_GERENCIAR })
class UploadDeArquivoIT extends AbstractPostgresIT {

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private UploadDeArquivoUseCase upload;
    @Autowired private ArquivosJdbc arquivos;
    @Autowired private Armazenamento armazenamento;

    private UUID salao() {
        return provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    /** Um JPEG de verdade, no que importa: os primeiros bytes. */
    private byte[] jpeg() {
        byte[] arquivo = new byte[64];
        arquivo[0] = (byte) 0xFF;
        arquivo[1] = (byte) 0xD8;
        arquivo[2] = (byte) 0xFF;
        arquivo[3] = (byte) 0xE0;
        return arquivo;
    }

    /** O que o navegador faria: escrever na URL que a autorização devolveu. */
    private void enviar(UploadDeArquivoUseCase.Autorizacao autorizacao, byte[] conteudo,
                        UUID tenant) throws IOException {
        var arquivo = TenantContext.obter(tenant, () -> arquivos.porId(autorizacao.arquivoId()))
                .orElseThrow();
        ((ArmazenamentoLocal) armazenamento).guardar(arquivo.chave(), conteudo);
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ o caminho normal

    @Test
    @DisplayName("autoriza, envia, confirma — e o tipo sai dos bytes")
    void caminho_completo() throws IOException {
        UUID tenant = salao();
        UUID cliente = UUID.randomUUID();

        var autorizacao = TenantContext.obter(tenant,
                () -> upload.autorizar("CLIENTE", cliente, "cabelo-antes.jpg"));
        enviar(autorizacao, jpeg(), tenant);
        var confirmado = TenantContext.obter(tenant,
                () -> upload.confirmar(autorizacao.arquivoId()));

        assertThat(confirmado.status()).isEqualTo(StatusDoArquivo.VALIDADO);
        assertThat(confirmado.tipo()).isEqualTo(TipoDeArquivo.JPEG);
        assertThat(confirmado.tamanhoBytes()).isEqualTo(64);
    }

    @Test
    @DisplayName("a chave é gerada, não derivada do nome enviado")
    void chave_nao_vem_do_nome() {
        // Nome de arquivo vem do cliente e aceita `../`. Derivar a chave dele faria um upload
        // chamado "../../outro-salao/foto.jpg" escrever fora do prefixo do salão — travessia de
        // caminho, que num sistema multi-tenant é vazamento entre clientes.
        UUID tenant = salao();

        var autorizacao = TenantContext.obter(tenant,
                () -> upload.autorizar("CLIENTE", UUID.randomUUID(), "../../etc/passwd"));
        var arquivo = TenantContext.obter(tenant,
                () -> arquivos.porId(autorizacao.arquivoId())).orElseThrow();

        assertThat(arquivo.chave()).startsWith(tenant + "/cliente/").doesNotContain("..");
        assertThat(arquivo.nomeOriginal()).as("o nome é guardado, só para exibir")
                .isEqualTo("../../etc/passwd");
    }

    // ------------------------------------------------------------------ a validação

    @Test
    @DisplayName("HTML com nome de foto é recusado E apagado")
    void html_disfarcado_e_recusado() throws IOException {
        // O ataque que a rotina existe para impedir. E deixar o arquivo no bucket manteria algo
        // que o sistema já sabe que não deveria existir, com a URL dele funcionando.
        UUID tenant = salao();
        var autorizacao = TenantContext.obter(tenant,
                () -> upload.autorizar("CLIENTE", UUID.randomUUID(), "foto.jpg"));
        enviar(autorizacao, "<html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8),
                tenant);

        // Recusa é RESULTADO, não exceção: o sistema fez o que devia e a resposta é "não".
        // Lançar daqui desfaria o próprio registro da recusa no rollback — foi o defeito da
        // primeira versão, e a linha voltava a AGUARDANDO com os bytes já apagados.
        var recusado = TenantContext.obter(tenant,
                () -> upload.confirmar(autorizacao.arquivoId()));

        assertThat(recusado.status()).isEqualTo(StatusDoArquivo.RECUSADO);
        assertThat(recusado.motivoRecusa()).contains("não é uma imagem");
        assertThat(armazenamento.primeirosBytes(recusado.chave(), 8))
                .as("os bytes foram apagados").isEmpty();
    }

    @Test
    @DisplayName("confirmar sem ter enviado nada não é recusa, é upload que não aconteceu")
    void nada_enviado() {
        // A distinção importa: recusar marcaria o arquivo como problemático, quando o que houve
        // foi o navegador fechar no meio. A linha continua aguardando, para o expurgo levar.
        UUID tenant = salao();
        var autorizacao = TenantContext.obter(tenant,
                () -> upload.autorizar("CLIENTE", UUID.randomUUID(), "foto.jpg"));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> upload.confirmar(autorizacao.arquivoId())));

        assertThat(codigoDe(erro)).isEqualTo("ER-ARQ-NADA_FOI_ENVIADO");
        assertThat(TenantContext.obter(tenant,
                () -> arquivos.porId(autorizacao.arquivoId())).orElseThrow().status())
                .isEqualTo(StatusDoArquivo.AGUARDANDO);
    }

    @Test
    @DisplayName("confirmar duas vezes é recusado")
    void confirmar_duas_vezes() throws IOException {
        UUID tenant = salao();
        var autorizacao = TenantContext.obter(tenant,
                () -> upload.autorizar("CLIENTE", UUID.randomUUID(), "foto.jpg"));
        enviar(autorizacao, jpeg(), tenant);
        TenantContext.executar(tenant, () -> upload.confirmar(autorizacao.arquivoId()));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> upload.confirmar(autorizacao.arquivoId())));

        assertThat(codigoDe(erro)).isEqualTo("ER-ARQ-JA_CONFIRMADO");
    }

    // ------------------------------------------------------------------ isolamento

    @Test
    @DisplayName("um salão não vê o arquivo do outro")
    void isolamento_entre_saloes() {
        UUID salaoA = salao();
        UUID salaoB = salao();
        var doA = TenantContext.obter(salaoA,
                () -> upload.autorizar("CLIENTE", UUID.randomUUID(), "foto.jpg"));

        assertThat(TenantContext.obter(salaoB, () -> arquivos.porId(doA.arquivoId()))).isEmpty();
    }

    @Test
    @DisplayName("a chave carrega o salão, então dois salões nunca colidem")
    void chave_por_salao() {
        UUID salaoA = salao();
        UUID salaoB = salao();

        var doA = TenantContext.obter(salaoA,
                () -> upload.autorizar("CLIENTE", UUID.randomUUID(), "foto.jpg"));
        var doB = TenantContext.obter(salaoB,
                () -> upload.autorizar("CLIENTE", UUID.randomUUID(), "foto.jpg"));

        assertThat(TenantContext.obter(salaoA, () -> arquivos.porId(doA.arquivoId()))
                .orElseThrow().chave()).startsWith(salaoA.toString());
        assertThat(TenantContext.obter(salaoB, () -> arquivos.porId(doB.arquivoId()))
                .orElseThrow().chave()).startsWith(salaoB.toString());
    }
}
