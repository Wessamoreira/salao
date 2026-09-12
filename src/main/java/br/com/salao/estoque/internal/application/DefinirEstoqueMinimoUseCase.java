package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.ErrosDoEstoque;
import br.com.salao.estoque.internal.infra.ProdutosJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-008 — quanto deste produto o salão não quer ficar sem.
 *
 * <h2>Caso de uso próprio, como o preço</h2>
 *
 * <p>Poderia ser um campo em {@code DadosDoProduto}, e não é — pela mesma razão de
 * {@code DefinirPrecoDeVendaUseCase}: mínimo se ajusta com o tempo, olhando o giro, e quem faz
 * isso não está refazendo o cadastro. Um campo no cadastro obrigaria a passar tudo de novo para
 * mudar um número.
 *
 * <h2>Nulo remove; zero é uma decisão</h2>
 *
 * <p>{@code null} tira o produto da lista de alertas. Zero significa "avise quando acabar", que é
 * o que o dono quer para o produto de vitrine. Se fossem a mesma coisa, não haveria como desligar
 * o alerta de um produto sem desligá-lo de todos.
 */
public class DefinirEstoqueMinimoUseCase {

    private final ProdutosJdbc produtos;
    private final AuditoriaApi auditoria;

    public DefinirEstoqueMinimoUseCase(ProdutosJdbc produtos, AuditoriaApi auditoria) {
        this.produtos = produtos;
        this.auditoria = auditoria;
    }

    /** @param minimo {@code null} remove o mínimo; zero significa "avise quando acabar" */
    @PreAuthorize("hasAuthority('" + Permissao.ESTOQUE_ESCREVER + "')")
    @Transactional
    public void executar(UUID produtoId, BigDecimal minimo) {
        if (minimo != null && minimo.signum() < 0) {
            throw new ErroDeDominio(ErrosDoEstoque.QUANTIDADE_INVALIDA,
                    "o mínimo não pode ser negativo; veio " + minimo);
        }
        if (produtos.definirEstoqueMinimo(produtoId, minimo) == 0) {
            throw new ErroDeDominio(ErrosDoEstoque.PRODUTO_NAO_ENCONTRADO,
                    "produto " + produtoId + " não encontrado");
        }
        auditoria.registrar(RegistroDeAuditoria.alteracao("ESTOQUE_MINIMO_DEFINIDO", "produto",
                produtoId, null,
                // `String.valueOf` e não vazio: remover o mínimo é diferente de pôr zero, e a
                // trilha precisa distinguir os dois.
                Map.of("minimo", String.valueOf(minimo))));
    }
}
