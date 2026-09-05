package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.AgendamentoResumo;
import br.com.salao.agenda.api.BloqueioDaGrade;
import br.com.salao.agenda.api.Janela;
import br.com.salao.agenda.internal.infra.AgendaJdbc;
import br.com.salao.cliente.api.ClienteApi;
import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.equipe.api.ProfissionalResumo;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.tenant.TenantContext;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-AGD-011 — tudo o que a grade de um dia precisa, numa chamada.
 *
 * <p>Uma chamada e não quatro. A recepção troca de dia a cada poucos segundos, e cada troca que
 * dispara quatro requisições produz quatro momentos em que a tela está pela metade — colunas sem
 * blocos, blocos sem nome. Um payload só chega inteiro ou não chega.
 *
 * <p>São seis consultas por dentro, nenhuma delas por profissional nem por atendimento:
 * profissionais · jornadas · cabeças · blocos · bloqueios · nomes.
 */
public class AgendaDoDiaUseCase {

    private final AgendaJdbc agenda;
    private final ProfissionalApi equipe;
    private final ClienteApi clientes;
    private final EstabelecimentoApi estabelecimentos;

    public AgendaDoDiaUseCase(AgendaJdbc agenda, ProfissionalApi equipe, ClienteApi clientes,
                              EstabelecimentoApi estabelecimentos) {
        this.agenda = agenda;
        this.equipe = equipe;
        this.clientes = clientes;
        this.estabelecimentos = estabelecimentos;
    }

    /**
     * @param jornadas o que cada profissional atende no dia, já sem os bloqueios de jornada. É o
     *                 que a grade usa para escurecer o que está fora do expediente — sem isso,
     *                 uma coluna vazia às 7h parece disponível
     */
    public record AgendaDoDia(
            LocalDate data,
            String fuso,
            List<ProfissionalResumo> profissionais,
            Map<UUID, List<Janela>> jornadas,
            List<AgendamentoResumo> agendamentos,
            List<BloqueioDaGrade> bloqueios,
            Map<UUID, String> nomesDeClientes) {
    }

    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_LER_TODAS + "')"
            + " or hasAuthority('" + Permissao.AGENDA_LER_PROPRIA + "')")
    @Transactional(readOnly = true)
    public AgendaDoDia executar(LocalDate data) {
        UUID tenant = TenantContext.obrigatorio();
        ZoneId fuso = estabelecimentos.fusoDe(tenant);
        Janela dia = new Janela(data.atStartOfDay(fuso).toInstant(),
                data.plusDays(1).atStartOfDay(fuso).toInstant());

        List<ProfissionalResumo> profissionais = equipe.ativos();
        List<UUID> ids = profissionais.stream().map(ProfissionalResumo::id).toList();

        var jornadas = new java.util.LinkedHashMap<UUID, List<Janela>>();
        equipe.intervalosEm(ids, data).forEach((profissional, intervalos) ->
                jornadas.put(profissional, intervalos.stream()
                        .map(intervalo -> new Janela(
                                data.atTime(intervalo.inicio()).atZone(fuso).toInstant(),
                                data.atTime(intervalo.fim()).atZone(fuso).toInstant()))
                        .toList()));

        List<AgendamentoResumo> agendamentos = agenda.doDia(dia);

        // Uma consulta para todos os nomes. Buscar um a um seria uma ida ao banco por tarja.
        var clienteIds = new LinkedHashSet<UUID>();
        agendamentos.forEach(a -> clienteIds.add(a.clienteId()));

        return new AgendaDoDia(data, fuso.getId(), profissionais, jornadas, agendamentos,
                agenda.bloqueiosEm(dia), clientes.nomesDe(clienteIds));
    }
}
