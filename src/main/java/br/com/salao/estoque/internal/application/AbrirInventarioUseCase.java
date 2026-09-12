package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.ErrosDoEstoque;
import br.com.salao.estoque.internal.infra.InventariosJdbc;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-006 — abrir a contagem.
 *
 * <h2>Um por salão, garantido pelo banco</h2>
 *
 * <p>Dois inventários abertos ao mesmo tempo contariam o mesmo lote com esperados diferentes, e
 * aplicar os dois somaria duas correções para uma única divergência — o estoque ficaria pior do
 * que antes da contagem.
 *
 * <p>O índice único parcial da V46 é quem garante; esta classe só traduz a violação numa frase.
 * Conferir antes e inserir depois deixaria a janela entre a leitura e a escrita — e duas pessoas
 * abrindo o inventário ao mesmo tempo no depósito é exatamente o caso comum.
 */
public class AbrirInventarioUseCase {

    private static final Logger log = LoggerFactory.getLogger(AbrirInventarioUseCase.class);

    private final InventariosJdbc inventarios;
    private final Relogio relogio;

    public AbrirInventarioUseCase(InventariosJdbc inventarios, Relogio relogio) {
        this.inventarios = inventarios;
        this.relogio = relogio;
    }

    @PreAuthorize("hasAuthority('" + Permissao.ESTOQUE_ESCREVER + "')")
    @Transactional
    public UUID executar(String observacao) {
        try {
            UUID id = inventarios.abrir(TenantContext.obrigatorio(), observacao,
                    relogio.agora(), UsuarioAtual.idOuNulo());
            log.info("Inventário {} aberto", id);
            return id;
        } catch (DataIntegrityViolationException e) {
            String detalhe = e.getMostSpecificCause().getMessage();
            if (detalhe != null && detalhe.contains("um_inventario_aberto_por_salao")) {
                throw new ErroDeDominio(ErrosDoEstoque.INVENTARIO_JA_ABERTO,
                        "encerre ou cancele o inventário aberto antes de começar outro");
            }
            throw e;
        }
    }
}
