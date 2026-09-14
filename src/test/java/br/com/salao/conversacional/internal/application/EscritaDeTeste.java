package br.com.salao.conversacional.internal.application;

import br.com.salao.shared.agente.FerramentaDeEscrita;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Uma ferramenta de escrita que conta simulações e confirmações. Onde quem manda é o modelo, a
 * segunda contagem tem de ficar em zero.
 */
class EscritaDeTeste implements FerramentaDeEscrita {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String nome;
    private final Set<String> permissoes;
    volatile Function<JsonNode, Simulacao> simulacao;
    final AtomicInteger simulacoes = new AtomicInteger();
    final AtomicInteger confirmacoes = new AtomicInteger();

    EscritaDeTeste(String nome, String... permissoes) {
        this.nome = nome;
        this.permissoes = Set.of(permissoes);
        this.simulacao = argumentos -> new Simulacao("resumo de " + nome,
                JSON.createObjectNode().put("alvo", "tudo"));
    }

    @Override public String nome() { return nome; }
    @Override public String descricao() { return "escrita de teste " + nome; }
    @Override public JsonNode esquemaDeEntrada() { return JSON.readTree("{\"type\":\"object\"}"); }
    @Override public Set<String> permissoesExigidas() { return permissoes; }

    @Override
    public Simulacao simular(JsonNode argumentos) {
        simulacoes.incrementAndGet();
        return simulacao.apply(argumentos);
    }

    @Override
    public Efetivacao confirmar(UUID intencaoId, JsonNode comando) {
        confirmacoes.incrementAndGet();
        return new Efetivacao("feito: " + nome, JSON.createObjectNode().put("ok", true));
    }
}
