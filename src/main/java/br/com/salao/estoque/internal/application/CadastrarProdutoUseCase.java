package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.ErrosDoEstoque;
import br.com.salao.estoque.api.FinalidadeDoProduto;
import br.com.salao.estoque.api.ProdutoResumo;
import br.com.salao.estoque.internal.infra.ProdutosJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tenant.TenantContext;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-001 — cadastrar produto.
 *
 * <p>A permissão é {@code estoque:write}, não {@code config:manage}: cadastrar produto é operação
 * de quem recebe mercadoria, e a recepção faz isso. Precificar é outra coisa, com outra permissão
 * — ver {@link DefinirPrecoDeVendaUseCase}.
 *
 * <p>Como a recepção não pode precificar, informar preço aqui exige as duas permissões. É o mesmo
 * cadastro visto de dois lugares: quem só recebe mercadoria cadastra sem preço, e o dono precifica
 * depois.
 */
public class CadastrarProdutoUseCase {

    private static final Logger log = LoggerFactory.getLogger(CadastrarProdutoUseCase.class);

    private final ProdutosJdbc produtos;
    private final AuditoriaApi auditoria;

    public CadastrarProdutoUseCase(ProdutosJdbc produtos, AuditoriaApi auditoria) {
        this.produtos = produtos;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.ESTOQUE_ESCREVER + "')"
            + " and (#dados.precoVenda() == null"
            + " or hasAuthority('" + Permissao.PRODUTO_PRECO_ESCREVER + "'))")
    @Transactional
    public ProdutoResumo executar(DadosDoProduto dados) {
        String nome = ProdutoValido.nome(dados.nome());
        ProdutoValido.finalidadeEUnidade(dados.finalidade(), dados.unidade());
        ProdutoValido.precoCabeNaFinalidade(dados.finalidade(), dados.precoVenda());

        try {
            UUID id = produtos.inserir(TenantContext.obrigatorio(), nome,
                    ProdutoValido.texto(dados.descricao()), dados.finalidade(), dados.unidade(),
                    dados.precoVenda(), UsuarioAtual.idOuNulo());
            registrar(id, nome, dados);
            log.info("Produto {} ({}) cadastrado", id, nome);
            return produtos.porId(id).orElseThrow();
        } catch (DuplicateKeyException e) {
            // O índice único é quem decide, não uma consulta antes: entre a consulta e o insert
            // cabe outra transação, e duas pessoas recebendo a mesma caixa cadastram junto.
            throw new ErroDeDominio(ErrosDoEstoque.NOME_JA_CADASTRADO,
                    "já existe um produto chamado " + nome);
        }
    }

    private void registrar(UUID id, String nome, DadosDoProduto dados) {
        Map<String, Object> depois = new HashMap<>();
        depois.put("nome", nome);
        depois.put("finalidade", dados.finalidade().name());
        depois.put("unidade", dados.unidade().name());
        depois.put("precoVenda", dados.precoVenda() == null ? null : dados.precoVenda().toString());
        auditoria.registrar(RegistroDeAuditoria.criacao("produto", id, depois));
    }

    /** Validações compartilhadas entre cadastrar e atualizar. */
    static final class ProdutoValido {

        private ProdutoValido() {
        }

        static String nome(String bruto) {
            String limpo = texto(bruto);
            if (limpo == null) {
                throw new ErroDeDominio(ErrosDoEstoque.DADOS_INVALIDOS, "o nome é obrigatório");
            }
            return limpo;
        }

        static void finalidadeEUnidade(FinalidadeDoProduto finalidade, Object unidade) {
            if (finalidade == null || unidade == null) {
                throw new ErroDeDominio(ErrosDoEstoque.DADOS_INVALIDOS,
                        "finalidade e unidade são obrigatórias");
            }
        }

        /**
         * O que não se vende não tem preço.
         *
         * <p>Um número ali acabaria lido por alguma tela de venda, e a tinta apareceria no balcão.
         * O {@code check} do banco também barraria, com uma mensagem de constraint que não diz
         * nada a quem chamou.
         */
        static void precoCabeNaFinalidade(FinalidadeDoProduto finalidade, Money preco) {
            if (preco != null && finalidade == FinalidadeDoProduto.CONSUMO_INTERNO) {
                throw new ErroDeDominio(ErrosDoEstoque.CONSUMO_INTERNO_NAO_TEM_PRECO,
                        "produto de consumo interno não tem preço de venda");
            }
            if (preco != null && preco.ehNegativo()) {
                throw new ErroDeDominio(ErrosDoEstoque.DADOS_INVALIDOS,
                        "preço negativo não é preço");
            }
        }

        static String texto(String bruto) {
            if (bruto == null) {
                return null;
            }
            String limpo = bruto.strip();
            return limpo.isEmpty() ? null : limpo;
        }
    }
}
