package br.com.salao.shared.erro;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.shared.tenant.TenantNaoDefinidoException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ManipuladorGlobalDeErrosTest {

    private final ManipuladorGlobalDeErros manipulador = new ManipuladorGlobalDeErros();

    @Test
    @DisplayName("erro de domínio vira Problem Details com código estável")
    void erro_de_dominio() {
        var erro = new ErroDeDominio(ErrosDaInfra.VERSAO_DESATUALIZADA,
                "O registro foi alterado por outra pessoa.",
                Map.of("versaoEsperada", 3));

        var problema = manipulador.tratarErroDeDominio(erro);

        assertThat(problema.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problema.getProperties()).containsEntry("codigo", "ER-INF-VERSAO_DESATUALIZADA");
        assertThat(problema.getProperties()).containsKey("parametros");
    }

    @Test
    @DisplayName("erro inesperado não vaza a mensagem original")
    void erro_inesperado_nao_vaza_detalhe() {
        // Stack trace e detalhe de infraestrutura são informação para quem sonda a API.
        var problema = manipulador.tratarInesperado(
                new IllegalStateException("connection to 10.0.0.4:5432 refused"));

        assertThat(problema.getStatus()).isEqualTo(500);
        assertThat(problema.getDetail()).isEqualTo("Erro interno.");
        assertThat(problema.getDetail()).doesNotContain("10.0.0.4");
    }

    @Test
    @DisplayName("tenant ausente é bug: 500, nunca mensagem amigável")
    void tenant_ausente_e_bug() {
        var problema = manipulador.tratarTenantAusente(new TenantNaoDefinidoException());

        assertThat(problema.getStatus()).isEqualTo(500);
        assertThat(problema.getProperties()).containsEntry("codigo", "ER-INF-ERRO_INTERNO");
    }
}
