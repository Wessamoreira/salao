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
import br.com.salao.conversacional.internal.application.ModeloDeLinguagem.Resposta;
import br.com.salao.conversacional.internal.application.ModeloDeLinguagem.Texto;
import br.com.salao.conversacional.internal.domain.BotaoDeAceite;
import br.com.salao.conversacional.internal.infra.ExpiradorDeAceites;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * RT-CNV-006 — o horário fora da jornada, de ponta a ponta: a recepção pede, confirma com o código, a
 * profissional aceita ou recusa pelo botão, e a recepção fica sabendo.
 *
 * <p>Ana, a profissional, NÃO tem conta de usuário — é o caso normal, e é o que prova que o aceite vale
 * pelo botão e pelo número, e não por identidade (RN-CNV-019).
 */
class AceiteDaProfissionalIT extends AbstractAgenteIT {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate DIA = LocalDate.now(SAO_PAULO).plusDays(7);
    private static final LocalTime FORA = LocalTime.of(19, 0);
    private static final String[] CADASTRO = { Permissao.USUARIO_GERENCIAR,
            Permissao.CONFIG_GERENCIAR, Permissao.CLIENTE_ESCREVER, Permissao.AGENDA_LER_TODAS,
            Permissao.AGENDA_ESCREVER_TODAS };

    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private DefinirJornadaUseCase definirJornada;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private CriarAgendamentoUseCase criarAgendamento;
    @Autowired private ExpiradorDeAceites expirador;

    private record Cenario(Salao salao, String bia, String anaTelefone, UUID ana, UUID marilda,
                           UUID corte) {
        UUID tenant() {
            return salao.tenant();
        }
    }

    private Cenario cenario(boolean anaComTelefone) {
        Salao salao = salao();
        String bia = pessoa(salao, Perfil.RECEPCAO);
        String anaTelefone = anaComTelefone ? telefoneNovo() : null;
        return TenantContext.obter(salao.tenant(), () -> com(() -> {
            UUID ana = cadastrarProfissional.executar(new DadosDoProfissional("Ana", null,
                    "11144477735", TipoDeVinculo.EMPREGADO, anaTelefone, null, null, null, null,
                    null)).id();
            definirJornada.executar(ana, Map.of(DIA.getDayOfWeek(),
                    List.of(new PeriodoBruto(LocalTime.of(9, 0), LocalTime.of(18, 0)))));
            UUID marilda = cadastrarCliente.executar(
                    new DadosDoCliente("Marilda", null, null, null, null));
            UUID corte = cadastrarServico.executar("Corte", null,
                    List.of(new EtapaBruta(TipoDeBloco.ATIVO, 30))).id();
            return new Cenario(salao, bia, anaTelefone, ana, marilda, corte);
        }, CADASTRO));
    }

    private ChamadaDeFerramenta agendar(Cenario c, LocalTime hora) {
        ObjectNode argumentos = json.createObjectNode()
                .put("cliente_id", c.marilda().toString())
                .put("servico_id", c.corte().toString())
                .put("profissional_id", c.ana().toString())
                .put("inicio", DIA.atTime(hora).toString());
        return new ChamadaDeFerramenta("tu_1", "agendar", argumentos);
    }

    private Map<String, Object> pedir(Cenario c, String wamid, LocalTime hora) throws SQLException {
        naFila(c.salao(), c.bia(), wamid, "marca corte da Marilda com a Ana as " + hora);
        modelo.roteiro.add(new Resposta(List.of(agendar(c, hora)), 300, 40));
        modelo.roteiro.add(new Resposta(List.of(new Texto("Preparei. Confirme pelo código.")),
                300, 30));
        processador.executar();
        return intencaoDa(c.tenant(), wamid);
    }

    private void responder(Cenario c, String telefone, String wamid, String texto) {
        naFila(c.salao(), telefone, wamid, texto);
        processador.executar();
    }

    /** Pede fora da jornada e confirma com o código: a intenção fica esperando a Ana. */
    private Map<String, Object> pedidoConfirmado(Cenario c) throws SQLException {
        var proposta = pedir(c, "wamid.1", FORA);
        responder(c, c.bia(), "wamid.2", "SIM " + proposta.get("codigo"));
        return proposta;
    }

