package br.com.salao.notificacao.internal.infra;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Correção de RT-NOT-001 (21/09/2026) — o mensageiro de produção de hoje não entrega nada, e precisa
 * dizer isso.
 *
 * <p>Parece um teste de uma linha, e é a guarda exata do defeito: até esta data ele voltava
 * normalmente de {@code enviar}, e toda mensagem do sistema virava ENVIADA sem ter saído.
 */
class MensageiroDeRegistroTest {

    @Test
    @DisplayName("o mensageiro de registro não se diz configurado — ele não entrega nada")
    void nao_finge_que_entrega() {
        assertThat(new MensageiroDeRegistro().configurado()).isFalse();
    }
}
