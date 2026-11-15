package br.com.salao.agenda.internal.application;

import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.catalogo.api.ServicoResumo;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.shared.agente.ArgumentosDaFerramenta;
import br.com.salao.shared.agente.FerramentaDoBalcao;
import br.com.salao.shared.tenant.TenantContext;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * RT-CNV-010 — que horários estão livres, para quem não é da equipe.
 *
 * <p>Irmã de {@link FerramentaConsultarDisponibilidade}, e deliberadamente não a mesma. Três
 * diferenças, e as três têm motivo:
 *
 * <ul>
 *   <li><b>o serviço vem pelo NOME</b>, não pelo id: a cliente não tem id de nada, e pedir um ao
 *       modelo o faria inventar;
 *   <li><b>não sai id de profissional</b> nem {@code minutosDoProfissional} — quanto daquele
 *       intervalo o salão consegue revender é conta de dentro do negócio;
 *   <li><b>o teto de horários é menor</b>: a cliente escolhe entre poucas opções, e cada horário na
 *       lista é token pago em toda chamada seguinte da conversa.
 * </ul>
 */
public class FerramentaHorariosDoBalcao implements FerramentaDoBalcao {

    static final int MAXIMO_DE_HORARIOS = 12;
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private final HorariosDoBalcaoUseCase horarios;
    // A API pública do catálogo, e não o caso de uso do balcão de lá: a fronteira entre módulos é
    // `api` ou evento (CLAUDE.md), e "qual serviço se chama assim?" é pergunta de cardápio.
    private final ServicoApi catalogo;
    private final EstabelecimentoApi estabelecimentos;
    private final ObjectMapper json;

    public FerramentaHorariosDoBalcao(HorariosDoBalcaoUseCase horarios, ServicoApi catalogo,
                                      EstabelecimentoApi estabelecimentos, ObjectMapper json) {
        this.horarios = horarios;
        this.catalogo = catalogo;
        this.estabelecimentos = estabelecimentos;
        this.json = json;
    }

    @Override
    public String nome() {
        return "horarios_livres";
    }

    @Override
    public String descricao() {
        return "Horários livres para um serviço num dia. O serviço vem pelo nome, como em "
                + "servicos_e_precos. Opcionalmente, só os de uma pessoa.";
    }

    @Override
    public JsonNode esquemaDeEntrada() {
        return json.readTree("""
                {"type":"object","properties":{
                   "data":{"type":"string","description":"o dia, AAAA-MM-DD"},
                   "servico":{"type":"string","description":"o nome do serviço, como em servicos_e_precos"},
                   "com_quem":{"type":"string","description":"opcional: só os horários desta pessoa"}},
                 "required":["data","servico"],"additionalProperties":false}""");
    }

    @Override
    public JsonNode executar(JsonNode argumentos) {
        LocalDate data = ArgumentosDaFerramenta.data(argumentos, "data");
        String nomeDoServico = ArgumentosDaFerramenta.texto(argumentos, "servico", 120);
        JsonNode pessoa = argumentos == null ? null : argumentos.get("com_quem");
        String comQuem = pessoa != null && pessoa.isString() && !pessoa.asString().isBlank()
                ? pessoa.asString().strip() : null;

        UUID servicoId = catalogo.ativos().stream()
                .filter(servico -> servico.nome().equalsIgnoreCase(nomeDoServico.strip()))
                .map(ServicoResumo::id)
                .findFirst()
                .orElse(null);
        if (servicoId == null) {
            // Nome que não existe é erro do MODELO, e dizê-lo assim o faz consultar o cardápio em
            // vez de responder "não há horário" — que é o que ele diria com uma lista vazia.
            return json.createObjectNode()
                    .put("erro", "não existe serviço chamado \"" + nomeDoServico
                            + "\"; consulte servicos_e_precos");
        }

        ZoneId fuso = estabelecimentos.fusoDe(TenantContext.obrigatorio());
        var achados = comQuem == null
                ? horarios.executar(data, servicoId)
                : horarios.executar(data, servicoId, comQuem);

        ArrayNode lista = json.createArrayNode();
        achados.stream().limit(MAXIMO_DE_HORARIOS).forEach(h -> lista.addObject()
                .put("quando", LOCAL.format(h.inicio().atZone(fuso)))
                .put("com", h.profissional()));
        var saida = json.createObjectNode();
        saida.set("horarios", lista);
        saida.put("total", achados.size());
        if (achados.size() > MAXIMO_DE_HORARIOS) {
            saida.put("aviso", "há mais horários; peça por pessoa ou por período do dia");
        }
        return saida;
    }
}
