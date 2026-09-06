package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.api.DadosDoPagamento;
import br.com.salao.financeiro.api.ErrosDoFinanceiro;
import br.com.salao.financeiro.api.FormaDePagamento;
import br.com.salao.financeiro.api.PagamentoApi;
import br.com.salao.financeiro.api.PagamentoResumo;
import br.com.salao.financeiro.api.StatusDeConciliacao;
import br.com.salao.financeiro.internal.infra.PagamentosJdbc;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-001 — o registro de pagamentos, como os outros módulos o usam.
 *
 * <p>Sem {@code @PreAuthorize}: quem autoriza é quem tem a pergunta de negócio. Registrar pagamento
 * é ato de balcão e exige {@code comanda:close}, que é vocabulário do {@code atendimento} — repetir
 * a checagem aqui espalharia a regra de alçada por dois módulos. É a mesma forma de
 * {@code AuditoriaApi.registrar} e de {@code ClienteApi}.
 *
 * <p>O que este módulo garante são as regras <strong>do pagamento</strong>: valor positivo,
 * parcelamento coerente com a forma, e o status de conciliação certo para a origem do dinheiro.
 */
public class Pagamentos implements PagamentoApi {

    private static final Logger log = LoggerFactory.getLogger(Pagamentos.class);

    private final PagamentosJdbc pagamentos;
    private final Relogio relogio;

    public Pagamentos(PagamentosJdbc pagamentos, Relogio relogio) {
        this.pagamentos = pagamentos;
        this.relogio = relogio;
    }

    @Override
    @Transactional
    public PagamentoResumo registrar(UUID comandaId, DadosDoPagamento dados) {
        if (dados == null || dados.forma() == null || dados.valor() == null) {
            throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                    "forma e valor são obrigatórios");
        }
        if (dados.valor().valor().signum() <= 0) {
            throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                    "o valor precisa ser maior que zero");
        }
        int parcelas = parcelasValidas(dados);

        UUID id = pagamentos.inserir(TenantContext.obrigatorio(), comandaId, dados.forma(),
                dados.valor(), dados.bandeira(), parcelas, dados.nsu(), dados.autorizacao(),
                conciliacaoInicial(dados.forma()), relogio.agora(), UsuarioAtual.idOuNulo());
        log.info("Pagamento {} de {} em {} na comanda {}", id, dados.valor(), dados.forma(),
                comandaId);
        return pagamentos.porId(id).orElseThrow();
    }

    @Override
    public List<PagamentoResumo> daOrigem(UUID comandaId) {
        return pagamentos.daOrigem(comandaId);
    }

    @Override
    public Money totalPago(UUID comandaId) {
        return pagamentos.totalPago(comandaId);
    }

    @Override
    public Optional<PagamentoResumo> porId(UUID pagamentoId) {
        return pagamentos.porId(pagamentoId);
    }

    @Override
    @Transactional
    public boolean remover(UUID pagamentoId) {
        return pagamentos.remover(pagamentoId) > 0;
    }

    /**
     * Parcela é coisa de crédito.
     *
     * <p>Débito em três vezes não existe, e aceitar o número faria a previsão de recebimento —
     * quando ela existir — dividir um dinheiro que cai de uma vez só.
     */
    private int parcelasValidas(DadosDoPagamento dados) {
        int parcelas = dados.parcelas() == null ? 1 : dados.parcelas();
        if (parcelas < 1) {
            throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                    "parcelas precisa ser pelo menos 1");
        }
        if (parcelas > 1 && dados.forma() != FormaDePagamento.CREDITO) {
            throw new ErroDeDominio(ErrosDoFinanceiro.SO_CREDITO_PARCELA,
                    dados.forma() + " não parcela");
        }
        return parcelas;
    }

    /**
     * Cartão nasce pendente de conciliação; o resto, não aplicável.
     *
     * <p>Deixar dinheiro e pix como {@code PENDENTE} encheria a fila da conciliação com linhas que
     * nunca vão sair de lá — não existe extrato de adquirente para casar com dinheiro.
     */
    private StatusDeConciliacao conciliacaoInicial(FormaDePagamento forma) {
        return forma.ehCartao() ? StatusDeConciliacao.PENDENTE
                : StatusDeConciliacao.NAO_APLICAVEL;
    }
}
