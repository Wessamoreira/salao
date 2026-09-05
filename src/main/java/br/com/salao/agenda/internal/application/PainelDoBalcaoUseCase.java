package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.AgendamentoResumo;
import br.com.salao.agenda.api.Janela;
import br.com.salao.agenda.api.StatusAgendamento;
import br.com.salao.agenda.internal.infra.AgendaJdbc;
import br.com.salao.cliente.api.ClienteApi;
import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.equipe.api.ProfissionalResumo;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-AGD-012 — o que a tela do balcão mostra.
 *
 * <h2>O que fica de fora, e é a maior parte</h2>
 *
 * <p>O painel fica pendurado a dois ou três metros de quem olha, e é visto por quem está esperando
 * — inclusive por clientes que não são as da lista. Então:
 *
 * <ul>
 *   <li><strong>Nenhum valor.</strong> Quanto custa a coloração da Marilda não é assunto de quem
 *       está na cadeira ao lado.</li>
 *   <li><strong>Nenhum detalhe.</strong> Serviço, observação e telefone ficam de fora: a três
 *       metros ninguém lê, e o que ninguém lê só serve para vazar.</li>
 *   <li><strong>Só o que ainda vai acontecer.</strong> Quem já foi atendida saiu; quem faltou não
 *       precisa ver o próprio nome na parede.</li>
 * </ul>
 *
 * <p>Sobram quatro colunas — horário, cliente, profissional e situação — que é o que o canvas
 * desenhou e o que se lê de longe.
 */
public class PainelDoBalcaoUseCase {

    private final AgendaJdbc agenda;
    private final ProfissionalApi equipe;
    private final ClienteApi clientes;
    private final EstabelecimentoApi estabelecimentos;
    private final Relogio relogio;

    public PainelDoBalcaoUseCase(AgendaJdbc agenda, ProfissionalApi equipe, ClienteApi clientes,
                                 EstabelecimentoApi estabelecimentos, Relogio relogio) {
        this.agenda = agenda;
        this.equipe = equipe;
        this.clientes = clientes;
        this.estabelecimentos = estabelecimentos;
        this.relogio = relogio;
    }

    /**
     * Uma linha do painel.
     *
     * @param cliente primeiro nome e a inicial do resto — "Marilda S.". O painel é visto por quem
     *                está esperando, e o nome inteiro de alguém na parede é dado pessoal exposto a
     *                estranhos sem que ela tenha escolhido isso
     */
    public record LinhaDoPainel(
            Instant inicio,
            String cliente,
            String profissional,
            StatusAgendamento situacao) {
    }

    public record Painel(
            String estabelecimento,
            String fuso,
            Instant atualizadoEm,
            List<LinhaDoPainel> linhas) {

        /** O número do rodapé: quantos ainda faltam hoje. */
        public int restantes() {
            return linhas.size();
        }
    }

    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_LER_TODAS + "')"
            + " or hasAuthority('" + Permissao.AGENDA_LER_PROPRIA + "')")
    @Transactional(readOnly = true)
    public Painel executar() {
        UUID tenant = TenantContext.obrigatorio();
        var configuracao = estabelecimentos.configuracao(tenant).orElseThrow();
        ZoneId fuso = configuracao.fuso();
        Instant agora = relogio.agora();
        LocalDate hoje = relogio.hojeEm(fuso);

        Janela dia = new Janela(hoje.atStartOfDay(fuso).toInstant(),
                hoje.plusDays(1).atStartOfDay(fuso).toInstant());

        List<AgendamentoResumo> doDia = agenda.doDia(dia).stream()
                // Em atendimento continua, mesmo tendo começado: é a linha que a cliente na
                // cadeira procura para saber que é a vez dela.
                .filter(a -> a.status() == StatusAgendamento.EM_ATENDIMENTO
                        || (a.status().ocupaAAgenda() && a.fim().isAfter(agora)))
                .toList();

        Map<UUID, String> nomes = clientes.nomesDe(
                doDia.stream().map(AgendamentoResumo::clienteId)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        Map<UUID, String> profissionais = equipe.ativos().stream()
                .collect(java.util.stream.Collectors.toMap(ProfissionalResumo::id,
                        ProfissionalResumo::comoChamar));

        List<LinhaDoPainel> linhas = doDia.stream()
                .map(a -> new LinhaDoPainel(a.inicio(),
                        encurtar(nomes.get(a.clienteId())),
                        profissionais.getOrDefault(a.profissionalId(), "—"),
                        a.status()))
                .toList();

        return new Painel(configuracao.nome(), fuso.getId(), agora, linhas);
    }

    /**
     * "Marilda Souza Andrade" vira "Marilda S.".
     *
     * <p>O painel é visto por quem está esperando, e o nome inteiro de alguém pendurado na parede é
     * dado pessoal exposto a estranhos sem que ela tenha escolhido isso. O primeiro nome basta para
     * ela se reconhecer, e a inicial desempata as duas Marias.
     */
    private String encurtar(String nome) {
        if (nome == null || nome.isBlank()) {
            return "—";
        }
        String[] partes = nome.strip().split("\\s+");
        if (partes.length == 1) {
            return partes[0];
        }
        return partes[0] + " " + partes[1].charAt(0) + ".";
    }
}
