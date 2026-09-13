package br.com.salao.conversacional.internal.application;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * RT-CNV-004 — o modelo de linguagem, como port.
 *
 * <p>O formato é o de uma conversa com ferramentas: mensagens alternadas, blocos de texto, pedidos de
 * ferramenta e resultados. Nenhum provedor aparece aqui; o adaptador traduz.
 *
 * <p>O modelo é tratado como COMPROMETIDO por definição. Nada do que ele devolve é executado sem
 * passar pelo orquestrador — e o orquestrador não confia no modelo para nada de segurança.
 */
public interface ModeloDeLinguagem {

    /** Falso sem credencial. O processador não reserva execução — as mensagens esperam. */
    boolean configurado();

    /** O identificador do modelo, para o registro de custo. */
    String nome();

    /**
     * @throws FalhaTransitoria quando tentar de novo pode resolver
     * @throws FalhaPermanente  quando não pode
     */
    Resposta responder(Pedido pedido);

    enum Papel {
        USUARIO,
        ASSISTENTE
    }

    sealed interface Bloco {
    }

    record Texto(String texto) implements Bloco {
    }

    /** @param argumentos vêm do modelo: entrada não confiável */
    record ChamadaDeFerramenta(String id, String nome, JsonNode argumentos) implements Bloco {
    }

    record ResultadoDeFerramenta(String idDaChamada, String conteudo, boolean erro)
            implements Bloco {
    }

    record Mensagem(Papel papel, List<Bloco> blocos) {

        public Mensagem {
            blocos = List.copyOf(blocos);
        }

        public static Mensagem doUsuario(String texto) {
            return new Mensagem(Papel.USUARIO, List.of(new Texto(texto)));
        }

        public static Mensagem doAssistente(String texto) {
            return new Mensagem(Papel.ASSISTENTE, List.of(new Texto(texto)));
        }
    }

    record FerramentaOferecida(String nome, String descricao, JsonNode esquema) {
    }

    record Pedido(String sistema, List<Mensagem> mensagens, List<FerramentaOferecida> ferramentas,
                  int maxTokens) {
    }

    record Resposta(List<Bloco> blocos, int tokensEntrada, int tokensSaida) {

        public Resposta {
            blocos = List.copyOf(blocos);
        }

        public List<ChamadaDeFerramenta> chamadas() {
            return blocos.stream()
                    .filter(ChamadaDeFerramenta.class::isInstance)
                    .map(ChamadaDeFerramenta.class::cast)
                    .toList();
        }

        public String texto() {
            return String.join("\n", blocos.stream()
                    .filter(Texto.class::isInstance)
                    .map(b -> ((Texto) b).texto())
                    .filter(t -> t != null && !t.isBlank())
                    .toList());
        }
    }
}
