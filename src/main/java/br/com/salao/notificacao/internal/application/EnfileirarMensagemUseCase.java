package br.com.salao.notificacao.internal.application;

import br.com.salao.cliente.api.ClienteApi;
import br.com.salao.cliente.api.ClienteResumo;
import br.com.salao.cliente.api.Finalidade;
import br.com.salao.notificacao.api.CanalDeEnvio;
import br.com.salao.notificacao.api.NotificacaoApi;
import br.com.salao.notificacao.api.StatusDaMensagem;
import br.com.salao.notificacao.internal.domain.Modelo;
import br.com.salao.notificacao.internal.infra.MensagensJdbc;
import br.com.salao.shared.erro.CodigoDeErro;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-NOT-001 — pôr a mensagem na fila.
 *
 * <h2>A pergunta do consentimento é feita aqui, e só aqui</h2>
 *
 * <p>{@code ClienteApi.podeReceber} é, nas palavras do próprio contrato, "a pergunta que o módulo
 * {@code notificacao} faz antes de qualquer envio, e a única que ele deve fazer". Quem pede o
 * lembrete não decide se pode mandá-lo.
 *
 * <p>E a recusa <strong>vira linha</strong>, com status {@code BLOQUEADA}, em vez de virar nada.
 * Sem esse registro, "por que a cliente não recebeu?" não tem resposta — e a resposta certa, "ela
 * não autorizou marketing", é justamente a que o salão precisa dar quando a cliente reclama.
 *
 * <h2>O texto é montado no envio, não aqui</h2>
 *
 * <p>A fila guarda o modelo e os parâmetros. Montar o texto agora fixaria uma redação que pode
 * mudar entre a fila e o envio — e um lembrete enfileirado hoje com o texto de ontem sairia com o
 * texto de ontem.
 *
 * <p>Mas o modelo é <strong>validado</strong> aqui: um parâmetro faltando descoberto na hora do
 * envio viraria uma falha que o retry repetiria cinco vezes, sem chance de dar certo.
 */
public class EnfileirarMensagemUseCase implements NotificacaoApi {

    private static final Logger log =
            LoggerFactory.getLogger(EnfileirarMensagemUseCase.class);

    /** Erros do módulo. Poucos: quase tudo aqui é assíncrono e vira status, não resposta. */
    public enum Erros implements CodigoDeErro {
        MODELO_DESCONHECIDO("ER-NOT-MODELO_DESCONHECIDO", HttpStatus.UNPROCESSABLE_ENTITY,
                "Modelo de mensagem não existe"),
        PARAMETROS_INVALIDOS("ER-NOT-PARAMETROS_INVALIDOS", HttpStatus.UNPROCESSABLE_ENTITY,
                "Parâmetros não combinam com o modelo"),
        SEM_DESTINO("ER-NOT-SEM_DESTINO", HttpStatus.UNPROCESSABLE_ENTITY,
                "Cliente sem telefone ou e-mail para este canal"),
        CLIENTE_NAO_ENCONTRADO("ER-NOT-CLIENTE_NAO_ENCONTRADO", HttpStatus.UNPROCESSABLE_ENTITY,
                "Cliente não encontrado");

        private final String codigo;
        private final HttpStatus status;
        private final String titulo;

        Erros(String codigo, HttpStatus status, String titulo) {
            this.codigo = codigo;
            this.status = status;
            this.titulo = titulo;
        }

        @Override public String codigo() { return codigo; }
        @Override public HttpStatus status() { return status; }
        @Override public String titulo() { return titulo; }
    }

    private final MensagensJdbc mensagens;
    private final ClienteApi clientes;
    private final CatalogoDeModelos modelos;

    /**
     * O relógio entra aqui porque {@code proxima_tentativa_em} é comparado pelo despachante contra
     * este mesmo port (RN-INF-006). Deixar o banco preencher com {@code now()} poria dois relógios
     * na mesma comparação — e qualquer defasagem faria a mensagem nunca ser vista, sem erro e sem
     * log, só uma fila que não anda.
     */
    private final Relogio relogio;

    public EnfileirarMensagemUseCase(MensagensJdbc mensagens, ClienteApi clientes,
                                     CatalogoDeModelos modelos, Relogio relogio) {
        this.mensagens = mensagens;
        this.clientes = clientes;
        this.modelos = modelos;
        this.relogio = relogio;
    }

    @Override
    @Transactional
    public UUID enfileirar(UUID clienteId, Finalidade finalidade, CanalDeEnvio canal,
                           String nomeDoModelo, Map<String, String> parametros, UUID referencia) {
        Modelo modelo = modelos.porNome(nomeDoModelo)
                .orElseThrow(() -> new ErroDeDominio(Erros.MODELO_DESCONHECIDO,
                        "não existe modelo chamado " + nomeDoModelo));
        try {
            // Validado agora e montado depois: um parâmetro faltando descoberto no envio viraria
            // uma falha que o retry repetiria cinco vezes, sem chance de dar certo.
            modelo.preencher(parametros);
        } catch (IllegalArgumentException e) {
            throw new ErroDeDominio(Erros.PARAMETROS_INVALIDOS, e.getMessage());
        }

        ClienteResumo cliente = clientes.porId(clienteId)
                .orElseThrow(() -> new ErroDeDominio(Erros.CLIENTE_NAO_ENCONTRADO,
                        "cliente " + clienteId + " não encontrado"));
        String destino = destinoPara(canal, cliente);

        // A pergunta do consentimento, feita uma vez e só aqui.
        StatusDaMensagem status = clientes.podeReceber(clienteId, finalidade)
                ? StatusDaMensagem.PENDENTE
                : StatusDaMensagem.BLOQUEADA;

        UUID id = mensagens.enfileirar(TenantContext.obrigatorio(), clienteId, finalidade, canal,
                nomeDoModelo, destino, parametros, referencia, status, relogio.agora());

        if (status == StatusDaMensagem.BLOQUEADA) {
            // Sem esse registro, "por que a cliente não recebeu?" não tem resposta — e a resposta
            // certa é justamente a que o salão precisa dar quando ela reclama.
            log.info("Mensagem bloqueada por consentimento: modelo={} finalidade={}",
                    nomeDoModelo, finalidade);
        }
        return id;
    }

    /**
     * O destino é resolvido e <strong>guardado</strong> no momento do enfileiramento.
     *
     * <p>A cliente pode trocar de telefone entre a fila e o envio, e a pergunta de auditoria é
     * "para onde ISTO foi", não "qual o telefone dela hoje".
     */
    private String destinoPara(CanalDeEnvio canal, ClienteResumo cliente) {
        String destino = canal == CanalDeEnvio.EMAIL ? cliente.email() : cliente.telefone();
        if (destino == null || destino.isBlank()) {
            throw new ErroDeDominio(Erros.SEM_DESTINO,
                    "cliente sem " + (canal == CanalDeEnvio.EMAIL ? "e-mail" : "telefone"));
        }
        return destino;
    }
}
