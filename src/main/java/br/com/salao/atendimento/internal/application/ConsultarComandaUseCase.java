package br.com.salao.atendimento.internal.application;

import br.com.salao.atendimento.api.ComandaDetalhada;
import br.com.salao.atendimento.internal.infra.ComandasJdbc;
import br.com.salao.iam.api.Permissao;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-ATD-002 — a comanda aberta na tela, com tudo que já foi lançado.
 *
 * <p>Separada da barra do balcão porque carrega os itens: mostrar trinta comandas com os itens de
 * todas seria pagar pela tela de fechamento em toda tela do sistema.
 */
public class ConsultarComandaUseCase {

    private final ComandasJdbc comandas;

    public ConsultarComandaUseCase(ComandasJdbc comandas) {
        this.comandas = comandas;
    }

    @PreAuthorize("hasAuthority('" + Permissao.COMANDA_ABRIR + "')"
            + " or hasAuthority('" + Permissao.COMANDA_FECHAR + "')")
    @Transactional(readOnly = true)
    public Optional<ComandaDetalhada> executar(UUID comandaId) {
        return comandas.porId(comandaId)
                .map(comanda -> new ComandaDetalhada(comanda, comandas.itensDe(comandaId)));
    }
}
