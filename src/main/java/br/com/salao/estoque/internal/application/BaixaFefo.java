package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.internal.infra.FichasJdbc;
import br.com.salao.estoque.api.ItemDaFichaTecnica;
import br.com.salao.estoque.api.ConsumoDeServico;
import br.com.salao.estoque.api.BaixaDeEstoque;
import br.com.salao.estoque.api.ErrosDoEstoque;
import br.com.salao.estoque.api.EstoqueApi;
import br.com.salao.estoque.api.LoteResumo;
import br.com.salao.estoque.api.TipoDeMovimento;
import br.com.salao.estoque.internal.infra.LotesJdbc;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-004 — sai primeiro o que vence primeiro.
 *
 * <h2>FEFO, e não FIFO</h2>
 *
 * <p>Num salão o que estraga não é o que chegou primeiro — é o que vence primeiro. Uma caixa
 * comprada ontem com validade curta precisa sair antes de uma comprada no mês passado com validade
 * longa. FIFO deixaria a primeira vencer no armário.
 *
 * <h2>Uma baixa quase nunca é de um lote só</h2>
 *
 * <p>Vender três quando o lote mais velho tem duas tira duas de lá e uma do seguinte. Os dois
 * custaram diferente, e o custo <strong>desta retirada</strong> é a média ponderada do que saiu de
 * cada um — não o custo médio do produto (RT-EST-003), que inclui o que ficou na prateleira.
 *
 * <p>São números diferentes de propósito. Este é o que a venda consumiu, e é ele que vira
 * {@code item_comanda.custo_medio_unitario}.
 *
 * <h2>Sem {@code @PreAuthorize}, porque baixa não é ação de usuário</h2>
 *
 * <p>É consequência de fechar comanda ou de executar serviço com ficha técnica. Quem autoriza é
 * quem tem a ação — a mesma forma de {@code Pagamentos} (RT-FIN-001) e de {@code AuditoriaApi}.
 */
public class BaixaFefo implements EstoqueApi {

    private static final Logger log = LoggerFactory.getLogger(BaixaFefo.class);

    /**
     * Casas para a média da retirada.
     *
     * <p>Quatro, como o resto do dinheiro do projeto (ADR-0009). O arredondamento aparece só aqui,
     * na média: o que é gravado em cada movimento é o custo <em>exato</em> do lote, e a soma das
     * parcelas fecha com o custo total sem sobra.
     */
    private static final int CASAS = Money.ESCALA;

    private final LotesJdbc lotes;
    private final FichasJdbc fichas;
    private final Relogio relogio;

    public BaixaFefo(LotesJdbc lotes, Relogio relogio, FichasJdbc fichas) {
        this.fichas = fichas;
        this.lotes = lotes;
        this.relogio = relogio;
    }

    @Override
    @Transactional
    public BaixaDeEstoque baixar(UUID produtoId, BigDecimal quantidade, TipoDeMovimento tipo,
                                 String origemTipo, UUID origemId) {
        return baixar(produtoId, quantidade, tipo, origemTipo, origemId, null);
    }

