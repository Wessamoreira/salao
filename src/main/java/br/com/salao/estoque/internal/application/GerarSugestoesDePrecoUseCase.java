package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.ErrosDoEstoque;
import br.com.salao.estoque.internal.domain.MotorDePreco;
import br.com.salao.estoque.internal.infra.PoliticaDePrecoJdbc;
import br.com.salao.estoque.internal.infra.SugestoesDePrecoJdbc;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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

    private final SugestoesDePrecoJdbc sugestoes;
    private final PoliticaDePrecoJdbc politica;
    private final EstabelecimentoApi estabelecimentos;
    private final Relogio relogio;

    public GerarSugestoesDePrecoUseCase(SugestoesDePrecoJdbc sugestoes, PoliticaDePrecoJdbc politica,
                                        EstabelecimentoApi estabelecimentos, Relogio relogio) {
        this.sugestoes = sugestoes;
        this.politica = politica;
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

        List<SugestoesDePrecoJdbc.Gravar> achadas = new ArrayList<>();
        for (MotorDePreco.Produto produto : produtos) {
            MotorDePreco.avaliar(produto, margemMinima)
                    // A explicação da IA entra em RT-EST-011; até lá o racional da conta responde.
                    .ifPresent(sugestao -> achadas.add(
                            new SugestoesDePrecoJdbc.Gravar(sugestao, null)));
        }
        sugestoes.substituir(achadas, relogio.agora());

        log.info("Sugestões de preço: {} geradas de {} produtos avaliados", achadas.size(),
                produtos.size());
        return achadas.size();
    }
}
