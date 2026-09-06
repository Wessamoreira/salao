package br.com.salao.estoque.internal;

import br.com.salao.estoque.internal.application.AtualizarProdutoUseCase;
import br.com.salao.estoque.internal.application.CadastrarProdutoUseCase;
import br.com.salao.estoque.internal.application.DefinirAtivacaoDoProdutoUseCase;
import br.com.salao.estoque.internal.application.DefinirPrecoDeVendaUseCase;
import br.com.salao.estoque.internal.application.ListarProdutosUseCase;
import br.com.salao.estoque.internal.infra.ProdutosJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** RT-EST-001 — fiação do módulo estoque. */
@Configuration(proxyBeanMethods = false)
public class EstoqueConfig {

    @Bean
    public ProdutosJdbc produtosJdbc(DataSource dataSource) {
        return new ProdutosJdbc(JdbcClient.create(dataSource));
    }

    @Bean
    public CadastrarProdutoUseCase cadastrarProdutoUseCase(ProdutosJdbc produtos,
                                                           AuditoriaApi auditoria) {
        return new CadastrarProdutoUseCase(produtos, auditoria);
    }

    @Bean
    public AtualizarProdutoUseCase atualizarProdutoUseCase(ProdutosJdbc produtos,
                                                           AuditoriaApi auditoria) {
        return new AtualizarProdutoUseCase(produtos, auditoria);
    }

    @Bean
    public DefinirPrecoDeVendaUseCase definirPrecoDeVendaUseCase(ProdutosJdbc produtos,
                                                                 AuditoriaApi auditoria) {
        return new DefinirPrecoDeVendaUseCase(produtos, auditoria);
    }

    @Bean
    public DefinirAtivacaoDoProdutoUseCase definirAtivacaoDoProdutoUseCase(
            ProdutosJdbc produtos, AuditoriaApi auditoria) {
        return new DefinirAtivacaoDoProdutoUseCase(produtos, auditoria);
    }

    @Bean
    public ListarProdutosUseCase listarProdutosUseCase(ProdutosJdbc produtos) {
        return new ListarProdutosUseCase(produtos);
    }
}
