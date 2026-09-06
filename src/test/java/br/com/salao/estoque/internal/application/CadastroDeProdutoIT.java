package br.com.salao.estoque.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.estoque.api.FinalidadeDoProduto;
import br.com.salao.estoque.api.ProdutoResumo;
import br.com.salao.estoque.api.UnidadeDeMedida;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-EST-001 — cadastro de produto: venda, consumo interno ou ambos. */
@WithMockUser(authorities = { Permissao.ESTOQUE_LER, Permissao.ESTOQUE_ESCREVER,
        Permissao.PRODUTO_PRECO_ESCREVER })
class CadastroDeProdutoIT extends AbstractPostgresIT {

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProdutoUseCase cadastrar;
    @Autowired private AtualizarProdutoUseCase atualizar;
    @Autowired private DefinirPrecoDeVendaUseCase definirPreco;
    @Autowired private DefinirAtivacaoDoProdutoUseCase ativacao;
    @Autowired private ListarProdutosUseCase listar;

    private UUID salao() {
        return provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    private ProdutoResumo cadastrar(UUID tenant, String nome, FinalidadeDoProduto finalidade,
                                    Money preco) {
        return TenantContext.obter(tenant, () -> cadastrar.executar(new DadosDoProduto(
                nome, null, finalidade, UnidadeDeMedida.UN, preco)));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ o caminho normal

    @Test
    @DisplayName("produto de venda com preço já nasce vendável")
    void produto_de_venda() {
        UUID tenant = salao();

        var shampoo = cadastrar(tenant, "Shampoo Hidratante", FinalidadeDoProduto.VENDA,
                Money.de("45"));

        assertThat(shampoo.precoVenda()).isEqualTo(Money.de("45"));
        assertThat(shampoo.unidade()).isEqualTo(UnidadeDeMedida.UN);
        assertThat(shampoo.vendavel()).isTrue();
    }

    @Test
    @DisplayName("produto de consumo interno existe sem preço")
    void consumo_interno() {
        UUID tenant = salao();

        var tinta = cadastrar(tenant, "Tinta 7.1", FinalidadeDoProduto.CONSUMO_INTERNO, null);

        assertThat(tinta.precoVenda()).isNull();
        assertThat(tinta.vendavel()).isFalse();
        assertThat(tinta.finalidade().podeSerVendido()).isFalse();
    }

    @Test
    @DisplayName("o mesmo frasco pode ser as duas coisas")
    void ambos() {
        // Modelar venda e consumo como entidades separadas dividiria o saldo entre elas: o frasco
        // vendido não baixaria do saldo que a coloração usa.
        UUID tenant = salao();

        var shampoo = cadastrar(tenant, "Shampoo Profissional", FinalidadeDoProduto.AMBOS,
                Money.de("60"));

        assertThat(shampoo.vendavel()).isTrue();
    }

    @Test
    @DisplayName("produto sem preço é cadastro válido e venda inválida")
    void sem_preco_e_cadastro_valido() {
        // Mesma filosofia de RN-ATD-005: o produto pode existir antes de alguém decidir quanto
        // cobrar. Quem recusa é a venda, não o cadastro.
        UUID tenant = salao();

        var produto = cadastrar(tenant, "Máscara Nutritiva", FinalidadeDoProduto.VENDA, null);

        assertThat(produto.precoVenda()).isNull();
        assertThat(produto.vendavel()).isFalse();
    }

    // ------------------------------------------------------------------ o que é recusado

    @Test
    @DisplayName("o que não se vende não tem preço")
    void consumo_interno_nao_tem_preco() {
        // Um número ali acabaria lido por alguma tela de venda, e a tinta apareceria no balcão.
        UUID tenant = salao();

        Throwable erro = catchThrowable(() -> cadastrar(tenant, "Tinta 7.1",
                FinalidadeDoProduto.CONSUMO_INTERNO, Money.de("10")));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-CONSUMO_INTERNO_NAO_TEM_PRECO");
    }

    @Test
    @DisplayName("nome repetido é recusado — o saldo ficaria dividido entre os dois")
    void nome_repetido() {
        UUID tenant = salao();
        cadastrar(tenant, "Shampoo Hidratante", FinalidadeDoProduto.VENDA, null);

        Throwable erro = catchThrowable(() -> cadastrar(tenant, "shampoo hidratante",
                FinalidadeDoProduto.VENDA, null));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-NOME_JA_CADASTRADO");
    }

    @Test
    @DisplayName("nome vazio e preço negativo são recusados")
    void dados_invalidos() {
        UUID tenant = salao();

        assertThat(codigoDe(catchThrowable(
                () -> cadastrar(tenant, "   ", FinalidadeDoProduto.VENDA, null))))
                .isEqualTo("ER-EST-DADOS_INVALIDOS");
        assertThat(codigoDe(catchThrowable(
                () -> cadastrar(tenant, "Creme", FinalidadeDoProduto.VENDA, Money.de("-1")))))
                .isEqualTo("ER-EST-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("cadastrar COM preço exige a permissão de preço")
    @WithMockUser(authorities = { Permissao.ESTOQUE_LER, Permissao.ESTOQUE_ESCREVER })
    void recepcao_cadastra_sem_preco() {
        // A recepção recebe mercadoria e cadastra; quem decide margem precifica. Se o preço
        // entrasse no mesmo ato sem checagem própria, a separação seria decorativa.
        UUID tenant = salao();

        var produto = cadastrar(tenant, "Shampoo", FinalidadeDoProduto.VENDA, null);
        assertThat(produto.id()).isNotNull();

        Throwable erro = catchThrowable(() -> cadastrar(tenant, "Condicionador",
                FinalidadeDoProduto.VENDA, Money.de("40")));
        assertThat(erro).isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------------------ alterar

    @Test
    @DisplayName("atualizar muda o cadastro e sobe a versão")
    void atualizar_produto() {
        UUID tenant = salao();
        var antes = cadastrar(tenant, "Tinta 7.1", FinalidadeDoProduto.CONSUMO_INTERNO, null);

        var depois = TenantContext.obter(tenant, () -> atualizar.executar(antes.id(),
                new DadosDoProduto("Tinta 7.1 Louro", "louro médio",
                        FinalidadeDoProduto.CONSUMO_INTERNO, UnidadeDeMedida.G, null),
                antes.versao()));

        assertThat(depois.nome()).isEqualTo("Tinta 7.1 Louro");
        assertThat(depois.unidade()).isEqualTo(UnidadeDeMedida.G);
        assertThat(depois.versao()).isEqualTo(antes.versao() + 1);
    }

    @Test
    @DisplayName("salvar por cima de uma tela velha é recusado")
    void conflito_de_versao() {
        UUID tenant = salao();
        var produto = cadastrar(tenant, "Tinta 7.1", FinalidadeDoProduto.CONSUMO_INTERNO, null);
        TenantContext.executar(tenant, () -> atualizar.executar(produto.id(),
                new DadosDoProduto("Tinta 7.1 A", null, FinalidadeDoProduto.CONSUMO_INTERNO,
                        UnidadeDeMedida.G, null), produto.versao()));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> atualizar.executar(produto.id(), new DadosDoProduto("Tinta 7.1 B", null,
                        FinalidadeDoProduto.CONSUMO_INTERNO, UnidadeDeMedida.G, null),
                        produto.versao())));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-CONFLITO_DE_VERSAO");
    }

    @Test
    @DisplayName("virar consumo interno com preço cadastrado é recusado, não limpo")
    void virar_consumo_interno_com_preco() {
        // Limpar o preço junto faria a recepção — que tem estoque:write e NÃO tem
        // produto:price:write — apagar um preço pela porta dos fundos.
        UUID tenant = salao();
        var shampoo = cadastrar(tenant, "Shampoo", FinalidadeDoProduto.VENDA, Money.de("45"));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> atualizar.executar(shampoo.id(), new DadosDoProduto("Shampoo", null,
                        FinalidadeDoProduto.CONSUMO_INTERNO, UnidadeDeMedida.UN, null),
                        shampoo.versao())));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-CONSUMO_INTERNO_NAO_TEM_PRECO");
        assertThat(TenantContext.obter(tenant, () -> listar.executar(true, false)).get(0)
                .precoVenda()).as("o preço continua lá").isEqualTo(Money.de("45"));
    }

    // ------------------------------------------------------------------ preço

    @Test
    @DisplayName("definir e remover o preço; sem preço o produto deixa de ser vendável")
    void preco_vai_e_volta() {
        UUID tenant = salao();
        var produto = cadastrar(tenant, "Shampoo", FinalidadeDoProduto.VENDA, null);

        var comPreco = TenantContext.obter(tenant,
                () -> definirPreco.executar(produto.id(), Money.de("45")));
        assertThat(comPreco.vendavel()).isTrue();

        var semPreco = TenantContext.obter(tenant,
                () -> definirPreco.executar(produto.id(), null));
        assertThat(semPreco.precoVenda()).isNull();
        assertThat(semPreco.vendavel()).isFalse();
    }

    @Test
    @DisplayName("precificar consumo interno é recusado")
    void precificar_consumo_interno() {
        UUID tenant = salao();
        var tinta = cadastrar(tenant, "Tinta 7.1", FinalidadeDoProduto.CONSUMO_INTERNO, null);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> definirPreco.executar(tinta.id(), Money.de("10"))));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-CONSUMO_INTERNO_NAO_TEM_PRECO");
    }

    // ------------------------------------------------------------------ listagem

    @Test
    @DisplayName("a lista do balcão traz só o que dá para vender agora")
    void lista_do_balcao() {
        UUID tenant = salao();
        cadastrar(tenant, "Shampoo", FinalidadeDoProduto.VENDA, Money.de("45"));
        cadastrar(tenant, "Tinta 7.1", FinalidadeDoProduto.CONSUMO_INTERNO, null);
        var semPreco = cadastrar(tenant, "Máscara", FinalidadeDoProduto.VENDA, null);
        var desativado = cadastrar(tenant, "Creme Antigo", FinalidadeDoProduto.VENDA,
                Money.de("30"));
        TenantContext.executar(tenant, () -> ativacao.executar(desativado.id(), false));

        var balcao = TenantContext.obter(tenant, () -> listar.executar(true, true));

        assertThat(balcao).extracting(ProdutoResumo::nome)
                .containsExactly("Máscara", "Shampoo");
        assertThat(balcao).filteredOn(ProdutoResumo::vendavel).extracting(ProdutoResumo::nome)
                .as("a tinta some da lista de venda; a máscara aparece e não é vendável")
                .containsExactly("Shampoo");
        assertThat(semPreco.vendavel()).isFalse();
    }

    @Test
    @DisplayName("a tela de cadastro vê também o que foi desativado")
    void lista_de_cadastro() {
        // Desativar tira da venda e mantém a linha que explica a venda antiga: apagar deixaria
        // lote, movimento e item de comanda apontando para o nada.
        UUID tenant = salao();
        var produto = cadastrar(tenant, "Creme Antigo", FinalidadeDoProduto.VENDA, Money.de("30"));
        TenantContext.executar(tenant, () -> ativacao.executar(produto.id(), false));

        assertThat(TenantContext.obter(tenant, () -> listar.executar(false, false)))
                .extracting(ProdutoResumo::nome).containsExactly("Creme Antigo");
        assertThat(TenantContext.obter(tenant, () -> listar.executar(true, false))).isEmpty();
    }

    @Test
    @DisplayName("um salão não vê o produto do outro")
    void isolamento_entre_saloes() {
        UUID salaoA = salao();
        UUID salaoB = salao();
        cadastrar(salaoA, "Shampoo", FinalidadeDoProduto.VENDA, Money.de("45"));

        assertThat(TenantContext.obter(salaoB, () -> listar.executar(false, false))).isEmpty();
        // E o mesmo nome pode existir nos dois: a unicidade é por salão.
        assertThat(cadastrar(salaoB, "Shampoo", FinalidadeDoProduto.VENDA, null).id()).isNotNull();
    }
}
