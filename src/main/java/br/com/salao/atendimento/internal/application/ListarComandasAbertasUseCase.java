package br.com.salao.atendimento.internal.application;

import br.com.salao.atendimento.api.ComandaResumo;
import br.com.salao.atendimento.internal.infra.ComandasJdbc;
import br.com.salao.cliente.api.ClienteApi;
import br.com.salao.iam.api.Permissao;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-ATD-001 — a barra de comandas abertas (R-UX-07).
 *
 * <p><em>"Comanda aberta esquecida é dinheiro que sai pela porta."</em> A lista existe para ficar
 * sempre à vista, e por isso vem da mais antiga para a mais nova: a que está lá desde as nove da
 * manhã é a que interessa.
 *
 * <p>Os nomes vêm em <strong>uma</strong> consulta, pela {@code ClienteApi.nomesDe} — que foi
 * escrita exatamente para isto. Buscar cliente a cliente seria uma consulta por linha para desenhar
 * uma barra, e a barra fica aberta o dia inteiro.
 */
public class ListarComandasAbertasUseCase {

    /** O que a barra mostra: a comanda e o nome de quem está sentada na cadeira. */
    public record ComandaEmAberto(ComandaResumo comanda, String clienteNome) {
    }

    private final ComandasJdbc comandas;
    private final ClienteApi clientes;

    public ListarComandasAbertasUseCase(ComandasJdbc comandas, ClienteApi clientes) {
        this.comandas = comandas;
        this.clientes = clientes;
    }

    /**
     * Quem pode abrir ou fechar comanda vê a barra.
     *
     * <p>Não há {@code comanda:read} no mapa de permissões, e inventar uma aqui seria criar
     * permissão fora da rotina que cuida disso (RT-IAM-004). Quem abre precisa ver o que já está
     * aberto — sem isso a barra que existe para evitar a comanda duplicada seria invisível
     * justamente para quem abriria a segunda.
     */
    @PreAuthorize("hasAuthority('" + Permissao.COMANDA_ABRIR + "')"
            + " or hasAuthority('" + Permissao.COMANDA_FECHAR + "')")
    @Transactional(readOnly = true)
    public List<ComandaEmAberto> executar() {
        List<ComandaResumo> abertas = comandas.abertas();
        Map<UUID, String> nomes = clientes.nomesDe(
                abertas.stream().map(ComandaResumo::clienteId).toList());
        return abertas.stream()
                .map(c -> new ComandaEmAberto(c, nomes.get(c.clienteId())))
                .toList();
    }
}
