package br.com.salao.atendimento.internal.application;

import br.com.salao.agenda.api.AgendaApi;
import br.com.salao.agenda.api.AgendamentoResumo;
import br.com.salao.agenda.api.StatusAgendamento;
import br.com.salao.atendimento.api.ErrosDoAtendimento;
import br.com.salao.atendimento.api.AberturaDeComanda;
import br.com.salao.atendimento.api.ComandaResumo;
import br.com.salao.atendimento.api.OrigemDaComanda;
import br.com.salao.atendimento.internal.infra.ComandasJdbc;
import br.com.salao.cliente.api.ClienteApi;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-ATD-001 — abrir a conta do atendimento.
 *
 * <h2>Abrir é idempotente por cliente</h2>
 *
 * <p>Se a cliente já tem comanda aberta, ela é <strong>devolvida</strong>, não duplicada. Não é
 * conveniência: duas comandas abertas para a mesma pessoa é o defeito que ninguém percebe na hora —
 * metade dos itens cai numa, metade na outra, e o salão cobra uma só.
 *
 * <p>E é o que o salão faz de qualquer forma. Quem marcou corte às 9h e coloração às 14h paga uma
 * conta, não duas. Por isso a segunda abertura não é erro: é a mesma comanda.
 *
 * <p>Quem chama sabe a diferença pelo {@code jaEstavaAberta} — a tela precisa dizer "a comanda 42
 * já estava aberta" em vez de anunciar uma abertura que não houve.
 *
 * <h2>O número é do salão, não do banco</h2>
 *
 * <p>{@code numero} é o que a recepção fala em voz alta. Sai de um contador por estabelecimento, e
 * não de uma {@code sequence}: sequence é global, o 42 de um salão gastaria o 42 do outro, e o
 * tamanho do salto contaria ao vizinho quantas comandas cada um abre.
 */
public class AbrirComandaUseCase {

    private static final Logger log = LoggerFactory.getLogger(AbrirComandaUseCase.class);

    private final ComandasJdbc comandas;
    private final ClienteApi clientes;
    private final AgendaApi agenda;
    private final AuditoriaApi auditoria;
    private final Relogio relogio;

    public AbrirComandaUseCase(ComandasJdbc comandas, ClienteApi clientes, AgendaApi agenda,
                               AuditoriaApi auditoria, Relogio relogio) {
        this.comandas = comandas;
        this.clientes = clientes;
        this.agenda = agenda;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    /**
     * A cliente chegou para o horário marcado.
     *
     * <p>Agendamento cancelado é recusado: a comanda diria que aconteceu um atendimento que o
     * próprio sistema registrou como desmarcado, e é dessa contradição que sai o faturamento que
     * não bate.
     *
     * <p><strong>No-show, não.</strong> Se a pessoa está no balcão, o no-show é que estava errado —
     * recusar aqui obrigaria a recepção a corrigir o status antes de poder atender alguém que já
     * está sentado na cadeira.
     */
    @PreAuthorize("hasAuthority('" + Permissao.COMANDA_ABRIR + "')")
    @Transactional
    public AberturaDeComanda doAgendamento(UUID agendamentoId) {
        AgendamentoResumo agendamento = agenda.porId(agendamentoId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoAtendimento.AGENDAMENTO_NAO_ENCONTRADO,
                        "agendamento " + agendamentoId + " não encontrado"));
        if (agendamento.status() == StatusAgendamento.CANCELADO) {
            throw new ErroDeDominio(ErrosDoAtendimento.AGENDAMENTO_CANCELADO,
                    "não se abre comanda de agendamento cancelado");
        }
        return abrir(agendamento.clienteId(), OrigemDaComanda.AGENDAMENTO);
    }

    /** Chegou sem hora marcada, ou é só uma venda de produto no balcão. */
    @PreAuthorize("hasAuthority('" + Permissao.COMANDA_ABRIR + "')")
    @Transactional
    public AberturaDeComanda avulsa(UUID clienteId) {
        if (!clientes.existe(clienteId)) {
            throw new ErroDeDominio(ErrosDoAtendimento.CLIENTE_NAO_ENCONTRADO,
                    "cliente " + clienteId + " não encontrado neste salão");
        }
        return abrir(clienteId, OrigemDaComanda.AVULSA);
    }

    /**
     * O caminho comum das duas portas.
     *
     * <p>Privado de propósito. Fosse público, seria uma terceira porta sem {@code @PreAuthorize} —
     * e chamada de dentro desta mesma classe ela nem passaria pelo proxy do Spring, que é o defeito
     * que RT-CAT-004 encontrou e que {@code ArquiteturaTest} passou a proibir.
     */
    private AberturaDeComanda abrir(UUID clienteId, OrigemDaComanda origem) {
        Optional<ComandaResumo> jaAberta = comandas.abertaDoCliente(clienteId);
        if (jaAberta.isPresent()) {
            return new AberturaDeComanda(jaAberta.get(), true);
        }

        UUID tenant = TenantContext.obrigatorio();
        long numero = comandas.proximoNumero(tenant);
        Optional<UUID> criada = comandas.inserirSeNaoHouverAberta(tenant, clienteId, numero,
                origem, relogio.agora(), UsuarioAtual.idOuNulo());

        if (criada.isEmpty()) {
            // Outra transação abriu a comanda desta cliente entre a consulta e o insert. O índice
            // parcial decidiu quem ganhou, e o desfecho é o mesmo das duas: existe uma comanda
            // aberta, e é ela que se devolve.
            log.info("Abertura concorrente para o cliente {}: devolvendo a que ficou", clienteId);
            return new AberturaDeComanda(comandas.abertaDoCliente(clienteId).orElseThrow(), true);
        }

        ComandaResumo comanda = comandas.porId(criada.get()).orElseThrow();
        auditoria.registrar(RegistroDeAuditoria.criacao("comanda", comanda.id(),
                Map.of("numero", comanda.numero(), "origem", origem.name(),
                        "clienteId", clienteId.toString())));
        log.info("Comanda {} aberta para o cliente {} ({})", comanda.numero(), clienteId, origem);
        return new AberturaDeComanda(comanda, false);
    }
}
