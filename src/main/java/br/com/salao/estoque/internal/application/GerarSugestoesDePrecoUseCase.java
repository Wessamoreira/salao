package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.ErrosDoEstoque;
import br.com.salao.estoque.internal.domain.MotivoDaSugestao;
import br.com.salao.estoque.internal.domain.MotorDePreco;
import br.com.salao.estoque.internal.infra.DecisoesDePrecoJdbc;
import br.com.salao.estoque.internal.infra.PoliticaDePrecoJdbc;
import br.com.salao.estoque.internal.infra.SugestoesDePrecoJdbc;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-010 — varre os produtos e guarda o que o motor achou (RN-EST-030 a 032).
 *
 * <h2>Nada aqui muda um preço</h2>
 *
 * <p>A rotina gera e mostra; aprovar e aplicar é RT-EST-011. É o que faz a Fase 6 fechar com "nenhuma
 * sugestão aplicada sem clique humano" — e aqui o clique nem existe ainda.
 *
 * <h2>Sem margem mínima, não roda</h2>
 *
 * <p>Recusa em vez de assumir um padrão. Um chão escolhido pelo sistema seria um número que ninguém
 * decidiu aparecendo na tela como se alguém tivesse decidido.
 */
public class GerarSugestoesDePrecoUseCase {

    private static final Logger log = LoggerFactory.getLogger(GerarSugestoesDePrecoUseCase.class);

    /** RT-EST-011: quanto tempo uma recusa cala o produto (RN-EST-035). */
    public static final int DIAS_DE_SILENCIO = 90;

    private final SugestoesDePrecoJdbc sugestoes;
    private final PoliticaDePrecoJdbc politica;
    private final DecisoesDePrecoJdbc decisoes;
    private final EstabelecimentoApi estabelecimentos;
    private final Relogio relogio;

    public GerarSugestoesDePrecoUseCase(SugestoesDePrecoJdbc sugestoes, PoliticaDePrecoJdbc politica,
                                        DecisoesDePrecoJdbc decisoes,
                                        EstabelecimentoApi estabelecimentos, Relogio relogio) {
        this.sugestoes = sugestoes;
        this.politica = politica;
        this.decisoes = decisoes;
        this.estabelecimentos = estabelecimentos;
        this.relogio = relogio;
    }

    @PreAuthorize("hasAuthority('" + Permissao.ESTOQUE_LER + "')")
    @Transactional
    public int executar() {
        BigDecimal margemMinima = politica.margemMinima()
                .orElseThrow(() -> new ErroDeDominio(ErrosDoEstoque.SEM_POLITICA_DE_PRECO,
                        "defina a margem mínima antes de pedir sugestões"));

        // O dia do salão, e não o do servidor: "60 dias sem vender" é contado no calendário de quem
        // abre a loja (ADR-0009).
        ZoneId fuso = estabelecimentos.fusoDe(TenantContext.obrigatorio());
        var produtos = sugestoes.produtosAAvaliar(fuso, relogio.hojeEm(fuso));

        // RT-EST-011 — o que o dono recusou nos últimos 90 dias fica calado, desde que a sugestão
        // seja a MESMA. Custo que subiu muda o preço sugerido, e aí é fato novo: fura o silêncio.
        var calados = decisoes.recusasDesde(
                        relogio.agora().minus(DIAS_DE_SILENCIO, ChronoUnit.DAYS)).stream()
                .collect(Collectors.toMap(DecisoesDePrecoJdbc.Silencio::produtoId, s -> s));

        List<SugestoesDePrecoJdbc.Gravar> achadas = new ArrayList<>();
        int calou = 0;
        for (MotorDePreco.Produto produto : produtos) {
            var sugestao = MotorDePreco.avaliar(produto, margemMinima);
            if (sugestao.isEmpty()) {
                continue;
            }
            if (calado(calados.get(produto.id()), sugestao.get())) {
                calou++;
                continue;
            }
            // A explicação da IA fica para outra rotina; até lá o racional da conta responde.
            achadas.add(new SugestoesDePrecoJdbc.Gravar(sugestao.get(), null));
        }
        sugestoes.substituir(achadas, relogio.agora());

        // RT-EST-012: a queima é a única sugestão abaixo da margem mínima, e por isso conta à parte.
        long queimas = achadas.stream()
                .filter(g -> g.sugestao().motivo() == MotivoDaSugestao.QUEIMA_DE_VENCIMENTO)
                .count();
        log.info("Sugestões de preço: {} geradas ({} de queima) de {} produtos avaliados "
                + "({} caladas por recusa)", achadas.size(), queimas, produtos.size(), calou);
        return achadas.size();
    }

    /** Mesma recusa, mesma conversa: mesmo motivo e mesmo preço sugerido. */
    private static boolean calado(DecisoesDePrecoJdbc.Silencio recusa,
                                  MotorDePreco.Sugestao sugestao) {
        return recusa != null
                && recusa.motivo() == sugestao.motivo()
                && recusa.precoSugerido().compareTo(sugestao.precoSugerido().valor()) == 0;
    }
}
