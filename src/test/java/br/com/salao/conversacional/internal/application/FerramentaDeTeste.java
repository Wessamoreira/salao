package br.com.salao.conversacional.internal.application;

import br.com.salao.shared.agente.FerramentaDeLeitura;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Uma ferramenta de leitura configurável, que conta quantas vezes foi de fato executada. */
class FerramentaDeTeste implements FerramentaDeLeitura {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String nome;
    private final Set<String> permissoes;
    private final Function<JsonNode, JsonNode> acao;
    final AtomicInteger execucoes = new AtomicInteger();

    FerramentaDeTeste(String nome, Function<JsonNode, JsonNode> acao, String... permissoes) {
        this.nome = nome;
        this.acao = acao;
        this.permissoes = Set.of(permissoes);
    }

    @Override public String nome() { return nome; }
    @Override public String descricao() { return "ferramenta de teste " + nome; }
    @Override public JsonNode esquemaDeEntrada() { return JSON.readTree("{\"type\":\"object\"}"); }
    @Override public Set<String> permissoesExigidas() { return permissoes; }

    @Override
    public JsonNode executar(JsonNode argumentos) {
        execucoes.incrementAndGet();
        return acao.apply(argumentos);
    }

    static JsonNode ok() {
        return JSON.readTree("{\"ok\":true}");
    }
}
