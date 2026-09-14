package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.HorarioDisponivel;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.agente.ArgumentosDaFerramenta;
import br.com.salao.shared.agente.FerramentaDeLeitura;
import br.com.salao.shared.tenant.TenantContext;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * RT-CNV-005 — que horário posso oferecer, para o agente.
 *
 * <p>Chama o caso de uso da disponibilidade (RT-AGD-001), com o {@code @PreAuthorize} dele: o agente
 * esbarra na mesma tranca que a tela (RN-CNV-013).
 *
 * <h2>Hora do salão, nunca UTC</h2>
 *
 * <p>Os horários vão ao modelo como data e hora LOCAIS do salão ({@code 2026-09-17T10:00}), e é no
 * mesmo formato que {@code agendar} os recebe. Um instante em UTC pediria ao modelo converter fuso — e
 * a conversão errada é uma marcação três horas fora, com resumo e tudo.
 *
 * <h2>Uma lista com teto</h2>
 *
 * <p>Um sábado com dez profissionais de quinze em quinze minutos são centenas de horários, e cada um é
 * token pago em toda chamada seguinte da conversa. Até {@value #MAXIMO_DE_HORARIOS}, com o total real
 * e um aviso para pedir por profissional.
 */
public class FerramentaConsultarDisponibilidade implements FerramentaDeLeitura {

    static final int MAXIMO_DE_HORARIOS = 40;
    static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final ConsultarDisponibilidadeUseCase consultar;
    private final EstabelecimentoApi estabelecimentos;
    private final ObjectMapper json;

    public FerramentaConsultarDisponibilidade(ConsultarDisponibilidadeUseCase consultar,
                                              EstabelecimentoApi estabelecimentos,
                                              ObjectMapper json) {
        this.consultar = consultar;
        this.estabelecimentos = estabelecimentos;
        this.json = json;
    }

    @Override
    public String nome() {
        return "consultar_disponibilidade";
    }

    @Override
    public String descricao() {
        return "Horários livres para um serviço num dia, em data e hora do salão. Sem "
                + "profissional_id, traz os de todos que fazem o serviço, com o nome e o id de cada um.";
    }

    @Override
    public JsonNode esquemaDeEntrada() {
        return json.readTree("""
                {"type":"object",
                 "properties":{
                   "data":{"type":"string","description":"o dia, AAAA-MM-DD"},
                   "servico_id":{"type":"string","description":"id do serviço, de listar_servicos"},
                   "profissional_id":{"type":"string",
                     "description":"opcional: só os horários desta pessoa"}},
                 "required":["data","servico_id"],"additionalProperties":false}""");
    }

    @Override
    public Set<String> permissoesExigidas() {
        return Set.of(Permissao.AGENDA_LER_TODAS);
    }

    @Override
    public JsonNode executar(JsonNode argumentos) {
        LocalDate data = ArgumentosDaFerramenta.data(argumentos, "data");
        UUID servico = ArgumentosDaFerramenta.uuid(argumentos, "servico_id");
        UUID profissional = ArgumentosDaFerramenta.uuidOpcional(argumentos, "profissional_id");

        List<HorarioDisponivel> horarios = consultar.consultar(data, servico, profissional);
        ZoneId fuso = estabelecimentos.fusoDe(TenantContext.obrigatorio());

        ObjectNode saida = json.createObjectNode();
        saida.put("data", data.toString());
        saida.put("total", horarios.size());
        ArrayNode lista = saida.putArray("horarios");
        for (HorarioDisponivel horario
                : horarios.subList(0, Math.min(horarios.size(), MAXIMO_DE_HORARIOS))) {
            lista.addObject()
                    .put("inicio", LOCAL.format(horario.inicio().atZone(fuso)))
                    .put("fim", LOCAL.format(horario.fim().atZone(fuso)))
                    .put("profissional_id", horario.profissionalId().toString())
                    .put("profissional", horario.profissional());
        }
        if (horarios.size() > MAXIMO_DE_HORARIOS) {
            saida.put("aviso", "mostrando os primeiros " + MAXIMO_DE_HORARIOS
                    + "; peça por profissional_id para ver os demais");
        }
        return saida;
    }
}
