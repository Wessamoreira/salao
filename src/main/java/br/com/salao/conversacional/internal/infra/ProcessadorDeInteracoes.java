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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * <h2>A unidade não é a mensagem: é a rajada — RT-CNV-009</h2>
 *
 * <p>Quem manda "oi", pensa, manda o resto e ainda grava um áudio fez UM pedido. O que a reserva
 * devolve, portanto, não é uma linha: é o que aquele telefone disse desde que começou a falar, numa
 * entrada só e numa volta só do modelo. Quem responde é a última mensagem; as outras ficam JUNTADA,
 * apontando para ela. Ver {@link #RESERVAR}.
 *
 * <p><strong>Nada é enviado daqui.</strong> Encerrar a conversa enfileira a resposta na saída para a
 * equipe, no mesmo commit ({@link ConcluirExecucao}); quem manda é o {@code DespachanteParaEquipe}.
 */
public class ProcessadorDeInteracoes {

    private static final Logger log = LoggerFactory.getLogger(ProcessadorDeInteracoes.class);

    private static final int LOTE = 10;
    /**
     * RT-CNV-009 — quantas mensagens cabem numa rajada.
     *
     * <p>Ninguém manda vinte frases em quinze segundos querendo dizer uma coisa só. Sem teto, porém,
     * uma enxurrada — engano, script, número em laço — viraria UM prompt gigante: o limite por
     * telefone contaria uma conversa, e o teto de custo do salão iria embora numa chamada. As que
     * sobram formam a rajada seguinte, na ordem, e são respondidas logo depois.
     */
    private static final int MAXIMO_DA_RAJADA = 20;
    private static final Duration RESERVA = Duration.ofMinutes(10);
    private static final Duration JANELA_DO_HISTORICO = Duration.ofHours(24);
    private static final int TROCAS_NO_HISTORICO = 6;
    private static final DateTimeFormatter FORMATO_DO_AGORA =
            DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy HH:mm", Locale.forLanguageTag("pt-BR"));

    /**
     * RT-CNV-009 — a janela de agregação mora AQUI, e em nenhum outro lugar.
     *
     * <p>Reservar já era decidir "quando", e a fila já tem a coluna que significa isso. Um agendador
     * à parte, uma fila em memória ou um {@code sleep} no consumidor seriam um SEGUNDO lugar onde o
     * quando mora — e o segundo lugar é o que diverge quando duas instâncias rodam.
     *
     * <p>Em prosa: das pendentes que já poderiam rodar ({@code pronta}), agrupam-se as conversas por
     * telefone ({@code rajada}); passam as rajadas que já podem ser respondidas ({@code madura}); e
     * reserva-se a rajada INTEIRA, não só a última linha — reservar só a última deixaria as
     * anteriores soltas para outra instância formar uma segunda rajada com elas.
     */
    private static final String RESERVAR = """
            with pronta as (
                select e.id, e.estabelecimento_id, e.de_telefone, e.tipo, e.proxima_tentativa_em,
                       m.sequencia
                  from execucao_do_agente e
                  join mensagem_recebida m on m.id = e.mensagem_id
                 where e.status = 'PENDENTE'
                   and e.proxima_tentativa_em <= :agora
                   and (e.reservada_ate is null or e.reservada_ate < :agora)
            ),
            rajada as (
                -- RN-CNV-030 — a unidade é o telefone, não a mensagem. `comecou_em` é de quando a
                -- pessoa começou a falar, e é dele que o teto é contado.
                select p.estabelecimento_id, p.de_telefone,
                       min(p.proxima_tentativa_em) as comecou_em
                  from pronta p
                 where p.tipo = 'CONVERSA'
                 group by p.estabelecimento_id, p.de_telefone
            ),
            madura as (
                select r.estabelecimento_id, r.de_telefone, r.comecou_em
                  from rajada r
                 -- RN-CNV-032 — o teto vence a janela. Transcrição travada, provedor fora do ar ou
                 -- quem não para de escrever: nada segura o agente para sempre.
                 where r.comecou_em <= :vencido_em
                    -- RN-CNV-031 — silêncio de verdade: nada chegou deste número dentro da janela.
                    -- Medido em `mensagem_recebida`, e não na fila: o áudio já é mensagem recebida
                    -- e ainda não é execução, e é ele o caso difícil.
                    or (not exists (select 1 from mensagem_recebida m
                                     where m.estabelecimento_id = r.estabelecimento_id
                                       and m.de_telefone = r.de_telefone
                                       and m.recebida_em > :silencio_desde)
                        -- E nenhum áudio dele ainda virando texto. Só os recentes: um travado de
                        -- ontem não pode calar o agente hoje.
                        and not exists (select 1 from mensagem_recebida m
                                          join transcricao_de_audio t on t.mensagem_id = m.id
                                         where m.estabelecimento_id = r.estabelecimento_id
                                           and m.de_telefone = r.de_telefone
                                           and m.recebida_em > :vencido_em
                                           and t.status in ('PENDENTE', 'BAIXADA')))
                 order by r.comecou_em
                 limit :lote
            ),
            escolhida as (
                -- O toque de botão não espera nada: não passa pelo modelo, não custa, e quem tocou
                -- está olhando a tela. Dois toques também não se juntam — cada um traz o seu token.
                (select p.id from pronta p
                  where p.tipo = 'BOTAO' order by p.proxima_tentativa_em limit :lote)
                union all
                -- `n` corta a enxurrada: as primeiras MAXIMO_DA_RAJADA, na ordem em que chegaram,
                -- e as que sobram formam a rajada seguinte.
                select c.id
                  from (select p.id, p.estabelecimento_id, p.de_telefone,
                               row_number() over (partition by p.estabelecimento_id, p.de_telefone
                                                      order by p.sequencia) as n
                          from pronta p
                         where p.tipo = 'CONVERSA') c
                  join madura d on d.estabelecimento_id = c.estabelecimento_id
                               and d.de_telefone = c.de_telefone
                 where c.n <= :maximo
            )
            update execucao_do_agente e
               set reservada_ate = :reserva, tentativas = e.tentativas + 1
              from mensagem_recebida m
             where m.id = e.mensagem_id
               and e.status = 'PENDENTE'
               and (e.reservada_ate is null or e.reservada_ate < :agora)
               -- O `for update skip locked` trava na TABELA, e não na CTE — Postgres não permite
               -- travar o resultado de um union. Sem ele duas instâncias se esperariam, e poderiam
               -- travar uma à outra se varressem as mesmas linhas em ordens diferentes.
               and e.id in (select x.id from execucao_do_agente x
                             where x.id in (select id from escolhida)
                               for update skip locked)
            returning e.id, e.estabelecimento_id, e.de_telefone, e.entrada, e.tipo, e.tentativas,
                      m.sequencia
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
    private final Duration janela;
    private final Duration tetoDeEspera;
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
                                   Duration janela, Duration tetoDeEspera,
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
        this.janela = janela;
        this.tetoDeEspera = tetoDeEspera;
        this.propor = propor;
        this.confirmar = confirmar;
        this.decidirAceite = decidirAceite;
        this.concluir = concluir;
        this.somenteLeitura = somenteLeitura;
        this.relogio = relogio;
        this.metricas = metricas;
    }

    private record Reservada(UUID id, UUID estabelecimentoId, String telefone, String entrada,
                             ExecucoesJdbc.Tipo tipo, int tentativas, long sequencia) {
    }

    /**
     * RT-CNV-009 — o que a pessoa disse, e a execução que responde por tudo.
     *
     * <p>{@code quemResponde} é sempre a última mensagem da rajada: é a linha que fica CONCLUÍDA, que
     * vai para a trilha e de quem sai a resposta. {@code absorvidas} são as anteriores, que viram
     * {@code JUNTADA} apontando para ela — no MESMO commit, senão uma queda no meio deixaria metade
     * da rajada respondida e metade pendente, e o agente responderia duas vezes.
     *
     * @param entrada as mensagens na ordem em que chegaram, que é o que o modelo lê
     */
    private record Rajada(Reservada quemResponde, List<UUID> absorvidas, String entrada) {

        ConcluirExecucao.Alvo alvo() {
            return new ConcluirExecucao.Alvo(quemResponde.id(), absorvidas);
        }

        int mensagens() {
            return absorvidas.size() + 1;
        }
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
        for (Rajada rajada : reservar()) {
            feitas += tentar(rajada);
        }
        return feitas;
    }

    /**
     * Reserva e agrupa: o banco decide QUAIS linhas saem; aqui elas viram rajadas.
     *
     * <p>O agrupamento é em memória de propósito. A consulta já garante que as conversas de um
     * telefone saem juntas ou não saem; montar a entrada e escolher quem responde é decisão de
     * domínio, e SQL que escolhesse isso seria a mesma regra escrita duas vezes.
     */
    private List<Rajada> reservar() {
        Instant agora = relogio.agora();
        var reservadas = manutencao.jdbc().sql(RESERVAR)
                .param("reserva", emUtc(agora.plus(RESERVA)))
                .param("agora", emUtc(agora))
                .param("silencio_desde", emUtc(agora.minus(janela)))
                .param("vencido_em", emUtc(agora.minus(tetoDeEspera)))
                .param("lote", LOTE)
                .param("maximo", MAXIMO_DA_RAJADA)
                .query((rs, n) -> new Reservada(
                        rs.getObject("id", UUID.class),
                        rs.getObject("estabelecimento_id", UUID.class),
                        rs.getString("de_telefone"),
                        rs.getString("entrada"),
                        ExecucoesJdbc.Tipo.valueOf(rs.getString("tipo")),
                        rs.getInt("tentativas"),
                        rs.getLong("sequencia")))
                .list();

        var rajadas = new ArrayList<Rajada>();
        var conversas = new LinkedHashMap<String, List<Reservada>>();
        for (Reservada r : reservadas) {
            if (r.tipo() == ExecucoesJdbc.Tipo.BOTAO) {
                rajadas.add(new Rajada(r, List.of(), r.entrada()));
            } else {
                conversas.computeIfAbsent(r.estabelecimentoId() + " " + r.telefone(),
                        k -> new ArrayList<>()).add(r);
            }
        }
        for (List<Reservada> mensagens : conversas.values()) {
            mensagens.sort(Comparator.comparingLong(Reservada::sequencia));
            Reservada ultima = mensagens.getLast();
            rajadas.add(new Rajada(ultima,
                    mensagens.subList(0, mensagens.size() - 1).stream().map(Reservada::id).toList(),
                    mensagens.stream().map(Reservada::entrada)
                            .collect(Collectors.joining(ExecucoesJdbc.SEPARADOR_DA_RAJADA))));
        }
        return rajadas;
    }

    private int tentar(Rajada rajada) {
        Reservada r = rajada.quemResponde();
        try {
            TenantContext.executar(r.estabelecimentoId(), () -> conduzir(rajada));
            metricas.summary("conversacional.rajada").record(rajada.mensagens());
            return 1;
        } catch (FalhaPermanente e) {
            registrar(r, () ->
                    concluir.falhou(rajada.alvo(), recortar(e.getMessage()), relogio.agora()));
            contar("falhou");
            log.warn("Conversa {} falhou de vez: {}", r.id(), e.getMessage());
        } catch (RuntimeException e) {
            boolean desistir = EsperaEntreTentativas.desistir(r.tentativas());
            Instant proxima = relogio.agora().plus(EsperaEntreTentativas.apos(r.tentativas()));
            String erro = recortar(e.getMessage());
            registrar(r, () -> {
                if (desistir) {
                    concluir.falhou(rajada.alvo(), erro, relogio.agora());
                } else {
                    // A rajada inteira volta, e junta: adiar só quem responderia deixaria as
                    // absorvidas reservadas por dez minutos e a próxima volta leria metade dela.
                    execucoes.adiar(rajada.alvo(), erro, proxima);
                }
            });
            contar(desistir ? "falhou" : "adiada");
            log.warn("Conversa {} {} (tentativa {}): {}", r.id(),
                    desistir ? "desistiu" : "adiada", r.tentativas(), e.getMessage());
        }
        return 0;
    }

    private void conduzir(Rajada rajada) {
        Reservada r = rajada.quemResponde();
        if (r.tipo() == ExecucoesJdbc.Tipo.BOTAO) {
            conduzirBotao(rajada);
            return;
        }
        UUID salao = r.estabelecimentoId();
        Instant agora = relogio.agora();

        // Uma consulta só. Duas — uma para decidir, outra para ler — abririam uma janela: desativado
        // entre elas, a segunda viria NaoIdentificado e o cast estouraria.
        var identificacao = usuarios.identificarPorTelefone(r.telefone());
        if (identificacao instanceof Identificacao.NaoIdentificado(var motivo)) {
            concluir.recusada(rajada.alvo(), motivo.name(), null, null, agora);
            contar("recusada_identidade");
            return;
        }
        UsuarioEfetivo usuario = ((Identificacao.Identificado) identificacao).usuario();

        if (!limite.consumir(salao, r.telefone())) {
            concluir.recusada(rajada.alvo(), "LIMITE_POR_TELEFONE", usuario.usuarioId(), null, agora);
            contar("recusada_limite");
            return;
        }

        // RT-CNV-005 — a confirmação é reconhecida, nunca interpretada: esta mensagem não chega ao
        // modelo, e a resposta a ela é do servidor.
        var codigo = CodigoDeConfirmacao.codigoEm(rajada.entrada());
        if (codigo.isPresent()) {
            var decisao = confirmar.executar(r.telefone(), usuario.usuarioId(), r.id(),
                    codigo.get());
            concluir.semModelo(rajada.alvo(), r.telefone(), usuario.usuarioId(), decisao.resposta(),
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
                agoraNoSalao, rajada.entrada(), historico), orcamento, executor);

        if (resultado.desfecho() == Orquestrador.Desfecho.TETO_ATINGIDO) {
            concluir.recusada(rajada.alvo(), "TETO_DE_CUSTO", usuario.usuarioId(), resultado,
                    relogio.agora());
            contar("recusada_teto");
            log.warn("Salão {} atingiu o teto diário de custo do agente", salao);
            return;
        }
        concluir.conduzida(rajada.alvo(), r.telefone(), usuario.usuarioId(), resultado, relogio.agora());
        contar("concluida");
    }

    /**
     * RT-CNV-006 — o toque num botão de aceite.
     *
     * <p>Sem identidade de usuário: o que vale é o botão, com o token, vindo do número para onde o
     * pedido foi mandado (RN-CNV-019). O limite por telefone continua valendo — é ele que torna tocar
     * em sequência inútil.
     */
    private void conduzirBotao(Rajada rajada) {
        Reservada r = rajada.quemResponde();
        if (!limite.consumir(r.estabelecimentoId(), r.telefone())) {
            concluir.recusada(rajada.alvo(), "LIMITE_POR_TELEFONE", null, null, relogio.agora());
            contar("recusada_limite");
            return;
        }
        var decisao = decidirAceite.executar(r.telefone(), r.id(), r.entrada());
        concluir.semModelo(rajada.alvo(), r.telefone(), decisao.usuarioEfetivoId(), decisao.resposta(),
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

    private static OffsetDateTime emUtc(Instant instante) {
        return OffsetDateTime.ofInstant(instante, ZoneOffset.UTC);
    }

    private static String recortar(String erro) {
        if (erro == null) {
            return null;
        }
        return erro.substring(0, Math.min(erro.length(), 500));
    }
}