    private void tocar(Cenario c, String telefone, String wamid, String idDoBotao) {
        naFilaBotao(c.salao(), telefone, wamid, idDoBotao);
        processador.executar();
    }

    private String botao(Cenario c, String titulo) throws SQLException {
        var pedido = mensagens(c.tenant(), c.anaTelefone(), "PEDIDO_DE_ACEITE");
        assertThat(pedido).as("o pedido de aceite foi enfileirado").hasSize(1);
        for (JsonNode botao : json.readTree((String) pedido.get(0).get("botoes"))) {
            if (titulo.equals(botao.path("titulo").asString())) {
                return botao.path("id").asString();
            }
        }
        throw new AssertionError("o pedido não tem o botão " + titulo);
    }

    private List<Map<String, Object>> mensagens(UUID tenant, String destino, String motivo)
            throws SQLException {
        return saida(tenant).stream()
                .filter(m -> destino.equals(m.get("destino")) && motivo.equals(m.get("motivo")))
                .toList();
    }

    private void vencerAceite(Cenario c) throws SQLException {
        try (var ps = comoOwner().prepareStatement("""
                update intencao_pendente set aceite_expira_em = now() - interval '1 minute'
                 where estabelecimento_id = ? and status = 'AGUARDANDO_ACEITE'""")) {
            ps.setObject(1, c.tenant());
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    private Map<String, Object> intencaoDa(UUID tenant, String wamid) throws SQLException {
        var linha = new HashMap<String, Object>();
        var conexao = comoApp(tenant);
        try (var ps = conexao.prepareStatement("""
                select i.status, i.codigo, i.pedido_de_confirmacao, i.motivo, i.resposta,
                       i.aceite_de_nome, i.aceite_de_telefone, i.aceite_token, i.usuario_efetivo_id
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
                    for (String coluna : List.of("status", "codigo", "pedido_de_confirmacao",
                            "motivo", "resposta", "aceite_de_nome", "aceite_de_telefone",
                            "aceite_token")) {
                        linha.put(coluna, rs.getString(coluna));
                    }
                    linha.put("usuario_efetivo_id", rs.getObject("usuario_efetivo_id", UUID.class));
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
                select id, origem, status from agendamento
                 where estabelecimento_id = ? order by inicio""")) {
            ps.setObject(1, tenant);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    var linha = new HashMap<String, Object>();
                    linha.put("id", rs.getObject("id", UUID.class));
                    linha.put("origem", rs.getString("origem"));
                    linha.put("status", rs.getString("status"));
                    linhas.add(linha);
                }
            }
        }
        return linhas;
    }

