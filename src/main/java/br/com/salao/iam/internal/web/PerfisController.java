package br.com.salao.iam.internal.web;

import br.com.salao.iam.api.PerfilDisponivel;
import br.com.salao.iam.internal.application.ListarPerfisUseCase;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-IAM-009 — os perfis que se pode dar a alguém.
 *
 * <p>Recurso próprio, e não um campo de {@code /usuarios}: a lista não depende de usuário nenhum,
 * e pendurá-la na listagem faria toda leitura da tela carregar o catálogo de novo.
 */
@RestController
@RequestMapping("/api/v1/perfis")
public class PerfisController {

    private final ListarPerfisUseCase listar;

    public PerfisController(ListarPerfisUseCase listar) {
        this.listar = listar;
    }

    @GetMapping
    public List<PerfilDisponivel> listar() {
        return listar.executar();
    }
}
