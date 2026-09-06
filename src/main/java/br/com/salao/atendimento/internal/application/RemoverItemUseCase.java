package br.com.salao.atendimento.internal.application;

import br.com.salao.atendimento.api.ErrosDoAtendimento;
import br.com.salao.atendimento.api.ComandaDetalhada;
import br.com.salao.atendimento.api.ComandaResumo;
import br.com.salao.atendimento.internal.infra.ComandasJdbc;
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
 * RT-ATD-002 — tirar da comanda o que foi lançado errado.
 *
 * <p>Existe porque sem ela um item digitado errado é permanente, e a comanda não teria como ser
 * corrigida antes de fechar. O plano não tem rotina própria para remover; ela pertence a esta, que
 * é a que lança.
 *
 * <p><strong>Apaga, não marca como removido.</strong> Item lançado por engano não é fato do
 * atendimento — é digitação, e uma comanda cheia de linhas riscadas é pior de conferir do que uma
 * comanda certa. O que precisa sobreviver a correção é o fechamento, e comanda fechada não aceita
 * remoção. Quem quer a trilha tem a auditoria, que é append-only e registra a remoção com o valor
 * que saiu.
 *
 * <p>É ação reversível no sentido de R-UX-05: executa na hora e a tela oferece "Desfazer" — pedir
 * confirmação para tirar uma linha errada é atrito onde não há risco.
 */
public class RemoverItemUseCase {

    private static final Logger log = LoggerFactory.getLogger(RemoverItemUseCase.class);

    private final ComandasJdbc comandas;
    private final AuditoriaApi auditoria;

    public RemoverItemUseCase(ComandasJdbc comandas, AuditoriaApi auditoria) {
        this.comandas = comandas;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.COMANDA_ABRIR + "')")
    @Transactional
    public ComandaDetalhada executar(UUID comandaId, UUID itemId) {
        // O item precisa ser DESTA comanda: sem a conferência, "remova o item X da comanda Y"
        // apagaria o item da comanda Z, e quem olha a tela de Y não veria nada acontecer.
        ComandaResumo comanda = ComandaEmEdicao.exigirAberta(comandas, comandaId);
        // A auditoria vem antes do delete de propósito: depois dele não há mais o que registrar,
        // e "o que saiu da comanda" é justamente a pergunta que a trilha precisa responder.
        var item = ComandaEmEdicao.exigirItem(comandas, comandaId, itemId);
        auditoria.registrar(RegistroDeAuditoria.alteracao("ITEM_COMANDA_REMOVIDO", "item_comanda",
                itemId, Map.of("descricao", item.descricao(),
                        "valorTotal", item.valorTotal().toString()), Map.of()));

        comandas.removerItem(itemId);
        comandas.recalcularTotais(comandaId);
        log.info("Item {} removido da comanda {}", itemId, comanda.numero());
        return new ComandaDetalhada(comandas.porId(comandaId).orElseThrow(),
                comandas.itensDe(comandaId));
    }
}
