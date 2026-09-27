package br.com.salao.conversacional.internal;

import br.com.salao.arquivos.api.IngestaoDeArquivoApi;
import br.com.salao.conversacional.internal.application.AvisarQuemNaoEDaEquipe;
import br.com.salao.conversacional.internal.application.CatalogoDeFerramentas;
import br.com.salao.conversacional.internal.application.ConcluirExecucao;
import br.com.salao.conversacional.internal.application.ConcluirTranscricao;
import br.com.salao.conversacional.internal.application.ConfirmarPeloCodigo;
import br.com.salao.conversacional.internal.application.ConsultarAcoesDoAgenteUseCase;
import br.com.salao.conversacional.internal.application.ConsultarCanalUseCase;
import br.com.salao.conversacional.internal.application.DecidirAceite;
import br.com.salao.conversacional.internal.application.EfetivarIntencao;
import br.com.salao.conversacional.internal.application.EnfileiradorDeAudios;
import br.com.salao.conversacional.internal.application.EnfileiradorDeInteracoes;
import br.com.salao.conversacional.internal.application.EnviadorDoWhatsApp;
import br.com.salao.conversacional.internal.application.GravarMensagemRecebidaUseCase;
import br.com.salao.conversacional.internal.application.GuardarAudioBaixado;
import br.com.salao.conversacional.internal.application.MidiaDoWhatsApp;
import br.com.salao.conversacional.internal.application.ModeloDeLinguagem;
import br.com.salao.conversacional.internal.application.Orquestrador;
import br.com.salao.conversacional.internal.application.PrecoDoModelo;
import br.com.salao.conversacional.internal.application.ProporIntencao;
import br.com.salao.conversacional.internal.application.ReceberLoteDoWhatsAppUseCase;
import br.com.salao.conversacional.internal.application.RegistrarCanalDoWhatsAppUseCase;
import br.com.salao.conversacional.internal.application.TransacaoSomenteLeitura;
import br.com.salao.conversacional.internal.application.Transcritor;
import br.com.salao.conversacional.internal.domain.AssinaturaDoWebhook;
import br.com.salao.conversacional.internal.infra.AcoesDoAgenteJdbc;
import br.com.salao.conversacional.internal.infra.AvisosDeIaJdbc;
import br.com.salao.conversacional.internal.infra.CanaisJdbc;
import br.com.salao.conversacional.internal.infra.ClaudeMessagesApi;
import br.com.salao.conversacional.internal.infra.ConsumoDeIaJdbc;
import br.com.salao.conversacional.internal.infra.DespachanteParaEquipe;
import br.com.salao.conversacional.internal.infra.EnviadorNaoConfigurado;
import br.com.salao.conversacional.internal.infra.ExecucoesJdbc;
import br.com.salao.conversacional.internal.infra.ExpiradorDeAceites;
import br.com.salao.conversacional.internal.infra.IntencoesJdbc;
import br.com.salao.conversacional.internal.infra.LimitePorTelefone;
import br.com.salao.conversacional.internal.infra.MensagensParaEquipeJdbc;
import br.com.salao.conversacional.internal.infra.MensagensRecebidasJdbc;
import br.com.salao.conversacional.internal.infra.MidiaDoWhatsAppGraph;
import br.com.salao.conversacional.internal.infra.ProcessadorDeAudios;
import br.com.salao.conversacional.internal.infra.ProcessadorDeInteracoes;
import br.com.salao.conversacional.internal.infra.PurgadorDeAcoesDoAgente;
import br.com.salao.conversacional.internal.infra.TranscricoesJdbc;
import br.com.salao.conversacional.internal.infra.TranscritorNaoConfigurado;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.NomesDeUsuariosApi;
import br.com.salao.iam.api.UsuarioEfetivoApi;
import br.com.salao.shared.agente.Ferramenta;
import br.com.salao.shared.cripto.CofreDeCampo;
import br.com.salao.shared.manutencao.ConexaoDeManutencao;
import br.com.salao.shared.tempo.Relogio;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** RT-CNV-001 — fiação do módulo conversacional. */
@Configuration(proxyBeanMethods = false)
public class ConversacionalConfig {

