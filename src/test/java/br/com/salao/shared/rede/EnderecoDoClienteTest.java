package br.com.salao.shared.rede;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EnderecoDoClienteTest {

    private HttpServletRequest requisicao(String remoto, String encaminhado) {
        var r = mock(HttpServletRequest.class);
        when(r.getRemoteAddr()).thenReturn(remoto);
        when(r.getHeader("X-Forwarded-For")).thenReturn(encaminhado);
        return r;
    }

    @Test
    @DisplayName("sem proxy declarado, o cabeçalho do cliente é IGNORADO")
    void ignora_cabecalho_por_padrao() {
        // O cabeçalho é enviado pelo cliente. Confiar nele sem proxy à frente daria a
        // qualquer um um IP novo por requisição — buckets infinitos, limite inexistente,
        // e nada indicando que parou de funcionar.
        var endereco = new EnderecoDoCliente(false);

        assertThat(endereco.de(requisicao("203.0.113.7", "1.2.3.4")))
                .isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("atrás de proxy, usa o primeiro da cadeia — o cliente original")
    void usa_o_primeiro_da_cadeia() {
        var endereco = new EnderecoDoCliente(true);

        assertThat(endereco.de(requisicao("10.0.0.1", "203.0.113.7, 10.0.0.9")))
                .isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("cabeçalho ausente ou vazio cai no endereço da conexão")
    void sem_cabecalho_usa_a_conexao() {
        var endereco = new EnderecoDoCliente(true);

        assertThat(endereco.de(requisicao("10.0.0.1", null))).isEqualTo("10.0.0.1");
        assertThat(endereco.de(requisicao("10.0.0.1", "  "))).isEqualTo("10.0.0.1");
    }
}
