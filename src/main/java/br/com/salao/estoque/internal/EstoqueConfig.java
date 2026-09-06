package br.com.salao.estoque.internal;

import br.com.salao.estoque.internal.application.AtualizarProdutoUseCase;
import br.com.salao.estoque.internal.application.CadastrarProdutoUseCase;
import br.com.salao.estoque.internal.application.DefinirAtivacaoDoProdutoUseCase;
import br.com.salao.estoque.internal.application.DefinirPrecoDeVendaUseCase;
import br.com.salao.estoque.internal.application.ConsultarSaldoUseCase;
import br.com.salao.estoque.internal.application.ListarLotesUseCase;
import br.com.salao.estoque.internal.application.ListarProdutosUseCase;
import br.com.salao.estoque.internal.application.RegistrarEntradaDeLoteUseCase;
import br.com.salao.estoque.api.ProdutoApi;
import br.com.salao.estoque.internal.infra.LotesJdbc;
import br.com.salao.estoque.internal.infra.ProdutosJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.shared.tempo.Relogio;
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
    public LotesJdbc lotesJdbc(DataSource dataSource) {
        return new LotesJdbc(JdbcClient.create(dataSource));
    }

    @Bean
    public RegistrarEntradaDeLoteUseCase registrarEntradaDeLoteUseCase(
            LotesJdbc lotes, ProdutoApi produtos, EstabelecimentoApi estabelecimentos,
            AuditoriaApi auditoria, Relogio relogio) {
        return new RegistrarEntradaDeLoteUseCase(lotes, produtos, estabelecimentos, auditoria,
                relogio);
    }

    @Bean
    public ListarLotesUseCase listarLotesUseCase(LotesJdbc lotes) {
        return new ListarLotesUseCase(lotes);
    }

    @Bean
    public ConsultarSaldoUseCase consultarSaldoUseCase(LotesJdbc lotes) {
        return new ConsultarSaldoUseCase(lotes);
    }

    @Bean
    public ListarProdutosUseCase listarProdutosUseCase(ProdutosJdbc produtos) {
        return new ListarProdutosUseCase(produtos);
    }
}