    private static final Logger log = LoggerFactory.getLogger(ConversacionalConfig.class);

    /**
     * O segredo do app da Meta.
     *
     * <p><strong>Padrão vazio, ao contrário de {@code app.jwt.segredo}</strong> — e a diferença é
     * proposital. Sem JWT a aplicação não serve para nada, então ela não sobe. Sem WhatsApp ela
     * serve para tudo, menos para o agente: ADR-0006 registra que nenhum provedor está contratado
     * e que o {@code Mensageiro} de hoje só registra no log.
     *
     * <p>Vazio aqui significa <em>desligado</em>, nunca <em>aberto</em>: a rota recusa toda
     * requisição com 503. O aviso na subida existe porque "desligado" e "esqueci a variável" são
     * indistinguíveis do lado de fora, e quem implanta precisa ver a diferença.
     */
    @Bean
    public AssinaturaDoWebhook assinaturaDoWebhook(
            @Value("${app.whatsapp.app-secret:}") String segredo) {
        if (segredo == null || segredo.isBlank()) {
            log.warn("app.whatsapp.app-secret não configurado: "
                    + "o webhook do WhatsApp vai recusar toda requisição com 503");
        }
        return new AssinaturaDoWebhook(segredo);
    }

    @Bean
    public CanaisJdbc canaisJdbc(DataSource dataSource, ConexaoDeManutencao manutencao) {
        return new CanaisJdbc(JdbcClient.create(dataSource), manutencao);
    }

    @Bean
    public MensagensRecebidasJdbc mensagensRecebidasJdbc(DataSource dataSource) {
        return new MensagensRecebidasJdbc(JdbcClient.create(dataSource));
    }

    @Bean
    public GravarMensagemRecebidaUseCase gravarMensagemRecebidaUseCase(
            MensagensRecebidasJdbc mensagens, ApplicationEventPublisher eventos, Relogio relogio) {
        return new GravarMensagemRecebidaUseCase(mensagens, eventos, relogio);
    }

    @Bean
    public ReceberLoteDoWhatsAppUseCase receberLoteDoWhatsAppUseCase(
            CanaisJdbc canais, GravarMensagemRecebidaUseCase gravar, MeterRegistry registro) {
        return new ReceberLoteDoWhatsAppUseCase(canais, gravar, registro);
    }

    @Bean
    public RegistrarCanalDoWhatsAppUseCase registrarCanalDoWhatsAppUseCase(
            CanaisJdbc canais, AuditoriaApi auditoria) {
        return new RegistrarCanalDoWhatsAppUseCase(canais, auditoria);
    }

    @Bean
    public ConsultarCanalUseCase consultarCanalUseCase(CanaisJdbc canais) {
        return new ConsultarCanalUseCase(canais);
    }

    // ------------------------------------------------------------------ RT-CNV-003

    /**
     * A política de hosts é a da Meta, fixa, e não uma propriedade: uma chave "hosts extras" seria a
     * chave que alguém vira em produção para destravar um teste — e o token do app iria junto.
     */
    @Bean
    public MidiaDoWhatsApp midiaDoWhatsApp(
            @Value("${app.whatsapp.graph-url:https://graph.facebook.com/v23.0}") String graphUrl,
            @Value("${app.whatsapp.token-de-acesso:}") String token,
            ObjectMapper json) {
        if (token == null || token.isBlank()) {
            log.warn("app.whatsapp.token-de-acesso não configurado: "
                    + "áudios recebidos vão esperar na fila sem ser baixados");
        }
        return new MidiaDoWhatsAppGraph(graphUrl, token,
                MidiaDoWhatsAppGraph.PoliticaDeUrl.daMeta(), json);
    }

