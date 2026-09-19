package br.com.salao.conversacional.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.conversacional.internal.application.ModeloDeLinguagem.ChamadaDeFerramenta;
import br.com.salao.conversacional.internal.application.ModeloDeLinguagem.Resposta;
import br.com.salao.conversacional.internal.application.ModeloDeLinguagem.Texto;
import br.com.salao.conversacional.internal.infra.DespachanteParaEquipe;
import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.tenant.TenantContext;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * RT-CNV-006 — a saída para a equipe: o que entra na fila, quando, em que ordem, e como sai.
 *
 * <p>O provedor é falso ({@code EnviadorFalso}); a fila, a reserva, a ordem por destino e as grants são
 * de verdade.
 */
class SaidaParaEquipeIT extends AbstractAgenteIT {

    @Autowired private DespachanteParaEquipe despachante;

    // ------------------------------------------------------------------ o que entra, e quando

    @Test
    @DisplayName("conversa encerrada põe a resposta na saída, para quem escreveu")
    void resposta_na_saida() throws SQLException {
        Salao salao = salao();
        String bia = pessoa(salao, Perfil.RECEPCAO);
        naFila(salao, bia, "wamid.1", "oi");
        modelo.roteiro.add(new Resposta(List.of(new Texto("Oi, Bia!")), 100, 10));

        processador.executar();

        assertThat(saida(salao.tenant())).singleElement().satisfies(m -> assertThat(m)
                .containsEntry("destino", bia)
                .containsEntry("motivo", "RESPOSTA")
                .containsEntry("texto", "Oi, Bia!")
                .containsEntry("status", "PENDENTE")
                .containsEntry("origem_id", execucao(salao.tenant(), "wamid.1").get("id")));
    }

    @Test
    @DisplayName("o pedido de confirmação sai DEPOIS da resposta do modelo")
    void pedido_depois_da_resposta() throws SQLException {
        Salao salao = salao();
        String bia = pessoa(salao, Perfil.RECEPCAO);
        naFila(salao, bia, "wamid.1", "cancela tudo");
        modelo.roteiro.add(pedindo("cancelar_tudo"));
        modelo.roteiro.add(new Resposta(List.of(new Texto("Preparei. Confirme pelo código.")),
                100, 10));

        processador.executar();

        assertThat(saida(salao.tenant())).extracting(m -> m.get("motivo"))
                .containsExactly("RESPOSTA", "PEDIDO_DE_CONFIRMACAO");
        assertThat((String) saida(salao.tenant()).get(1).get("texto")).contains("responda SIM ");
    }

    @Test
    @DisplayName("conversa recusada: o agente não responde nada")
    void recusada_nao_responde() throws SQLException {
        Salao salao = salao();
        naFila(salao, telefoneNovo(), "wamid.1", "oi, quem é?");

        processador.executar();

        assertThat(execucao(salao.tenant(), "wamid.1")).containsEntry("status", "RECUSADA");
        // Desde RT-CNV-008 o desconhecido recebe o aviso de IA — por outro caminho, na chegada da
        // mensagem, e provado em AvisoDeIaIT. Aqui, o que importa continua: o agente não respondeu.
        assertThat(saida(salao.tenant()))
                .filteredOn(mensagem -> !"AVISO_DE_IA".equals(mensagem.get("motivo")))
                .isEmpty();
    }

    @Test
    @DisplayName("conversa que parou no teto não entrega o pedido de confirmação que chegou a propor")
    void teto_nao_entrega_pedido() throws SQLException {
        Salao salao = salao();
        String bia = pessoa(salao, Perfil.RECEPCAO);
        try (var ps = comoOwner().prepareStatement("""
                insert into consumo_de_ia (estabelecimento_id, dia, custo_confirmado_usd)
                values (?, (now() at time zone 'America/Sao_Paulo')::date, 1.70)""")) {
            ps.setObject(1, salao.tenant());
            ps.executeUpdate();
        }
        naFila(salao, bia, "wamid.1", "cancela tudo");
        // A primeira chamada cabe e custa 0,30: a segunda não cabe mais.
        modelo.roteiro.add(new Resposta(List.of(new ChamadaDeFerramenta("tu_0", "cancelar_tudo",
                json.createObjectNode())), 100_000, 0));

        processador.executar();

        assertThat(execucao(salao.tenant(), "wamid.1")).containsEntry("motivo_recusa", "TETO_DE_CUSTO");
        assertThat(saida(salao.tenant())).isEmpty();
    }

    @Test
    @DisplayName("o toque num botão não entra no histórico da conversa")
    void botao_fora_do_historico() throws SQLException {
        // O id do botão carrega o token do aceite; no histórico, iria para o modelo.
        Salao salao = salao();
        String bia = pessoa(salao, Perfil.RECEPCAO);
        naFilaBotao(salao, bia, "wamid.B", "aceite:qualquer-coisa");
        processador.executar();
        assertThat(execucao(salao.tenant(), "wamid.B")).containsEntry("status", "CONCLUIDA")
                .containsEntry("resposta", "Não encontrei esse pedido.");

        naFila(salao, bia, "wamid.T", "oi");
        processador.executar();

        assertThat(modelo.pedidos).singleElement()
                .satisfies(p -> assertThat(p.mensagens()).hasSize(1));
    }

    // ------------------------------------------------------------------ como sai

