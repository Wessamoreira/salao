package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.api.ErrosDoFinanceiro;
import br.com.salao.financeiro.api.Vale;
import br.com.salao.financeiro.internal.infra.ValesJdbc;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.erro.ErroDeDominio;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-005 — os vales de um período.
 *
 * <p>O período é obrigatório pela mesma razão do extrato (RT-FIN-004): um padrão de "mês corrente"
 * produziria duas pessoas olhando telas com períodos diferentes, cada uma concluindo que a outra
 * está errada.
 *
 * <p>O profissional é opcional — sem ele, os vales do salão inteiro, que é a pergunta do dono
 * ("quanto saiu de adiantamento este mês?"). Os cancelados aparecem marcados, e não escondidos:
 * quem confere o caixa precisa ver que a saída existiu e foi desfeita.
 */
public class ListarValesUseCase {

    private final ValesJdbc vales;

    public ListarValesUseCase(ValesJdbc vales) {
        this.vales = vales;
    }

    @PreAuthorize("hasAuthority('" + Permissao.FINANCEIRO_LER_TODOS + "')"
            + " or hasAuthority('" + Permissao.FINANCEIRO_LER_PROPRIO + "')")
    @Transactional(readOnly = true)
    public List<Vale> executar(UUID profissionalId, LocalDate de, LocalDate ate) {
        if (de == null || ate == null || ate.isBefore(de)) {
            throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                    "informe um período com início antes do fim");
        }
        return vales.doPeriodo(profissionalId, de, ate);
    }
}
