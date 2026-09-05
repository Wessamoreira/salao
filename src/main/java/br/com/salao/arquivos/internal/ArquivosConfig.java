package br.com.salao.arquivos.internal;

import br.com.salao.arquivos.api.Armazenamento;
import br.com.salao.arquivos.internal.application.UploadDeArquivoUseCase;
import br.com.salao.arquivos.internal.infra.ArmazenamentoLocal;
import br.com.salao.arquivos.internal.infra.ArquivosJdbc;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** RT-ARQ-001 — fiação do módulo arquivos. */
@Configuration(proxyBeanMethods = false)
public class ArquivosConfig {

    @Bean
    public ArquivosJdbc arquivosJdbc(DataSource dataSource) {
        return new ArquivosJdbc(JdbcClient.create(dataSource));
    }

    /**
     * O armazenamento padrão, em disco. Um provedor de objeto entra como bean {@code @Primary} e
     * passa na frente — mesma forma do mensageiro em RT-NOT-001, e pela mesma razão: nada de
     * {@code @ConditionalOnMissingBean} fora de auto-configuração, que depende da ordem de
     * registro dos beans.
     *
     * <p>O tipo declarado é o concreto porque esta implementação cumpre <em>dois</em> contratos: o
     * port {@code Armazenamento} e o {@code EscritaDeConteudo}, que só vale enquanto a aplicação
     * está no caminho dos bytes. Declarar o bean como {@code Armazenamento} esconderia o segundo.
     *
     * <p>Quem entrar como {@code @Primary} entra como {@code Armazenamento} e passa na frente de
     * todo mundo que injeta o port; a porta local de escrita sai junto, por
     * {@code app.arquivos.modo=objeto}.
     */
    @Bean
    public ArmazenamentoLocal armazenamento(
            @Value("${app.arquivos.raiz:${java.io.tmpdir}/salao-arquivos}") String raiz) {
        return new ArmazenamentoLocal(Path.of(raiz));
    }

    @Bean
    public UploadDeArquivoUseCase uploadDeArquivoUseCase(ArquivosJdbc arquivos,
                                                         Armazenamento armazenamento) {
        return new UploadDeArquivoUseCase(arquivos, armazenamento);
    }
}
