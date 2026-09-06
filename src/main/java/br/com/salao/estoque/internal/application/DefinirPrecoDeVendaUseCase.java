package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.ErrosDoEstoque;
import br.com.salao.estoque.api.ProdutoResumo;
import br.com.salao.estoque.internal.application.CadastrarProdutoUseCase.ProdutoValido;
import br.com.salao.estoque.internal.infra.ProdutosJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-001 — quanto o produto custa para a cliente.
 *
 * <h2>Permissão própria, e é o mapa que já dizia isso</h2>
 *
 * <p>{@code produto:price:write} existe desde RT-IAM-007 e a recepção não a tem, embora tenha
 * {@code estoque:write}. É a divisão real do salão: quem recebe a mercadoria cadastra o produto;
 * quem decide margem precifica.
 *
 * <h2>Sem vigência, ao contrário do serviço</h2>
 *
 * <p>Preço de serviço tem histórico (RT-CAT-002) porque virou rotina própria. Produto não tem, e o
 * que a vigência daria — <em>por quanto se vendia em março?</em> — o snapshot em
 * {@code item_comanda.preco_unitario} já garante para toda venda fechada.
 *
 * <p>O que se perde é a pergunta sobre o que <strong>não</strong> foi vendido: "por quanto este
 * shampoo estava anunciado em março" não tem resposta. É assimetria consciente, e está na lista de
 * pendências da rotina.
 */
public class DefinirPrecoDeVendaUseCase {

    private static final Logger log = LoggerFactory.getLogger(DefinirPrecoDeVendaUseCase.class);

    private final ProdutosJdbc produtos;
    private final AuditoriaApi auditoria;

    public DefinirPrecoDeVendaUseCase(ProdutosJdbc produtos, AuditoriaApi auditoria) {
        this.produtos = produtos;
        this.auditoria = auditoria;
    }

    /** {@code preco} nulo remove o preço — o produto continua existindo e deixa de ser vendável. */
    @PreAuthorize("hasAuthority('" + Permissao.PRODUTO_PRECO_ESCREVER + "')")
    @Transactional
    public ProdutoResumo executar(UUID id, Money preco) {
        ProdutoResumo antes = produtos.porId(id)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoEstoque.PRODUTO_NAO_ENCONTRADO,
                        "produto " + id + " não encontrado"));
        ProdutoValido.precoCabeNaFinalidade(antes.finalidade(), preco);

        produtos.definirPreco(id, preco);

        Map<String, Object> de = new HashMap<>();
        de.put("precoVenda", antes.precoVenda() == null ? null : antes.precoVenda().toString());
        Map<String, Object> para = new HashMap<>();
        para.put("precoVenda", preco == null ? null : preco.toString());
        auditoria.registrar(RegistroDeAuditoria.alteracao("PRECO_DE_VENDA_DEFINIDO", "produto",
                id, de, para));
        log.info("Preço do produto {} definido: {}", id, preco);
        return produtos.porId(id).orElseThrow();
    }
}
