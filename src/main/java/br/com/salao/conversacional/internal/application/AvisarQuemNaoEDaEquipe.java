package br.com.salao.conversacional.internal.application;

import br.com.salao.conversacional.api.MensagemDeClienteRecebida;
import br.com.salao.conversacional.api.TipoDeMensagem;
import br.com.salao.conversacional.internal.domain.AvisoDeIa;
import br.com.salao.conversacional.internal.infra.AvisosDeIaJdbc;
import br.com.salao.conversacional.internal.infra.MensagensParaEquipeJdbc;
import br.com.salao.conversacional.internal.infra.MensagensRecebidasJdbc;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Identificacao;
import br.com.salao.iam.api.MotivoDaRecusa;
import br.com.salao.iam.api.UsuarioEfetivoApi;
import br.com.salao.shared.cripto.CofreDeCampo;
import br.com.salao.shared.tempo.Relogio;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * RT-CNV-008 — o aviso a quem não é da equipe, na primeira mensagem (RN-CNV-027 a 029).
 *
 * <h2>Na chegada, e não no processador de conversas</h2>
 *
 * <p>O processador não reserva nada sem modelo configurado — e o aviso não pode esperar um LLM para
 * dizer que ali atende um. Também não passa pela transcrição: a primeira mensagem de muita cliente é
 * um áudio, e ele esperaria um provedor. Então nasce aqui, na chegada de qualquer tipo de mensagem.
 *
 * <h2>Quem recebe</h2>
 *
 * <p>Só {@code TELEFONE_DESCONHECIDO}, resolvido agora (RN-IAM-019). Conta inativa, bloqueada, de
 * perfil com segundo fator ou de dispositivo é da equipe: "não atende clientes" não se aplica a ela.
 * Toque de botão não conta — botão é resposta a mensagem que o salão mandou.
 *
 * <h2>Uma vez</h2>
 *
 * <p>A impressão do número entra em {@code aviso_de_ia} e o aviso entra na fila na mesma transação —
 * a que {@code @ApplicationModuleListener} abre. Duas mensagens juntas do mesmo número novo disputam a
 * mesma linha; a que perde não enfileira nada.
 */
public class AvisarQuemNaoEDaEquipe {

    private final MensagensRecebidasJdbc mensagens;
    private final UsuarioEfetivoApi usuarios;
    private final EstabelecimentoApi estabelecimentos;
    private final AvisosDeIaJdbc avisos;
    private final MensagensParaEquipeJdbc saida;
    private final CofreDeCampo cofre;
    private final Relogio relogio;
    private final MeterRegistry metricas;

    public AvisarQuemNaoEDaEquipe(MensagensRecebidasJdbc mensagens, UsuarioEfetivoApi usuarios,
                                  EstabelecimentoApi estabelecimentos, AvisosDeIaJdbc avisos,
                                  MensagensParaEquipeJdbc saida, CofreDeCampo cofre, Relogio relogio,
                                  MeterRegistry metricas) {
        this.mensagens = mensagens;
        this.usuarios = usuarios;
        this.estabelecimentos = estabelecimentos;
        this.avisos = avisos;
        this.saida = saida;
        this.cofre = cofre;
        this.relogio = relogio;
        this.metricas = metricas;
    }

    @ApplicationModuleListener
    public void aoReceber(MensagemDeClienteRecebida evento) {
        if (evento.tipo() == TipoDeMensagem.BOTAO) {
            return;
        }
        mensagens.remetente(evento.mensagemId())
                .ifPresent(remetente -> avisar(evento, remetente.telefone()));
    }

    private void avisar(MensagemDeClienteRecebida evento, String telefone) {
        boolean desconhecido = usuarios.identificarPorTelefone(telefone)
                instanceof Identificacao.NaoIdentificado(var motivo)
                && motivo == MotivoDaRecusa.TELEFONE_DESCONHECIDO;
        if (!desconhecido) {
            contar("equipe");
            return;
        }

        Instant agora = relogio.agora();
        // O salão entra na impressão: a mesma pessoa não deixa o mesmo rastro em dois salões.
        byte[] impressao = cofre.impressao(evento.estabelecimentoId() + ":" + telefone);
        if (!avisos.registrar(impressao, evento.mensagemId(), agora)) {
            contar("ja_avisado");
            return;
        }

        var salao = estabelecimentos.configuracao(evento.estabelecimentoId()).orElseThrow();
        saida.enfileirar(MensagensParaEquipeJdbc.Motivo.AVISO_DE_IA, evento.mensagemId(), telefone,
                AvisoDeIa.texto(salao.nome(), salao.contatoParaClientes()), List.of(), agora);
        contar("enfileirado");
    }

    private void contar(String desfecho) {
        metricas.counter("conversacional.aviso_de_ia", "desfecho", desfecho).increment();
    }
}
