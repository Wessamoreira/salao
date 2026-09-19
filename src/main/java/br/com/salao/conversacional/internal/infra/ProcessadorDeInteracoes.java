package br.com.salao.conversacional.internal.infra;

import br.com.salao.conversacional.internal.application.ConcluirExecucao;
import br.com.salao.conversacional.internal.application.ConfirmarPeloCodigo;
import br.com.salao.conversacional.internal.application.DecidirAceite;
import br.com.salao.conversacional.internal.application.ExecutorDeFerramenta;
import br.com.salao.conversacional.internal.application.FalhaPermanente;
import br.com.salao.conversacional.internal.application.ModeloDeLinguagem;
import br.com.salao.conversacional.internal.application.Orcamento;
import br.com.salao.conversacional.internal.application.Orquestrador;
import br.com.salao.conversacional.internal.application.PrecoDoModelo;
import br.com.salao.conversacional.internal.application.ProporIntencao;
import br.com.salao.conversacional.internal.application.TransacaoSomenteLeitura;
import br.com.salao.conversacional.internal.domain.CodigoDeConfirmacao;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Identificacao;
import br.com.salao.iam.api.UsuarioEfetivo;
import br.com.salao.iam.api.UsuarioEfetivoApi;
import br.com.salao.shared.agente.FerramentaDeEscrita;
import br.com.salao.shared.agente.FerramentaDeLeitura;
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
import tools.jackson.databind.JsonNode;

