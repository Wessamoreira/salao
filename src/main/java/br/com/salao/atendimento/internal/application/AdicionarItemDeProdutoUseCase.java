package br.com.salao.atendimento.internal.application;

import br.com.salao.atendimento.api.ComandaDetalhada;
import br.com.salao.atendimento.api.ComandaResumo;
import br.com.salao.atendimento.api.ErrosDoAtendimento;
import br.com.salao.atendimento.internal.infra.ComandasJdbc;
import br.com.salao.estoque.api.ProdutoApi;
import br.com.salao.estoque.api.ProdutoResumo;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-ATD-003 — vender produto na comanda.
 *
 * <h2>Três recusas diferentes, com correções diferentes</h2>
 *
 * <p>O produto pode não existir, existir e não estar à venda (consumo interno, ou desativado), ou
 * estar à venda sem preço. São três telas diferentes para quem está no balcão:
 *
 * <ul>
 *   <li>não existe — procure outro, ou cadastre;</li>
 *   <li>não está à venda — é tinta, ou saiu de linha; digitar preço não resolve;</li>
 *   <li>sem preço — falta o dono precificar, e é ele quem pode.</li>
 * </ul>
 *
 * <p>Um "não deu" só serviria para todas, e a recepção ficaria adivinhando qual delas era.
 *
 * <h2>Produto não tem executante</h2>
 *
 * <p>A pergunta 3 de {@code 13-perguntas-em-aberto} — <em>produto paga comissão?</em> — tem como
 * padrão de trabalho <strong>0%</strong>. Com zero, a linha de executante não teria o que guardar:
 * seria uma comissão de zero para alguém que ninguém escolheu.
 *
 * <p>O preço disso é que <em>quem vendeu</em> não fica registrado — e é uma perda real no dia em
 * que o salão resolver premiar venda de produto. Quando a cascata (RT-FIN-002) trouxer o nível de
 * produto, é ela que decide se a linha passa a existir.
 *
 * <h2>O estoque não se mexe aqui</h2>
 *
 * <p>Lançar na comanda não baixa saldo. A baixa acontece no <strong>fechamento</strong>
 * (RT-ATD-006), junto com pagamento e comissão, numa transação só — porque comanda aberta é
 * intenção, e item lançado por engano seria estoque baixado por engano.
 */
public class AdicionarItemDeProdutoUseCase {

    private static final Logger log =
            LoggerFactory.getLogger(AdicionarItemDeProdutoUseCase.class);

    private final ComandasJdbc comandas;
    private final ProdutoApi produtos;
    private final AuditoriaApi auditoria;
    private final Relogio relogio;

    public AdicionarItemDeProdutoUseCase(ComandasJdbc comandas, ProdutoApi produtos,
                                         AuditoriaApi auditoria, Relogio relogio) {
        this.comandas = comandas;
        this.produtos = produtos;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    @PreAuthorize("hasAuthority('" + Permissao.COMANDA_ABRIR + "')")
    @Transactional
    public ComandaDetalhada executar(UUID comandaId, UUID produtoId, BigDecimal quantidade) {
        UUID tenant = TenantContext.obrigatorio();
        ComandaResumo comanda = ComandaEmEdicao.exigirAberta(comandas, comandaId);
        BigDecimal quantas = ComandaEmEdicao.quantidadeValida(quantidade);
        ProdutoResumo produto = vendavel(produtoId);

        Money precoUnitario = produto.precoVenda();
        Money valorTotal = precoUnitario.multiplicar(quantas);
        UUID itemId = comandas.inserirItemDeProduto(tenant, comandaId, produtoId, produto.nome(),
                quantas, precoUnitario, valorTotal, relogio.agora());
        comandas.recalcularTotais(comandaId);

        auditoria.registrar(RegistroDeAuditoria.criacao("item_comanda", itemId,
                Map.of("comandaId", comandaId.toString(), "descricao", produto.nome(),
                        "quantidade", quantas.toPlainString(),
                        "valorTotal", valorTotal.toString())));
        log.info("Produto {} x{} lançado na comanda {}", produto.nome(), quantas,
                comanda.numero());
        return new ComandaDetalhada(comandas.porId(comandaId).orElseThrow(),
                comandas.itensDe(comandaId));
    }

    private ProdutoResumo vendavel(UUID produtoId) {
        ProdutoResumo produto = produtos.porId(produtoId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoAtendimento.PRODUTO_NAO_ENCONTRADO,
                        "produto " + produtoId + " não encontrado"));
        if (!produto.ativo() || !produto.finalidade().podeSerVendido()) {
            throw new ErroDeDominio(ErrosDoAtendimento.PRODUTO_NAO_E_VENDAVEL,
                    produto.nome() + " não está à venda");
        }
        if (produto.precoVenda() == null) {
            // Zero seria um brinde, e a falha de cadastro viraria faturamento perdido silencioso.
            // É a mesma regra do serviço (RN-ATD-005), do outro lado do balcão.
            throw new ErroDeDominio(ErrosDoAtendimento.PRODUTO_SEM_PRECO,
                    produto.nome() + " não tem preço de venda");
        }
        return produto;
    }
}