    /** Sem provedor escolhido (pergunta 14). Um provedor real entra como bean {@code @Primary}. */
    @Bean
    public Transcritor transcritor() {
        return new TranscritorNaoConfigurado();
    }

    @Bean
    public TranscricoesJdbc transcricoesJdbc(DataSource dataSource) {
        return new TranscricoesJdbc(JdbcClient.create(dataSource));
    }

    @Bean
    public EnfileiradorDeAudios enfileiradorDeAudios(TranscricoesJdbc transcricoes,
                                                     Relogio relogio) {
        return new EnfileiradorDeAudios(transcricoes, relogio);
    }

    @Bean
    public GuardarAudioBaixado guardarAudioBaixado(TranscricoesJdbc transcricoes,
                                                   IngestaoDeArquivoApi ingestao) {
        return new GuardarAudioBaixado(transcricoes, ingestao);
    }

    @Bean
    public ProcessadorDeAudios processadorDeAudios(
            ConexaoDeManutencao manutencao, TranscricoesJdbc transcricoes,
            MensagensRecebidasJdbc mensagens, MidiaDoWhatsApp midia, GuardarAudioBaixado guardar,
            IngestaoDeArquivoApi ingestao, Transcritor transcritor, ConcluirTranscricao concluir,
            Relogio relogio, MeterRegistry metricas) {
        return new ProcessadorDeAudios(manutencao, transcricoes, mensagens, midia, guardar,
                ingestao, transcritor, concluir, relogio, metricas);
    }

    // ------------------------------------------------------------------ RT-CNV-004

    /** O endpoint é fixo no adaptador: a chave de API não vai para uma URL de configuração. */
    @Bean
    public ModeloDeLinguagem modeloDeLinguagem(
            @Value("${app.agente.chave-api:}") String chave,
            @Value("${app.agente.modelo:claude-opus-5}") String modelo,
            ObjectMapper json) {
        if (chave == null || chave.isBlank()) {
            log.warn("app.agente.chave-api não configurada: as conversas vão esperar na fila");
        }
        return new ClaudeMessagesApi(chave, modelo, json);
    }

    @Bean
    public PrecoDoModelo precoDoModelo(
            @Value("${app.agente.preco.entrada-por-milhao-usd:}") String entrada,
            @Value("${app.agente.preco.saida-por-milhao-usd:}") String saida) {
        PrecoDoModelo preco = PrecoDoModelo.de(entrada, saida);
        if (!preco.configurado()) {
            log.warn("Preço do modelo não configurado: sem preço não há teto de custo, "
                    + "e o agente não roda (R-17)");
        }
        return preco;
    }

    /** Recolhe as ferramentas que os módulos contribuem. Nenhuma é declarada aqui. */
    @Bean
    public CatalogoDeFerramentas catalogoDeFerramentas(ObjectProvider<Ferramenta> ferramentas) {
        return new CatalogoDeFerramentas(ferramentas.orderedStream().toList());
    }

    @Bean
    public Orquestrador orquestrador(ModeloDeLinguagem modelo, CatalogoDeFerramentas catalogo,
                                     PrecoDoModelo preco, ObjectMapper json) {
        return new Orquestrador(modelo, catalogo, preco, json);
    }

    @Bean
    public ExecucoesJdbc execucoesJdbc(DataSource dataSource, ObjectMapper json) {
        return new ExecucoesJdbc(JdbcClient.create(dataSource), json);
    }

    @Bean
    public ConsumoDeIaJdbc consumoDeIaJdbc(DataSource dataSource) {
        return new ConsumoDeIaJdbc(JdbcClient.create(dataSource));
    }

    @Bean
    public LimitePorTelefone limitePorTelefone(
            @Value("${app.agente.limite-por-telefone-por-hora:30}") int porHora) {
        return new LimitePorTelefone(porHora);
    }

    @Bean
    public EnfileiradorDeInteracoes enfileiradorDeInteracoes(MensagensRecebidasJdbc mensagens,
                                                             ExecucoesJdbc execucoes,
                                                             Relogio relogio) {
        return new EnfileiradorDeInteracoes(mensagens, execucoes, relogio);
    }

