package br.com.salao.catalogo.internal.application;

import br.com.salao.catalogo.api.ServicoResumo;
import br.com.salao.catalogo.internal.infra.ServicosJdbc;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CAT-005 — o cardápio com preço, que é como o cardápio se lê.
 *
 * <h2>Por que não é o {@link ListarServicosUseCase}</h2>
 *
 * Aquele devolve o serviço como os outros módulos o usam: para encher o seletor da agenda e da
 * comanda, onde o preço não entra (quem precifica a comanda é RN-CAT-006, com o preço do dia da
 * venda). Este é para a tela de quem administra o cardápio, e ali a linha sem preço é a
 * informação mais importante da lista — é o serviço que a comanda vai recusar vender.
 *
 * <h2>"Vigente" é vigente HOJE, no fuso do salão</h2>
 *
 * Pela mesma razão de {@code DefinirPrecoUseCase}: à meia-noite de Londres o dia de São Paulo
 * ainda é ontem, e um aumento marcado para hoje apareceria como futuro por três horas.
 */
public class ListarCardapioUseCase {

    /** Um serviço e quanto ele custa hoje — {@code null} quando ninguém precificou ainda. */
    public record ItemDoCardapio(ServicoResumo servico, Money precoVigente) {
    }

    private final ServicosJdbc servicos;
    private final EstabelecimentoApi estabelecimentos;
    private final Relogio relogio;

    public ListarCardapioUseCase(ServicosJdbc servicos, EstabelecimentoApi estabelecimentos,
                                 Relogio relogio) {
        this.servicos = servicos;
        this.estabelecimentos = estabelecimentos;
        this.relogio = relogio;
    }

    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_LER_TODAS + "')"
            + " or hasAuthority('" + Permissao.AGENDA_LER_PROPRIA + "')"
            + " or hasAuthority('" + Permissao.CONFIG_GERENCIAR + "')")
    @Transactional(readOnly = true)
    public List<ItemDoCardapio> executar(boolean incluirInativos) {
        List<ServicoResumo> lista = incluirInativos ? servicos.todos() : servicos.ativos();
        LocalDate hoje = relogio.hojeEm(
                estabelecimentos.fusoDe(TenantContext.obrigatorio()));
        Map<UUID, Money> precos = servicos.precosVigentesEm(
                lista.stream().map(ServicoResumo::id).toList(), hoje);

        return lista.stream()
                .map(servico -> new ItemDoCardapio(servico, precos.get(servico.id())))
                .toList();
    }
}
