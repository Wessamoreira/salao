package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.ItemDaFichaTecnica;
import br.com.salao.estoque.internal.infra.FichasJdbc;
import br.com.salao.iam.api.Permissao;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-007 — a ficha técnica de um serviço.
 *
 * <p>Lista vazia quando o serviço não consome nada, e isso é o caso normal: corte, escova e
 * manicure não têm ficha. Ausência de ficha não é configuração pendente.
 */
public class ConsultarFichaUseCase {

    private final FichasJdbc fichas;

    public ConsultarFichaUseCase(FichasJdbc fichas) {
        this.fichas = fichas;
    }

    @PreAuthorize("hasAuthority('" + Permissao.ESTOQUE_LER + "')")
    @Transactional(readOnly = true)
    public List<ItemDaFichaTecnica> executar(UUID servicoId) {
        return fichas.doServico(servicoId);
    }
}
