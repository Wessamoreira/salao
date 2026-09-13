package br.com.salao.conversacional.internal;

import tools.jackson.databind.ObjectMapper;
import br.com.salao.conversacional.internal.infra.TranscritorNaoConfigurado;
import br.com.salao.conversacional.internal.infra.TranscricoesJdbc;
import br.com.salao.conversacional.internal.infra.ProcessadorDeAudios;
import br.com.salao.conversacional.internal.infra.MidiaDoWhatsAppGraph;
import br.com.salao.conversacional.internal.application.Transcritor;
import br.com.salao.conversacional.internal.application.MidiaDoWhatsApp;
import br.com.salao.conversacional.internal.application.GuardarAudioBaixado;
import br.com.salao.conversacional.internal.application.EnfileiradorDeAudios;
import br.com.salao.arquivos.api.IngestaoDeArquivoApi;
import br.com.salao.conversacional.internal.application.ConsultarCanalUseCase;
import br.com.salao.conversacional.internal.application.GravarMensagemRecebidaUseCase;
import br.com.salao.conversacional.internal.application.ReceberLoteDoWhatsAppUseCase;
import br.com.salao.conversacional.internal.application.RegistrarCanalDoWhatsAppUseCase;
import br.com.salao.conversacional.internal.domain.AssinaturaDoWebhook;
import br.com.salao.conversacional.internal.infra.CanaisJdbc;
import br.com.salao.conversacional.internal.infra.MensagensRecebidasJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.shared.manutencao.ConexaoDeManutencao;
import br.com.salao.shared.tempo.Relogio;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

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
            IngestaoDeArquivoApi ingestao, Transcritor transcritor, Relogio relogio,
            MeterRegistry metricas) {
        return new ProcessadorDeAudios(manutencao, transcricoes, mensagens, midia, guardar,
                ingestao, transcritor, relogio, metricas);
    }
}