    /**
     * Baixa o que existe, até o desejado, sem reclamar da falta.
     *
     * <h2>Por que a falta não é erro aqui</h2>
     *
     * <p>É o consumo interno de um serviço que <strong>já foi executado</strong> (RT-EST-007). Se o
     * sistema diz que não há tinta, ou o saldo está errado ou a profissional substituiu o produto —
     * nos dois casos, o serviço aconteceu. Recusar o fechamento da comanda por causa de uma
     * inferência de estoque pararia o balcão com a cliente na cadeira, por um motivo contábil.
     *
     * <p>E não é a mesma coisa que a venda de um produto: ali alguém lançou o item, e vender o que
     * não existe é erro de operação. Aqui ninguém lançou nada — a ficha técnica inferiu.
     *
     * <p>A diferença acumulada entre o previsto e o real é justamente o que o inventário
     * (RT-EST-006) existe para pegar.
     *
     * @return o que efetivamente saiu; zero quando não havia nada
     */
    @Transactional
    public BigDecimal baixarAteODisponivel(UUID produtoId, BigDecimal desejada,
                                           TipoDeMovimento tipo, String origemTipo, UUID origemId,
                                           String observacao) {
        if (desejada == null || desejada.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        // Trava antes de medir, e mede em comando separado — a armadilha de READ COMMITTED de
        // sempre (ver `baixar`).
        lotes.travarLotesDoProduto(produtoId);
        BigDecimal disponivel = lotes.doProduto(produtoId, false).stream()
                .map(LoteResumo::quantidade)
                .filter(quantidade -> quantidade.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal sai = disponivel.min(desejada);
        if (sai.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        baixar(produtoId, sai, tipo, origemTipo, origemId, observacao);
        return sai;
    }

    /**
     * A mesma baixa, carregando um motivo para a coluna {@code observacao}.
     *
     * <p>Fora de {@link EstoqueApi} de propósito: venda e consumo interno têm ORIGEM — item de
     * comanda, serviço — e a origem já explica a saída. Só perda, ajuste e devolução ao
     * fornecedor exigem que alguém digite o porquê (RT-EST-005), e isso é assunto interno do
     * módulo. Acrescentar o parâmetro ao contrato público obrigaria `atendimento` a passar
     * {@code null} em toda venda.
     */
    public BaixaDeEstoque baixar(UUID produtoId, BigDecimal quantidade, TipoDeMovimento tipo,
                                 String origemTipo, UUID origemId, String observacao) {
        if (quantidade == null || quantidade.signum() <= 0) {
            throw new ErroDeDominio(ErrosDoEstoque.QUANTIDADE_INVALIDA,
                    "quantidade a baixar: " + quantidade);
        }
        UUID tenant = TenantContext.obrigatorio();

        // Trava ANTES de decidir, e lê o saldo DEPOIS de travar — em dois comandos.
        //
        // Sem o lock, duas comandas leem o mesmo saldo, as duas decidem que cabe, e o lote fica
        // negativo: o saldo ser projeção resolve saldo errado, não corrida.
        //
        // E com lock e leitura no mesmo comando também não funciona: em READ COMMITTED, quem
        // acorda do `for update` reavalia a linha travada, mas a subconsulta do saldo já foi
        // resolvida com o snapshot antigo — a segunda transação acorda com o lock na mão e o
        // saldo de antes. Cada comando pega snapshot novo, então o segundo `select` já enxerga o
        // commit do vizinho.
        lotes.travarLotesDoProduto(produtoId);
        List<LoteResumo> candidatos = lotes.doProduto(produtoId, false);
        BigDecimal disponivel = candidatos.stream()
                .map(LoteResumo::quantidade)
                .filter(q -> q.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (disponivel.compareTo(quantidade) < 0) {
            // A mensagem diz quanto HÁ: quem está no balcão precisa saber se vende o que resta ou
            // se troca o produto, e "saldo insuficiente" sozinho não responde nenhuma das duas.
            throw new ErroDeDominio(ErrosDoEstoque.SALDO_INSUFICIENTE,
                    "há " + disponivel.stripTrailingZeros().toPlainString()
                            + " em estoque e a saída é de "
                            + quantidade.stripTrailingZeros().toPlainString());
        }

        List<BaixaDeEstoque.ParcelaDaBaixa> parcelas = new ArrayList<>();
        Money custoTotal = Money.ZERO;
        BigDecimal restante = quantidade;

        for (LoteResumo lote : candidatos) {
            if (restante.signum() <= 0) {
                break;
            }
            if (lote.quantidade().signum() <= 0) {
                // Lote vazio (ou negativo, se um ajuste errou) não tem o que dar.
                continue;
            }
            BigDecimal daqui = lote.quantidade().min(restante);
            lotes.inserirMovimento(tenant, produtoId, lote.id(), tipo, daqui.negate(),
                    lote.custoUnitario(), origemTipo, origemId, observacao, relogio.agora(),
                    UsuarioAtual.idOuNulo());

            parcelas.add(new BaixaDeEstoque.ParcelaDaBaixa(lote.id(), daqui,
                    lote.custoUnitario()));
            custoTotal = custoTotal.somar(lote.custoUnitario().multiplicar(daqui));
            restante = restante.subtract(daqui);
        }

        Money custoMedio = new Money(
                custoTotal.valor().divide(quantidade, CASAS, RoundingMode.HALF_UP));
        log.info("Baixa {} de {} x{} em {} lote(s), custo médio {}", tipo, produtoId, quantidade,
                parcelas.size(), custoMedio);
        return new BaixaDeEstoque(produtoId, quantidade, custoMedio, custoTotal,
                List.copyOf(parcelas));
    }

    /**
     * Devolve ao estoque o que saiu por uma origem, nos lotes de onde saiu.
     *
     * <p>Movimento novo, de sinal contrário — nunca {@code delete} do movimento original. É o que
     * RN-EST-006 promete: o razão de estoque não é reescrito, ele ganha a linha que corrige.
     *
     * <p>E é <strong>idempotente</strong> porque olha o saldo líquido da origem em cada lote: o
     * que já voltou soma zero e não aparece na lista de pendentes. Não há marca de "já devolvido"
     * para manter em dia — o próprio razão responde.
     */
    @Override
    @Transactional
    public BigDecimal devolver(String origemTipo, UUID origemId) {
        UUID tenant = TenantContext.obrigatorio();
        BigDecimal total = BigDecimal.ZERO;
        for (LotesJdbc.PendenteDaOrigem pendente : lotes.pendentesDaOrigem(origemTipo, origemId)) {
            lotes.inserirMovimento(tenant, pendente.produtoId(), pendente.loteId(),
                    TipoDeMovimento.DEVOLUCAO, pendente.quantidade(), pendente.custoUnitario(),
                    origemTipo, origemId, "devolução por " + origemTipo, relogio.agora(),
                    UsuarioAtual.idOuNulo());
            total = total.add(pendente.quantidade());
        }
        if (total.signum() > 0) {
            log.info("Devolvidas {} unidade(s) ao estoque por {} {}", total, origemTipo, origemId);
        }
        return total;
    }

    /**
     * RT-EST-007 — o consumo interno de um serviço, pela ficha técnica.
     *
     * <p>Mora aqui, e não num caso de uso próprio, porque é uma BAIXA: quem sabe travar o lote,
     * medir o disponível e repartir por FEFO é esta classe. Um caso de uso separado teria de
     * reimplementar o cuidado de READ COMMITTED, que é justamente o que não se quer duplicar.
     */
    @Override
    @Transactional
    public ConsumoDeServico consumirPorServico(UUID servicoId, String origemTipo, UUID origemId) {
        List<ConsumoDeServico.LinhaConsumida> linhas = new ArrayList<>();
        for (ItemDaFichaTecnica item : fichas.doServico(servicoId)) {
            BigDecimal baixada = baixarAteODisponivel(item.produtoId(), item.quantidade(),
                    TipoDeMovimento.CONSUMO_INTERNO, origemTipo, origemId, null);
            linhas.add(new ConsumoDeServico.LinhaConsumida(item.produtoId(), item.produto(),
                    item.quantidade(), baixada));
        }
        ConsumoDeServico consumo = new ConsumoDeServico(servicoId, List.copyOf(linhas));
        if (!consumo.incompletas().isEmpty()) {
            // WARN porque é sintoma: ou o saldo está errado, ou alguém substituiu o insumo. Nos
            // dois casos o dono quer saber, e nenhum dos dois impede o atendimento de ter
            // acontecido.
            consumo.incompletas().forEach(linha -> log.warn(
                    "Consumo incompleto de {} no serviço {}: faltaram {}", linha.produto(),
                    servicoId, linha.faltou().stripTrailingZeros().toPlainString()));
        }
        return consumo;
    }
}
