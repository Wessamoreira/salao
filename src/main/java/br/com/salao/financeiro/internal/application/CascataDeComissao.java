package br.com.salao.financeiro.internal.application;

import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.equipe.api.ProfissionalResumo;
import br.com.salao.financeiro.api.ComissaoApi;
import br.com.salao.financeiro.api.ComissaoResolvida;
import br.com.salao.financeiro.api.EscopoDaRegra;
import br.com.salao.financeiro.api.OrigemDaComissao;
import br.com.salao.financeiro.api.RegraDeComissao;
import br.com.salao.financeiro.internal.infra.RegrasDeComissaoJdbc;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-002 — quanto esta pessoa ganha por este serviço.
 *
 * <h2>A ordem, e por que ela é esta</h2>
 *
 * <ol>
 *   <li><strong>Profissional × serviço</strong> — "a Ana faz coloração por 45%".</li>
 *   <li><strong>Serviço</strong> — "coloração paga 30%, o material é caro".</li>
 *   <li><strong>Ficha do profissional</strong> — "a Ana ganha 40% em tudo".</li>
 *   <li><strong>Padrão do salão</strong> — "a casa paga 30%".</li>
 * </ol>
 *
 * <p>O ponto discutível é o serviço vir <em>antes</em> da ficha, e a razão é que sem isso o nível
 * seria decoração: toda profissional tem percentual na ficha, então uma regra por serviço só
 * alcançaria quem estivesse com o campo vazio.
 *
 * <p>E uma regra de serviço existe justamente para proteger a margem de um serviço caro — que é
 * onde trabalham as pessoas com percentual alto. Se a ficha vencesse, a proteção falharia
 * exatamente para quem ela precisa alcançar.
 *
 * <p>Quem quiser manter os 45% da Ana na coloração tem o nível mais específico, que existe para
 * isso.
 *
 * <h2>Nada configurado devolve zero, dizendo que é por falta de regra</h2>
 *
 * <p>Zero com origem {@code SEM_REGRA} é diferente de zero com origem {@code PROFISSIONAL}: o
 * primeiro é cadastro esquecido, o segundo é decisão. Sem a distinção, <em>"por que não recebi
 * comissão?"</em> teria a mesma resposta para os dois.
 */
public class CascataDeComissao implements ComissaoApi {

    private final RegrasDeComissaoJdbc regras;
    private final ProfissionalApi profissionais;

    public CascataDeComissao(RegrasDeComissaoJdbc regras, ProfissionalApi profissionais) {
        this.regras = regras;
        this.profissionais = profissionais;
    }

    @Override
    @Transactional(readOnly = true)
    public ComissaoResolvida resolver(UUID profissionalId, UUID servicoId) {
        List<RegraDeComissao> candidatas = regras.candidatas(profissionalId, servicoId);

        // As duas primeiras vêm ordenadas do banco; basta olhar antes da ficha.
        for (RegraDeComissao regra : candidatas) {
            if (regra.escopo() == EscopoDaRegra.PROFISSIONAL_SERVICO) {
                return new ComissaoResolvida(regra.percentual(),
                        OrigemDaComissao.PROFISSIONAL_SERVICO);
            }
            if (regra.escopo() == EscopoDaRegra.SERVICO) {
                return new ComissaoResolvida(regra.percentual(), OrigemDaComissao.SERVICO);
            }
        }

        BigDecimal daFicha = profissionais.porId(profissionalId)
                .map(ProfissionalResumo::comissaoPercentual)
                .orElse(null);
        if (daFicha != null) {
            return new ComissaoResolvida(daFicha, OrigemDaComissao.PROFISSIONAL);
        }

        return candidatas.stream()
                .filter(r -> r.escopo() == EscopoDaRegra.SALAO)
                .findFirst()
                .map(r -> new ComissaoResolvida(r.percentual(), OrigemDaComissao.PADRAO_DO_SALAO))
                .orElseGet(ComissaoResolvida::semRegra);
    }
}
