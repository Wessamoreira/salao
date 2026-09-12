package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.ErrosDoEstoque;
import br.com.salao.estoque.api.LoteResumo;
import br.com.salao.estoque.api.StatusDoInventario;
import br.com.salao.estoque.internal.infra.InventariosJdbc;
import br.com.salao.estoque.internal.infra.LotesJdbc;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-006 — registrar o que havia na prateleira.
 *
 * <h2>O esperado congela AQUI, no instante desta contagem</h2>
 *
 * <p>É a decisão que sustenta a rotina inteira, e as duas alternativas estragam o resultado:
 *
 * <ul>
 *   <li><strong>Congelar ao abrir o inventário</strong> faria uma venda durante a contagem virar
 *       divergência falsa — o sistema esperava 10, venderam 2, você contou 7, e o ajuste sairia
 *       −3 quando a divergência real é −1.</li>
 *   <li><strong>Recalcular ao aplicar</strong> faria o número confirmado não ser o número visto,
 *       e a confirmação deixaria de confirmar coisa alguma. É o mesmo defeito que a versão da
 *       prévia resolve no fechamento (RN-FIN-019).</li>
 * </ul>
 *
 * <p>Congelando por linha, o ajuste é um <strong>delta</strong> — {@code contado − esperado} —, e
 * movimento legítimo depois da contagem continua valendo em cima dele: 10 − 2 − 3 dá 5, que é
 * exatamente 7 − 2. A janela de erro fica do tamanho de uma prateleira, não do inventário inteiro.
 *
 * <h2>Recontar substitui</h2>
 *
 * <p>Contar o mesmo lote de novo não soma: substitui, com o esperado do novo instante. Quem
 * recontou uma prateleira está corrigindo a própria contagem, não achando mais unidades.
 */
public class ContarLoteUseCase {

    private final InventariosJdbc inventarios;
    private final LotesJdbc lotes;
    private final Relogio relogio;

    public ContarLoteUseCase(InventariosJdbc inventarios, LotesJdbc lotes, Relogio relogio) {
        this.inventarios = inventarios;
        this.lotes = lotes;
        this.relogio = relogio;
    }

    @PreAuthorize("hasAuthority('" + Permissao.ESTOQUE_ESCREVER + "')")
    @Transactional
    public void executar(UUID inventarioId, UUID loteId, BigDecimal contada, String observacao) {
        if (contada == null || contada.signum() < 0) {
            // Zero É uma contagem — significa "a prateleira está vazia", que é a divergência mais
            // importante de todas. Negativo não existe.
            throw new ErroDeDominio(ErrosDoEstoque.QUANTIDADE_INVALIDA,
                    "a quantidade contada não pode ser negativa; veio " + contada);
        }
        StatusDoInventario status = inventarios.statusDe(inventarioId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoEstoque.INVENTARIO_NAO_ENCONTRADO,
                        "inventário " + inventarioId + " não encontrado"));
        if (status != StatusDoInventario.ABERTO) {
            throw new ErroDeDominio(ErrosDoEstoque.INVENTARIO_ENCERRADO,
                    "este inventário está " + status.name().toLowerCase());
        }
        LoteResumo lote = lotes.porId(loteId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoEstoque.LOTE_NAO_ENCONTRADO,
                        "lote " + loteId + " não encontrado"));

        inventarios.contar(TenantContext.obrigatorio(), inventarioId, loteId, lote.produtoId(),
                // O esperado DESTE instante.
                lote.quantidade(), contada, observacao, relogio.agora(),
                UsuarioAtual.idOuNulo());
    }
}
