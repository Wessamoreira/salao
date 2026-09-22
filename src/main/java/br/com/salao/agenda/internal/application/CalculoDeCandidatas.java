package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.EsperaNaFila;
import br.com.salao.agenda.api.HorarioDisponivel;
import br.com.salao.agenda.internal.domain.CandidatasDaVaga;
import br.com.salao.agenda.internal.domain.CandidatasDaVaga.Candidata;
import br.com.salao.agenda.internal.infra.FilaDeEsperaJdbc;
import br.com.salao.agenda.internal.infra.VagasAbertasJdbc.Vaga;
import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.equipe.api.ProfissionalApi;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RT-AGD-010 — as candidatas de cada vaga, para quem lista e para quem registra a chamada.
 *
 * <p>As duas pontas precisam da mesma resposta: a tela mostra a lista, e o registro confere que a
 * espera ainda está nela e quem vem antes. Duas contas diferentes dariam, algum dia, duas respostas
 * diferentes para a mesma pergunta.
 *
 * <h2>Uma consulta de disponibilidade por serviço e dia, não por espera</h2>
 *
 * <p>Dez clientes esperando corte com a Ana na quinta são uma consulta só. A chave é
 * (profissional, dia, serviço), e ela vale para a leitura inteira: duas vagas da Ana na mesma
 * quinta também dividem a consulta.
 *
 * <p>Não é um caso de uso: não tem permissão nem transação próprias. Roda dentro da de quem chama.
 */
public class CalculoDeCandidatas {

    private final FilaDeEsperaJdbc fila;
    private final ConsultarDisponibilidadeUseCase disponibilidade;
    private final ProfissionalApi equipe;
    private final ServicoApi catalogo;

    public CalculoDeCandidatas(FilaDeEsperaJdbc fila,
                               ConsultarDisponibilidadeUseCase disponibilidade,
                               ProfissionalApi equipe, ServicoApi catalogo) {
        this.fila = fila;
        this.disponibilidade = disponibilidade;
        this.equipe = equipe;
        this.catalogo = catalogo;
    }

    /** As candidatas de cada vaga, pela ordem das vagas recebidas. */
    public Map<UUID, List<Candidata>> de(List<Vaga> vagas, Instant agora, ZoneId fuso) {
        var cache = new HashMap<Chave, List<Instant>>();
        var porVaga = new LinkedHashMap<UUID, List<Candidata>>();
        for (Vaga vaga : vagas) {
            LocalDate dia = vaga.inicio().atZone(fuso).toLocalDate();
            List<EsperaNaFila> esperas = fila.naFilaParaODia(vaga.profissionalId(), dia);

            var horariosPorServico = new HashMap<UUID, List<Instant>>();
            for (EsperaNaFila espera : esperas) {
                horariosPorServico.computeIfAbsent(espera.servicoId(), servico -> cache
                        .computeIfAbsent(new Chave(vaga.profissionalId(), dia, servico),
                                this::horarios));
            }
            porVaga.put(vaga.id(), CandidatasDaVaga.de(
                    new CandidatasDaVaga.Vaga(vaga.profissionalId(), vaga.inicio(), vaga.fim()),
                    esperas, horariosPorServico, agora, fuso));
        }
        return porVaga;
    }

    /**
     * Os inícios que a disponibilidade de RT-AGD-001 oferece — jornada, bloqueio, pausa e recurso
     * já descontados (RN-AGD-025).
     *
     * <p>Serviço que ela não faz, ou que saiu do catálogo, é lista vazia, e não erro: uma espera
     * velha não pode derrubar a tela da fila inteira.
     *
     * <p>Por isso as duas perguntas vêm <strong>antes</strong>, e não um {@code catch} depois. A
     * consulta de disponibilidade é transacional: a exceção dela marcaria a transação de quem chama
     * como perdida, e o registro de uma chamada falharia por causa de uma espera que nem era a dela.
     */
    private List<Instant> horarios(Chave chave) {
        if (catalogo.porId(chave.servicoId()).isEmpty()
                || !equipe.executa(chave.profissionalId(), chave.servicoId())) {
            return List.of();
        }
        return disponibilidade.consultar(chave.dia(), chave.servicoId(), chave.profissionalId())
                .stream()
                .map(HorarioDisponivel::inicio)
                .toList();
    }

    private record Chave(UUID profissionalId, LocalDate dia, UUID servicoId) {
    }
}
