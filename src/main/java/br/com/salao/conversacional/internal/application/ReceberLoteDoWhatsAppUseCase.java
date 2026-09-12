package br.com.salao.conversacional.internal.application;

import br.com.salao.conversacional.internal.domain.CargaDoWhatsApp;
import br.com.salao.conversacional.internal.infra.CanaisJdbc;
import br.com.salao.shared.tenant.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.JsonNode;

/**
 * RT-CNV-001 — o lote que a Meta entregou.
 *
 * <h2>Uma mensagem, um tenant, uma transação</h2>
 *
 * <p>O laço roda sem tenant e resolve o estabelecimento <strong>por mensagem</strong>, porque um
 * POST pode trazer mensagens de salões diferentes (ver {@link CargaDoWhatsApp}). Cada gravação é
 * a sua própria transação: uma mensagem que falha não desfaz as outras, e o reenvio da Meta
 * reencontra as que já entraram pelo índice único.
 *
 * <h2>Duas categorias de falha, duas respostas para a Meta</h2>
 *
 * <p>A Meta reenvia o que não recebeu 200. Isso é útil para falha <em>passageira</em> — banco
 * fora do ar — e daninho para falha <em>permanente</em>: um payload que a constraint recusa seria
 * reenviado para sempre, e cada reenvio traz junto o lote inteiro.
 *
 * <ul>
 *   <li>{@link DataIntegrityViolationException} — a forma da mensagem não cabe no modelo. Não vai
 *       melhorar numa próxima tentativa: conta como recusada, registra a ESTRUTURA no log e segue.
 *       O lote responde 200.</li>
 *   <li>qualquer outra — infraestrutura. Propaga, o controller responde 500, e a Meta reenvia.</li>
 * </ul>
 *
 * <h2>O log não carrega conteúdo</h2>
 *
 * <p>Nem texto, nem telefone. O log não tem RLS, é lido por quem opera a máquina e sai do
 * perímetro — mesma decisão de {@code MensageiroDeRegistro}. O que vai é tipo, contagem e o
 * {@code phone_number_id}, que identifica o salão e não a pessoa.
 */
public class ReceberLoteDoWhatsAppUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReceberLoteDoWhatsAppUseCase.class);

    private final CanaisJdbc canais;
    private final GravarMensagemRecebidaUseCase gravar;
    private final Counter gravadas;
    private final Counter repetidas;
    private final Counter semDono;
    private final Counter recusadas;

    public ReceberLoteDoWhatsAppUseCase(CanaisJdbc canais, GravarMensagemRecebidaUseCase gravar,
                                        MeterRegistry registro) {
        this.canais = canais;
        this.gravar = gravar;
        this.gravadas = contador(registro, "gravada", "Mensagens novas gravadas");
        this.repetidas = contador(registro, "repetida", "Reenvios da Meta já conhecidos");
        // A métrica que denuncia configuração errada. Sem ela, um número apontado para o app e
        // não cadastrado aqui perde toda mensagem em silêncio — e ninguém percebe até a cliente
        // reclamar que ninguém respondeu.
        this.semDono = contador(registro, "sem_dono", "Mensagens de número não cadastrado");
        this.recusadas = contador(registro, "recusada", "Mensagens que o modelo não aceitou");
    }

    private static Counter contador(MeterRegistry registro, String desfecho, String descricao) {
        return Counter.builder("conversacional.webhook.mensagens")
                .tag("desfecho", desfecho)
                .description(descricao)
                .register(registro);
    }

    /** @return o resumo do lote, para o log da requisição */
    public Resumo executar(JsonNode raiz) {
        List<CargaDoWhatsApp.Entrante> entrantes = CargaDoWhatsApp.mensagens(raiz);
        int novas = 0;
        int jaConhecidas = 0;
        int orfas = 0;
        int negadas = 0;

        for (CargaDoWhatsApp.Entrante entrante : entrantes) {
            Optional<UUID> dono = canais.estabelecimentoAtivoDe(entrante.phoneNumberId());
            if (dono.isEmpty()) {
                orfas++;
                semDono.increment();
                log.warn("Mensagem de número sem canal ativo: phoneNumberId={}",
                        entrante.phoneNumberId());
                continue;
            }
            try {
                boolean inedita = TenantContext
                        .obter(dono.get(), () -> gravar.executar(dono.get(), entrante))
                        .isPresent();
                if (inedita) {
                    novas++;
                    gravadas.increment();
                    if (entrante.tipo() == br.com.salao.conversacional.api.TipoDeMensagem
                            .NAO_SUPORTADO) {
                        // Só o tipo literal, nunca o conteúdo. É o que responde "a Meta começou a
                        // mandar `reaction`" sem expor o que a cliente escreveu.
                        log.info("Tipo não tratado guardado sem conteúdo: tipo={}",
                                entrante.tipoOriginal());
                    }
                } else {
                    jaConhecidas++;
                    repetidas.increment();
                }
            } catch (DataIntegrityViolationException e) {
                negadas++;
                recusadas.increment();
                log.warn("Mensagem recusada pelo modelo: tipo={} motivo={}",
                        entrante.tipoOriginal(), e.getMostSpecificCause().getMessage());
            }
        }
        return new Resumo(novas, jaConhecidas, orfas, negadas);
    }

    public record Resumo(int gravadas, int repetidas, int semDono, int recusadas) {

        /** Verdadeiro quando o lote não trazia mensagem nenhuma — o caso mais comum. */
        public boolean vazio() {
            return gravadas + repetidas + semDono + recusadas == 0;
        }
    }
}
