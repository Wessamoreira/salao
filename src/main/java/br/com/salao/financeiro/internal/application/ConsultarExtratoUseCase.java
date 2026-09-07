package br.com.salao.financeiro.internal.application;

import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.equipe.api.ProfissionalResumo;
import br.com.salao.financeiro.api.ErrosDoFinanceiro;
import br.com.salao.financeiro.api.ExtratoDoProfissional;
import br.com.salao.financeiro.api.Lancamento;
import br.com.salao.financeiro.internal.infra.LancamentosJdbc;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import java.time.LocalDate;
import java.util.List;
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
                        l.valor().negado(), l.origemId()))
                .toList();
        Money aReceber = linhas.stream()
                .map(ExtratoDoProfissional.LinhaDoExtrato::valor)
                .reduce(Money.ZERO, Money::somar);

        return new ExtratoDoProfissional(profissionalId, profissional.comoChamar(), de, ate,
                aReceber, linhas);
    }
}
