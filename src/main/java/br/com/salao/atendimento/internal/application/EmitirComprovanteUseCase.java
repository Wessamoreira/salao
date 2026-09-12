package br.com.salao.atendimento.internal.application;

import br.com.salao.atendimento.api.ComandaDetalhada;
import br.com.salao.atendimento.api.ComprovanteDeAtendimento;
import br.com.salao.atendimento.api.ErrosDoAtendimento;
import br.com.salao.atendimento.api.StatusDaComanda;
import br.com.salao.cliente.api.ClienteApi;
import br.com.salao.financeiro.api.PagamentoApi;
import br.com.salao.financeiro.api.PagamentoResumo;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-008 — o comprovante de um atendimento.
 *
 * <h2>Por que esta rotina mora no `atendimento`, e não no `financeiro`</h2>
 *
 * <p>O plano a numerou RT-FIN-008, e o número é só um número: o comprovante mostra os ITENS da
 * comanda, e {@code financeiro} não sabe o que é uma comanda — {@code comandaId} é id de origem
 * opaco (RN-FIN-002). Montá-lo lá exigiria {@code financeiro → atendimento}, e {@code atendimento}
 * já depende de {@code financeiro} para perguntar quanto foi pago: seria ciclo, e o Spring
 * Modulith reprovaria o build.
 *
 * <p>A alternativa seria enfeitar o evento {@code ComandaFechada} com o recibo inteiro, e ela é
 * pior pelo motivo que RN-FIN-009 já deu: o payload de {@code event_publication} não tem RLS, e
 * aqui o payload teria nome de cliente e o que ela consumiu.
 *
 * <h2>Só para comanda fechada</h2>
 *
 * <p>Aberta, ela ainda vai mudar — um recibo impresso agora seria desmentido no próximo item.
 * Reaberta ou estornada, o comprovante que a cliente tem na mão deixou de valer, e emitir outro
 * fingindo que está tudo certo é pior do que recusar.
 *
 * <h2>Nada é gravado</h2>
 *
 * <p>O comprovante é projeção: ele se monta do que já está na comanda, com o snapshot de preço de
 * RN-ATD-010. Dois comprovantes da mesma comanda são idênticos, e não existe cópia para discordar
 * depois.
 */
public class EmitirComprovanteUseCase {

    private final ConsultarComandaUseCase comandas;
    private final PagamentoApi pagamentos;
    private final ClienteApi clientes;
    private final EstabelecimentoApi estabelecimentos;

    public EmitirComprovanteUseCase(ConsultarComandaUseCase comandas, PagamentoApi pagamentos,
                                    ClienteApi clientes, EstabelecimentoApi estabelecimentos) {
        this.comandas = comandas;
        this.pagamentos = pagamentos;
        this.clientes = clientes;
        this.estabelecimentos = estabelecimentos;
    }

    /** O documento, para quem vai desenhar a tela. */
    @PreAuthorize("hasAuthority('" + Permissao.COMANDA_ABRIR + "')"
            + " or hasAuthority('" + Permissao.COMANDA_FECHAR + "')")
    @Transactional(readOnly = true)
    public ComprovanteDeAtendimento executar(UUID comandaId) {
        ComandaDetalhada detalhada = comandas.executar(comandaId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoAtendimento.COMANDA_NAO_ENCONTRADA,
                        "comanda " + comandaId + " não encontrada"));
        if (detalhada.comanda().status() != StatusDaComanda.FECHADA) {
            throw new ErroDeDominio(ErrosDoAtendimento.COMPROVANTE_EXIGE_FECHADA,
                    "a comanda " + detalhada.comanda().numero() + " está "
                            + detalhada.comanda().status().name().toLowerCase());
        }
        var salao = estabelecimentos.configuracao(TenantContext.obrigatorio()).orElseThrow();

        return new ComprovanteDeAtendimento(
                salao.nome(),
                detalhada.comanda().numero(),
                detalhada.comanda().fechadaEm(),
                salao.fuso().getId(),
                clientes.porId(detalhada.comanda().clienteId())
                        .map(br.com.salao.cliente.api.ClienteResumo::nome)
                        .orElse("Cliente"),
                detalhada.itens().stream()
                        .map(item -> new ComprovanteDeAtendimento.LinhaDoComprovante(
                                item.descricao(), item.quantidade(), item.precoUnitario(),
                                item.desconto(), item.valorTotal()))
                        .toList(),
                detalhada.comanda().valorBruto(),
                detalhada.comanda().valorDesconto(),
                detalhada.comanda().valorLiquido(),
                pagamentosDe(comandaId));
    }

    /** O texto de 48 colunas, para a impressora térmica do balcão. */
    @PreAuthorize("hasAuthority('" + Permissao.COMANDA_ABRIR + "')"
            + " or hasAuthority('" + Permissao.COMANDA_FECHAR + "')")
    @Transactional(readOnly = true)
    public String texto(UUID comandaId) {
        return TextoDoComprovante.render(executar(comandaId));
    }

    /**
     * As formas de pagamento em palavra, não em sigla de enum.
     *
     * <p>A cliente lê "Crédito", não "CREDITO". Traduzir aqui e não na tela é o que faz o texto da
     * impressora e o da tela dizerem a mesma coisa.
     */
    private List<ComprovanteDeAtendimento.PagamentoDoComprovante> pagamentosDe(UUID comandaId) {
        return pagamentos.daOrigem(comandaId).stream()
                .map(pago -> new ComprovanteDeAtendimento.PagamentoDoComprovante(
                        emPalavra(pago), pago.parcelas(), pago.valor()))
                .toList();
    }

    private static String emPalavra(PagamentoResumo pago) {
        String nome = pago.forma().name().toLowerCase().replace('_', ' ');
        return nome.substring(0, 1).toUpperCase() + nome.substring(1);
    }
}