    /** RT-CNV-008 — quem já foi avisado, pela impressão do número. */
    @Bean
    public AvisosDeIaJdbc avisosDeIaJdbc(DataSource dataSource) {
        return new AvisosDeIaJdbc(JdbcClient.create(dataSource));
    }

    /** RT-CNV-008 — o aviso a quem não é da equipe, na chegada da mensagem, sem depender do modelo. */
    @Bean
    public AvisarQuemNaoEDaEquipe avisarQuemNaoEDaEquipe(MensagensRecebidasJdbc mensagens,
                                                         UsuarioEfetivoApi usuarios,
                                                         EstabelecimentoApi estabelecimentos,
                                                         AvisosDeIaJdbc avisos,
                                                         MensagensParaEquipeJdbc saida,
                                                         CofreDeCampo cofre, Relogio relogio,
                                                         MeterRegistry metricas) {
        return new AvisarQuemNaoEDaEquipe(mensagens, usuarios, estabelecimentos, avisos, saida,
                cofre, relogio, metricas);
    }

    @Bean
    public ConcluirTranscricao concluirTranscricao(TranscricoesJdbc transcricoes,
                                                   MensagensRecebidasJdbc mensagens,
                                                   ExecucoesJdbc execucoes) {
        return new ConcluirTranscricao(transcricoes, mensagens, execucoes);
    }

    @Bean
    public ProcessadorDeInteracoes processadorDeInteracoes(
            ConexaoDeManutencao manutencao, ExecucoesJdbc execucoes, UsuarioEfetivoApi usuarios,
            EstabelecimentoApi estabelecimentos, Orquestrador orquestrador,
            ModeloDeLinguagem modelo, PrecoDoModelo preco, ConsumoDeIaJdbc consumo,
            LimitePorTelefone limite,
            @Value("${app.agente.teto-diario-usd:2.00}") BigDecimal tetoDiario,
            @Value("${app.agente.janela-de-agregacao:PT15S}") Duration janela,
            @Value("${app.agente.teto-de-espera:PT2M}") Duration tetoDeEspera,
            ProporIntencao propor, ConfirmarPeloCodigo confirmar, DecidirAceite decidirAceite,
            ConcluirExecucao concluir, TransacaoSomenteLeitura somenteLeitura, Relogio relogio,
            MeterRegistry metricas) {
        return new ProcessadorDeInteracoes(manutencao, execucoes, usuarios, estabelecimentos,
                orquestrador, modelo, preco, consumo, limite, tetoDiario, janela, tetoDeEspera,
                propor, confirmar, decidirAceite, concluir, somenteLeitura, relogio, metricas);
    }

    // ------------------------------------------------------------------ RT-CNV-005

    @Bean
    public IntencoesJdbc intencoesJdbc(DataSource dataSource, ObjectMapper json) {
        return new IntencoesJdbc(JdbcClient.create(dataSource), json);
    }

    /** Bean, e não {@code new}: sem proxy, o {@code @Transactional(readOnly)} seria decoração. */
    @Bean
    public TransacaoSomenteLeitura transacaoSomenteLeitura() {
        return new TransacaoSomenteLeitura();
    }

    /**
     * {@code SecureRandom} sem que o código seja segredo — a identidade é o telefone atestado pela
     * Meta —, mas também sem motivo para ele ser previsível.
     */
    @Bean
    public ProporIntencao proporIntencao(
            IntencoesJdbc intencoes, EstabelecimentoApi estabelecimentos, Relogio relogio,
            @Value("${app.agente.intencao.validade:PT10M}") String validade) {
        return new ProporIntencao(intencoes, estabelecimentos, new SecureRandom(), relogio,
                Duration.parse(validade));
    }

