package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.CapacidadeDeRecurso;
import br.com.salao.agenda.api.DisponibilidadeApi;
import br.com.salao.agenda.api.ErrosDaAgenda;
import br.com.salao.agenda.api.HorarioDisponivel;
import br.com.salao.agenda.api.Janela;
import br.com.salao.agenda.internal.domain.BuscaDeEncaixe;
import br.com.salao.agenda.internal.domain.SubtracaoDeJanelas;
import br.com.salao.agenda.internal.infra.AgendaJdbc;
import br.com.salao.catalogo.api.RecursoApi;
import br.com.salao.catalogo.api.Recurso;
import br.com.salao.catalogo.api.SequenciaDeBlocos;
import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.equipe.api.IntervaloDeTrabalho;
import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.equipe.api.ProfissionalResumo;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-AGD-001 — que horário posso oferecer.
 *
 * <h2>A conta</h2>
 *
 * <p>Disponibilidade é <strong>jornada − bloqueios − blocos ocupados</strong>, com a receita de
 * cada profissional encaixada no que sobrou. As duas primeiras parcelas vêm prontas de
 * {@code equipe}; a terceira é desta rotina; o encaixe é {@link BuscaDeEncaixe}.
 *
 * <h2>Um punhado fixo de consultas, não uma por profissional</h2>
 *
 * <p>Cinco, independente do tamanho da equipe: quem executa, as receitas, as jornadas do dia, as
 * ocupações e os recursos. A grade da recepção pergunta por dez pessoas de uma vez, e um laço de
 * consultas aqui viraria trinta idas ao banco a cada troca de dia na tela.
 *
 * <h2>Ler a agenda dos outros exige permissão</h2>
 *
 * <p>{@code agenda:read:all} ou {@code config:manage}. Quem só tem {@code agenda:read:own}
 * consegue consultar, mas a rotina não filtra por dono — está registrado como pendência, porque o
 * filtro certo depende do vínculo usuário↔profissional, que ainda não existe.
 */
public class ConsultarDisponibilidadeUseCase implements DisponibilidadeApi {

    private final ProfissionalApi equipe;
    private final ServicoApi catalogo;
    private final RecursoApi recursos;
    private final EstabelecimentoApi estabelecimentos;
    private final AgendaJdbc agenda;
    private final Relogio relogio;

    public ConsultarDisponibilidadeUseCase(ProfissionalApi equipe, ServicoApi catalogo,
                                           RecursoApi recursos,
                                           EstabelecimentoApi estabelecimentos,
                                           AgendaJdbc agenda, Relogio relogio) {
        this.equipe = equipe;
        this.catalogo = catalogo;
        this.recursos = recursos;
        this.estabelecimentos = estabelecimentos;
        this.agenda = agenda;
        this.relogio = relogio;
    }

    @Override
    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_LER_TODAS + "')"
            + " or hasAuthority('" + Permissao.AGENDA_LER_PROPRIA + "')"
            + " or hasAuthority('" + Permissao.CONFIG_GERENCIAR + "')")
    @Transactional(readOnly = true)
    public List<HorarioDisponivel> consultar(LocalDate data, UUID servicoId,
                                             UUID profissionalId) {
        if (catalogo.porId(servicoId).isEmpty()) {
            throw new ErroDeDominio(ErrosDaAgenda.SERVICO_NAO_ENCONTRADO,
                    "serviço " + servicoId + " não encontrado");
        }
        UUID tenant = TenantContext.obrigatorio();
        ZoneId fuso = estabelecimentos.fusoDe(tenant);
        var configuracao = estabelecimentos.configuracao(tenant).orElseThrow();
        Duration granularidade =
                Duration.ofMinutes(configuracao.granularidadeAgendaMinutos());

        List<ProfissionalResumo> candidatos = equipe.queExecutam(servicoId).stream()
                .filter(p -> profissionalId == null || p.id().equals(profissionalId))
                .toList();
        if (candidatos.isEmpty()) {
            // Lista vazia diria "sem horário", e a recepção ofereceria outro dia quando o problema
            // é que aquela pessoa não faz aquilo (RN-AGD-001).
            if (profissionalId != null) {
                throw new ErroDeDominio(ErrosDaAgenda.PROFISSIONAL_NAO_EXECUTA,
                        "o profissional " + profissionalId + " não executa este serviço");
            }
            return List.of();
        }

        List<UUID> ids = candidatos.stream().map(ProfissionalResumo::id).toList();
        Map<UUID, SequenciaDeBlocos> receitas = equipe.receitasDe(ids, servicoId);
        Map<UUID, List<IntervaloDeTrabalho>> jornadas = equipe.intervalosEm(ids, data);

        // O dia civil no fuso do salão, não em UTC (ADR-0009): a agenda de "hoje" calculada em UTC
        // mostra o dia errado depois das 21h em Brasília.
        Janela dia = new Janela(data.atStartOfDay(fuso).toInstant(),
                data.plusDays(1).atStartOfDay(fuso).toInstant());
        var ocupacoes = agenda.ocupacoesEm(dia);
        Map<UUID, CapacidadeDeRecurso> capacidades = capacidadesEm(ocupacoes);

        Instant agora = relogio.agora();
        var horarios = new ArrayList<HorarioDisponivel>();
        for (ProfissionalResumo profissional : candidatos) {
            SequenciaDeBlocos receita = receitas.get(profissional.id());
            List<IntervaloDeTrabalho> jornada = jornadas.get(profissional.id());
            if (receita == null || jornada == null) {
                continue;
            }
            List<Janela> livres = SubtracaoDeJanelas.subtrair(
                    jornada.stream().map(intervalo -> janelaDe(intervalo, data, fuso)).toList(),
                    ocupacoes.doProfissional(profissional.id()));

            for (Instant inicio : BuscaDeEncaixe.horarios(livres, receita, capacidades,
                    granularidade, dia)) {
                // RN-AGD-008 já vale na oferta, e não só na gravação: oferecer 9h quando são 15h
                // faria a recepção prometer um horário que o cadastro vai recusar.
                if (inicio.isBefore(agora)) {
                    continue;
                }
                horarios.add(new HorarioDisponivel(inicio,
                        inicio.plus(Duration.ofMinutes(receita.duracaoTotal())),
                        profissional.id(),
                        profissional.apelido() == null ? profissional.nome()
                                : profissional.apelido(),
                        receita.minutosDoProfissional()));
            }
        }
        horarios.sort(Comparator.comparing(HorarioDisponivel::inicio)
                .thenComparing(HorarioDisponivel::profissional));
        return List.copyOf(horarios);
    }

    /**
     * Quantidade e ocupação de cada recurso ativo do salão.
     *
     * <p>Todos, e não só os que a receita exige: são poucas linhas, e filtrar pediria varrer as
     * receitas antes de saber o que buscar — mais código para economizar nada.
     */
    private Map<UUID, CapacidadeDeRecurso> capacidadesEm(AgendaJdbc.Ocupacoes ocupacoes) {
        var capacidades = new LinkedHashMap<UUID, CapacidadeDeRecurso>();
        for (Recurso recurso : recursos.ativos()) {
            capacidades.put(recurso.id(), new CapacidadeDeRecurso(recurso.quantidade(),
                    ocupacoes.doRecurso(recurso.id())));
        }
        return capacidades;
    }

    private Janela janelaDe(IntervaloDeTrabalho intervalo, LocalDate data, ZoneId fuso) {
        return new Janela(data.atTime(intervalo.inicio()).atZone(fuso).toInstant(),
                data.atTime(intervalo.fim()).atZone(fuso).toInstant());
    }
}