    @Test
    @DisplayName("o despachante manda na ordem por destino, e marca ENVIADA com o id do provedor")
    void em_ordem_por_destino() throws SQLException {
        Salao salao = salao();
        String bia = pessoa(salao, Perfil.RECEPCAO);
        naFila(salao, bia, "wamid.1", "cancela tudo");
        modelo.roteiro.add(pedindo("cancelar_tudo"));
        modelo.roteiro.add(new Resposta(List.of(new Texto("Preparei.")), 100, 10));
        processador.executar();

        assertThat(despachante.executar()).as("a resposta; o pedido espera a vez").isEqualTo(1);
        assertThat(despachante.executar()).as("agora o pedido").isEqualTo(1);

        assertThat(enviador.envios).extracting(EnviadorDoWhatsApp.Envio::texto)
                .first().isEqualTo("Preparei.");
        assertThat(enviador.envios.get(1).texto()).contains("responda SIM ");
        assertThat(enviador.envios).allSatisfy(envio -> {
            assertThat(envio.phoneNumberId()).isEqualTo(NUMERO);
            assertThat(envio.destino()).isEqualTo(bia);
            assertThat(envio.iniciadaPeloSalao()).isFalse();
        });
        assertThat(saida(salao.tenant())).allSatisfy(m -> {
            assertThat(m).containsEntry("status", "ENVIADA");
            assertThat((String) m.get("provedor_id")).startsWith("wamid.saida.");
        });
    }

    @Test
    @DisplayName("sem provedor, nada é reservado: a mensagem espera sem gastar tentativa")
    void sem_provedor_espera() throws SQLException {
        Salao salao = salao();
        String bia = pessoa(salao, Perfil.RECEPCAO);
        naFila(salao, bia, "wamid.1", "oi");
        processador.executar();
        enviador.configurado = false;

        assertThat(despachante.executar()).isZero();

        assertThat(saida(salao.tenant())).singleElement().satisfies(m -> assertThat(m)
                .containsEntry("status", "PENDENTE").containsEntry("tentativas", 0));
    }

    @Test
    @DisplayName("falha transitória adia com a tentativa contada; permanente falha de vez")
    void falhas() throws SQLException {
        Salao salao = salao();
        String bia = pessoa(salao, Perfil.RECEPCAO);
        String carla = pessoa(salao, Perfil.RECEPCAO);
        naFila(salao, bia, "wamid.1", "oi");
        naFila(salao, carla, "wamid.2", "oi");
        processador.executar();
        enviador.falhas.add(new FalhaTransitoria("provedor respondeu 429"));
        enviador.falhas.add(new FalhaPermanente("número não existe no WhatsApp"));

        despachante.executar();

        var linhas = saida(salao.tenant());
        assertThat(linhas).anySatisfy(m -> assertThat(m)
                .containsEntry("status", "PENDENTE").containsEntry("tentativas", 1)
                .containsEntry("ultimo_erro", "provedor respondeu 429")
                .containsEntry("adiada", true));
        assertThat(linhas).anySatisfy(m -> assertThat(m)
                .containsEntry("status", "FALHOU")
                .containsEntry("ultimo_erro", "número não existe no WhatsApp"));
        assertThat(enviador.envios).isEmpty();
    }

    @Test
    @DisplayName("canal do salão desligado: a mensagem falha, e nada sai")
    void canal_desligado() throws SQLException {
        Salao salao = salao();
        String bia = pessoa(salao, Perfil.RECEPCAO);
        naFila(salao, bia, "wamid.1", "oi");
        processador.executar();
        TenantContext.executar(salao.tenant(), () -> com(() -> {
            registrarCanal.executar(NUMERO, "551199999" + NUMERO.substring(NUMERO.length() - 4),
                    false);
            return null;
        }, Permissao.CONFIG_GERENCIAR));

        despachante.executar();

        assertThat(saida(salao.tenant())).singleElement().satisfies(m -> {
            assertThat(m).containsEntry("status", "FALHOU");
            assertThat((String) m.get("ultimo_erro")).contains("desligado");
        });
        assertThat(enviador.envios).isEmpty();
    }

    @Test
    @DisplayName("duas instâncias despachando ao mesmo tempo não mandam a mesma mensagem duas vezes")
    void reserva_atomica() throws Exception {
        Salao salao = salao();
        for (int i = 1; i <= 6; i++) {
            naFila(salao, pessoa(salao, Perfil.RECEPCAO), "wamid." + i, "oi");
        }
        processador.executar();
        enviador.esperaMillis = 50;

        var largada = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futuros = new ArrayList<Future<?>>();
            for (int i = 0; i < 2; i++) {
                futuros.add(pool.submit(() -> {
                    largada.await();
                    return despachante.executar();
                }));
            }
            largada.countDown();
            for (var futuro : futuros) {
                futuro.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(enviador.envios).hasSize(6);
        assertThat(new HashSet<>(enviador.envios.stream().map(EnviadorDoWhatsApp.Envio::destino)
                .toList())).as("seis destinos, cada um uma vez").hasSize(6);
    }

    @Test
    @DisplayName("a aplicação não reescreve nem apaga o que foi enfileirado")
    void enfileirado_e_imutavel() throws SQLException {
        Salao salao = salao();
        String bia = pessoa(salao, Perfil.RECEPCAO);
        naFila(salao, bia, "wamid.1", "oi");
        processador.executar();

        for (String sql : List.of("update mensagem_para_equipe set texto = 'outra coisa'",
                "update mensagem_para_equipe set destino = '5511900000000'",
                "update mensagem_para_equipe set status = 'ENVIADA', enviada_em = now()",
                "delete from mensagem_para_equipe")) {
            var conexao = comoApp(salao.tenant());
            try (var st = conexao.createStatement()) {
                Throwable erro = catchThrowable(() -> st.executeUpdate(sql));
                assertThat(erro).as(sql).isNotNull();
                assertThat(erro.getMessage()).containsIgnoringCase("permission denied");
            } finally {
                conexao.rollback();
            }
        }
    }
}
