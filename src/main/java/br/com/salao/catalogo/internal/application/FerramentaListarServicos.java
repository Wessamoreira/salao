package br.com.salao.catalogo.internal.application;

import br.com.salao.catalogo.api.ServicoResumo;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.agente.FerramentaDeLeitura;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * RT-CNV-004 — o cardápio, para o agente.
 *
 * <p>A primeira ferramenta, e a de referência: só leitura, sem argumento, e chamando o caso de uso
 * com o {@code @PreAuthorize} dele — não o repositório, não a {@code ServicoApi}. É isso que faz o
 * agente esbarrar na mesma tranca que a tela.
 *
 * <p>Declara {@code agenda:read:all}. O caso de uso aceita também {@code agenda:read:own} e
 * {@code config:manage}, mas pelo canal o {@code :own} nunca chega (RN-IAM-021) e quem tem
 * {@code config:manage} exige segundo fator e não conversa (RN-IAM-020).
 */
public class FerramentaListarServicos implements FerramentaDeLeitura {

    private final ListarServicosUseCase listar;
    private final ObjectMapper json;

    public FerramentaListarServicos(ListarServicosUseCase listar, ObjectMapper json) {
        this.listar = listar;
        this.json = json;
    }

    @Override
    public String nome() {
        return "listar_servicos";
    }

    @Override
    public String descricao() {
        return "Lista os serviços que o salão oferece hoje, com a duração total em minutos.";
    }

    @Override
    public JsonNode esquemaDeEntrada() {
        return json.readTree("""
                {"type":"object","properties":{},"additionalProperties":false}""");
    }

    @Override
    public Set<String> permissoesExigidas() {
        return Set.of(Permissao.AGENDA_LER_TODAS);
    }

    @Override
    public JsonNode executar(JsonNode argumentos) {
        ArrayNode saida = json.createArrayNode();
        for (ServicoResumo servico : listar.executar(false)) {
            saida.addObject()
                    .put("id", servico.id().toString())
                    .put("nome", servico.nome())
                    .put("descricao", servico.descricao())
                    .put("duracao_minutos", servico.duracaoTotal());
        }
        return saida;
    }
}