/**
 * RT-CNV-004 / RT-CNV-005 / RT-CNV-006 — quem tira a conversa da fila e a conduz.
 *
 * <p>Na ordem, e cada passo pode encerrar sem gastar nada:
 *
 * <ol>
 *   <li><b>identidade</b> (RT-CNV-002): quem não é da equipe, ou é e o canal não atende, é RECUSADO sem
 *       chamar o modelo — docs/05, "o bot responde como desconhecido e não executa nada";
 *   <li><b>limite por telefone</b>: um número em laço não consome o dia do salão;
 *   <li><b>confirmação</b> (RT-CNV-005): "SIM 4821" é reconhecido aqui e decidido sem o modelo — nem
 *       custo, nem conversa, e nenhum caminho do modelo até a gravação;
 *   <li><b>teto de custo</b>: reservado antes de cada chamada, dentro do orquestrador;
 *   <li><b>conversa</b>, fora de transação, com as ferramentas executadas em nome do usuário efetivo e
 *       dentro de uma transação somente leitura.
 * </ol>
 *
 * <p>O toque num botão de aceite (RT-CNV-006) não é conversa: tem caminho próprio, sem identidade de
 * usuário — quem aceita é quem vai atender, e ela pode nem ter conta —, sem modelo e sem custo.
 *
 * <p>A reserva da linha é a mesma do processador de áudio (RN-CNV-008): um comando atômico, que vence.
 *
 * <p><strong>Nada é enviado daqui.</strong> Encerrar a conversa enfileira a resposta na saída para a
 * equipe, no mesmo commit ({@link ConcluirExecucao}); quem manda é o {@code DespachanteParaEquipe}.
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
            returning e.id, e.estabelecimento_id, e.de_telefone, e.entrada, e.tipo, e.tentativas
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
    private final ProporIntencao propor;
    private final ConfirmarPeloCodigo confirmar;
    private final DecidirAceite decidirAceite;
    private final ConcluirExecucao concluir;
    private final TransacaoSomenteLeitura somenteLeitura;
    private final Relogio relogio;
    private final MeterRegistry metricas;

    public ProcessadorDeInteracoes(ConexaoDeManutencao manutencao, ExecucoesJdbc execucoes,
                                   UsuarioEfetivoApi usuarios, EstabelecimentoApi estabelecimentos,
                                   Orquestrador orquestrador, ModeloDeLinguagem modelo,
                                   PrecoDoModelo preco, ConsumoDeIaJdbc consumo,
                                   LimitePorTelefone limite, BigDecimal tetoDiario,
                                   ProporIntencao propor, ConfirmarPeloCodigo confirmar,
                                   DecidirAceite decidirAceite, ConcluirExecucao concluir,
                                   TransacaoSomenteLeitura somenteLeitura, Relogio relogio,
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
        this.propor = propor;
        this.confirmar = confirmar;
        this.decidirAceite = decidirAceite;
        this.concluir = concluir;
        this.somenteLeitura = somenteLeitura;
        this.relogio = relogio;
        this.metricas = metricas;
    }

    private record Reservada(UUID id, UUID estabelecimentoId, String telefone, String entrada,
                             ExecucoesJdbc.Tipo tipo, int tentativas) {
    }

    private record Simulada(UUID usuarioId, FerramentaDeEscrita.Simulacao simulacao) {
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
        // Sem modelo ou sem preço, nada é reservado: as conversas esperam sem gastar tentativa. Nem
        // as confirmações andam — mas sem modelo não nasce intenção, e a que ficou vence sozinha.
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
                        ExecucoesJdbc.Tipo.valueOf(rs.getString("tipo")),
                        rs.getInt("tentativas")))
                .list();
    }

    private int tentar(Reservada r) {
        try {
            TenantContext.executar(r.estabelecimentoId(), () -> conduzir(r));
            return 1;
        } catch (FalhaPermanente e) {
            registrar(r, () -> concluir.falhou(r.id(), recortar(e.getMessage()), relogio.agora()));
            contar("falhou");
            log.warn("Conversa {} falhou de vez: {}", r.id(), e.getMessage());
        } catch (RuntimeException e) {
            boolean desistir = EsperaEntreTentativas.desistir(r.tentativas());
            Instant proxima = relogio.agora().plus(EsperaEntreTentativas.apos(r.tentativas()));
            String erro = recortar(e.getMessage());
            registrar(r, () -> {
                if (desistir) {
                    concluir.falhou(r.id(), erro, relogio.agora());
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
        if (r.tipo() == ExecucoesJdbc.Tipo.BOTAO) {
            conduzirBotao(r);
            return;
        }
        UUID salao = r.estabelecimentoId();
        Instant agora = relogio.agora();

        // Uma consulta só. Duas — uma para decidir, outra para ler — abririam uma janela: desativado
        // entre elas, a segunda viria NaoIdentificado e o cast estouraria.
        var identificacao = usuarios.identificarPorTelefone(r.telefone());
        if (identificacao instanceof Identificacao.NaoIdentificado(var motivo)) {
            concluir.recusada(r.id(), motivo.name(), null, null, agora);
            contar("recusada_identidade");
            return;
        }
        UsuarioEfetivo usuario = ((Identificacao.Identificado) identificacao).usuario();

        if (!limite.consumir(salao, r.telefone())) {
            concluir.recusada(r.id(), "LIMITE_POR_TELEFONE", usuario.usuarioId(), null, agora);
            contar("recusada_limite");
            return;
        }

        // RT-CNV-005 — a confirmação é reconhecida, nunca interpretada: esta mensagem não chega ao
        // modelo, e a resposta a ela é do servidor.
        var codigo = CodigoDeConfirmacao.codigoEm(r.entrada());
        if (codigo.isPresent()) {
            var decisao = confirmar.executar(r.telefone(), usuario.usuarioId(), r.id(),
                    codigo.get());
            concluir.semModelo(r.id(), r.telefone(), usuario.usuarioId(), decisao.resposta(),
                    relogio.agora());
            contar(desfechoDaConfirmacao(decisao.desfecho()));
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

        // Cada ferramenta roda EM NOME DE quem escreveu — resolvido de novo na hora (RN-IAM-019) — e
        // numa transação somente leitura: nem a leitura nem a simulação conseguem gravar.
        ExecutorDeFerramenta executor = new ExecutorDeFerramenta() {
            @Override
            public JsonNode executar(FerramentaDeLeitura ferramenta, JsonNode argumentos) {
                return usuarios.emNomeDe(r.telefone(),
                        u -> somenteLeitura.executar(() -> ferramenta.executar(argumentos)));
            }

            @Override
            public String propor(FerramentaDeEscrita ferramenta, JsonNode argumentos) {
                Simulada simulada = usuarios.emNomeDe(r.telefone(), u -> new Simulada(
                        u.usuarioId(),
                        somenteLeitura.executar(() -> ferramenta.simular(argumentos))));
                propor.executar(new ProporIntencao.Proposta(r.id(), r.telefone(),
                        simulada.usuarioId(), ferramenta.nome(), argumentos,
                        simulada.simulacao()));
                var aceite = simulada.simulacao().aceite();
                // O modelo precisa saber que o SIM não basta, ou diria "marcado" a quem ainda espera.
                return aceite == null ? simulada.simulacao().resumo()
                        : simulada.simulacao().resumo() + " — " + aceite.motivo()
                                + ": depois da confirmação, " + aceite.nome() + " precisa aceitar";
            }
        };

        var resultado = orquestrador.conduzir(new Orquestrador.Conversa(usuario, nomeDoSalao,
                agoraNoSalao, r.entrada(), historico), orcamento, executor);

        if (resultado.desfecho() == Orquestrador.Desfecho.TETO_ATINGIDO) {
            concluir.recusada(r.id(), "TETO_DE_CUSTO", usuario.usuarioId(), resultado,
                    relogio.agora());
            contar("recusada_teto");
            log.warn("Salão {} atingiu o teto diário de custo do agente", salao);
            return;
        }
        concluir.conduzida(r.id(), r.telefone(), usuario.usuarioId(), resultado, relogio.agora());
        contar("concluida");
    }

    /**
     * RT-CNV-006 — o toque num botão de aceite.
     *
     * <p>Sem identidade de usuário: o que vale é o botão, com o token, vindo do número para onde o
     * pedido foi mandado (RN-CNV-019). O limite por telefone continua valendo — é ele que torna tocar
     * em sequência inútil.
     */
    private void conduzirBotao(Reservada r) {
        if (!limite.consumir(r.estabelecimentoId(), r.telefone())) {
            concluir.recusada(r.id(), "LIMITE_POR_TELEFONE", null, null, relogio.agora());
            contar("recusada_limite");
            return;
        }
        var decisao = decidirAceite.executar(r.telefone(), r.id(), r.entrada());
        concluir.semModelo(r.id(), r.telefone(), decisao.usuarioEfetivoId(), decisao.resposta(),
                relogio.agora());
        contar("aceite_" + decisao.desfecho().name().toLowerCase(Locale.ROOT));
    }

    private static String desfechoDaConfirmacao(ConfirmarPeloCodigo.Desfecho desfecho) {
        return switch (desfecho) {
            case CONFIRMADA -> "confirmada";
            case AGUARDANDO_ACEITE -> "confirmacao_aguardando_aceite";
            case RECUSADA -> "confirmacao_recusada";
            case VENCIDA -> "confirmacao_vencida";
            case JA_DECIDIDA -> "confirmacao_ja_decidida";
            case SEM_PEDIDO -> "confirmacao_sem_pedido";
        };
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
