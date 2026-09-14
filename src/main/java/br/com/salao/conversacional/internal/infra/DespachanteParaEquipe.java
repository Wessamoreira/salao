package br.com.salao.conversacional.internal.infra;

import br.com.salao.conversacional.internal.application.EnviadorDoWhatsApp;
import br.com.salao.conversacional.internal.application.FalhaPermanente;
import br.com.salao.shared.manutencao.ConexaoDeManutencao;
import br.com.salao.shared.tempo.EsperaEntreTentativas;
import br.com.salao.shared.tempo.Relogio;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * RT-CNV-006 — quem tira da fila da equipe e manda.
 *
 * <h2>Reserva atômica, e não {@code select ... for update skip locked} solto</h2>
 *
 * <p>O despachante da notificação reserva com um {@code SELECT ... FOR UPDATE SKIP LOCKED} em
 * autocommit — e o lock é solto no fim do próprio SELECT: duas instâncias mandam a mesma mensagem
 * (defeito registrado à parte). Aqui é o padrão de RN-CNV-008: um {@code UPDATE ... RETURNING} que
 * trava, marca a reserva e conta a tentativa no mesmo comando.
 *
 * <h2>Na ordem, por destino</h2>
 *
 * <p>A resposta do agente e o pedido de confirmação vão para a mesma pessoa, e chegar invertidos seria
 * o "SIM + código" antes do "preparei, confirme pelo código". Uma mensagem só é reservada quando não há
 * outra pendente, mais antiga, para o mesmo destino — inclusive uma esperando nova tentativa. Custa uma
 * rodada por mensagem de uma mesma conversa; a rodada é de segundos.
 *
 * <h2>Pela conexão de manutenção, fora de transação</h2>
 *
 * <p>Atende todos os salões, e o envio é rede: nada disto segura conexão do pool da aplicação.
 */
public class DespachanteParaEquipe {

    private static final Logger log = LoggerFactory.getLogger(DespachanteParaEquipe.class);

    private static final int LOTE = 20;
    private static final Duration RESERVA = Duration.ofMinutes(2);

    private static final String RESERVAR = """
            update mensagem_para_equipe m
               set reservada_ate = :reserva, tentativas = m.tentativas + 1
             where m.id in (
                   select s.id from mensagem_para_equipe s
                    where s.status = 'PENDENTE'
                      and s.proxima_tentativa_em <= :agora
                      and (s.reservada_ate is null or s.reservada_ate < :agora)
                      and not exists (
                          select 1 from mensagem_para_equipe antes
                           where antes.estabelecimento_id = s.estabelecimento_id
                             and antes.destino = s.destino
                             and antes.status = 'PENDENTE'
                             and antes.sequencia < s.sequencia)
                    order by s.sequencia
                    limit :lote
                      for update skip locked)
            returning m.id, m.estabelecimento_id, m.destino, m.motivo, m.texto,
                      m.botoes::text as botoes, m.tentativas
            """;

    private final ConexaoDeManutencao manutencao;
    private final EnviadorDoWhatsApp enviador;
    private final ObjectMapper json;
    private final Relogio relogio;
    private final MeterRegistry metricas;

    public DespachanteParaEquipe(ConexaoDeManutencao manutencao, EnviadorDoWhatsApp enviador,
                                 ObjectMapper json, Relogio relogio, MeterRegistry metricas) {
        this.manutencao = manutencao;
        this.enviador = enviador;
        this.json = json;
        this.relogio = relogio;
        this.metricas = metricas;
    }

    private record Reservada(UUID id, UUID estabelecimentoId, String destino,
                             MensagensParaEquipeJdbc.Motivo motivo, String texto,
                             List<EnviadorDoWhatsApp.Botao> botoes, int tentativas) {
    }

    @Scheduled(fixedDelayString = "${app.whatsapp.saida.intervalo:PT5S}",
               initialDelayString = "${app.whatsapp.saida.atraso-inicial:PT20S}")
    public void despachar() {
        int enviadas = executar();
        if (enviadas > 0) {
            log.info("Saída para a equipe: {} mensagem(ns) enviada(s)", enviadas);
        }
    }

