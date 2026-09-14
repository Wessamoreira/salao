package br.com.salao.cliente.internal.application;

import br.com.salao.cliente.api.ClienteResumo;
import br.com.salao.cliente.api.ResultadoDaBusca;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.agente.ArgumentosDaFerramenta;
import br.com.salao.shared.agente.FerramentaDeLeitura;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * RT-CNV-005 — encontrar a cliente, para o agente.
 *
 * <p>Sem ela, marcar pelo agente não existe: {@code agendar} pede o id, e o modelo não pode inventar
 * um. Chama {@link BuscarClientesUseCase} — com o {@code @PreAuthorize} dele —, e não a
 * {@code ClienteApi}: é o que faz o agente esbarrar na mesma tranca que a tela (RN-CNV-013).
 *
 * <h2>Para o modelo vai o mínimo para escolher</h2>
 *
 * <p>O resultado sai do sistema: vai para o provedor de LLM. Então só id, nome, apelido e os QUATRO
 * ÚLTIMOS dígitos do telefone — o que a recepção usa para distinguir duas Marias, e o mesmo recorte
 * que a busca por telefone já aceita. O número inteiro e o e-mail não têm o que fazer numa conversa,
 * e o que não sai não vaza.
 *
 * <p>Dez resultados, e não os vinte da tela: cada linha é token pago em toda chamada seguinte da
 * conversa, e uma busca que devolve dez Marias pede um termo melhor, não uma lista maior.
 */
public class FerramentaBuscarClientes implements FerramentaDeLeitura {

    static final int LIMITE = 10;
    private static final int MAXIMO_DO_TERMO = 100;

    private final BuscarClientesUseCase buscar;
    private final ObjectMapper json;

    public FerramentaBuscarClientes(BuscarClientesUseCase buscar, ObjectMapper json) {
        this.buscar = buscar;
        this.json = json;
    }

    @Override
    public String nome() {
        return "buscar_clientes";
    }

    @Override
    public String descricao() {
        return "Procura clientes do salão por parte do nome ou do apelido, ou pelos 4 últimos "
                + "dígitos do telefone. Devolve até 10, com o id que as outras ferramentas pedem.";
    }

    @Override
    public JsonNode esquemaDeEntrada() {
        return json.readTree("""
                {"type":"object",
                 "properties":{"termo":{"type":"string",
                   "description":"parte do nome ou do apelido, ou os 4 últimos dígitos do telefone"}},
                 "required":["termo"],"additionalProperties":false}""");
    }

    @Override
    public Set<String> permissoesExigidas() {
        return Set.of(Permissao.CLIENTE_LER);
    }

    @Override
    public JsonNode executar(JsonNode argumentos) {
        ResultadoDaBusca resultado = buscar.executar(
                ArgumentosDaFerramenta.texto(argumentos, "termo", MAXIMO_DO_TERMO), LIMITE);

        ObjectNode saida = json.createObjectNode();
        saida.put("interpretacao", resultado.interpretacao().name());
        if (resultado.aviso() != null) {
            saida.put("aviso", resultado.aviso());
        }
        ArrayNode clientes = saida.putArray("clientes");
        for (ClienteResumo cliente : resultado.itens()) {
            ObjectNode item = clientes.addObject()
                    .put("id", cliente.id().toString())
                    .put("nome", cliente.nome());
            if (cliente.apelido() != null && !cliente.apelido().isBlank()) {
                item.put("apelido", cliente.apelido());
            }
            String fim = finalDoTelefone(cliente.telefone());
            if (fim != null) {
                item.put("telefone_final", fim);
            }
        }
        return saida;
    }

    private static String finalDoTelefone(String telefone) {
        if (telefone == null || telefone.length() < 4) {
            return null;
        }
        return telefone.substring(telefone.length() - 4);
    }
}
