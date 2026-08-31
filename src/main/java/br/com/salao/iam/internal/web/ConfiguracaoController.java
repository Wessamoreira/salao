package br.com.salao.iam.internal.web;

import br.com.salao.iam.api.BaseDeComissao;
import br.com.salao.iam.api.ConfiguracaoDoEstabelecimento;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.PeriodicidadeDeFechamento;
import br.com.salao.iam.internal.application.AtualizarConfiguracaoUseCase;
import br.com.salao.iam.internal.application.ConfiguracaoCommand;
import br.com.salao.shared.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-CFG-001 — a configuração do salão.
 *
 * <p>Sem {@code {id}} na rota: o estabelecimento é o do token, e aceitar um id no caminho criaria
 * um parâmetro que parece permitir escolher outro salão — o convite exato para um IDOR, ainda que
 * a RLS o barrasse.
 */
@RestController
@RequestMapping("/api/v1/configuracao")
public class ConfiguracaoController {

    private final EstabelecimentoApi estabelecimentos;
    private final AtualizarConfiguracaoUseCase atualizar;

    public ConfiguracaoController(EstabelecimentoApi estabelecimentos,
                                  AtualizarConfiguracaoUseCase atualizar) {
        this.estabelecimentos = estabelecimentos;
        this.atualizar = atualizar;
    }

    /**
     * Leitura sem {@code config:manage}: quem opera o salão precisa saber o fuso e a moeda para a
     * tela funcionar, e esconder isso não protege nada — já vai em {@code /me/capabilities}.
     */
    @GetMapping
    public ConfiguracaoDoEstabelecimento atual() {
        return estabelecimentos.configuracao(TenantContext.obrigatorio()).orElseThrow();
    }

    public record ConfiguracaoRequest(
            @NotBlank @Size(max = 200) String nome,
            @Size(max = 20) String documento,
            @NotBlank String fusoIana,
            @NotBlank @Size(min = 3, max = 3) String moeda,
            @NotNull BaseDeComissao baseComissao,
            @NotNull Boolean descontoAfetaComissao,
            @NotNull PeriodicidadeDeFechamento periodicidadeDeFechamento,
            /* Sem @Pattern: o DDD chega como "(11)" ou "011" e quem sabe normalizá-lo é o
               domínio, que também sabe recusar o que sobra. */
            @Size(max = 5) String dddPadrao) {
    }

    @PutMapping
    public ConfiguracaoDoEstabelecimento salvar(@Valid @RequestBody ConfiguracaoRequest req) {
        return atualizar.executar(new ConfiguracaoCommand(req.nome(), req.documento(),
                req.fusoIana(), req.moeda(), req.baseComissao(), req.descontoAfetaComissao(),
                req.periodicidadeDeFechamento(), req.dddPadrao()));
    }
}
