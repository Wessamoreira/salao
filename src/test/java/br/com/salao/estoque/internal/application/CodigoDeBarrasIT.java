package br.com.salao.estoque.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.estoque.api.FinalidadeDoProduto;
import br.com.salao.estoque.api.ProdutoApi;
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

/** RT-EST-009 — código de barras: do que o leitor emite ao produto. */
@WithMockUser(authorities = { Permissao.ESTOQUE_LER, Permissao.ESTOQUE_ESCREVER,
        Permissao.PRODUTO_PRECO_ESCREVER, Permissao.USUARIO_GERENCIAR })
class CodigoDeBarrasIT extends AbstractPostgresIT {

    private static final String EAN = "7891234567895";

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProdutoUseCase cadastrarProduto;
    @Autowired private DefinirCodigoDeBarrasUseCase definirCodigo;
    @Autowired private ProdutoApi produtos;

    private UUID tenant;

    private void cenario() {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    private UUID produto(String nome) {
        return TenantContext.obter(tenant, () -> cadastrarProduto.executar(new DadosDoProduto(
                nome, null, FinalidadeDoProduto.VENDA, UnidadeDeMedida.UN,
                Money.de("45")))).id();
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    @Test
    @DisplayName("bipar acha o produto")
    void bipar_acha() {
        cenario();
        UUID shampoo = produto("Shampoo");
        TenantContext.executar(tenant, () -> definirCodigo.executar(shampoo, EAN));

        var achado = TenantContext.obter(tenant, () -> produtos.porCodigoDeBarras(EAN));

        assertThat(achado).get().extracting(ProdutoResumo::nome).isEqualTo("Shampoo");
        assertThat(achado.orElseThrow().codigoBarras()).isEqualTo(EAN);
    }

    @Test
    @DisplayName("código com dígito verificador INVÁLIDO é aceito")
    void digito_invalido_e_aceito() {
        /*
         * É decisão, não descuido. Validar EAN-13 recusaria cadastro legítimo: importado com
         * etiqueta mal impressa, item reembalado pelo distribuidor, e a etiqueta que o próprio
         * salão imprime para o que não tem código.
         *
         * O que importa é o guardado ser EXATAMENTE o que o leitor emite. Se ele lê um código
         * inválido, o cadastro precisa poder guardá-lo — senão o produto nunca é encontrado, e o
         * sistema estaria recusando a realidade.
         */
        cenario();
        UUID shampoo = produto("Shampoo importado");
        String invalido = "7891234567890";

        TenantContext.executar(tenant, () -> definirCodigo.executar(shampoo, invalido));

        assertThat(TenantContext.obter(tenant, () -> produtos.porCodigoDeBarras(invalido)))
                .isPresent();
    }

    @Test
    @DisplayName("código interno e alfanumérico também valem: nem toda etiqueta é EAN-13")
    void codigo_interno_aceito() {
        /*
         * O distribuidor reetiqueta com código interno de 5 dígitos, e a etiqueta que o próprio
         * salão imprime costuma ser Code 128 alfanumérico.
         *
         * O campo é texto porque a realidade é texto. Restringi-lo a 13 dígitos recusaria os dois
         * casos — que são justamente os do produto fracionado de revenda.
         */
        cenario();
        UUID interno = produto("Óleo fracionado");
        UUID impresso = produto("Kit montado no salão");

        TenantContext.executar(tenant, () -> {
            definirCodigo.executar(interno, "40312");
            definirCodigo.executar(impresso, "SAL-KIT-01");
        });

        assertThat(TenantContext.obter(tenant, () -> produtos.porCodigoDeBarras("40312")))
                .map(ProdutoResumo::id).contains(interno);
        assertThat(TenantContext.obter(tenant, () -> produtos.porCodigoDeBarras("SAL-KIT-01")))
                .map(ProdutoResumo::id).contains(impresso);
    }

    @Test
    @DisplayName("o código é aparado, então o CR/LF do leitor não estraga a busca")
    void codigo_aparado() {
        cenario();
        UUID shampoo = produto("Shampoo");

        TenantContext.executar(tenant, () -> definirCodigo.executar(shampoo, "  " + EAN + "\n"));

        assertThat(TenantContext.obter(tenant, () -> produtos.porCodigoDeBarras(EAN)))
                .as("busca pelo código limpo acha").isPresent();
    }

    @Test
    @DisplayName("código com espaço no meio é recusado")
    void espaco_no_meio() {
        // Espaço no meio não vem de leitor: vem de quem digitou olhando a etiqueta. E ele nunca
        // casaria com o que o leitor emite.
        cenario();
        UUID shampoo = produto("Shampoo");

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> definirCodigo.executar(shampoo, "789 123")));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-QUANTIDADE_INVALIDA");
    }