    @Bean
    public EfetivarIntencao efetivarIntencao(
            IntencoesJdbc intencoes, CatalogoDeFerramentas catalogo, UsuarioEfetivoApi usuarios,
            MensagensParaEquipeJdbc saida, EstabelecimentoApi estabelecimentos, Relogio relogio,
            @Value("${app.agente.aceite.validade:PT2H}") String validadeDoAceite) {
        return new EfetivarIntencao(intencoes, catalogo, usuarios, saida, estabelecimentos, relogio,
                Duration.parse(validadeDoAceite));
    }

    @Bean
    public ConfirmarPeloCodigo confirmarPeloCodigo(IntencoesJdbc intencoes,
                                                   EfetivarIntencao efetivar, Relogio relogio) {
        return new ConfirmarPeloCodigo(intencoes, efetivar, relogio);
    }

    // ------------------------------------------------------------------ RT-CNV-006

    @Bean
    public MensagensParaEquipeJdbc mensagensParaEquipeJdbc(DataSource dataSource, ObjectMapper json) {
        return new MensagensParaEquipeJdbc(JdbcClient.create(dataSource), json);
    }

    /**
     * Sem provedor de envio contratado. Um adaptador real entra como bean {@code @Primary}; até lá, a
     * saída espera na fila — e a subida diz isso, porque "esperando" e "esqueci a credencial" são
     * indistinguíveis de fora.
     */
    @Bean
    public EnviadorDoWhatsApp enviadorDoWhatsApp() {
        log.warn("Nenhum provedor de envio do WhatsApp: respostas e pedidos para a equipe "
                + "vão esperar na fila");
        return new EnviadorNaoConfigurado();
    }

    @Bean
    public DespachanteParaEquipe despachanteParaEquipe(ConexaoDeManutencao manutencao,
                                                       EnviadorDoWhatsApp enviador,
                                                       ObjectMapper json, Relogio relogio,
                                                       MeterRegistry metricas) {
        return new DespachanteParaEquipe(manutencao, enviador, json, relogio, metricas);
    }

    @Bean
    public ConcluirExecucao concluirExecucao(ExecucoesJdbc execucoes,
                                             MensagensParaEquipeJdbc saida,
                                             IntencoesJdbc intencoes, AcoesDoAgenteJdbc acoes) {
        return new ConcluirExecucao(execucoes, saida, intencoes, acoes);
    }

    @Bean
    public DecidirAceite decidirAceite(EfetivarIntencao efetivar) {
        return new DecidirAceite(efetivar);
    }

    @Bean
    public ExpiradorDeAceites expiradorDeAceites(ConexaoDeManutencao manutencao, Relogio relogio,
                                                 MeterRegistry metricas) {
        return new ExpiradorDeAceites(manutencao, relogio, metricas);
    }

    // ------------------------------------------------------------------ RT-CNV-007

    @Bean
    public AcoesDoAgenteJdbc acoesDoAgenteJdbc(DataSource dataSource, ObjectMapper json) {
        return new AcoesDoAgenteJdbc(JdbcClient.create(dataSource), json);
    }

    @Bean
    public ConsultarAcoesDoAgenteUseCase consultarAcoesDoAgenteUseCase(
            AcoesDoAgenteJdbc acoes, NomesDeUsuariosApi nomes, EstabelecimentoApi estabelecimentos,
            Relogio relogio) {
        return new ConsultarAcoesDoAgenteUseCase(acoes, nomes, estabelecimentos, relogio);
    }

    /** Os prazos de docs/04: o conteúdo sai aos 90 dias, a linha aos 12 meses. */
    @Bean
    public PurgadorDeAcoesDoAgente purgadorDeAcoesDoAgente(
            ConexaoDeManutencao manutencao,
            @Value("${app.agente.acao-ia.anonimizar-apos:P90D}") String anonimizarApos,
            @Value("${app.agente.acao-ia.apagar-apos:P365D}") String apagarApos) {
        return new PurgadorDeAcoesDoAgente(manutencao, Duration.parse(anonimizarApos),
                Duration.parse(apagarApos));
    }
}
