package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.ErrosDoEstoque;
import br.com.salao.estoque.api.FinalidadeDoProduto;
import br.com.salao.estoque.api.ProdutoResumo;
import br.com.salao.estoque.internal.application.CadastrarProdutoUseCase.ProdutoValido;
import br.com.salao.estoque.internal.infra.ProdutosJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-001 — corrigir o cadastro do produto.
 *
 * <h2>Virar consumo interno com preço cadastrado é recusado</h2>
 *
 * <p>A alternativa seria limpar o preço junto com a mudança — e aí a recepção, que tem
 * {@code estoque:write} e <strong>não</strong> tem {@code produto:price:write}, apagaria um preço
 * pela porta dos fundos. A permissão que existe para separar quem precifica de quem cadastra
 * deixaria de valer por causa de um efeito colateral.
 *
 * <p>Custa um passo a mais: alguém que possa mexer em preço o remove antes. É o passo que mantém a
 * fronteira honesta.
 */
public class AtualizarProdutoUseCase {

    private static final Logger log = LoggerFactory.getLogger(AtualizarProdutoUseCase.class);

    private final ProdutosJdbc produtos;
    private final AuditoriaApi auditoria;

    public AtualizarProdutoUseCase(ProdutosJdbc produtos, AuditoriaApi auditoria) {
        this.produtos = produtos;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.ESTOQUE_ESCREVER + "')")
    @Transactional
    public ProdutoResumo executar(UUID id, DadosDoProduto dados, int versao) {
        ProdutoResumo antes = produtos.porId(id)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoEstoque.PRODUTO_NAO_ENCONTRADO,
                        "produto " + id + " não encontrado"));
        String nome = ProdutoValido.nome(dados.nome());
        ProdutoValido.finalidadeEUnidade(dados.finalidade(), dados.unidade());

        if (dados.finalidade() == FinalidadeDoProduto.CONSUMO_INTERNO
                && antes.precoVenda() != null) {
            throw new ErroDeDominio(ErrosDoEstoque.CONSUMO_INTERNO_NAO_TEM_PRECO,
                    "remova o preço de venda antes de tornar o produto só de consumo interno");
        }

        int linhas;
        try {
            linhas = produtos.atualizar(id, nome, ProdutoValido.texto(dados.descricao()),
                    dados.finalidade(), dados.unidade(), versao);
        } catch (DuplicateKeyException e) {
            throw new ErroDeDominio(ErrosDoEstoque.NOME_JA_CADASTRADO,
                    "já existe um produto chamado " + nome);
        }
        if (linhas == 0) {
            // A versão não bate: alguém salvou entre a leitura da tela e este clique. Sobrescrever
            // faria a alteração do outro sumir sem que ninguém percebesse.
            throw new ErroDeDominio(ErrosDoEstoque.CONFLITO_DE_VERSAO,
                    "o produto foi alterado por outra pessoa; recarregue e tente de novo");
        }

        auditoria.registrar(RegistroDeAuditoria.alteracao("PRODUTO_ALTERADO", "produto", id,
                Map.of("nome", antes.nome(), "finalidade", antes.finalidade().name(),
                        "unidade", antes.unidade().name()),
                Map.of("nome", nome, "finalidade", dados.finalidade().name(),
                        "unidade", dados.unidade().name())));
        log.info("Produto {} alterado", id);
        return produtos.porId(id).orElseThrow();
    }
}
