package br.com.salao.estoque.internal;

import br.com.salao.estoque.internal.application.AtualizarProdutoUseCase;
import br.com.salao.estoque.internal.application.CadastrarProdutoUseCase;
import br.com.salao.estoque.internal.application.DefinirAtivacaoDoProdutoUseCase;
import br.com.salao.estoque.internal.application.DefinirPrecoDeVendaUseCase;
import br.com.salao.estoque.internal.application.AbrirInventarioUseCase;
import br.com.salao.estoque.internal.application.AplicarInventarioUseCase;
import br.com.salao.estoque.internal.application.CancelarInventarioUseCase;
import br.com.salao.estoque.internal.application.ConsultarInventarioUseCase;
import br.com.salao.estoque.internal.application.ContarLoteUseCase;
import br.com.salao.estoque.internal.application.ConsultarFichaUseCase;
import br.com.salao.estoque.internal.application.DefinirItemDaFichaUseCase;
import br.com.salao.estoque.internal.application.RemoverItemDaFichaUseCase;
import br.com.salao.estoque.internal.infra.FichasJdbc;
import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.estoque.internal.application.ConsultarAlertasUseCase;
import br.com.salao.estoque.internal.application.DefinirEstoqueMinimoUseCase;
import br.com.salao.estoque.internal.application.BaixaFefo;
import br.com.salao.estoque.internal.application.DevolverAoFornecedorUseCase;
import br.com.salao.estoque.internal.application.MovimentoComMotivo;
import br.com.salao.estoque.internal.application.RegistrarAjusteUseCase;
import br.com.salao.estoque.internal.application.RegistrarPerdaUseCase;
import br.com.salao.estoque.internal.application.ConsultarSaldoUseCase;
import br.com.salao.estoque.internal.application.ListarLotesUseCase;
import br.com.salao.estoque.internal.application.ListarProdutosUseCase;
import br.com.salao.estoque.internal.application.RegistrarEntradaDeLoteUseCase;
import br.com.salao.estoque.api.ProdutoApi;
import br.com.salao.estoque.internal.infra.InventariosJdbc;
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
    public DefinirEstoqueMinimoUseCase definirEstoqueMinimoUseCase(ProdutosJdbc produtos,
                                                                   AuditoriaApi auditoria) {
        return new DefinirEstoqueMinimoUseCase(produtos, auditoria);
    }

    @Bean
    public ConsultarAlertasUseCase consultarAlertasUseCase(LotesJdbc lotes) {
        return new ConsultarAlertasUseCase(lotes);
    }

    @Bean
    public FichasJdbc fichasJdbc(DataSource dataSource) {
        return new FichasJdbc(JdbcClient.create(dataSource));
    }

    @Bean
    public BaixaFefo baixaFefo(LotesJdbc lotes, Relogio relogio, FichasJdbc fichas) {
        return new BaixaFefo(lotes, relogio, fichas);
    }

    @Bean
    public DefinirItemDaFichaUseCase definirItemDaFichaUseCase(
            FichasJdbc fichas, ProdutosJdbc produtos, ServicoApi servicos,
            AuditoriaApi auditoria, Relogio relogio) {
        return new DefinirItemDaFichaUseCase(fichas, produtos, servicos, auditoria, relogio);
    }

    @Bean
    public RemoverItemDaFichaUseCase removerItemDaFichaUseCase(FichasJdbc fichas,
                                                               AuditoriaApi auditoria) {
        return new RemoverItemDaFichaUseCase(fichas, auditoria);
    }

    @Bean
    public ConsultarFichaUseCase consultarFichaUseCase(FichasJdbc fichas) {
        return new ConsultarFichaUseCase(fichas);
    }

    @Bean
    public InventariosJdbc inventariosJdbc(DataSource dataSource) {
        return new InventariosJdbc(JdbcClient.create(dataSource));
    }

    @Bean
    public AbrirInventarioUseCase abrirInventarioUseCase(InventariosJdbc inventarios,
                                                         Relogio relogio) {
        return new AbrirInventarioUseCase(inventarios, relogio);
    }

    @Bean
    public ContarLoteUseCase contarLoteUseCase(InventariosJdbc inventarios, LotesJdbc lotes,
                                               Relogio relogio) {
        return new ContarLoteUseCase(inventarios, lotes, relogio);
    }

    @Bean
    public ConsultarInventarioUseCase consultarInventarioUseCase(InventariosJdbc inventarios) {
        return new ConsultarInventarioUseCase(inventarios);
    }

    @Bean
    public AplicarInventarioUseCase aplicarInventarioUseCase(
            InventariosJdbc inventarios, MovimentoComMotivo movimentos,
            EstabelecimentoApi estabelecimentos, AuditoriaApi auditoria, Relogio relogio) {
        return new AplicarInventarioUseCase(inventarios, movimentos, estabelecimentos,
                auditoria, relogio);
    }

    @Bean
    public CancelarInventarioUseCase cancelarInventarioUseCase(InventariosJdbc inventarios,
                                                               AuditoriaApi auditoria,
                                                               Relogio relogio) {
        return new CancelarInventarioUseCase(inventarios, auditoria, relogio);
    }

    @Bean
    public MovimentoComMotivo movimentoComMotivo(LotesJdbc lotes, Relogio relogio) {
        return new MovimentoComMotivo(lotes, relogio);
    }

    @Bean
    public RegistrarPerdaUseCase registrarPerdaUseCase(BaixaFefo baixa,
                                                       MovimentoComMotivo movimentos,
                                                       AuditoriaApi auditoria) {
        return new RegistrarPerdaUseCase(baixa, movimentos, auditoria);
    }

    @Bean
    public RegistrarAjusteUseCase registrarAjusteUseCase(MovimentoComMotivo movimentos,
                                                         AuditoriaApi auditoria) {
        return new RegistrarAjusteUseCase(movimentos, auditoria);
    }

    @Bean
    public DevolverAoFornecedorUseCase devolverAoFornecedorUseCase(
            MovimentoComMotivo movimentos, AuditoriaApi auditoria) {
        return new DevolverAoFornecedorUseCase(movimentos, auditoria);
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
