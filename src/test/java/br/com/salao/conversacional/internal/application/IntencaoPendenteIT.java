package br.com.salao.conversacional.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.agenda.api.OrigemDoAgendamento;
import br.com.salao.agenda.internal.application.CriarAgendamentoUseCase;
import br.com.salao.agenda.internal.application.NovoAgendamento;
import br.com.salao.catalogo.api.TipoDeBloco;
import br.com.salao.catalogo.internal.application.CadastrarServicoUseCase;
import br.com.salao.catalogo.internal.application.EtapaBruta;
import br.com.salao.cliente.internal.application.CadastrarClienteUseCase;
import br.com.salao.cliente.internal.application.DadosDoCliente;
import br.com.salao.conversacional.internal.application.ModeloDeLinguagem.ChamadaDeFerramenta;
import br.com.salao.conversacional.internal.application.ModeloDeLinguagem.FerramentaOferecida;
import br.com.salao.conversacional.internal.application.ModeloDeLinguagem.Resposta;
import br.com.salao.conversacional.internal.application.ModeloDeLinguagem.Texto;
import br.com.salao.equipe.api.TipoDeVinculo;
import br.com.salao.equipe.internal.application.CadastrarProfissionalUseCase;
import br.com.salao.equipe.internal.application.DadosDoProfissional;
import br.com.salao.equipe.internal.application.DefinirJornadaUseCase;
import br.com.salao.equipe.internal.application.PeriodoBruto;
import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.tenant.TenantContext;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.node.ObjectNode;

/**
 * RT-CNV-005 — marcar pelo agente, de ponta a ponta, com as ferramentas REAIS da agenda e da busca de
 * clientes, e o modelo falso roteirizado.
 *
 * <p>O que se prova: o modelo nunca grava; a pessoa grava respondendo com o código, sem o modelo; e
 * cada caminho torto — código errado, vencido, trocado, repetido, simultâneo, de outra pessoa, de
 * outro salão, com o horário tomado ou a permissão retirada no meio — termina sem gravação e com o
 * motivo registrado.
 */
class IntencaoPendenteIT extends AbstractAgenteIT {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate DIA = LocalDate.now(SAO_PAULO).plusDays(7);
    private static final String[] CADASTRO = { Permissao.USUARIO_GERENCIAR,
            Permissao.CONFIG_GERENCIAR, Permissao.CLIENTE_ESCREVER, Permissao.AGENDA_LER_TODAS,
            Permissao.AGENDA_ESCREVER_TODAS };

    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private DefinirJornadaUseCase definirJornada;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private CriarAgendamentoUseCase criarAgendamento;
    @Autowired private ConfirmarPeloCodigo confirmarPeloCodigo;

    private record Cenario(Salao salao, String bia, UUID ana, UUID marilda, UUID corte) {
        UUID tenant() {
            return salao.tenant();
        }
    }

    private Cenario cenario() {
        return cenario(salao());
    }

    private Cenario cenario(Salao salao) {
        String bia = pessoa(salao, Perfil.RECEPCAO);
        return TenantContext.obter(salao.tenant(), () -> com(() -> {
            UUID ana = cadastrarProfissional.executar(new DadosDoProfissional("Ana", null,
                    "11144477735", TipoDeVinculo.EMPREGADO, null, null, null, null, null, null))
                    .id();
            definirJornada.executar(ana, Map.of(DIA.getDayOfWeek(),
                    List.of(new PeriodoBruto(LocalTime.of(9, 0), LocalTime.of(18, 0)))));
            UUID marilda = cadastrarCliente.executar(
                    new DadosDoCliente("Marilda", null, null, null, null));
            UUID corte = cadastrarServico.executar("Corte", null,
                    List.of(new EtapaBruta(TipoDeBloco.ATIVO, 30))).id();
            return new Cenario(salao, bia, ana, marilda, corte);
        }, CADASTRO));
    }

    private static String quando(LocalTime inicio, LocalTime fim) {
        return DateTimeFormatter.ofPattern("EEEE, dd/MM", Locale.forLanguageTag("pt-BR"))
                .format(DIA) + ", das " + inicio + " às " + fim;
    }

