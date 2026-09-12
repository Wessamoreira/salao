package br.com.salao.atendimento.internal.application;

import br.com.salao.atendimento.api.ComandaDetalhada;
import br.com.salao.atendimento.api.ComandaEstornada;
import br.com.salao.atendimento.api.ComandaResumo;
import br.com.salao.atendimento.api.ErrosDoAtendimento;
import br.com.salao.atendimento.api.ItemDaComanda;
import br.com.salao.atendimento.api.StatusDaComanda;
import br.com.salao.atendimento.api.TipoDeItem;
import br.com.salao.atendimento.internal.infra.ComandasJdbc;
import br.com.salao.estoque.api.EstoqueApi;
import br.com.salao.financeiro.api.RazaoApi;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-ATD-007 — desfazer um atendimento pago.
 *
 * <h2>Terminal, e por isso guarda o motivo na própria linha</h2>
 *
 * <p>A comanda não volta de {@code ESTORNADA}. A linha vai ficar ali para sempre dizendo que um
 * atendimento pago foi desfeito, e <em>por quê</em> faz parte do que ela afirma — por isso o motivo
 * é coluna, e não só trilha.
 *
 * <p>Reabrir é o contrário: transitório, a comanda volta à vida normal, e o que sobra do episódio é
 * trilha — que é da auditoria.
 *
 * <h2>Estornar não é apagar</h2>
 *
 * <p>Os itens ficam. Os pagamentos ficam. O que muda é o estado: aquela comanda deixa de valer como
 * faturamento. Apagar as linhas faria sumir a única explicação de por que o caixa do dia tem um
 * buraco — e é justamente ela que alguém vai procurar.
 *
 * <p>O <strong>dinheiro</strong>, esse volta por fora do sistema hoje: não há devolução de
 * pagamento nem lançamento de sinal contrário no razão. É RT-FIN-007, e está nas pendências.
 *
 * <h2>O estoque volta</h2>
 *
 * <p>As unidades voltam aos lotes de onde saíram, por movimento de sinal contrário — igual à
 * reabertura, e pela mesma razão (RN-EST-006).
 */
public class EstornarComandaUseCase {

    private static final Logger log = LoggerFactory.getLogger(EstornarComandaUseCase.class);

    private final ComandasJdbc comandas;
    private final EstoqueApi estoque;
    private final RazaoApi livroRazao;
    private final AuditoriaApi auditoria;
    private final ApplicationEventPublisher eventos;
    private final Relogio relogio;

    public EstornarComandaUseCase(ComandasJdbc comandas, EstoqueApi estoque, RazaoApi livroRazao,
                                  AuditoriaApi auditoria, ApplicationEventPublisher eventos,
                                  Relogio relogio) {
        this.comandas = comandas;
        this.estoque = estoque;
        this.livroRazao = livroRazao;
        this.auditoria = auditoria;
        this.eventos = eventos;
        this.relogio = relogio;
    }

    @PreAuthorize("hasAuthority('" + Permissao.COMANDA_REABRIR + "')")
    @Transactional
    public ComandaDetalhada executar(UUID comandaId, String motivo) {
        String razao = MotivoDoAto.exigir(motivo);
        ComandaResumo comanda = comandas.porId(comandaId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoAtendimento.COMANDA_NAO_ENCONTRADA,
                        "comanda " + comandaId + " não encontrada"));
        if (comanda.status() != StatusDaComanda.FECHADA) {
            throw new ErroDeDominio(ErrosDoAtendimento.COMANDA_NAO_ESTA_FECHADA,
                    "a comanda " + comanda.numero() + " está " + comanda.status());
        }
        if (comandas.estornar(comandaId, razao) == 0) {
            throw new ErroDeDominio(ErrosDoAtendimento.COMANDA_NAO_ESTA_FECHADA,
                    "a comanda " + comanda.numero() + " acabou de mudar de estado");
        }

        BigDecimal devolvidas = BigDecimal.ZERO;
        for (ItemDaComanda item : comandas.itensDe(comandaId)) {
            // Todo item, sem filtrar por tipo — ver o comentário em `ReabrirComandaUseCase`:
            // o serviço com ficha técnica consome pela mesma origem, e `devolver` já é
            // idempotente e devolve zero quando não há o que devolver.
            devolvidas = devolvidas.add(estoque.devolver("ITEM_COMANDA", item.id()));
        }

        int anulados = livroRazao.estornarOrigem("COMANDA", comandaId, "estorno: " + razao);

        Instant agora = relogio.agora();
        auditoria.registrar(RegistroDeAuditoria.alteracao("COMANDA_ESTORNADA", "comanda",
                comandaId, Map.of("status", "FECHADA"),
                Map.of("status", "ESTORNADA", "motivo", razao,
                        "valorLiquido", comanda.valorLiquido().toString(),
                        "unidadesDevolvidas", devolvidas.toPlainString(),
                        "lancamentosAnulados", anulados)));
        eventos.publishEvent(new ComandaEstornada(TenantContext.obrigatorio(), comandaId,
                comanda.clienteId(), comanda.numero(), comanda.valorLiquido().valor(), agora));

        log.warn("Comanda {} ESTORNADA ({}): {} — {} unidade(s) devolvidas", comanda.numero(),
                comanda.valorLiquido(), razao, devolvidas);
        return new ComandaDetalhada(comandas.porId(comandaId).orElseThrow(),
                comandas.itensDe(comandaId));
    }
}
