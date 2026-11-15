package br.com.salao.catalogo.internal.application;

import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.shared.agente.FerramentaDoBalcao;
import br.com.salao.shared.tenant.TenantContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * RT-CNV-010 — o cardápio da parede, para quem não é da equipe.
 *
 * <p>Irmã de {@code FerramentaListarServicos}, e deliberadamente não a mesma: aquela devolve o
 * <strong>id</strong> de cada serviço, porque a equipe vai usá-lo para marcar. Aqui não há o que
 * marcar, e um id no prompt de uma conversa com desconhecido é um dado interno viajando sem motivo.
 *
 * <p>Preço nulo vira "pergunte no salão", e não some nem vira zero: um serviço sem preço cadastrado
 * é pendência do salão, e omitir a linha faria a cliente achar que ele não existe.
 */
public class FerramentaServicosDoBalcao implements FerramentaDoBalcao {

    private final ServicosDoBalcaoUseCase servicos;
    private final EstabelecimentoApi estabelecimentos;
    private final ObjectMapper json;

    public FerramentaServicosDoBalcao(ServicosDoBalcaoUseCase servicos,
                                      EstabelecimentoApi estabelecimentos, ObjectMapper json) {
        this.servicos = servicos;
        this.estabelecimentos = estabelecimentos;
        this.json = json;
    }

    @Override
    public String nome() {
        return "servicos_e_precos";
    }

    @Override
    public String descricao() {
        return "Os serviços que o salão faz, com a duração em minutos e o preço de hoje.";
    }

    @Override
    public JsonNode esquemaDeEntrada() {
        return json.readTree("""
                {"type":"object","properties":{},"additionalProperties":false}""");
    }

    @Override
    public JsonNode executar(JsonNode argumentos) {
        var fuso = estabelecimentos.fusoDe(TenantContext.obrigatorio());
        ArrayNode saida = json.createArrayNode();
        for (var servico : servicos.executar(fuso)) {
            ObjectNode linha = saida.addObject()
                    .put("nome", servico.nome())
                    .put("descricao", servico.descricao())
                    .put("duracao_minutos", servico.duracaoMinutos());
            if (servico.preco() == null) {
                linha.put("preco", "pergunte no salão");
            } else {
                linha.put("preco_reais", servico.preco().valor());
            }
        }
        return saida;
    }
}
