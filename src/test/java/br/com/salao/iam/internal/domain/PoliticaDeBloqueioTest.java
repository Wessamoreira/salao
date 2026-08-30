package br.com.salao.iam.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Domínio puro: a política de bloqueio é testável sem banco, sem Spring e sem esperar o relógio. */
class PoliticaDeBloqueioTest {

    private static final Instant AGORA = Instant.parse("2026-08-29T12:00:00Z");

    @Test
    @DisplayName("as primeiras falhas não bloqueiam: errar a senha é normal")
    void primeiras_falhas_nao_bloqueiam() {
        for (int falhas = 1; falhas <= PoliticaDeBloqueio.FALHAS_ANTES_DO_BLOQUEIO; falhas++) {
            assertThat(PoliticaDeBloqueio.bloqueioApos(falhas, AGORA))
                    .as("falha %d", falhas)
                    .isNull();
        }
    }

    @Test
    @DisplayName("o bloqueio dobra a cada falha seguinte")
    void bloqueio_progressivo() {
        var primeiro = PoliticaDeBloqueio.bloqueioApos(5, AGORA);
        var segundo = PoliticaDeBloqueio.bloqueioApos(6, AGORA);
        var terceiro = PoliticaDeBloqueio.bloqueioApos(7, AGORA);

        assertThat(Duration.between(AGORA, primeiro)).isEqualTo(Duration.ofSeconds(30));
        assertThat(Duration.between(AGORA, segundo)).isEqualTo(Duration.ofMinutes(1));
        assertThat(Duration.between(AGORA, terceiro)).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("o bloqueio tem teto, para não virar negação de serviço contra o usuário")
    void bloqueio_tem_teto() {
        // Sem teto, um ataque contra o e-mail de alguém deixaria essa pessoa permanentemente
        // fora do sistema — o ataque falharia em entrar e teria sucesso em derrubar.
        assertThat(Duration.between(AGORA, PoliticaDeBloqueio.bloqueioApos(50, AGORA)))
                .isEqualTo(PoliticaDeBloqueio.BLOQUEIO_MAXIMO);
        assertThat(Duration.between(AGORA, PoliticaDeBloqueio.bloqueioApos(1_000_000, AGORA)))
                .as("não estoura nem inverte o sinal com contagem absurda")
                .isEqualTo(PoliticaDeBloqueio.BLOQUEIO_MAXIMO);
    }

    @Test
    void bloqueio_expirado_nao_bloqueia_mais() {
        Instant passado = AGORA.minus(Duration.ofSeconds(1));

        assertThat(PoliticaDeBloqueio.estaBloqueado(passado, AGORA)).isFalse();
        assertThat(PoliticaDeBloqueio.estaBloqueado(AGORA.plusSeconds(1), AGORA)).isTrue();
        assertThat(PoliticaDeBloqueio.estaBloqueado(null, AGORA)).isFalse();
    }
}
