package br.com.salao.estoque.internal.application;

import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.estoque.api.ErrosDoEstoque;
import br.com.salao.estoque.api.FinalidadeDoProduto;
import br.com.salao.estoque.internal.infra.FichasJdbc;
import br.com.salao.estoque.internal.infra.ProdutosJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
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
 * RT-EST-007 — dizer quanto um serviço consome de um insumo.
 *
 * <h2>Definir é substituir</h2>
 *
 * <p>Mandar a mesma dupla de novo troca a quantidade. Duas linhas do mesmo produto no mesmo serviço
 * fariam a baixa dobrar sem ninguém pedir — e o índice único já impediria, com uma mensagem de
 * constraint que não diz nada a quem chamou.
 *
 * <h2>Só produto consumível</h2>
 *
 * <p>Um shampoo de prateleira usado no serviço precisa ter finalidade {@code AMBOS}. A recusa é
 * explícita em vez de um aceite silencioso porque a decisão tem consequência de preço — produto de
 * consumo interno não tem preço de venda (RT-EST-001) — e ela é do cadastro, não desta tela.
 *
 * <h2>Confere serviço e produto pelas APIs dos donos</h2>
 *
 * <p>Não há FK para nenhum dos dois, e no PostgreSQL a FK nem separaria tenant (RN-CAT-011). Sem a
 * checagem, uma ficha apontando para o serviço de outro salão ficaria gravada e nunca casaria com
 * nada — silenciosa, que é a pior forma de errar.
 */
public class DefinirItemDaFichaUseCase {

    private static final Logger log = LoggerFactory.getLogger(DefinirItemDaFichaUseCase.class);

    private final FichasJdbc fichas;
    private final ProdutosJdbc produtos;
    private final ServicoApi servicos;
    private final AuditoriaApi auditoria;
    private final Relogio relogio;

    public DefinirItemDaFichaUseCase(FichasJdbc fichas, ProdutosJdbc produtos,
                                     ServicoApi servicos, AuditoriaApi auditoria,
                                     Relogio relogio) {
        this.fichas = fichas;
        this.produtos = produtos;
        this.servicos = servicos;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    @PreAuthorize("hasAuthority('" + Permissao.ESTOQUE_ESCREVER + "')")
    @Transactional
    public UUID executar(UUID servicoId, UUID produtoId, BigDecimal quantidade) {
        if (quantidade == null || quantidade.signum() <= 0) {
            throw new ErroDeDominio(ErrosDoEstoque.QUANTIDADE_INVALIDA,
                    "a ficha diz quanto SAI; a quantidade precisa ser positiva");
        }
        if (servicoId == null || servicos.porId(servicoId).isEmpty()) {
            throw new ErroDeDominio(ErrosDoEstoque.SERVICO_NAO_ENCONTRADO,
                    "serviço " + servicoId + " não encontrado neste salão");
        }
        var produto = produtos.porId(produtoId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoEstoque.PRODUTO_NAO_ENCONTRADO,
                        "produto " + produtoId + " não encontrado"));
        if (produto.finalidade() == FinalidadeDoProduto.VENDA) {
            throw new ErroDeDominio(ErrosDoEstoque.PRODUTO_NAO_E_CONSUMIVEL,
                    produto.nome() + " está cadastrado só para venda; para usá-lo no serviço,"
                            + " mude a finalidade para AMBOS");
        }

        UUID id = fichas.definir(TenantContext.obrigatorio(), servicoId, produtoId, quantidade,
                relogio.agora(), UsuarioAtual.idOuNulo());

        auditoria.registrar(RegistroDeAuditoria.criacao("ficha_tecnica", id, Map.of(
                "servicoId", String.valueOf(servicoId),
                "produtoId", String.valueOf(produtoId),
                "quantidade", quantidade.stripTrailingZeros().toPlainString())));
        log.info("Ficha técnica: serviço {} consome {} de {}", servicoId,
                quantidade.stripTrailingZeros().toPlainString(), produto.nome());
        return id;
    }
}
