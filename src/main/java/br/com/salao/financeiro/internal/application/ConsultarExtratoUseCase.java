package br.com.salao.financeiro.internal.application;

import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.equipe.api.ProfissionalResumo;
import br.com.salao.financeiro.api.ErrosDoFinanceiro;
import br.com.salao.financeiro.api.ExtratoDoProfissional;
import br.com.salao.financeiro.api.Lancamento;
import br.com.salao.financeiro.api.TipoDeLancamento;
import br.com.salao.financeiro.internal.infra.LancamentosJdbc;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-004 — o extrato de quem trabalha, item a item.
 *
 * <h2>Item a item, e não só o total</h2>
 *
 * <p>*"Recebi menos esse mês"* é a conversa que este extrato existe para encerrar. Um total sozinho
 * não a encerra: ele obriga a pessoa a acreditar. A lista mostra cada comanda, o serviço, o
 * percentual e de qual regra ele veio — e é a descrição gravada no lançamento (RT-FIN-003) que
 * carrega isso, sem consultar regra nenhuma.
 *
 * <p>Por isso o estorno aparece como linha, e não como subtração invisível: uma comanda reaberta e
 * refechada deixa seis lançamentos no razão, e esconder quatro deles faria o total não bater com
 * nada que a pessoa consiga conferir.
 *
 * <h2>Desde RT-FIN-005, o líquido mistura duas coisas</h2>
 *
 * <p>{@code aReceber} deixou de ser "quanto a pessoa ganhou": o vale entra na mesma conta,
 * negativo, e o número passou a ser "quanto ganhou menos o que já levou". Por isso o extrato abre
 * o total por tipo — sem a abertura, quem ganhou R$ 600 e adiantou R$ 550 lê "R$ 50" e conclui que
 * trabalhou por R$ 50.
 *
 * <h2>`financeiro:read:own` ainda não funciona</h2>
 *
 * <p>Ver o próprio extrato exige saber <strong>qual profissional</strong> é o usuário logado — e o
 * {@code usuario} não tem {@code profissional_id}. É a mesma lacuna que impede
 * {@code agenda:read:own} nas doze rotinas da agenda, e resolvê-la é rotina do {@code iam}.
 *
 * <p>Enquanto isso, quem tem só a permissão própria recebe uma recusa que <strong>diz o que
 * falta</strong>, em vez de um extrato vazio — que pareceria "você não tem nada a receber".
 */
public class ConsultarExtratoUseCase {

    private final LancamentosJdbc lancamentos;
    private final ProfissionalApi profissionais;

    public ConsultarExtratoUseCase(LancamentosJdbc lancamentos, ProfissionalApi profissionais) {
        this.lancamentos = lancamentos;
        this.profissionais = profissionais;
    }

    @PreAuthorize("hasAuthority('" + Permissao.FINANCEIRO_LER_TODOS + "')"
            + " or hasAuthority('" + Permissao.FINANCEIRO_LER_PROPRIO + "')")
    @Transactional(readOnly = true)
    public ExtratoDoProfissional executar(UUID profissionalId, LocalDate de, LocalDate ate) {
        if (de == null || ate == null || ate.isBefore(de)) {
            throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                    "informe um período com início antes do fim");
        }
        ProfissionalResumo profissional = profissionais.porId(profissionalId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                        "profissional não encontrado"));

        List<Lancamento> doPeriodo = lancamentos.daContraparte("PROFISSIONAL", profissionalId,
                de, ate);
        List<ExtratoDoProfissional.LinhaDoExtrato> linhas = doPeriodo.stream()
                .map(l -> new ExtratoDoProfissional.LinhaDoExtrato(
                        l.dataCompetencia(), l.tipo(), l.descricao(),
                        // Invertido uma vez, aqui: no razão a comissão sai do salão; na conta da
                        // pessoa ela entra.
                        l.valor().negado(), l.origemTipo(), l.origemId()))
                .toList();
        Money aReceber = linhas.stream()
                .map(ExtratoDoProfissional.LinhaDoExtrato::valor)
                .reduce(Money.ZERO, Money::somar);

        return new ExtratoDoProfissional(profissionalId, profissional.comoChamar(), de, ate,
                aReceber, totaisPorTipo(linhas), linhas);
    }

    /**
     * O mesmo líquido, aberto por tipo.
     *
     * <p>Existe desde RT-FIN-005, porque o vale mudou a natureza do total: antes {@code aReceber}
     * era "quanto ela ganhou"; agora é "quanto ela ganhou menos o que já levou". Sem a abertura,
     * quem ganhou R$ 600 e adiantou R$ 550 lê "R$ 50" e conclui que trabalhou por R$ 50 — que é a
     * mesma conversa que esta rotina existe para encerrar, voltando pela outra porta.
     *
     * <p>{@code LinkedHashMap} para a ordem ser a de aparição no período, e não a do {@code enum}
     * nem a do {@code hashCode}: quem lê está conferindo contra uma lista, na ordem dela.
     */
    private List<ExtratoDoProfissional.TotalPorTipo> totaisPorTipo(
            List<ExtratoDoProfissional.LinhaDoExtrato> linhas) {
        Map<TipoDeLancamento, Money> soma = new LinkedHashMap<>();
        for (ExtratoDoProfissional.LinhaDoExtrato linha : linhas) {
            soma.merge(linha.tipo(), linha.valor(), Money::somar);
        }
        return soma.entrySet().stream()
                .map(e -> new ExtratoDoProfissional.TotalPorTipo(e.getKey(), e.getValue()))
                .toList();
    }
}