    private String trilhaDe(UUID agendamentoId) throws SQLException {
        try (var ps = comoOwner().prepareStatement("""
                select depois::text from auditoria
                 where entidade = 'agendamento' and entidade_id = ?""")) {
            ps.setObject(1, agendamentoId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ------------------------------------------------------------------ o caminho completo

    @Test
    @DisplayName("fora da jornada: a recepção confirma, a Ana aceita pelo botão, e só então marca")
    void caminho_completo() throws SQLException {
        Cenario c = cenario(true);

        var proposta = pedir(c, "wamid.1", FORA);

        assertThat(proposta).containsEntry("status", "PENDENTE")
                .containsEntry("aceite_de_nome", "Ana")
                .containsEntry("aceite_de_telefone", c.anaTelefone());
        assertThat((String) proposta.get("pedido_de_confirmacao"))
                .endsWith("Depois do seu SIM, Ana ainda precisa aceitar (fora da jornada).");
        assertThat(modelo.pedidos.get(1).mensagens().getLast().blocos().toString())
                .as("o modelo sabe que o SIM não basta").contains("precisa aceitar")
                .doesNotContain((String) proposta.get("aceite_token"));

        responder(c, c.bia(), "wamid.2", "SIM " + proposta.get("codigo"));

        assertThat(intencaoDa(c.tenant(), "wamid.1")).containsEntry("status", "AGUARDANDO_ACEITE");
        assertThat(agendamentos(c.tenant())).as("o SIM de quem pediu não grava").isEmpty();
        assertThat((String) execucao(c.tenant(), "wamid.2").get("resposta"))
                .startsWith("Pedi a Ana para aceitar (fora da jornada).");
        assertThat(mensagens(c.tenant(), c.anaTelefone(), "PEDIDO_DE_ACEITE")).singleElement()
                .satisfies(m -> assertThat((String) m.get("texto"))
                        .startsWith("Bia pediu um horário fora da jornada:")
                        .contains("Corte para Marilda com Ana").contains("Você aceita?"));

        tocar(c, c.anaTelefone(), "wamid.3", botao(c, BotaoDeAceite.ACEITAR));

        assertThat(intencaoDa(c.tenant(), "wamid.1")).containsEntry("status", "CONFIRMADA");
        var marcados = agendamentos(c.tenant());
        assertThat(marcados).singleElement().satisfies(a -> assertThat(a)
                .containsEntry("origem", OrigemDoAgendamento.AGENTE.name())
                .containsEntry("status", "CONFIRMADO"));
        assertThat(trilhaDe((UUID) marcados.get(0).get("id")))
                .contains("forcouForaJornada").contains("aceito por Ana pelo WhatsApp");
        assertThat(mensagens(c.tenant(), c.bia(), "DESFECHO_DO_ACEITE")).singleElement()
                .satisfies(m -> assertThat((String) m.get("texto"))
                        .startsWith("Ana aceitou. Marcado: Corte para Marilda com Ana"));
        var toque = execucao(c.tenant(), "wamid.3");
        assertThat((String) toque.get("resposta")).startsWith("Obrigado! Ficou marcado:");
        assertThat(mensagens(c.tenant(), c.anaTelefone(), "RESPOSTA")).as("a resposta à Ana sai")
                .singleElement().satisfies(m -> assertThat((String) m.get("texto"))
                        .startsWith("Obrigado! Ficou marcado:"));
        assertThat(toque).as("a gravação foi em nome de quem pediu")
                .containsEntry("usuario_efetivo_id", proposta.get("usuario_efetivo_id"));
    }

    @Test
    @DisplayName("dentro da jornada ninguém precisa aceitar")
    void dentro_da_jornada() throws SQLException {
        Cenario c = cenario(true);

        var proposta = pedir(c, "wamid.1", LocalTime.of(13, 0));

        assertThat(proposta).containsEntry("aceite_de_nome", null)
                .containsEntry("aceite_token", null);
        assertThat((String) proposta.get("pedido_de_confirmacao")).doesNotContain("aceitar");
    }

    @Test
    @DisplayName("sem telefone no cadastro não há a quem perguntar: recusa dizendo por quê")
    void sem_telefone() throws SQLException {
        Cenario c = cenario(false);

        var proposta = pedir(c, "wamid.1", FORA);

        assertThat(proposta).isEmpty();
        assertThat((String) execucao(c.tenant(), "wamid.1").get("ferramentas"))
                .contains("\"ERRO_DE_DOMINIO\"");
        assertThat(modelo.pedidos.get(1).mensagens().getLast().blocos().toString())
                .contains("não há telefone válido");
    }

    // ------------------------------------------------------------------ as decisões

    @Test
    @DisplayName("a Ana recusa: nada grava, e a recepção fica sabendo")
    void recusa() throws SQLException {
        Cenario c = cenario(true);
        pedidoConfirmado(c);

        tocar(c, c.anaTelefone(), "wamid.3", botao(c, BotaoDeAceite.RECUSAR));

        assertThat(intencaoDa(c.tenant(), "wamid.1")).containsEntry("status", "RECUSADA")
                .containsEntry("motivo", "RECUSADO_POR_QUEM_ATENDE");
        assertThat(agendamentos(c.tenant())).isEmpty();
        assertThat(mensagens(c.tenant(), c.bia(), "DESFECHO_DO_ACEITE")).singleElement()
                .satisfies(m -> assertThat((String) m.get("texto"))
                        .startsWith("Ana recusou o horário fora da jornada:")
                        .endsWith("Nada foi gravado."));
        assertThat(execucao(c.tenant(), "wamid.3"))
                .containsEntry("resposta", "Tudo bem. Avisei quem pediu.");
    }

    @Test
    @DisplayName("toque repetido: um agendamento só, e a segunda resposta diz que já foi respondido")
    void toque_repetido() throws SQLException {
        Cenario c = cenario(true);
        pedidoConfirmado(c);
        String aceitar = botao(c, BotaoDeAceite.ACEITAR);

        tocar(c, c.anaTelefone(), "wamid.3", aceitar);
        tocar(c, c.anaTelefone(), "wamid.4", aceitar);

        assertThat(agendamentos(c.tenant())).hasSize(1);
        assertThat(execucao(c.tenant(), "wamid.4"))
                .containsEntry("resposta", "Esse pedido já foi respondido.");
    }

    // ------------------------------------------------------------------ quem pode tocar

    @Test
    @DisplayName("o botão vindo de outro número não vale: não encontra, e o pedido continua esperando")
    void outro_numero() throws SQLException {
        Cenario c = cenario(true);
        pedidoConfirmado(c);

        tocar(c, telefoneNovo(), "wamid.3", botao(c, BotaoDeAceite.ACEITAR));

        assertThat(execucao(c.tenant(), "wamid.3"))
                .containsEntry("resposta", "Não encontrei esse pedido.");
        assertThat(intencaoDa(c.tenant(), "wamid.1")).containsEntry("status", "AGUARDANDO_ACEITE");
        assertThat(agendamentos(c.tenant())).isEmpty();
    }

    @Test
    @DisplayName("quem pediu tocando no botão da Ana também não vale")
    void quem_pediu_nao_aceita_por_ela() throws SQLException {
        Cenario c = cenario(true);
        pedidoConfirmado(c);

        tocar(c, c.bia(), "wamid.3", botao(c, BotaoDeAceite.ACEITAR));

        assertThat(intencaoDa(c.tenant(), "wamid.1")).containsEntry("status", "AGUARDANDO_ACEITE");
        assertThat(agendamentos(c.tenant())).isEmpty();
    }

    @Test
    @DisplayName("token forjado, do número certo: não encontra")
    void token_forjado() throws SQLException {
        Cenario c = cenario(true);
        pedidoConfirmado(c);
        var original = BotaoDeAceite.de(botao(c, BotaoDeAceite.ACEITAR)).orElseThrow();
        String forjado = new BotaoDeAceite(original.intencaoId(), "A".repeat(24), true).idDoBotao();

        tocar(c, c.anaTelefone(), "wamid.3", forjado);

        assertThat(execucao(c.tenant(), "wamid.3"))
                .containsEntry("resposta", "Não encontrei esse pedido.");
        assertThat(intencaoDa(c.tenant(), "wamid.1")).containsEntry("status", "AGUARDANDO_ACEITE");
    }

    @Test
    @DisplayName("a Ana mandando o código não confirma nada: ela não é da equipe no sistema")
    void profissional_mandando_o_codigo() throws SQLException {
        Cenario c = cenario(true);
        var proposta = pedir(c, "wamid.1", FORA);

        responder(c, c.anaTelefone(), "wamid.2", "SIM " + proposta.get("codigo"));

        assertThat(execucao(c.tenant(), "wamid.2"))
                .containsEntry("motivo_recusa", "TELEFONE_DESCONHECIDO");
        assertThat(intencaoDa(c.tenant(), "wamid.1")).containsEntry("status", "PENDENTE");
    }

    // ------------------------------------------------------------------ o tempo

    @Test
    @DisplayName("sem resposta até o prazo, o expirador vence o pedido e avisa quem pediu, uma vez")
    void vencido_pelo_expirador() throws SQLException {
        Cenario c = cenario(true);
        pedidoConfirmado(c);
        vencerAceite(c);

        assertThat(expirador.executar()).isEqualTo(1);
        assertThat(expirador.executar()).as("a segunda rodada não encontra nada").isZero();

        assertThat(intencaoDa(c.tenant(), "wamid.1")).containsEntry("status", "EXPIRADA");
        assertThat(mensagens(c.tenant(), c.bia(), "DESFECHO_DO_ACEITE")).singleElement()
                .satisfies(m -> assertThat((String) m.get("texto"))
                        .startsWith("Ana não respondeu a tempo:").endsWith("Nada foi gravado."));
        assertThat(agendamentos(c.tenant())).isEmpty();
    }

    @Test
    @DisplayName("o toque depois do prazo, antes do expirador: vence, avisa uma vez, e não grava")
    void toque_depois_do_prazo() throws SQLException {
        Cenario c = cenario(true);
        pedidoConfirmado(c);
        String aceitar = botao(c, BotaoDeAceite.ACEITAR);
        vencerAceite(c);

        tocar(c, c.anaTelefone(), "wamid.3", aceitar);
        expirador.executar();

        assertThat(execucao(c.tenant(), "wamid.3"))
                .containsEntry("resposta", "Esse pedido já tinha vencido, e nada foi gravado.");
        assertThat(intencaoDa(c.tenant(), "wamid.1")).containsEntry("status", "EXPIRADA");
        assertThat(mensagens(c.tenant(), c.bia(), "DESFECHO_DO_ACEITE")).hasSize(1);
        assertThat(agendamentos(c.tenant())).isEmpty();
    }

    // ------------------------------------------------------------------ o que o banco garante

    @Test
    @DisplayName("o banco recusa CONFIRMADA sem o aceite registrado, mesmo com a grant da aplicação")
    void confirmada_sem_aceite_e_recusada_pelo_banco() throws SQLException {
        // Nenhum caminho da aplicação faz isso hoje. A constraint existe para o dia em que um fizer.
        Cenario c = cenario(true);
        pedidoConfirmado(c);

        var conexao = comoApp(c.tenant());
        try (var st = conexao.createStatement()) {
            Throwable erro = catchThrowable(() -> st.executeUpdate("""
                    update intencao_pendente
                       set status = 'CONFIRMADA', resposta = 'marcado', resultado = '{}'::jsonb
                     where status = 'AGUARDANDO_ACEITE'"""));
            assertThat(erro).isNotNull();
            assertThat(erro.getMessage()).contains("intencao_com_aceite_so_confirma_aceita");
        } finally {
            conexao.rollback();
        }
    }

    // ------------------------------------------------------------------ a gravação ainda confere

    @Test
    @DisplayName("o horário foi tomado enquanto a Ana pensava: aceita, não grava, e os dois ficam sabendo")
    void horario_tomado_antes_do_aceite() throws SQLException {
        Cenario c = cenario(true);
        pedidoConfirmado(c);
        TenantContext.executar(c.tenant(), () -> com(() -> criarAgendamento.executar(
                new NovoAgendamento(c.marilda(), c.ana(), c.corte(),
                        DIA.atTime(FORA).atZone(SAO_PAULO).toInstant(), OrigemDoAgendamento.PAINEL,
                        null, null, true, false, "encaixe combinado no balcão", null)),
                CADASTRO));

        tocar(c, c.anaTelefone(), "wamid.3", botao(c, BotaoDeAceite.ACEITAR));

        assertThat(intencaoDa(c.tenant(), "wamid.1")).containsEntry("status", "RECUSADA")
                .containsEntry("motivo", "ER-AGD-CONFLITO_HORARIO");
        assertThat(mensagens(c.tenant(), c.bia(), "DESFECHO_DO_ACEITE")).singleElement()
                .satisfies(m -> assertThat(m).containsEntry("texto", "Ana aceitou, mas não deu "
                        + "para marcar: quem vai atender já tem atendimento nesse horário. "
                        + "Nada foi gravado."));
        assertThat(execucao(c.tenant(), "wamid.3")).containsEntry("resposta", "Obrigado. Não deu "
                + "para marcar: quem vai atender já tem atendimento nesse horário. "
                + "Avisei quem pediu.");
        assertThat(agendamentos(c.tenant())).singleElement()
                .satisfies(a -> assertThat(a).containsEntry("origem", "PAINEL"));
    }

    @Test
    @DisplayName("quem pediu perdeu a permissão antes do aceite: a Ana aceita, e nada grava")
    void permissao_retirada_de_quem_pediu() throws SQLException {
        Cenario c = cenario(true);
        pedidoConfirmado(c);
        try (var ps = comoOwner().prepareStatement(
                "update usuario set perfil = 'PROFISSIONAL' where telefone = ?")) {
            ps.setString(1, c.bia());
            ps.executeUpdate();
        }

        tocar(c, c.anaTelefone(), "wamid.3", botao(c, BotaoDeAceite.ACEITAR));

        assertThat(intencaoDa(c.tenant(), "wamid.1")).containsEntry("status", "RECUSADA")
                .containsEntry("motivo", "SEM_PERMISSAO");
        assertThat(agendamentos(c.tenant())).isEmpty();
        assertThat(mensagens(c.tenant(), c.bia(), "DESFECHO_DO_ACEITE")).singleElement()
                .satisfies(m -> assertThat((String) m.get("texto"))
                        .contains("não tem mais permissão"));
    }
}
