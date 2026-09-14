package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.AgendamentoResumo;
import br.com.salao.agenda.api.ErrosDaAgenda;
import br.com.salao.agenda.api.OrigemDoAgendamento;
import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.catalogo.api.ServicoResumo;
import br.com.salao.cliente.api.ClienteApi;
import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.equipe.api.ProfissionalResumo;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.agente.ArgumentosDaFerramenta;
import br.com.salao.shared.agente.FerramentaDeEscrita;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.erro.ErrosDaInfra;
import br.com.salao.shared.telefone.Telefone;
import br.com.salao.shared.telefone.TelefoneInvalidoException;
import br.com.salao.shared.tenant.TenantContext;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * RT-CNV-005 — marcar pelo agente: {@code simular} para o modelo, {@code confirmar} para a pessoa.
 *
 * <h2>As duas metades passam pelo mesmo caso de uso</h2>
 *
 * <p>{@link #simular} chama {@code CriarAgendamentoUseCase.simular}; {@link #confirmar} chama
 * {@code executar}. O mesmo {@code preparar}, o mesmo {@code @PreAuthorize}. O que foi mostrado à
 * pessoa é o que a gravação confere — e a gravação confere tudo de novo, porque o horário pode ter
 * sido tomado entre os dois.
 *
 * <h2>O resumo é do servidor</h2>
 *
 * <p>Nomes e horário saem do cadastro e do caso de uso, nunca dos argumentos do modelo. Um modelo que
 * dissesse "Maria às 10h" mandando o id da Joana e 15h produziria um resumo que diz Joana às 15h — e é
 * esse texto que a pessoa confirma.
 *
 * <h2>Origem AGENTE, sem forçar nada</h2>
 *
 * <p>Quem confirma é alguém da equipe, com o resumo diante dos olhos: nasce confirmado, como no balcão
 * (RN-AGD-013). O agente não força jornada, passado nem duração: forçar exige justificativa de quem
 * sabe por quê, e isso é da tela.
 *
 * <h2>Fora da jornada não é "não": é "pergunte a quem vai atender" — RT-CNV-006</h2>
 *
 * <p>A simulação informa a jornada em vez de recusá-la, e fora dela sai com um
 * {@linkplain Aceite aceite} pedido à profissional, pelo telefone do cadastro. O resto continua valendo
 * — conflito, recurso, passado. E {@link #confirmar} só força a jornada com a prova do aceite: a
 * justificativa que a tela exige vira "aceito por Ana pelo WhatsApp em 14/09 às 14:32", e vai para a
 * trilha.
 *
 * <h2>Sem chave de idempotência</h2>
 *
 * <p>"No máximo uma vez" é garantido por quem chama {@link #confirmar}: a intenção vira CONFIRMADA no
 * mesmo commit desta gravação, sob trava da linha (RN-CNV-016). Uma chave aqui repetiria a garantia
 * com outra tabela.
 */
public class FerramentaAgendar implements FerramentaDeEscrita {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("EEEE, dd/MM", PT_BR);
    private static final DateTimeFormatter DIA_CURTO = DateTimeFormatter.ofPattern("dd/MM", PT_BR);
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm", PT_BR);
    private static final DateTimeFormatter DATA_HORA =
            DateTimeFormatter.ofPattern("dd/MM 'às' HH:mm", PT_BR);

    private final CriarAgendamentoUseCase criar;
    private final ServicoApi servicos;
    private final ClienteApi clientes;
    private final ProfissionalApi equipe;
    private final EstabelecimentoApi estabelecimentos;
    private final ObjectMapper json;

    public FerramentaAgendar(CriarAgendamentoUseCase criar, ServicoApi servicos, ClienteApi clientes,
                             ProfissionalApi equipe, EstabelecimentoApi estabelecimentos,
                             ObjectMapper json) {
        this.criar = criar;
        this.servicos = servicos;
        this.clientes = clientes;
        this.equipe = equipe;
        this.estabelecimentos = estabelecimentos;
        this.json = json;
    }

    @Override
    public String nome() {
        return "agendar";
    }

    @Override
    public String descricao() {
        return "Prepara uma marcação para a pessoa confirmar. Não grava nada: devolve o resumo, e o "
                + "sistema pede a confirmação a ela com um código. inicio é data e hora do salão "
                + "(AAAA-MM-DDTHH:MM), como consultar_disponibilidade devolve.";
    }

    @Override
    public JsonNode esquemaDeEntrada() {
        return json.readTree("""
                {"type":"object",
                 "properties":{
                   "cliente_id":{"type":"string","description":"id da cliente, de buscar_clientes"},
                   "servico_id":{"type":"string","description":"id do serviço, de listar_servicos"},
                   "profissional_id":{"type":"string",
                     "description":"id de quem vai atender, de consultar_disponibilidade"},
                   "inicio":{"type":"string","description":"data e hora do salão, AAAA-MM-DDTHH:MM"}},
                 "required":["cliente_id","servico_id","profissional_id","inicio"],
                 "additionalProperties":false}""");
    }

    @Override
    public Set<String> permissoesExigidas() {
        return Set.of(Permissao.AGENDA_ESCREVER_TODAS);
    }

    @Override
    public Simulacao simular(JsonNode argumentos) {
        UUID cliente = ArgumentosDaFerramenta.uuid(argumentos, "cliente_id");
        UUID servico = ArgumentosDaFerramenta.uuid(argumentos, "servico_id");
        UUID profissional = ArgumentosDaFerramenta.uuid(argumentos, "profissional_id");
        ZoneId fuso = fuso();
        Instant inicio = ArgumentosDaFerramenta.dataHora(argumentos, "inicio").atZone(fuso)
                .toInstant();

        AgendamentoSimulado simulado;
        try {
            simulado = criar.simular(pedido(cliente, profissional, servico, inicio, null));
        } catch (ErroDeDominio erro) {
            throw legivel(erro);
        }
        // RT-CNV-006. Uma chamada só, e a jornada lida como dado: pegar a recusa e simular de novo
        // envenenava a transação somente leitura em que a simulação roda. Sem telefone válido não há a
        // quem perguntar, e a recusa continua sendo a da jornada — dita com o motivo.
        Aceite aceite = null;
        if (simulado.foraDaJornada()) {
            aceite = aceiteDe(profissional).orElseThrow(() -> new ErroDeDominio(
                    ErrosDaAgenda.FORA_DA_JORNADA, "o horário está fora da jornada de quem vai "
                            + "atender, e não há telefone válido no cadastro para pedir o aceite."));
        }

        ObjectNode comando = json.createObjectNode()
                .put("cliente_id", cliente.toString())
                .put("profissional_id", profissional.toString())
                .put("servico_id", servico.toString())
                .put("inicio", simulado.periodo().inicio().toString())
                .put("fora_da_jornada", aceite != null);
        return new Simulacao(resumo(simulado.servico(), cliente, profissional,
                simulado.periodo().inicio(), simulado.periodo().fim(), fuso), comando, aceite);
    }

    @Override
    public Efetivacao confirmar(UUID intencaoId, JsonNode comando,
                                Optional<AceiteRegistrado> aceite) {
        UUID cliente = ArgumentosDaFerramenta.uuid(comando, "cliente_id");
        UUID servico = ArgumentosDaFerramenta.uuid(comando, "servico_id");
        UUID profissional = ArgumentosDaFerramenta.uuid(comando, "profissional_id");
        Instant inicio = instante(comando, "inicio");

        boolean foraDaJornada = comando.path("fora_da_jornada").asBoolean(false);
        if (foraDaJornada && aceite.isEmpty()) {
            // Forçar a jornada só com a prova de que quem vai atender aceitou. Sem ela, seria a regra
            // contornada por quem não tinha como justificar.
            throw new ErroDeDominio(ErrosDaAgenda.FORA_DA_JORNADA,
                    "o horário está fora da jornada, e o aceite de quem vai atender não chegou.");
        }
        String justificativa = foraDaJornada ? justificativa(aceite.get()) : null;

        AgendamentoResumo criado;
        try {
            criado = criar.executar(pedido(cliente, profissional, servico, inicio, justificativa));
        } catch (ErroDeDominio erro) {
            throw legivel(erro);
        }

        String nomeDoServico = servicos.porId(criado.servicoId()).map(ServicoResumo::nome)
                .orElse("Atendimento");
        String mensagem = "Marcado: " + resumo(nomeDoServico, criado.clienteId(),
                criado.profissionalId(), criado.inicio(), criado.fim(), fuso()) + ".";
        ObjectNode registro = json.createObjectNode()
                .put("agendamento_id", criado.id().toString())
                .put("status", criado.status().name());
        return new Efetivacao(mensagem, registro);
    }

    /** Força a jornada se, e somente se, vier justificativa. Passado e duração, nunca. */
    private static NovoAgendamento pedido(UUID cliente, UUID profissional, UUID servico,
                                          Instant inicio, String justificativaForaDaJornada) {
        return new NovoAgendamento(cliente, profissional, servico, inicio,
                OrigemDoAgendamento.AGENTE, null, null, justificativaForaDaJornada != null, false,
                justificativaForaDaJornada, null);
    }

    /**
     * Quem vai atender, com o telefone normalizado — ou vazio, se não há a quem perguntar.
     *
     * <p>Normalizado aqui também, e não só no cadastro (RN-EQP-016): o que foi cadastrado antes da
     * normalização ficou como foi digitado.
     */
    private Optional<Aceite> aceiteDe(UUID profissionalId) {
        var profissional = equipe.porId(profissionalId).filter(ProfissionalResumo::ativo);
        if (profissional.isEmpty() || profissional.get().telefone() == null) {
            return Optional.empty();
        }
        var configuracao = estabelecimentos.configuracao(TenantContext.obrigatorio()).orElseThrow();
        try {
            String telefone = Telefone.de(profissional.get().telefone(), configuracao.paisPadrao(),
                    configuracao.dddPadrao()).e164();
            return Optional.of(new Aceite(profissional.get().comoChamar(), telefone,
                    "fora da jornada"));
        } catch (TelefoneInvalidoException e) {
            return Optional.empty();
        }
    }

    /** "Fora da jornada, aceito por Ana pelo WhatsApp (final 4321) em 14/09 às 14:32". */
    private String justificativa(AceiteRegistrado aceite) {
        String telefone = aceite.telefone();
        return "Fora da jornada, aceito por %s pelo WhatsApp (final %s) em %s".formatted(
                aceite.nome(), telefone.substring(Math.max(0, telefone.length() - 4)),
                DATA_HORA.format(aceite.em().atZone(fuso())));
    }

    private ZoneId fuso() {
        return estabelecimentos.fusoDe(TenantContext.obrigatorio());
    }

    /** O comando é nosso, gravado na simulação; fora do formato é dado corrompido, e é recusa. */
    private static Instant instante(JsonNode comando, String campo) {
        try {
            return Instant.parse(ArgumentosDaFerramenta.texto(comando, campo, 40));
        } catch (DateTimeException e) {
            throw new ErroDeDominio(ErrosDaInfra.DADOS_INVALIDOS,
                    "o pedido guardado está com o horário fora do formato.");
        }
    }

    /** "Corte para Marilda com Ana, quarta-feira, 17/09, das 13:00 às 13:30". */
    private String resumo(String servico, UUID clienteId, UUID profissionalId, Instant inicio,
                          Instant fim, ZoneId fuso) {
        String cliente = clientes.nomesDe(List.of(clienteId)).getOrDefault(clienteId, "cliente");
        String profissional = equipe.porId(profissionalId).map(ProfissionalResumo::comoChamar)
                .orElse("profissional");
        ZonedDateTime de = inicio.atZone(fuso);
        ZonedDateTime ate = fim.atZone(fuso);
        String termino = de.toLocalDate().equals(ate.toLocalDate())
                ? HORA.format(ate)
                : HORA.format(ate) + " de " + DIA_CURTO.format(ate);
        return "%s para %s com %s, %s, das %s às %s".formatted(servico, cliente, profissional,
                DIA.format(de), HORA.format(de), termino);
    }

    /**
     * O erro da agenda, dito para gente.
     *
     * <p>A mensagem técnica traz ids e instantes em UTC ("o horário 2026-09-17T13:00:00Z já passou").
     * Ela iria ao modelo, que a repetiria, e à pessoa, na resposta a uma confirmação. O código continua
     * o mesmo — é ele que fica no rastro. Erro que não é da agenda (argumento inválido) passa como
     * veio: ele nomeia o campo que o modelo precisa corrigir.
     */
    static ErroDeDominio legivel(ErroDeDominio erro) {
        if (!(erro.codigo() instanceof ErrosDaAgenda codigo)) {
            return erro;
        }
        String texto = switch (codigo) {
            case CONFLITO_HORARIO -> "quem vai atender já tem atendimento nesse horário.";
            case RECURSO_OCUPADO -> "não há recurso livre nesse horário.";
            case FORA_DA_JORNADA -> "o horário está fora da jornada de quem vai atender.";
            case DATA_PASSADA -> "o horário já passou.";
            case PROFISSIONAL_NAO_EXECUTA -> "quem vai atender não faz esse serviço.";
            case SERVICO_INATIVO -> "o serviço está fora do cardápio.";
            case SERVICO_NAO_ENCONTRADO -> "o serviço não foi encontrado.";
            case CLIENTE_NAO_ENCONTRADO -> "a cliente não foi encontrada no cadastro.";
            default -> "a agenda recusou esse pedido.";
        };
        return new ErroDeDominio(codigo, texto);
    }
}
