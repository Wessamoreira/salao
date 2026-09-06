package br.com.salao.atendimento.internal.application;

import br.com.salao.atendimento.api.ErrosDoAtendimento;
import br.com.salao.atendimento.api.ComandaDetalhada;
import br.com.salao.atendimento.api.ComandaResumo;
import br.com.salao.atendimento.api.ExecutanteDoItem;
import br.com.salao.atendimento.api.PapelDoExecutante;
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
 * RT-ATD-005 — tirar do item quem não ajudou.
 *
 * <p><strong>Só o assistente sai.</strong> O principal não: um item de serviço sem principal é um
 * serviço que ninguém executou, e a comissão dele não teria dono — o item continuaria faturando e
 * pararia de pagar alguém, sem nada acusar.
 *
 * <p>Trocar o principal é remover o item e lançá-lo de novo. Parece mais trabalho, e é o trabalho
 * certo: quem executou o serviço é parte do que a linha afirma, não um campo dela.
 */
public class RemoverExecutanteUseCase {

    private static final Logger log = LoggerFactory.getLogger(RemoverExecutanteUseCase.class);

    private final ComandasJdbc comandas;
    private final AuditoriaApi auditoria;

    public RemoverExecutanteUseCase(ComandasJdbc comandas, AuditoriaApi auditoria) {
        this.comandas = comandas;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.COMANDA_ABRIR + "')")
    @Transactional
    public ComandaDetalhada executar(UUID comandaId, UUID itemId, UUID executanteId) {
        ComandaResumo comanda = ComandaEmEdicao.exigirAberta(comandas, comandaId);
        if (comandas.itemDoExecutante(executanteId).filter(itemId::equals).isEmpty()) {
            throw new ErroDeDominio(ErrosDoAtendimento.EXECUTANTE_NAO_ENCONTRADO,
                    "executante " + executanteId + " não é do item " + itemId);
        }

        ExecutanteDoItem executante = comandas.itensDe(comandaId).stream()
                .filter(i -> i.id().equals(itemId))
                .flatMap(i -> i.executantes().stream())
                .filter(e -> e.id().equals(executanteId))
                .findFirst()
                .orElseThrow(() -> new ErroDeDominio(ErrosDoAtendimento.EXECUTANTE_NAO_ENCONTRADO,
                        "executante " + executanteId + " não é desta comanda"));
        if (executante.papel() == PapelDoExecutante.PRINCIPAL) {
            throw new ErroDeDominio(ErrosDoAtendimento.PRINCIPAL_NAO_SAI,
                    "para trocar quem executou, remova o item e lance de novo");
        }

        // Antes do delete: depois dele não há mais o que registrar, e "quem saiu do item, com
        // que percentual" é a pergunta que a trilha precisa responder.
        auditoria.registrar(RegistroDeAuditoria.alteracao("EXECUTANTE_REMOVIDO", "executante",
                executanteId, Map.of("profissionalId", executante.profissionalId().toString(),
                        "percentual", executante.percentualComissao().toPlainString()), Map.of()));
        comandas.removerExecutante(executanteId);
        log.info("Assistente {} removido do item {} da comanda {}", executante.profissionalId(),
                itemId, comanda.numero());
        return new ComandaDetalhada(comandas.porId(comandaId).orElseThrow(),
                comandas.itensDe(comandaId));
    }
}