    @Test
    @DisplayName("o mesmo código em dois produtos é recusado, nomeando quem já o tem")
    void codigo_duplicado() {
        // A ação de quem bipou é ir ver aquele produto: quase sempre é o mesmo item cadastrado
        // duas vezes.
        cenario();
        UUID primeiro = produto("Shampoo 300ml");
        UUID segundo = produto("Shampoo 300 ml");
        TenantContext.executar(tenant, () -> definirCodigo.executar(primeiro, EAN));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> definirCodigo.executar(segundo, EAN)));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-CODIGO_DE_BARRAS_EM_USO");
        assertThat(erro).hasMessageContaining("Shampoo 300ml");
    }

    @Test
    @DisplayName("dois salões podem ter o mesmo EAN: é o mesmo shampoo do mesmo fabricante")
    void mesmo_ean_em_saloes_diferentes() {
        cenario();
        UUID doA = produto("Shampoo");
        TenantContext.executar(tenant, () -> definirCodigo.executar(doA, EAN));
        UUID salaoB = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Outro " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        UUID doB = TenantContext.obter(salaoB, () -> cadastrarProduto.executar(new DadosDoProduto(
                "Shampoo", null, FinalidadeDoProduto.VENDA, UnidadeDeMedida.UN,
                Money.de("48")))).id();

        TenantContext.executar(salaoB, () -> definirCodigo.executar(doB, EAN));

        assertThat(TenantContext.obter(tenant, () -> produtos.porCodigoDeBarras(EAN))
                .orElseThrow().id()).isEqualTo(doA);
        assertThat(TenantContext.obter(salaoB, () -> produtos.porCodigoDeBarras(EAN))
                .orElseThrow().id()).isEqualTo(doB);
    }

    @Test
    @DisplayName("remover o código libera o EAN para outro produto")
    void remover_libera() {
        cenario();
        UUID primeiro = produto("Shampoo antigo");
        UUID segundo = produto("Shampoo novo");
        TenantContext.executar(tenant, () -> definirCodigo.executar(primeiro, EAN));

        TenantContext.executar(tenant, () -> definirCodigo.executar(primeiro, null));
        TenantContext.executar(tenant, () -> definirCodigo.executar(segundo, EAN));

        assertThat(TenantContext.obter(tenant, () -> produtos.porCodigoDeBarras(EAN))
                .orElseThrow().nome()).isEqualTo("Shampoo novo");
    }

    @Test
    @DisplayName("vazio remove, e não grava string vazia")
    void vazio_remove() {
        cenario();
        UUID shampoo = produto("Shampoo");
        TenantContext.executar(tenant, () -> definirCodigo.executar(shampoo, EAN));

        TenantContext.executar(tenant, () -> definirCodigo.executar(shampoo, "   "));

        assertThat(TenantContext.obter(tenant, () -> produtos.porId(shampoo))
                .orElseThrow().codigoBarras()).isNull();
    }

    @Test
    @DisplayName("código desconhecido devolve vazio, e quem decide a frase é a tela")
    void desconhecido() {
        cenario();

        assertThat(TenantContext.obter(tenant,
                () -> produtos.porCodigoDeBarras("0000000000000"))).isEmpty();
    }

    @Test
    @DisplayName("produto inexistente é 404")
    void produto_inexistente() {
        cenario();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> definirCodigo.executar(UUID.randomUUID(), EAN)));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-PRODUTO_NAO_ENCONTRADO");
    }

    @Test
    @DisplayName("sem estoque:write, não define código")
    @WithMockUser(authorities = Permissao.ESTOQUE_LER)
    void sem_permissao() {
        UUID salao = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        assertThatThrownBy(() -> TenantContext.executar(salao,
                () -> definirCodigo.executar(UUID.randomUUID(), EAN)))
                .isInstanceOf(AccessDeniedException.class);
    }
}
