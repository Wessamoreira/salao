package br.com.salao.estoque.internal.infra;

import br.com.salao.estoque.api.FinalidadeDoProduto;
import br.com.salao.estoque.api.ProdutoApi;
import br.com.salao.estoque.api.ProdutoResumo;
import br.com.salao.estoque.api.UnidadeDeMedida;
import br.com.salao.shared.dinheiro.Money;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** RT-EST-001 — os produtos do salão. */
public class ProdutosJdbc implements ProdutoApi {

    private static final String CAMPOS = """
            id, nome, descricao, finalidade, unidade, preco_venda, codigo_barras, ativo, versao
            """;

    private final JdbcClient jdbc;

    public ProdutosJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public UUID inserir(UUID estabelecimentoId, String nome, String descricao,
                        FinalidadeDoProduto finalidade, UnidadeDeMedida unidade,
                        Money precoVenda, UUID criadoPor) {
        return jdbc.sql("""
                insert into produto (estabelecimento_id, nome, descricao, finalidade, unidade,
                                     preco_venda, criado_por)
                values (:estabelecimento, :nome, :descricao, :finalidade, :unidade, :preco, :por)
                returning id
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("nome", nome)
                .param("descricao", descricao)
                .param("finalidade", finalidade.name())
                .param("unidade", unidade.name())
                .param("preco", precoVenda == null ? null : precoVenda.valor())
                .param("por", criadoPor)
                .query(UUID.class)
                .single();
    }

    /**
     * Atualiza o cadastro conferindo a versão.
     *
     * <p>Devolve quantas linhas mudaram: zero significa que a versão não bate, e quem chama
     * transforma isso em conflito. Ler antes para comparar deixaria uma janela entre a leitura e a
     * escrita — a mesma janela que o {@code where versao = :versao} fecha.
     */
    @Transactional
    public int atualizar(UUID id, String nome, String descricao, FinalidadeDoProduto finalidade,
                         UnidadeDeMedida unidade, int versao) {
        return jdbc.sql("""
                update produto
                   set nome = :nome, descricao = :descricao, finalidade = :finalidade,
                       unidade = :unidade, versao = versao + 1
                 where id = :id and versao = :versao
                """)
                .param("id", id)
                .param("nome", nome)
                .param("descricao", descricao)
                .param("finalidade", finalidade.name())
                .param("unidade", unidade.name())
                .param("versao", versao)
                .update();
    }

    /** Preço fora do {@code atualizar} porque a permissão é outra: `produto:price:write`. */
    @Transactional
    public int definirPreco(UUID id, Money preco) {
        return jdbc.sql("""
                update produto set preco_venda = :preco, versao = versao + 1 where id = :id
                """)
                .param("id", id)
                .param("preco", preco == null ? null : preco.valor())
                .update();
    }

    /**
     * Define ou remove o mínimo. {@code null} remove — e remover é diferente de pôr zero.
     *
     * <p>Nulo significa "sem mínimo definido" e não alerta; zero significa "avise quando acabar",
     * que é uma decisão comum para o produto de vitrine. Ver V48.
     */
    @Transactional
    public int definirEstoqueMinimo(UUID id, java.math.BigDecimal minimo) {
        return jdbc.sql("""
                update produto set estoque_minimo = :minimo, versao = versao + 1 where id = :id
                """)
                .param("id", id)
                .param("minimo", minimo)
                .update();
    }

    @Transactional
    public int definirAtivacao(UUID id, boolean ativo) {
        return jdbc.sql("update produto set ativo = :ativo, versao = versao + 1 where id = :id")
                .param("id", id)
                .param("ativo", ativo)
                .update();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProdutoResumo> porId(UUID id) {
        return jdbc.sql("select " + CAMPOS + " from produto where id = :id")
                .param("id", id)
                .query(ProdutosJdbc::mapear)
                .optional();
    }

    /**
     * O catálogo de produtos, em ordem de nome.
     *
     * <p>Sem paginação: um salão tem dezenas de produtos, não milhares. A regra de paginar existe
     * para listagem que cresce sem limite, e esta não cresce — é a mesma decisão de
     * {@code ServicoApi.ativos}.
     */
    @Transactional(readOnly = true)
    public List<ProdutoResumo> listar(boolean somenteAtivos, boolean somenteVendaveis) {
        return jdbc.sql("select " + CAMPOS + """
                  from produto
                 where (:somenteAtivos = false or ativo)
                   and (:somenteVendaveis = false or finalidade in ('VENDA', 'AMBOS'))
                 order by lower(nome)
                """)
                .param("somenteAtivos", somenteAtivos)
                .param("somenteVendaveis", somenteVendaveis)
                .query(ProdutosJdbc::mapear)
                .list();
    }

    /**
     * RT-EST-009 — o produto que o leitor bipou.
     *
     * <p>O código entra na consulta como texto exato, sem `trim` nem `lower`: o que foi guardado é
     * o que o leitor emitiu, e normalizar na leitura e não na escrita criaria pares que casam num
     * lado e não no outro. Quem normaliza é o cadastro, uma vez.
     */
    @Transactional(readOnly = true)
    public Optional<ProdutoResumo> porCodigoDeBarras(String codigo) {
        return jdbc.sql("select " + CAMPOS + " from produto where codigo_barras = :codigo")
                .param("codigo", codigo)
                .query(ProdutosJdbc::mapear)
                .optional();
    }

    /**
     * Define ou remove o código. {@code null} remove.
     *
     * <p>A unicidade por salão é do índice parcial da V49 — conferir antes e gravar depois
     * deixaria a janela entre a leitura e a escrita, e duas pessoas cadastrando o mesmo produto
     * bipado ao mesmo tempo é justamente o caso comum no recebimento de uma carga.
     */
    @Transactional
    public int definirCodigoDeBarras(UUID id, String codigo) {
        return jdbc.sql("""
                update produto set codigo_barras = :codigo, versao = versao + 1 where id = :id
                """)
                .param("id", id)
                .param("codigo", codigo)
                .update();
    }

    private static ProdutoResumo mapear(ResultSet rs, int linha) throws SQLException {
        BigDecimal preco = rs.getBigDecimal("preco_venda");
        return new ProdutoResumo(
                rs.getObject("id", UUID.class),
                rs.getString("nome"),
                rs.getString("descricao"),
                FinalidadeDoProduto.valueOf(rs.getString("finalidade")),
                UnidadeDeMedida.valueOf(rs.getString("unidade")),
                preco == null ? null : new Money(preco),
                rs.getString("codigo_barras"),
                rs.getBoolean("ativo"),
                rs.getInt("versao"));
    }
}
