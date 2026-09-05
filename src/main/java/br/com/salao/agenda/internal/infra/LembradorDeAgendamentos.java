package br.com.salao.agenda.internal.infra;

import br.com.salao.cliente.api.ClienteApi;
import br.com.salao.cliente.api.Finalidade;
import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.notificacao.api.CanalDeEnvio;
import br.com.salao.notificacao.api.NotificacaoApi;
import br.com.salao.shared.manutencao.ConexaoDeManutencao;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * RT-NOT-002 — "seu horário é amanhã às 14h".
 *
 * <p>É a rotina de maior retorno do sistema inteiro, e não precisa de IA nenhuma: lembrete reduz
 * no-show, e cada no-show evitado é um horário que o salão vende.
 *
 * <h2>Larga de propósito</h2>
 *
 * <p>A janela vai de 12 a 26 horas à frente, e não de 24 a 25. Uma faixa estreita deixaria sem
 * lembrete todas as clientes daquela hora se a execução falhasse — reinício, deploy, uma exceção
 * qualquer —, e o silêncio não apareceria em lugar nenhum.
 *
 * <p>Com a faixa larga, a execução seguinte alcança as mesmas clientes. A duplicata é impedida por
 * RN-NOT-003 — índice único sobre a referência —, não por acertar o horário: precisão de
 * agendamento de job é a garantia mais frágil que existe.
 *
 * <h2>Quem acabou de marcar não é lembrada</h2>
 *
 * <p>A cliente que combinou o horário no balcão há dez minutos não precisa de uma mensagem dizendo
 * o que ela acabou de combinar. Duas horas de idade mínima resolvem, e a janela larga garante que
 * ela seja alcançada na execução seguinte.
 *
 * <h2>Um estabelecimento com problema não cala os outros</h2>
 *
 * <p>Mesmo padrão do reenviador de eventos: tenant a tenant, com o erro contido. Um salão com dado
 * inconsistente não pode fazer o salão vizinho perder os lembretes do dia.
 */
public class LembradorDeAgendamentos {

    private static final Logger log = LoggerFactory.getLogger(LembradorDeAgendamentos.class);

    private static final String ESTABELECIMENTOS = "select id from estabelecimento where ativo";

    /** A faixa de "amanhã". Larga porque perder uma execução não pode custar os lembretes. */
    private static final Duration DAQUI_A = Duration.ofHours(12);
    private static final Duration ATE = Duration.ofHours(26);

    /** Quem marcou há menos disto acabou de falar com o salão. */
    private static final Duration IDADE_MINIMA = Duration.ofHours(2);

    private static final String MODELO = "lembrete-de-agendamento";

    private final ConexaoDeManutencao manutencao;
    private final AgendaJdbc agenda;
    private final NotificacaoApi notificacao;
    private final ClienteApi clientes;
    private final ProfissionalApi equipe;
    private final EstabelecimentoApi estabelecimentos;
    private final Relogio relogio;

    public LembradorDeAgendamentos(ConexaoDeManutencao manutencao, AgendaJdbc agenda,
                                   NotificacaoApi notificacao, ClienteApi clientes,
                                   ProfissionalApi equipe, EstabelecimentoApi estabelecimentos,
                                   Relogio relogio) {
        this.manutencao = manutencao;
        this.agenda = agenda;
        this.notificacao = notificacao;
        this.clientes = clientes;
        this.equipe = equipe;
        this.estabelecimentos = estabelecimentos;
        this.relogio = relogio;
    }

    /** De hora em hora. A janela é larga; a frequência não precisa ser. */
    @Scheduled(cron = "0 5 * * * *")
    public void lembrar() {
        int enfileirados = executar();
        if (enfileirados > 0) {
            log.info("Lembretes: {} mensagem(ns) enfileirada(s)", enfileirados);
        }
    }

    /** Exposto para teste e para acionamento manual pelo runbook. */
    public int executar() {
        Instant agora = relogio.agora();
        int total = 0;
        for (UUID tenant : tenants()) {
            try {
                total += TenantContext.obter(tenant, () -> lembrarDe(tenant, agora));
            } catch (RuntimeException e) {
                // Um salão com dado inconsistente não pode fazer o vizinho perder os lembretes.
                log.error("Falha ao gerar lembretes do estabelecimento {}", tenant, e);
            }
        }
        return total;
    }

    private int lembrarDe(UUID tenant, Instant agora) {
        var configuracao = estabelecimentos.configuracao(tenant).orElseThrow();
        ZoneId fuso = configuracao.fuso();

        List<AgendaJdbc.AgendamentoALembrar> candidatos = agenda.aLembrar(
                agora.plus(DAQUI_A), agora.plus(ATE), agora.minus(IDADE_MINIMA));

        int enfileirados = 0;
        for (var candidato : candidatos) {
            try {
                notificacao.enfileirar(candidato.clienteId(),
                        Finalidade.LEMBRETE_DE_AGENDAMENTO, CanalDeEnvio.WHATSAPP, MODELO,
                        parametros(candidato, configuracao.nome(), fuso),
                        // A referência é o agendamento: pedir de novo não manda de novo.
                        candidato.id());
                enfileirados++;
            } catch (RuntimeException e) {
                // Cliente sem telefone é o caso comum, e não é erro do sistema: é cadastro
                // incompleto. Registrar em debug evita encher o log com o que o salão já sabe.
                log.debug("Sem lembrete para o agendamento {}: {}", candidato.id(),
                        e.getMessage());
            }
        }
        return enfileirados;
    }

    private Map<String, String> parametros(AgendaJdbc.AgendamentoALembrar candidato,
                                           String salao, ZoneId fuso) {
        String cliente = clientes.porId(candidato.clienteId())
                .map(c -> primeiroNome(c.nome()))
                .orElse("");
        String profissional = equipe.porId(candidato.profissionalId())
                .map(p -> p.comoChamar())
                .orElse("");
        return Map.of(
                "nome", cliente,
                "salao", salao,
                "quando", quando(candidato.inicio(), fuso),
                "profissional", profissional);
    }

    /**
     * "amanhã às 14h" — e não a data por extenso.
     *
     * <p>A mensagem chega no dia anterior; "amanhã" é como a pessoa pensa no horário. Escrever
     * "18/09 às 14:00" obriga quem lê a converter, e a conversão é onde ela erra o dia.
     */
    private String quando(Instant inicio, ZoneId fuso) {
        var quando = inicio.atZone(fuso);
        var hoje = relogio.agora().atZone(fuso).toLocalDate();
        String hora = quando.format(DateTimeFormatter.ofPattern("HH'h'mm", new Locale("pt", "BR")))
                .replace("h00", "h");

        long dias = java.time.temporal.ChronoUnit.DAYS.between(hoje, quando.toLocalDate());
        if (dias == 0) {
            return "hoje às " + hora;
        }
        if (dias == 1) {
            return "amanhã às " + hora;
        }
        return quando.format(DateTimeFormatter.ofPattern("dd/MM", new Locale("pt", "BR")))
                + " às " + hora;
    }

    /** "Marilda Souza Andrade" vira "Marilda": mensagem de salão fala com a pessoa, não com o RG. */
    private String primeiroNome(String nome) {
        if (nome == null || nome.isBlank()) {
            return "";
        }
        return nome.strip().split("\\s+")[0];
    }

    private List<UUID> tenants() {
        return manutencao.jdbc().sql(ESTABELECIMENTOS).query(UUID.class).list();
    }
}
