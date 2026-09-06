package br.com.salao.atendimento.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.atendimento.api.TipoDeItem;
import br.com.salao.cliente.internal.application.CadastrarClienteUseCase;
import br.com.salao.cliente.internal.application.DadosDoCliente;
import br.com.salao.estoque.api.FinalidadeDoProduto;
import br.com.salao.estoque.api.ProdutoResumo;
import br.com.salao.estoque.api.UnidadeDeMedida;
import br.com.salao.estoque.internal.application.CadastrarProdutoUseCase;
import br.com.salao.estoque.internal.application.DadosDoProduto;
import br.com.salao.estoque.internal.application.DefinirAtivacaoDoProdutoUseCase;
import br.com.salao.estoque.internal.application.DefinirPrecoDeVendaUseCase;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-ATD-003 — vender produto na comanda. */
@WithMockUser(authorities = { Permissao.COMANDA_ABRIR, Permissao.CLIENTE_ESCREVER,
        Permissao.ESTOQUE_LER, Permissao.ESTOQUE_ESCREVER, Permissao.PRODUTO_PRECO_ESCREVER })
class AdicionarItemDeProdutoIT extends AbstractPostgresIT {

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private CadastrarProdutoUseCase cadastrarProduto;
    @Autowired private DefinirAtivacaoDoProdutoUseCase ativacao;
    @Autowired private DefinirPrecoDeVendaUseCase definirPreco;
    @Autowired private AbrirComandaUseCase abrir;
    @Autowired private AdicionarItemDeProdutoUseCase adicionar;

    private UUID tenant;
    private UUID comanda;

    private void cenario() {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        UUID maria = TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Maria Silva", null, null, null, null)));
        comanda = TenantContext.obter(tenant, () -> abrir.avulsa(maria)).comanda().id();
    }

