package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.api.ErrosDoFinanceiro;
import br.com.salao.financeiro.api.FechamentoDoProfissional;
import br.com.salao.financeiro.internal.infra.FechamentosJdbc;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.erro.ErroDeDominio;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-006 — os contracheques já emitidos.
 *
 * <p>É o que responde <em>"quanto eu recebi em março?"</em> sem refazer conta nenhuma: os números
 * estão congelados no documento, e não são recalculados.
 */
public class ListarFechamentosUseCase {

    private final FechamentosJdbc fechamentos;

    public ListarFechamentosUseCase(FechamentosJdbc fechamentos) {
        this.fechamentos = fechamentos;
    }

    // Falha fechada enquanto usuário ainda não tem vínculo com profissional. Aceitar :own aqui
    // permitiria escolher qualquer profissionalId — vazamento de salário por IDOR.
    @PreAuthorize("hasAuthority('" + Permissao.FINANCEIRO_LER_TODOS + "')")
    @Transactional(readOnly = true)
    public List<FechamentoDoProfissional> executar(UUID profissionalId, LocalDate de,
                                                   LocalDate ate) {
        if (de == null || ate == null || ate.isBefore(de)) {
            throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                    "informe um período com início antes do fim");
        }
        return fechamentos.doPeriodo(profissionalId, de, ate);
    }
}