    private ChamadaDeFerramenta agendar(String id, Cenario c, LocalTime hora) {
        ObjectNode argumentos = json.createObjectNode()
                .put("cliente_id", c.marilda().toString())
                .put("servico_id", c.corte().toString())
                .put("profissional_id", c.ana().toString())
                .put("inicio", DIA.atTime(hora).toString());
        return new ChamadaDeFerramenta(id, "agendar", argumentos);
    }

    /** A recepção pede, o modelo chama agendar e responde; devolve a intenção que ficou. */
    private Map<String, Object> pedirMarcacao(Cenario c, String wamid, LocalTime hora)
            throws SQLException {
        naFila(c.salao(), c.bia(), wamid, "marca corte da Marilda com a Ana as " + hora);
        modelo.roteiro.add(new Resposta(List.of(agendar("tu_1", c, hora)), 300, 40));
        modelo.roteiro.add(new Resposta(List.of(new Texto("Preparei. Confirme pelo código.")),
                300, 30));
        processador.executar();
        return intencaoDa(c.tenant(), wamid);
    }

    private void responder(Cenario c, String telefone, String wamid, String texto) {
        naFila(c.salao(), telefone, wamid, texto);
        processador.executar();
    }

    private Map<String, Object> intencaoDa(UUID tenant, String wamid) throws SQLException {
        var linha = new HashMap<String, Object>();
        var conexao = comoApp(tenant);
        try (var ps = conexao.prepareStatement("""
                select i.status, i.codigo, i.resumo, i.pedido_de_confirmacao, i.motivo, i.resposta,
                       i.comando::text as comando, i.resultado::text as resultado,
                       i.usuario_efetivo_id, i.decidida_por_execucao_id
                  from intencao_pendente i
                  join execucao_do_agente e on e.id = i.execucao_id
                  join mensagem_recebida m on m.id = e.mensagem_id
                 where m.provedor_id = ?
                 order by i.criada_em desc
                 limit 1
                """)) {
            ps.setString(1, wamid);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    for (String coluna : List.of("status", "codigo", "resumo",
                            "pedido_de_confirmacao", "motivo", "resposta", "comando",
                            "resultado")) {
                        linha.put(coluna, rs.getString(coluna));
                    }
                    linha.put("usuario_efetivo_id", rs.getObject("usuario_efetivo_id", UUID.class));
                    linha.put("decidida_por", rs.getObject("decidida_por_execucao_id", UUID.class));
                }
            }
        } finally {
            conexao.rollback();
        }
        return linha;
    }

    private List<Map<String, Object>> agendamentos(UUID tenant) throws SQLException {
        var linhas = new ArrayList<Map<String, Object>>();
        try (var ps = comoOwner().prepareStatement("""
                select id, origem, status, criado_por from agendamento
                 where estabelecimento_id = ? order by inicio""")) {
            ps.setObject(1, tenant);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    var linha = new HashMap<String, Object>();
                    linha.put("id", rs.getObject("id", UUID.class));
                    linha.put("origem", rs.getString("origem"));
                    linha.put("status", rs.getString("status"));
                    linha.put("criado_por", rs.getObject("criado_por", UUID.class));
                    linhas.add(linha);
                }
            }
        }
        return linhas;
    }

    private Map<String, Object> trilhaDe(UUID agendamentoId) throws SQLException {
        var linha = new HashMap<String, Object>();
        try (var ps = comoOwner().prepareStatement("""
                select ator, usuario_id from auditoria
                 where entidade = 'agendamento' and entidade_id = ?""")) {
            ps.setObject(1, agendamentoId);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    linha.put("ator", rs.getString("ator"));
                    linha.put("usuario_id", rs.getObject("usuario_id", UUID.class));
                }
            }
        }
        return linha;
    }

    private static String outroCodigo(String codigo) {
        return "%04d".formatted((Integer.parseInt(codigo) + 1) % 10_000);
    }

    // ------------------------------------------------------------------ o caminho completo

    @Test
    @DisplayName("a recepção pede, o modelo prepara e nada grava; ela responde SIM com o código e o agendamento nasce — sem o modelo")
    void caminho_completo() throws SQLException {
        Cenario c = cenario();

        var intencao = pedirMarcacao(c, "wamid.1", LocalTime.of(13, 0));

        String codigo = (String) intencao.get("codigo");
        assertThat(intencao).containsEntry("status", "PENDENTE")
                .containsEntry("resumo", "Corte para Marilda com Ana, "
                        + quando(LocalTime.of(13, 0), LocalTime.of(13, 30)));
        assertThat((String) intencao.get("pedido_de_confirmacao"))
                .contains("Corte para Marilda com Ana").contains("responda SIM " + codigo);
        assertThat(agendamentos(c.tenant())).as("simular não grava").isEmpty();
        assertThat((String) execucao(c.tenant(), "wamid.1").get("ferramentas"))
                .contains("\"PROPOSTA\"");
        assertThat(modelo.pedidos.get(1).mensagens().getLast().blocos().toString())
                .as("o código não vai ao modelo").doesNotContain(codigo);
        int chamadasAoModelo = modelo.pedidos.size();

        responder(c, c.bia(), "wamid.2", "SIM " + codigo);

        assertThat(modelo.pedidos).as("a confirmação não passa pelo modelo")
                .hasSize(chamadasAoModelo);
        UUID bia = (UUID) intencao.get("usuario_efetivo_id");
        var marcados = agendamentos(c.tenant());
        assertThat(marcados).singleElement().satisfies(a -> assertThat(a)
                .containsEntry("origem", OrigemDoAgendamento.AGENTE.name())
                .containsEntry("status", "CONFIRMADO")
                .containsEntry("criado_por", bia));
        UUID agendamento = (UUID) marcados.get(0).get("id");

        var decidida = intencaoDa(c.tenant(), "wamid.1");
        assertThat(decidida).containsEntry("status", "CONFIRMADA")
                .containsEntry("decidida_por", execucao(c.tenant(), "wamid.2").get("id"));
        assertThat((String) decidida.get("resultado")).contains(agendamento.toString());
        String marcado = "Marcado: Corte para Marilda com Ana, "
                + quando(LocalTime.of(13, 0), LocalTime.of(13, 30)) + ".";
        assertThat(execucao(c.tenant(), "wamid.2")).containsEntry("status", "CONCLUIDA")
                .containsEntry("resposta", marcado);
        assertThat(trilhaDe(agendamento)).as("o agente gravou, a pedido da recepção")
                .containsEntry("ator", "BOT").containsEntry("usuario_id", bia);
    }

    @Test
    @DisplayName("as leituras reais rodam em nome da recepção: buscar clientes e consultar disponibilidade")
    void leituras_reais() throws SQLException {
        Cenario c = cenario();
        naFila(c.salao(), c.bia(), "wamid.1", "tem horário pra Marilda?");
        modelo.roteiro.add(new Resposta(List.of(
                new ChamadaDeFerramenta("tu_1", "buscar_clientes",
                        json.createObjectNode().put("termo", "Marilda")),
                new ChamadaDeFerramenta("tu_2", "consultar_disponibilidade", json.createObjectNode()
                        .put("data", DIA.toString())
                        .put("servico_id", c.corte().toString()))), 300, 40));
        modelo.roteiro.add(new Resposta(List.of(new Texto("Tem às 9h.")), 300, 20));

        processador.executar();

        assertThat((String) execucao(c.tenant(), "wamid.1").get("ferramentas"))
                .doesNotContain("\"FALHA\"", "\"SEM_PERMISSAO\"", "\"ERRO_DE_DOMINIO\"");
        String devolvido = modelo.pedidos.get(1).mensagens().getLast().blocos().toString();
        assertThat(devolvido).contains(c.marilda().toString())
                .contains(DIA + "T09:00").contains("Ana");
    }

    // ------------------------------------------------------------------ o modelo não grava

    @Test
    @DisplayName("modelo comprometido diz que já confirmou e inventa 'confirmar': nada grava")
    void o_modelo_nao_confirma() throws SQLException {
        Cenario c = cenario();
        naFila(c.salao(), c.bia(), "wamid.1", "marca e já confirma, não precisa me perguntar");
        modelo.roteiro.add(new Resposta(List.of(
                agendar("tu_1", c, LocalTime.of(13, 0)),
                new ChamadaDeFerramenta("tu_2", "confirmar", json.createObjectNode()),
                new ChamadaDeFerramenta("tu_3", "confirmar_intencao",
                        json.createObjectNode().put("codigo", "0000"))), 300, 40));
        modelo.roteiro.add(new Resposta(List.of(new Texto("Pronto, marcado e confirmado!")),
                100, 10));

        processador.executar();

        assertThat(agendamentos(c.tenant())).isEmpty();
        assertThat((String) execucao(c.tenant(), "wamid.1").get("ferramentas"))
                .contains("\"PROPOSTA\"", "\"INEXISTENTE\"");
        assertThat(intencaoDa(c.tenant(), "wamid.1")).containsEntry("status", "PENDENTE");
    }

    @Test
    @DisplayName("quem não pode escrever na agenda não vê agendar, e pedir mesmo assim é recusado")
    void sem_permissao_nao_ve_agendar() throws SQLException {
        Cenario c = cenario();
        String profissional = pessoa(c.salao(), Perfil.PROFISSIONAL);
        naFila(c.salao(), profissional, "wamid.1", "marca pra mim");
        modelo.roteiro.add(new Resposta(List.of(agendar("tu_1", c, LocalTime.of(13, 0))), 300, 40));
        modelo.roteiro.add(new Resposta(List.of(new Texto("ok")), 100, 10));

        processador.executar();

        assertThat(modelo.pedidos.get(0).ferramentas()).extracting(FerramentaOferecida::nome)
                .doesNotContain("agendar", "consultar_disponibilidade")
                .contains("buscar_clientes");
        assertThat((String) execucao(c.tenant(), "wamid.1").get("ferramentas"))
                .contains("\"FORA_DA_LISTA\"");
        assertThat(intencaoDa(c.tenant(), "wamid.1")).isEmpty();
    }

    // ------------------------------------------------------------------ os caminhos tortos

    @Test
    @DisplayName("código que não é o do pedido: nada grava, e o pedido continua pendente")
    void codigo_errado() throws SQLException {
        Cenario c = cenario();
        String codigo = (String) pedirMarcacao(c, "wamid.1", LocalTime.of(13, 0)).get("codigo");

        responder(c, c.bia(), "wamid.2", "SIM " + outroCodigo(codigo));

        assertThat(execucao(c.tenant(), "wamid.2")).containsEntry("resposta",
                "Não encontrei pedido com o código " + outroCodigo(codigo) + ". Nada foi gravado.");
        assertThat(intencaoDa(c.tenant(), "wamid.1")).containsEntry("status", "PENDENTE");
        assertThat(agendamentos(c.tenant())).isEmpty();
    }

    @Test
    @DisplayName("confirmação depois da validade: EXPIRADA, nada grava")
    void vencida() throws SQLException {
        Cenario c = cenario();
        String codigo = (String) pedirMarcacao(c, "wamid.1", LocalTime.of(13, 0)).get("codigo");
        try (var ps = comoOwner().prepareStatement("""
                update intencao_pendente
                   set criada_em = now() - interval '20 minutes',
                       expira_em = now() - interval '1 minute'
                 where estabelecimento_id = ?""")) {
            ps.setObject(1, c.tenant());
            ps.executeUpdate();
        }

        responder(c, c.bia(), "wamid.2", "SIM " + codigo);

        assertThat(intencaoDa(c.tenant(), "wamid.1")).containsEntry("status", "EXPIRADA");
        assertThat((String) execucao(c.tenant(), "wamid.2").get("resposta")).contains("venceu");
        assertThat(agendamentos(c.tenant())).isEmpty();
    }

    @Test
    @DisplayName("o pedido novo substitui o anterior: o código antigo não grava mais, o novo grava")
    void nova_proposta_substitui() throws SQLException {
        Cenario c = cenario();
        String antigo = (String) pedirMarcacao(c, "wamid.1", LocalTime.of(13, 0)).get("codigo");
        String novo = (String) pedirMarcacao(c, "wamid.2", LocalTime.of(15, 0)).get("codigo");

        assertThat(novo).as("o sorteio não repete o código recente").isNotEqualTo(antigo);
        assertThat(intencaoDa(c.tenant(), "wamid.1")).containsEntry("status", "SUBSTITUIDA");
        assertThat(intencaoDa(c.tenant(), "wamid.2")).containsEntry("status", "PENDENTE");

        responder(c, c.bia(), "wamid.3", "SIM " + antigo);

        assertThat((String) execucao(c.tenant(), "wamid.3").get("resposta"))
                .contains("trocado por um mais novo");
        assertThat(agendamentos(c.tenant())).isEmpty();

        responder(c, c.bia(), "wamid.4", "sim, " + novo);

        assertThat(agendamentos(c.tenant())).hasSize(1);
        assertThat((String) execucao(c.tenant(), "wamid.4").get("resposta"))
                .contains("das 15:00 às 15:30");
    }

    @Test
    @DisplayName("SIM repetido: um agendamento só, e a segunda resposta diz que já estava confirmado")
    void confirmacao_repetida() throws SQLException {
        Cenario c = cenario();
        String codigo = (String) pedirMarcacao(c, "wamid.1", LocalTime.of(13, 0)).get("codigo");

        responder(c, c.bia(), "wamid.2", "SIM " + codigo);
        responder(c, c.bia(), "wamid.3", "SIM " + codigo);

        assertThat(agendamentos(c.tenant())).hasSize(1);
        assertThat(execucao(c.tenant(), "wamid.3"))
                .containsEntry("resposta", "O pedido " + codigo + " já estava confirmado.");
    }

    @Test
    @DisplayName("duas confirmações ao mesmo tempo: um agendamento só, e a segunda sabe que já estava confirmado")
    void confirmacoes_simultaneas() throws Exception {
        Cenario c = cenario();
        var intencao = pedirMarcacao(c, "wamid.1", LocalTime.of(13, 0));
        UUID execucaoId = (UUID) execucao(c.tenant(), "wamid.1").get("id");
        UUID bia = (UUID) intencao.get("usuario_efetivo_id");
        String codigo = (String) intencao.get("codigo");

        var largada = new CountDownLatch(1);
        var desfechos = new ConcurrentLinkedQueue<ConfirmarPeloCodigo.Desfecho>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futuros = new ArrayList<Future<?>>();
            for (int i = 0; i < 2; i++) {
                futuros.add(pool.submit(() -> {
                    largada.await();
                    desfechos.add(TenantContext.obter(c.tenant(), () -> confirmarPeloCodigo
                            .executar(c.bia(), bia, execucaoId, codigo)).desfecho());
                    return null;
                }));
            }
            largada.countDown();
            for (var futuro : futuros) {
                futuro.get(30, TimeUnit.SECONDS);
            }
        }

        // A trava da linha enfileira as duas: a segunda encontra CONFIRMADA. Sem ela, as duas
        // gravariam — e a segunda perderia para a exclusion constraint como RECUSADA.
        assertThat(desfechos).containsExactlyInAnyOrder(ConfirmarPeloCodigo.Desfecho.CONFIRMADA,
                ConfirmarPeloCodigo.Desfecho.JA_DECIDIDA);
        assertThat(agendamentos(c.tenant())).hasSize(1);
    }

    @Test
    @DisplayName("o horário foi tomado entre o resumo e o SIM: RECUSADA com o motivo, e o agente não grava")
    void horario_tomado_no_meio() throws SQLException {
        Cenario c = cenario();
        String codigo = (String) pedirMarcacao(c, "wamid.1", LocalTime.of(13, 0)).get("codigo");
        TenantContext.executar(c.tenant(), () -> com(() -> criarAgendamento.executar(
                new NovoAgendamento(c.marilda(), c.ana(), c.corte(),
                        DIA.atTime(13, 0).atZone(SAO_PAULO).toInstant(),
                        OrigemDoAgendamento.PAINEL, null, null, false, false, null, null)),
                CADASTRO));

        responder(c, c.bia(), "wamid.2", "SIM " + codigo);

        assertThat(intencaoDa(c.tenant(), "wamid.1")).containsEntry("status", "RECUSADA")
                .containsEntry("motivo", "ER-AGD-CONFLITO_HORARIO");
        assertThat(execucao(c.tenant(), "wamid.2")).containsEntry("resposta",
                "Não deu para confirmar: quem vai atender já tem atendimento nesse horário. "
                        + "Nada foi gravado.");
        assertThat(agendamentos(c.tenant())).singleElement()
                .satisfies(a -> assertThat(a).containsEntry("origem", "PAINEL"));
    }

    @Test
    @DisplayName("a permissão foi retirada entre o resumo e o SIM: nada grava")
    void permissao_retirada_no_meio() throws SQLException {
        Cenario c = cenario();
        String codigo = (String) pedirMarcacao(c, "wamid.1", LocalTime.of(13, 0)).get("codigo");
        try (var ps = comoOwner().prepareStatement(
                "update usuario set perfil = 'PROFISSIONAL' where telefone = ?")) {
            ps.setString(1, c.bia());
            ps.executeUpdate();
        }

        responder(c, c.bia(), "wamid.2", "SIM " + codigo);

        assertThat(intencaoDa(c.tenant(), "wamid.1")).containsEntry("status", "RECUSADA")
                .containsEntry("motivo", "SEM_PERMISSAO");
        assertThat(agendamentos(c.tenant())).isEmpty();
    }

    @Test
    @DisplayName("outra pessoa da equipe com o código da Bia: não encontra, nada grava")
    void outra_pessoa_nao_confirma() throws SQLException {
        Cenario c = cenario();
        String codigo = (String) pedirMarcacao(c, "wamid.1", LocalTime.of(13, 0)).get("codigo");
        String carla = pessoa(c.salao(), Perfil.RECEPCAO);

        responder(c, carla, "wamid.2", "SIM " + codigo);

        assertThat((String) execucao(c.tenant(), "wamid.2").get("resposta"))
                .startsWith("Não encontrei pedido");
        assertThat(intencaoDa(c.tenant(), "wamid.1")).containsEntry("status", "PENDENTE");
        assertThat(agendamentos(c.tenant())).isEmpty();
    }

    @Test
    @DisplayName("o número passou a outra pessoa entre o resumo e o SIM: não encontra, nada grava")
    void telefone_trocou_de_dono() throws SQLException {
        // A busca é pelo telefone, então só o dono NOVO do mesmo número chega à intenção. É o caso que
        // a comparação com o usuário efetivo protege — outra pessoa, com outro número, nem a encontra.
        Cenario c = cenario();
        var intencao = pedirMarcacao(c, "wamid.1", LocalTime.of(13, 0));
        try (var ps = comoOwner().prepareStatement(
                "update usuario set telefone = null where telefone = ?")) {
            ps.setString(1, c.bia());
            ps.executeUpdate();
        }
        pessoa(c.salao(), Perfil.RECEPCAO, c.bia());

        responder(c, c.bia(), "wamid.2", "SIM " + intencao.get("codigo"));

        var resposta = execucao(c.tenant(), "wamid.2");
        assertThat(resposta.get("usuario_efetivo_id")).as("quem respondeu é a nova dona do número")
                .isNotEqualTo(intencao.get("usuario_efetivo_id"));
        assertThat((String) resposta.get("resposta")).startsWith("Não encontrei pedido");
        assertThat(intencaoDa(c.tenant(), "wamid.1")).containsEntry("status", "PENDENTE");
        assertThat(agendamentos(c.tenant())).isEmpty();
    }

    @Test
    @DisplayName("o mesmo telefone em outro salão não alcança a intenção deste")
    void outro_salao() throws SQLException {
        Cenario c = cenario();
        String codigo = (String) pedirMarcacao(c, "wamid.1", LocalTime.of(13, 0)).get("codigo");
        Salao outro = salao("6666666666");
        pessoa(outro, Perfil.RECEPCAO, c.bia());

        naFila(outro, c.bia(), "wamid.2", "SIM " + codigo);
        processador.executar();

        assertThat((String) execucao(outro.tenant(), "wamid.2").get("resposta"))
                .startsWith("Não encontrei pedido");
        assertThat(intencaoDa(c.tenant(), "wamid.1")).containsEntry("status", "PENDENTE");
        assertThat(agendamentos(c.tenant())).isEmpty();
        assertThat(agendamentos(outro.tenant())).isEmpty();
    }

    // ------------------------------------------------------------------ o que o banco garante

    @Test
    @DisplayName("a aplicação não reescreve o que foi proposto, nem apaga")
    void proposto_e_imutavel() throws SQLException {
        Cenario c = cenario();
        pedirMarcacao(c, "wamid.1", LocalTime.of(13, 0));

        for (String sql : List.of("update intencao_pendente set comando = '{}'::jsonb",
                "update intencao_pendente set resumo = 'outra coisa'",
                "update intencao_pendente set codigo = '0000'",
                "update intencao_pendente set usuario_efetivo_id = gen_random_uuid()",
                "delete from intencao_pendente")) {
            var conexao = comoApp(c.tenant());
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
