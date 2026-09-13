package br.com.salao.conversacional.internal.infra;

import br.com.salao.conversacional.internal.application.ExecutorDeFerramenta;
import br.com.salao.conversacional.internal.application.FalhaPermanente;
import br.com.salao.conversacional.internal.application.ModeloDeLinguagem;
import br.com.salao.conversacional.internal.application.Orcamento;
import br.com.salao.conversacional.internal.application.Orquestrador;
import br.com.salao.conversacional.internal.application.PrecoDoModelo;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Identificacao;
import br.com.salao.iam.api.UsuarioEfetivo;
import br.com.salao.iam.api.UsuarioEfetivoApi;
import br.com.salao.shared.manutencao.ConexaoDeManutencao;
import br.com.salao.shared.tempo.EsperaEntreTentativas;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * RT-CNV-004 — quem tira a conversa da fila e a conduz.
 *
 * <p>Na ordem, e cada passo pode encerrar sem gastar nada:
 *
 * <ol>
 *   <li><b>identidade</b> (RT-CNV-002): quem não é da equipe, ou é e o canal não atende, é RECUSADO sem
 *       chamar o modelo — docs/05, "o bot responde como desconhecido e não executa nada";
 *   <li><b>limite por telefone</b>: um número em laço não consome o dia do salão;
 *   <li><b>teto de custo</b>: reservado antes de cada chamada, dentro do orquestrador;
 *   <li><b>conversa</b>, fora de transação, com as ferramentas executadas em nome do usuário efetivo.
 * </ol>
 *
 * <p>A reserva da linha é a mesma do processador de áudio (RN-CNV-008): um comando atômico, que vence.
 *
 * <p><strong>Nada é enviado a ninguém daqui.</strong> A resposta fica registrada; entregá-la pelo
 * WhatsApp é uma pendência explícita da rotina — não existe hoje um mensageiro real, e o sistema não
 * sabe falar com a própria equipe pela fila de notificação.
 */
public class ProcessadorDeInteracoes {

    private static final Logger log = LoggerFactory.getLogger(ProcessadorDeInteracoes.class);

    private static final int LOTE = 10;
    private static final Duration RESERVA = Duration.ofMinutes(10);
    private static final Duration JANELA_DO_HISTORICO = Duration.ofHours(24);
    private static final int TROCAS_NO_HISTORICO = 6;
    private static final DateTimeFormatter FORMATO_DO_AGORA =
            DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy HH:mm", Locale.forLanguageTag("pt-BR"));

    private static final String RESERVAR = """
            update execucao_do_agente e
               set reservada_ate = :reserva, tentativas = e.tentativas + 1
             where e.id in (
                   select id from execucao_do_agente
                    where status = 'PENDENTE'
                      and proxima_tentativa_em <= :agora
                      and (reservada_ate is null or reservada_ate < :agora)
                    order by proxima_tentativa_em
                    limit :lote
                      for update skip locked)
            returning e.id, e.estabelecimento_id, e.de_telefone, e.entrada, e.tentativas
            """;

    private final ConexaoDeManutencao manutencao;
    private final ExecucoesJdbc execucoes;
    private final UsuarioEfetivoApi usuarios;
    private final EstabelecimentoApi estabelecimentos;
    private final Orquestrador orquestrador;
    private final ModeloDeLinguagem modelo;
    private final PrecoDoModelo preco;
    private final ConsumoDeIaJdbc consumo;
    private final LimitePorTelefone limite;
    private final BigDecimal tetoDiario;
    private final Relogio relogio;
    private final MeterRegistry metricas;

    public ProcessadorDeInteracoes(ConexaoDeManutencao manutencao, ExecucoesJdbc execucoes,
                                   UsuarioEfetivoApi usuarios, EstabelecimentoApi estabelecimentos,
                                   Orquestrador orquestrador, ModeloDeLinguagem modelo,
                                   PrecoDoModelo preco, ConsumoDeIaJdbc consumo,
                                   LimitePorTelefone limite, BigDecimal tetoDiario, Relogio relogio,
                                   MeterRegistry metricas) {
        this.manutencao = manutencao;
        this.execucoes = execucoes;
        this.usuarios = usuarios;
        this.estabelecimentos = estabelecimentos;
        this.orquestrador = orquestrador;
        this.modelo = modelo;
        this.preco = preco;
        this.consumo = consumo;
        this.limite = limite;
        this.tetoDiario = tetoDiario;
        this.relogio = relogio;
        this.metricas = metricas;
    }

    private record Reservada(UUID id, UUID estabelecimentoId, String telefone, String entrada,
                             int tentativas) {
    }

    @Scheduled(fixedDelayString = "${app.agente.intervalo:PT15S}",
               initialDelayString = "${app.agente.atraso-inicial:PT25S}")
    public void processar() {
        int feitas = executar();
        if (feitas > 0) {
            log.info("Agente: {} conversa(s) encerrada(s)", feitas);
        }
    }

    /** Exposto para teste e para acionamento manual pelo runbook. */
    public int executar() {
        // Sem modelo ou sem preço, nada é reservado: as conversas esperam sem gastar tentativa.
        if (!modelo.configurado() || !preco.configurado()) {
            return 0;
        }
        int feitas = 0;
        for (Reservada r : reservar()) {
            feitas += tentar(r);
        }
        return feitas;
    }

