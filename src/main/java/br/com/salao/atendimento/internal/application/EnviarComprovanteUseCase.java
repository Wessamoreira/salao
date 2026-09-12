package br.com.salao.atendimento.internal.application;

import br.com.salao.atendimento.api.ComprovanteDeAtendimento;
import br.com.salao.cliente.api.Finalidade;
import br.com.salao.iam.api.Permissao;
import br.com.salao.notificacao.api.CanalDeEnvio;
import br.com.salao.notificacao.api.NotificacaoApi;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-008 — mandar o comprovante pela mensagem.
 *
 * <h2>O que NÃO vai pela mensagem: a lista do que ela consumiu</h2>
 *
 * <p>O papel fica na mão da cliente; a mensagem atravessa um provedor de terceiro e fica no
 * histórico do aparelho dela, que pode ser compartilhado. Mandar "Coloração, Shampoo
 * pós-coloração, Escova" é expor o que a pessoa faz no cabelo a quem pegar o telefone — e não é
 * necessário para o que a mensagem serve, que é confirmar o valor e a data.
 *
 * <p>Não é limitação técnica: é minimização de dado (LGPD art. 6, III). Quem quer o detalhe pede o
 * papel, ou a recepção imprime de novo.
 *
 * <h2>Execução de contrato, não consentimento</h2>
 *
 * <p>{@code COMPROVANTE_DE_ATENDIMENTO} tem base legal de execução de contrato: a cliente pediu o
 * recibo do serviço que acabou de pagar. Exigir opt-in para entregar o comprovante do que ela
 * comprou transformaria a LGPD em obstáculo ao próprio contrato — e o efeito prático seria a
 * recepção mandando pelo WhatsApp pessoal, fora de qualquer registro.
 *
 * <p>Quem decide se pode enviar continua sendo o {@code notificacao}, contra o {@code ClienteApi}.
 * Esta rotina não repete a pergunta.
 *
 * <h2>Segundo clique não manda segunda mensagem</h2>
 *
 * <p>A referência é o id da comanda. {@code NotificacaoApi.enfileirar} devolve a mensagem que já
 * existia em vez de criar outra — é o mesmo mecanismo que impede o job de varredura de mandar seis
 * lembretes do mesmo horário.
 */
public class EnviarComprovanteUseCase {

    private static final Logger log = LoggerFactory.getLogger(EnviarComprovanteUseCase.class);

    private static final DateTimeFormatter DIA =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", new Locale("pt", "BR"));

    private final EmitirComprovanteUseCase emitir;
    private final NotificacaoApi notificacoes;

    public EnviarComprovanteUseCase(EmitirComprovanteUseCase emitir,
                                    NotificacaoApi notificacoes) {
        this.emitir = emitir;
        this.notificacoes = notificacoes;
    }

    /** @return o id da mensagem enfileirada, ou da que já existia */
    @PreAuthorize("hasAuthority('" + Permissao.COMANDA_FECHAR + "')")
    @Transactional
    public UUID executar(UUID comandaId, UUID clienteId) {
        // Passa pelo emissor, e não pela comanda direto: assim a recusa de comanda não fechada
        // vale para os dois caminhos de saída, sem a regra escrita duas vezes.
        ComprovanteDeAtendimento comprovante = emitir.executar(comandaId);

        UUID mensagem = notificacoes.enfileirar(clienteId,
                Finalidade.COMPROVANTE_DE_ATENDIMENTO,
                CanalDeEnvio.WHATSAPP,
                "comprovante-de-atendimento",
                Map.of("nome", comprovante.cliente(),
                        "salao", comprovante.salao(),
                        "numero", String.valueOf(comprovante.numero()),
                        "data", DIA.format(comprovante.fechadaEm()
                                .atZone(ZoneId.of(comprovante.fuso()))),
                        "total", comprovante.total().toString()),
                comandaId);

        log.info("Comprovante da comanda {} enfileirado na mensagem {}", comprovante.numero(),
                mensagem);
        return mensagem;
    }
}
