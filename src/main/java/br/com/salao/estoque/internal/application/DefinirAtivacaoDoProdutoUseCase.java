package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.ErrosDoEstoque;
import br.com.salao.estoque.api.ProdutoResumo;
import br.com.salao.estoque.internal.infra.ProdutosJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-001 — tirar o produto de circulação sem apagá-lo.
 *
 * <p>Apagar não é opção: o produto aparece em lote, movimento e item de comanda de meses atrás, e
 * removê-lo deixaria o histórico apontando para o nada. Desativar tira da lista de quem vende e
 * mantém a linha que explica a venda antiga.
 */
public class DefinirAtivacaoDoProdutoUseCase {

    private static final Logger log =
            LoggerFactory.getLogger(DefinirAtivacaoDoProdutoUseCase.class);

    private final ProdutosJdbc produtos;
    private final AuditoriaApi auditoria;

    public DefinirAtivacaoDoProdutoUseCase(ProdutosJdbc produtos, AuditoriaApi auditoria) {
        this.produtos = produtos;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.ESTOQUE_ESCREVER + "')")
    @Transactional
    public ProdutoResumo executar(UUID id, boolean ativo) {
        ProdutoResumo antes = produtos.porId(id)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoEstoque.PRODUTO_NAO_ENCONTRADO,
                        "produto " + id + " não encontrado"));
        produtos.definirAtivacao(id, ativo);

        auditoria.registrar(RegistroDeAuditoria.alteracao(
                ativo ? "PRODUTO_ATIVADO" : "PRODUTO_DESATIVADO", "produto", id,
                Map.of("ativo", antes.ativo()), Map.of("ativo", ativo)));
        log.info("Produto {} {}", id, ativo ? "ativado" : "desativado");
        return produtos.porId(id).orElseThrow();
    }
}