    private List<Reservada> reservar() {
        Instant agora = relogio.agora();
        return manutencao.jdbc().sql(RESERVAR)
                .param("reserva", OffsetDateTime.ofInstant(agora.plus(RESERVA), ZoneOffset.UTC))
                .param("agora", OffsetDateTime.ofInstant(agora, ZoneOffset.UTC))
                .param("lote", LOTE)
                .query((rs, n) -> new Reservada(
                        rs.getObject("id", UUID.class),
                        rs.getObject("estabelecimento_id", UUID.class),
                        rs.getString("de_telefone"),
                        rs.getString("entrada"),
                        rs.getInt("tentativas")))
                .list();
    }

    private int tentar(Reservada r) {
        try {
            TenantContext.executar(r.estabelecimentoId(), () -> conduzir(r));
            return 1;
        } catch (FalhaPermanente e) {
            registrar(r, () -> execucoes.falhar(r.id(), recortar(e.getMessage())));
            contar("falhou");
            log.warn("Conversa {} falhou de vez: {}", r.id(), e.getMessage());
        } catch (RuntimeException e) {
            boolean desistir = EsperaEntreTentativas.desistir(r.tentativas());
            Instant proxima = relogio.agora().plus(EsperaEntreTentativas.apos(r.tentativas()));
            String erro = recortar(e.getMessage());
            registrar(r, () -> {
                if (desistir) {
                    execucoes.falhar(r.id(), erro);
                } else {
                    execucoes.adiar(r.id(), erro, proxima);
                }
            });
            contar(desistir ? "falhou" : "adiada");
            log.warn("Conversa {} {} (tentativa {}): {}", r.id(),
                    desistir ? "desistiu" : "adiada", r.tentativas(), e.getMessage());
        }
        return 0;
    }

    private void conduzir(Reservada r) {
        UUID salao = r.estabelecimentoId();
        Instant agora = relogio.agora();

        // Uma consulta só. Duas — uma para decidir, outra para ler — abririam uma janela: desativado
        // entre elas, a segunda viria NaoIdentificado e o cast estouraria.
        var identificacao = usuarios.identificarPorTelefone(r.telefone());
        if (identificacao instanceof Identificacao.NaoIdentificado(var motivo)) {
            execucoes.recusar(r.id(), motivo.name(), null, null, agora);
            contar("recusada_identidade");
            return;
        }
        UsuarioEfetivo usuario = ((Identificacao.Identificado) identificacao).usuario();

        if (!limite.consumir(salao, r.telefone())) {
            execucoes.recusar(r.id(), "LIMITE_POR_TELEFONE", usuario.usuarioId(), null, agora);
            contar("recusada_limite");
            return;
        }

        ZoneId fuso = estabelecimentos.fusoDe(salao);
        LocalDate dia = relogio.hojeEm(fuso);
        String nomeDoSalao = estabelecimentos.configuracao(salao).map(c -> c.nome()).orElse("");
        String agoraNoSalao = agora.atZone(fuso).format(FORMATO_DO_AGORA);
        var historico = execucoes.historico(r.telefone(), agora.minus(JANELA_DO_HISTORICO),
                TROCAS_NO_HISTORICO);

        Orcamento orcamento = new Orcamento() {
            @Override
            public boolean reservar(BigDecimal piorCaso) {
                return consumo.reservar(salao, dia, piorCaso, tetoDiario);
            }

            @Override
            public void liquidar(BigDecimal reservado, BigDecimal real, int entrada, int saida) {
                consumo.liquidar(salao, dia, reservado, real, entrada, saida);
            }
        };
        // Cada ferramenta roda EM NOME DE quem escreveu — resolvido de novo na hora (RN-IAM-019).
        ExecutorDeFerramenta executor = (ferramenta, argumentos) ->
                usuarios.emNomeDe(r.telefone(), u -> ferramenta.executar(argumentos));

        var resultado = orquestrador.conduzir(new Orquestrador.Conversa(usuario, nomeDoSalao,
                agoraNoSalao, r.entrada(), historico), orcamento, executor);

        if (resultado.desfecho() == Orquestrador.Desfecho.TETO_ATINGIDO) {
            execucoes.recusar(r.id(), "TETO_DE_CUSTO", usuario.usuarioId(), resultado, relogio.agora());
            contar("recusada_teto");
            log.warn("Salão {} atingiu o teto diário de custo do agente", salao);
            return;
        }
        execucoes.concluir(r.id(), usuario.usuarioId(), resultado, relogio.agora());
        contar("concluida");
    }

    private void registrar(Reservada r, Runnable gravacao) {
        try {
            TenantContext.executar(r.estabelecimentoId(), gravacao);
        } catch (RuntimeException e) {
            log.error("Não foi possível registrar o desfecho da conversa {}: {}", r.id(),
                    e.getMessage());
        }
    }

    private void contar(String desfecho) {
        metricas.counter("conversacional.agente", "desfecho", desfecho).increment();
    }

    private static String recortar(String erro) {
        if (erro == null) {
            return null;
        }
        return erro.substring(0, Math.min(erro.length(), 500));
    }
}
