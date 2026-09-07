package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.api.RegraDeComissao;
import br.com.salao.financeiro.internal.infra.RegrasDeComissaoJdbc;
import br.com.salao.iam.api.Permissao;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-002 — as regras, na ordem da cascata.
 *
 * <p>Ordenadas da mais específica para a mais geral porque é assim que elas se aplicam: uma lista
 * alfabética faria a tela de configuração mostrar as exceções misturadas com o padrão, e quem
 * configura precisaria reconstruir a precedência de cabeça.
 *
 * <p>Leitura exige {@code financeiro:read:all}: percentual de comissão é o contrato de todo mundo,
 * e quem só vê o próprio extrato não vê o dos outros.
 */
public class ListarRegrasDeComissaoUseCase {

    private final RegrasDeComissaoJdbc regras;

    public ListarRegrasDeComissaoUseCase(RegrasDeComissaoJdbc regras) {
        this.regras = regras;
    }

    @PreAuthorize("hasAuthority('" + Permissao.FINANCEIRO_LER_TODOS + "')")
    @Transactional(readOnly = true)
    public List<RegraDeComissao> executar() {
        return regras.todas();
    }
}
