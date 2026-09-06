package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.ErrosDoEstoque;
import br.com.salao.estoque.api.LoteResumo;
import br.com.salao.estoque.api.ProdutoApi;
import br.com.salao.estoque.api.ProdutoResumo;
import br.com.salao.estoque.api.TipoDeMovimento;
import br.com.salao.estoque.internal.infra.LotesJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-002 — a caixa chegou.
 *
 * <h2>O lote e o movimento nascem juntos, numa transação</h2>
 *
 * <p>O lote é a <em>caixa</em>: custo, validade, código. O movimento {@code ENTRADA} é o
 * <em>lançamento</em> que põe as unidades no saldo. Um sem o outro seria lote fantasma (existe e
 * não tem nada) ou movimento órfão (unidades sem custo nem validade).
 *
 * <p>Não há coluna {@code quantidade_inicial} no lote, ao contrário do desenho em
 * {@code 04-modelo-de-dados}: ela seria uma segunda fonte para o mesmo número, e a projeção dos
 * movimentos já responde "quanto veio" e "quanto resta". Duas fontes divergem — é a mesma razão de
 * RN-ATD-008.
 *
 * <h2>Entrada não se corrige; corrige-se com movimento novo</h2>
 *
 * <p>Movimento é imutável. Custo digitado errado vira ajuste (RT-EST-005), nunca {@code update} —
 * senão o custo de uma venda já feita mudaria retroativamente, e a margem do mês passado mudaria
 * sozinha.
 *
 * <h2>Validade no passado é aceita</h2>
 *
 * <p>Recusar impediria justamente o caso que precisa de registro: a caixa que chegou vencida e vai
 * voltar para o fornecedor. O sistema registra o que aconteceu; quem reclama é o alerta
 * (RT-EST-008), e quem devolve é RT-EST-005.
 */
public class RegistrarEntradaDeLoteUseCase {

    private static final Logger log =
            LoggerFactory.getLogger(RegistrarEntradaDeLoteUseCase.class);

    private final LotesJdbc lotes;
    private final ProdutoApi produtos;
    private final EstabelecimentoApi estabelecimentos;
    private final AuditoriaApi auditoria;
    private final Relogio relogio;

    public RegistrarEntradaDeLoteUseCase(LotesJdbc lotes, ProdutoApi produtos,
                                         EstabelecimentoApi estabelecimentos,
                                         AuditoriaApi auditoria, Relogio relogio) {
        this.lotes = lotes;
        this.produtos = produtos;
        this.estabelecimentos = estabelecimentos;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    @PreAuthorize("hasAuthority('" + Permissao.ESTOQUE_ESCREVER + "')")
    @Transactional
    public LoteResumo executar(UUID produtoId, DadosDaEntrada dados) {
        UUID tenant = TenantContext.obrigatorio();
        ProdutoResumo produto = produtos.porId(produtoId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoEstoque.PRODUTO_NAO_ENCONTRADO,
                        "produto " + produtoId + " não encontrado"));
        if (!produto.ativo()) {
            // Receber mercadoria de um produto tirado de circulação é quase sempre o cadastro
            // errado escolhido na tela. Reativar é um clique; descobrir depois que o saldo entrou
            // no produto errado é um inventário.
            throw new ErroDeDominio(ErrosDoEstoque.PRODUTO_INATIVO,
                    produto.nome() + " está desativado");
        }
        BigDecimal quantidade = quantidadeValida(dados);
        Money custo = custoValido(dados);

        LocalDate hoje = relogio.hojeEm(estabelecimentos.fusoDe(tenant));
        LocalDate recebidoEm = dados.recebidoEm() == null ? hoje : dados.recebidoEm();

        UUID loteId = lotes.inserirLote(tenant, produtoId, texto(dados.codigo()), custo,
                dados.validade(), recebidoEm, relogio.agora(), UsuarioAtual.idOuNulo());
        lotes.inserirMovimento(tenant, produtoId, loteId, TipoDeMovimento.ENTRADA, quantidade,
                custo, "LOTE", loteId, null, relogio.agora(), UsuarioAtual.idOuNulo());

        auditoria.registrar(RegistroDeAuditoria.criacao("lote", loteId,
                Map.of("produtoId", produtoId.toString(), "quantidade", quantidade.toPlainString(),
                        "custoUnitario", custo.toString(),
                        "validade", String.valueOf(dados.validade()))));
        log.info("Lote {} de {} x{} a {} recebido", loteId, produto.nome(), quantidade, custo);
        return lotes.porId(loteId).orElseThrow();
    }

    private BigDecimal quantidadeValida(DadosDaEntrada dados) {
        if (dados == null || dados.quantidade() == null || dados.quantidade().signum() <= 0) {
            throw new ErroDeDominio(ErrosDoEstoque.QUANTIDADE_INVALIDA,
                    "quantidade informada: " + (dados == null ? null : dados.quantidade()));
        }
        return dados.quantidade();
    }

    /** Zero é custo válido — amostra e brinde do fornecedor chegam sem custar nada. */
    private Money custoValido(DadosDaEntrada dados) {
        if (dados.custoUnitario() == null || dados.custoUnitario().ehNegativo()) {
            throw new ErroDeDominio(ErrosDoEstoque.DADOS_INVALIDOS,
                    "custo unitário não pode ser negativo");
        }
        return dados.custoUnitario();
    }

    private String texto(String bruto) {
        if (bruto == null) {
            return null;
        }
        String limpo = bruto.strip();
        return limpo.isEmpty() ? null : limpo;
    }
}
