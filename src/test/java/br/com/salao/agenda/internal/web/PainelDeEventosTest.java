package br.com.salao.agenda.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.agenda.api.AgendamentoCancelado;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** RT-AGD-012 — o roteamento por tenant e a limpeza das conexões. */
class PainelDeEventosTest {

    /** Um emissor que anota o que recebeu, para o teste poder olhar. */
    private static class EmissorEspiao extends SseEmitter {
        final List<Object> recebidos = new CopyOnWriteArrayList<>();
        boolean quebrado;

        @Override
        public void send(SseEventBuilder evento) throws IOException {
            if (quebrado) {
                throw new IOException("navegador fechado");
            }
            recebidos.add(evento);
        }
    }

    private final PainelDeEventos painel = new PainelDeEventos();

    private AgendamentoCancelado cancelamentoDe(UUID tenant) {
        return new AgendamentoCancelado(tenant, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), Instant.now(), Instant.now(), true);
    }

    @Test
    @DisplayName("o painel de um salão NÃO recebe o movimento do outro")
    void tenant_separa_as_conexoes() {
        // O vazamento mais direto que este sistema poderia ter, porque apareceria numa tela
        // pendurada na parede da recepção.
        UUID salaoA = UUID.randomUUID();
        UUID salaoB = UUID.randomUUID();
        var doA = new EmissorEspiao();
        var doB = new EmissorEspiao();
        painel.conectar(salaoA, doA);
        painel.conectar(salaoB, doB);

        painel.aoCancelar(cancelamentoDe(salaoA));

        assertThat(doA.recebidos).hasSize(1);
        assertThat(doB.recebidos).as("o salão B não viu nada").isEmpty();
    }

    @Test
    @DisplayName("todos os painéis do mesmo salão recebem")
    void varios_paineis_do_mesmo_salao() {
        // O balcão e a sala de espera são duas telas da mesma recepção.
        UUID salao = UUID.randomUUID();
        var balcao = new EmissorEspiao();
        var espera = new EmissorEspiao();
        painel.conectar(salao, balcao);
        painel.conectar(salao, espera);

        painel.aoCancelar(cancelamentoDe(salao));

        assertThat(balcao.recebidos).hasSize(1);
        assertThat(espera.recebidos).hasSize(1);
    }

    @Test
    @DisplayName("salão sem painel aberto não estoura")
    void sem_conexao_nao_estoura() {
        painel.aoCancelar(cancelamentoDe(UUID.randomUUID()));

        assertThat(painel.conexoesDe(UUID.randomUUID())).isZero();
    }

    @Test
    @DisplayName("um painel desligado não derruba o aviso dos outros")
    void painel_quebrado_sai_da_lista() {
        // Navegador fechado no meio da escrita não é erro do salão: sai da lista e segue. Sem
        // isso, uma tela desligada silenciaria as que continuam ligadas.
        UUID salao = UUID.randomUUID();
        var quebrado = new EmissorEspiao();
        quebrado.quebrado = true;
        var bom = new EmissorEspiao();
        painel.conectar(salao, quebrado);
        painel.conectar(salao, bom);

        painel.aoCancelar(cancelamentoDe(salao));

        assertThat(bom.recebidos).as("o painel bom recebeu mesmo assim").hasSize(1);
        assertThat(painel.conexoesDe(salao)).as("o quebrado saiu da lista").isEqualTo(1);
    }

    @Test
    @DisplayName("desconectar a última conexão tira o salão do mapa")
    void limpeza_nao_deixa_salao_vazio_no_mapa() {
        // Sem remover a chave, o mapa cresce um salão por painel que já foi embora — e o
        // vazamento só aparece depois de meses de operação.
        //
        // O DISPARO de onCompletion/onTimeout é contrato do Spring com o container e não acontece
        // num emissor solto; o que se testa aqui é a limpeza em si, que é lógica deste código.
        UUID salao = UUID.randomUUID();
        var primeiro = new EmissorEspiao();
        var segundo = new EmissorEspiao();
        painel.conectar(salao, primeiro);
        painel.conectar(salao, segundo);

        painel.desconectar(salao, primeiro);
        assertThat(painel.conexoesDe(salao)).isEqualTo(1);

        painel.desconectar(salao, segundo);
        assertThat(painel.conexoesDe(salao)).isZero();
        assertThat(painel.salõesConectados()).isZero();
    }
}