    /** Exposto para teste e para acionamento manual pelo runbook. */
    public int executar() {
        // Sem provedor, nada é reservado: as mensagens esperam sem gastar tentativa.
        if (!enviador.configurado()) {
            return 0;
        }
        Instant agora = relogio.agora();
        List<Reservada> lote = manutencao.jdbc().sql(RESERVAR)
                .param("reserva", OffsetDateTime.ofInstant(agora.plus(RESERVA), ZoneOffset.UTC))
                .param("agora", OffsetDateTime.ofInstant(agora, ZoneOffset.UTC))
                .param("lote", LOTE)
                .query((rs, n) -> new Reservada(
                        rs.getObject("id", UUID.class),
                        rs.getObject("estabelecimento_id", UUID.class),
                        rs.getString("destino"),
                        MensagensParaEquipeJdbc.Motivo.valueOf(rs.getString("motivo")),
                        rs.getString("texto"),
                        botoes(rs.getString("botoes")),
                        rs.getInt("tentativas")))
                .list();
        int enviadas = 0;
        for (Reservada mensagem : lote) {
            if (tentar(mensagem)) {
                enviadas++;
            }
        }
        return enviadas;
    }

    private boolean tentar(Reservada mensagem) {
        try {
            String numero = canalAtivo(mensagem.estabelecimentoId()).orElseThrow(() ->
                    new FalhaPermanente("o canal do WhatsApp do salão está desligado"));
            String provedorId = enviador.enviar(new EnviadorDoWhatsApp.Envio(numero,
                    mensagem.destino(), mensagem.texto(), mensagem.botoes(),
                    mensagem.motivo() == MensagensParaEquipeJdbc.Motivo.PEDIDO_DE_ACEITE));
            marcarEnviada(mensagem.id(), provedorId);
            contar("enviada");
            return true;
        } catch (FalhaPermanente e) {
            falhar(mensagem.id(), e.getMessage());
            contar("falhou");
            log.warn("Mensagem para a equipe {} falhou de vez: {}", mensagem.id(), e.getMessage());
        } catch (RuntimeException e) {
            boolean desistir = EsperaEntreTentativas.desistir(mensagem.tentativas());
            if (desistir) {
                falhar(mensagem.id(), e.getMessage());
            } else {
                adiar(mensagem.id(), e.getMessage(),
                        relogio.agora().plus(EsperaEntreTentativas.apos(mensagem.tentativas())));
            }
            contar(desistir ? "falhou" : "adiada");
            log.warn("Mensagem para a equipe {} {} (tentativa {}): {}", mensagem.id(),
                    desistir ? "desistiu" : "adiada", mensagem.tentativas(), e.getMessage());
        }
        return false;
    }

    private Optional<String> canalAtivo(UUID estabelecimentoId) {
        return manutencao.jdbc().sql("""
                select phone_number_id from canal_whatsapp
                 where estabelecimento_id = :estabelecimento and ativo
                 order by criado_em
                 limit 1
                """)
                .param("estabelecimento", estabelecimentoId)
                .query(String.class)
                .optional();
    }

    private void marcarEnviada(UUID id, String provedorId) {
        manutencao.jdbc().sql("""
                update mensagem_para_equipe
                   set status = 'ENVIADA', enviada_em = :agora, provedor_id = :provedor,
                       reservada_ate = null, ultimo_erro = null
                 where id = :id and status = 'PENDENTE'
                """)
                .param("id", id)
                .param("provedor", provedorId)
                .param("agora", OffsetDateTime.ofInstant(relogio.agora(), ZoneOffset.UTC))
                .update();
    }

    private void adiar(UUID id, String erro, Instant proxima) {
        manutencao.jdbc().sql("""
                update mensagem_para_equipe
                   set reservada_ate = null, proxima_tentativa_em = :proxima, ultimo_erro = :erro
                 where id = :id and status = 'PENDENTE'
                """)
                .param("id", id)
                .param("proxima", OffsetDateTime.ofInstant(proxima, ZoneOffset.UTC))
                .param("erro", recortar(erro))
                .update();
    }

    private void falhar(UUID id, String erro) {
        manutencao.jdbc().sql("""
                update mensagem_para_equipe
                   set status = 'FALHOU', reservada_ate = null, ultimo_erro = :erro
                 where id = :id and status = 'PENDENTE'
                """)
                .param("id", id)
                .param("erro", recortar(erro))
                .update();
    }

    private List<EnviadorDoWhatsApp.Botao> botoes(String bruto) {
        if (bruto == null) {
            return List.of();
        }
        var botoes = new ArrayList<EnviadorDoWhatsApp.Botao>();
        for (JsonNode botao : json.readTree(bruto)) {
            botoes.add(new EnviadorDoWhatsApp.Botao(botao.path("id").asString(),
                    botao.path("titulo").asString()));
        }
        return botoes;
    }

    private void contar(String desfecho) {
        metricas.counter("conversacional.saida", "desfecho", desfecho).increment();
    }

    /** Truncado: erro de provedor às vezes ecoa o pedido, e o pedido tem a mensagem da pessoa. */
    private static String recortar(String erro) {
        if (erro == null) {
            return null;
        }
        return erro.substring(0, Math.min(erro.length(), 300));
    }
}