    private ProdutoResumo produto(String nome, FinalidadeDoProduto finalidade, Money preco) {
        return TenantContext.obter(tenant, () -> cadastrarProduto.executar(new DadosDoProduto(
                nome, null, finalidade, UnidadeDeMedida.UN, preco)));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ o caminho normal

    @Test
    @DisplayName("o produto entra na comanda e o total sobe")
    void vende_produto() {
        cenario();
        UUID shampoo = produto("Shampoo", FinalidadeDoProduto.VENDA, Money.de("45")).id();

        var detalhada = TenantContext.obter(tenant,
                () -> adicionar.executar(comanda, shampoo, null));

        var item = detalhada.itens().get(0);
        assertThat(item.tipo()).isEqualTo(TipoDeItem.PRODUTO);
        assertThat(item.produtoId()).isEqualTo(shampoo);
        assertThat(item.servicoId()).isNull();
        assertThat(item.descricao()).isEqualTo("Shampoo");
        assertThat(item.valorTotal()).isEqualTo(Money.de("45"));
        assertThat(detalhada.comanda().valorLiquido()).isEqualTo(Money.de("45"));
    }

    @Test
    @DisplayName("produto não tem executante")
    void produto_nao_tem_executante() {
        // Pergunta 3 de 13-perguntas-em-aberto: produto paga 0% por padrão. Com zero, a linha de
        // executante seria uma comissão de nada para alguém que ninguém escolheu.
        cenario();
        UUID shampoo = produto("Shampoo", FinalidadeDoProduto.VENDA, Money.de("45")).id();

        var item = TenantContext.obter(tenant,
                () -> adicionar.executar(comanda, shampoo, null)).itens().get(0);

        assertThat(item.executantes()).isEmpty();
    }

    @Test
    @DisplayName("quantidade multiplica; a unidade do produto é que dá sentido ao número")
    void quantidade_multiplica() {
        cenario();
        UUID shampoo = produto("Shampoo", FinalidadeDoProduto.VENDA, Money.de("45")).id();

        var detalhada = TenantContext.obter(tenant,
                () -> adicionar.executar(comanda, shampoo, new BigDecimal("3")));

        assertThat(detalhada.itens().get(0).valorTotal()).isEqualTo(Money.de("135"));
        assertThat(detalhada.comanda().valorBruto()).isEqualTo(Money.de("135"));
    }

    @Test
    @DisplayName("produto AMBOS é vendável: o mesmo frasco lava aqui e vai na sacola")
    void produto_ambos() {
        cenario();
        UUID shampoo = produto("Shampoo Profissional", FinalidadeDoProduto.AMBOS,
                Money.de("60")).id();

        var detalhada = TenantContext.obter(tenant,
                () -> adicionar.executar(comanda, shampoo, null));

        assertThat(detalhada.itens()).hasSize(1);
    }

    @Test
    @DisplayName("o preço é snapshot: mudar o cadastro depois não mexe no item já lançado")
    void preco_e_snapshot() {
        // Mesma razão de RN-ATD-010 no serviço: reprocessar uma venda de três meses atrás com o
        // preço de hoje daria número diferente do que a cliente pagou.
        cenario();
        var shampoo = produto("Shampoo", FinalidadeDoProduto.VENDA, Money.de("45"));
        TenantContext.executar(tenant, () -> adicionar.executar(comanda, shampoo.id(), null));

        TenantContext.executar(tenant, () -> definirPreco.executar(shampoo.id(), Money.de("60")));
        var detalhada = TenantContext.obter(tenant,
                () -> adicionar.executar(comanda, shampoo.id(), null));

        assertThat(detalhada.itens().get(0).precoUnitario())
                .as("o primeiro item continua com o preço do momento da venda")
                .isEqualTo(Money.de("45"));
        assertThat(detalhada.itens().get(1).precoUnitario()).isEqualTo(Money.de("60"));
        assertThat(detalhada.comanda().valorBruto()).isEqualTo(Money.de("105"));
    }

    // ------------------------------------------------------------------ as três recusas

    @Test
    @DisplayName("produto que não existe")
    void produto_inexistente() {
        cenario();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> adicionar.executar(comanda, UUID.randomUUID(), null)));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-PRODUTO_NAO_ENCONTRADO");
    }

    @Test
    @DisplayName("consumo interno não vai para a comanda")
    void consumo_interno_nao_vende() {
        // Digitar preço não resolve: é tinta. Por isso o código é outro, e não "sem preço".
        cenario();
        UUID tinta = produto("Tinta 7.1", FinalidadeDoProduto.CONSUMO_INTERNO, null).id();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> adicionar.executar(comanda, tinta, null)));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-PRODUTO_NAO_E_VENDAVEL");
    }

    @Test
    @DisplayName("produto desativado saiu de linha, e o código diz isso")
    void produto_desativado() {
        cenario();
        var creme = produto("Creme Antigo", FinalidadeDoProduto.VENDA, Money.de("30"));
        TenantContext.executar(tenant, () -> ativacao.executar(creme.id(), false));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> adicionar.executar(comanda, creme.id(), null)));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-PRODUTO_NAO_E_VENDAVEL");
    }

    @Test
    @DisplayName("produto sem preço é falha de cadastro, não brinde")
    void produto_sem_preco() {
        cenario();
        UUID mascara = produto("Máscara", FinalidadeDoProduto.VENDA, null).id();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> adicionar.executar(comanda, mascara, null)));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-PRODUTO_SEM_PRECO");
    }

    @Test
    @DisplayName("quantidade zero é recusada")
    void quantidade_zero() {
        cenario();
        UUID shampoo = produto("Shampoo", FinalidadeDoProduto.VENDA, Money.de("45")).id();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> adicionar.executar(comanda, shampoo, BigDecimal.ZERO)));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-QUANTIDADE_INVALIDA");
    }

    @Test
    @DisplayName("produto de outro salão não existe para este")
    void produto_de_outro_salao() {
        cenario();
        UUID outro = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Outro " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        UUID tenantOriginal = tenant;
        tenant = outro;
        UUID shampooDoOutro = produto("Shampoo", FinalidadeDoProduto.VENDA, Money.de("45")).id();
        tenant = tenantOriginal;

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> adicionar.executar(comanda, shampooDoOutro, null)));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-PRODUTO_NAO_ENCONTRADO");
    }
}
