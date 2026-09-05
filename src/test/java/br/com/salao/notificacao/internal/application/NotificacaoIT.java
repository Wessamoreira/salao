package br.com.salao.notificacao.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.cliente.api.CanalDeConsentimento;
import br.com.salao.cliente.api.Finalidade;
import br.com.salao.cliente.internal.application.CadastrarClienteUseCase;
import br.com.salao.cliente.internal.application.DadosDoCliente;
import br.com.salao.cliente.internal.application.RegistrarConsentimentoUseCase;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.notificacao.api.CanalDeEnvio;
import br.com.salao.notificacao.api.Mensageiro;
import br.com.salao.notificacao.api.StatusDaMensagem;
import br.com.salao.notificacao.internal.infra.DespachanteDeMensagens;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-NOT-001 — fila, opt-out e retry. */
@WithMockUser(authorities = { Permissao.CLIENTE_ESCREVER, Permissao.USUARIO_GERENCIAR })
class NotificacaoIT extends AbstractPostgresIT {

    /**
     * Um mensageiro que registra e pode ser mandado a falhar.
     *
     * <p>Substitui o de produção pelo próprio mecanismo do port ({@code @ConditionalOnMissingBean}):
     * o teste prova, de quebra, que trocar o provedor é trocar um bean.
     */
    static class MensageiroDeTeste implements Mensageiro {
        final List<String> enviados = new CopyOnWriteArrayList<>();
        volatile int falharAsProximas;

        @Override
        public void enviar(CanalDeEnvio canal, String destino, String texto) {
            if (falharAsProximas > 0) {
                falharAsProximas--;
                throw new FalhaDeEnvio("provedor fora do ar");
            }
            enviados.add(texto);
        }
    }

    @TestConfiguration
    static class Fiacao {
        /** {@code @Primary}: é assim que um provedor real também vai passar na frente. */
        @Bean
        @Primary
        Mensageiro mensageiroDeTeste() {
            return new MensageiroDeTeste();
        }
    }

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private RegistrarConsentimentoUseCase registrarConsentimento;
    @Autowired private EnfileirarMensagemUseCase enfileirar;
    @Autowired private DespachanteDeMensagens despachante;
    @Autowired private Mensageiro mensageiro;

    private MensageiroDeTeste teste() {
        return (MensageiroDeTeste) mensageiro;
    }

    private UUID salao() {
        return provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Studio Marilda", null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    private UUID cliente(UUID tenant, String telefone) {
        return TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Marilda Souza", null, telefone, null, null)));
    }

    private Map<String, String> parametrosDoLembrete() {
        return Map.of("nome", "Marilda", "salao", "Studio Marilda",
                "quando", "amanhã às 14h", "profissional", "Ana");
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ o caminho normal

    @Test
    @DisplayName("enfileira e despacha, com o texto montado do modelo")
    void enfileira_e_despacha() {
        teste().enviados.clear();
        UUID tenant = salao();
        UUID marilda = cliente(tenant, "11987654321");

        TenantContext.executar(tenant, () -> enfileirar.enfileirar(marilda,
                Finalidade.LEMBRETE_DE_AGENDAMENTO, CanalDeEnvio.WHATSAPP,
                "lembrete-de-agendamento", parametrosDoLembrete(), UUID.randomUUID()));
        despachante.executar();

        assertThat(teste().enviados).anySatisfy(texto -> assertThat(texto)
                .contains("Marilda").contains("amanhã às 14h").contains("Ana"));
    }

    @Test
    @DisplayName("o mesmo fato não vira duas mensagens")
    void referencia_impede_duplicata() throws SQLException {
        // É o que impede o job de varredura de mandar seis lembretes do mesmo horário. Sem isto,
        // a proteção teria de estar em cada chamador.
        UUID tenant = salao();
        UUID marilda = cliente(tenant, "11987654321");
        UUID agendamento = UUID.randomUUID();

        UUID primeira = TenantContext.obter(tenant, () -> enfileirar.enfileirar(marilda,
                Finalidade.LEMBRETE_DE_AGENDAMENTO, CanalDeEnvio.WHATSAPP,
                "lembrete-de-agendamento", parametrosDoLembrete(), agendamento));
        UUID segunda = TenantContext.obter(tenant, () -> enfileirar.enfileirar(marilda,
                Finalidade.LEMBRETE_DE_AGENDAMENTO, CanalDeEnvio.WHATSAPP,
                "lembrete-de-agendamento", parametrosDoLembrete(), agendamento));

        assertThat(segunda).isEqualTo(primeira);
        assertThat(quantasMensagens(tenant)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ opt-out

    @Test
    @DisplayName("marketing sem consentimento vira BLOQUEADA, não some")
    void sem_consentimento_bloqueia() throws SQLException {
        // "Por que a cliente não recebeu?" precisa de resposta. Sem o registro, a recusa vira uma
        // linha ausente e ninguém consegue explicar.
        teste().enviados.clear();
        UUID tenant = salao();
        UUID marilda = cliente(tenant, "11987654321");

        TenantContext.executar(tenant, () -> enfileirar.enfileirar(marilda,
                Finalidade.MARKETING, CanalDeEnvio.WHATSAPP, "vaga-disponivel",
                Map.of("nome", "Marilda", "salao", "Studio Marilda", "quando", "hoje"), null));
        despachante.executar();

        assertThat(statusDe(tenant)).isEqualTo(StatusDaMensagem.BLOQUEADA);
        assertThat(teste().enviados).isEmpty();
    }

    @Test
    @DisplayName("o lembrete não precisa de consentimento: é execução de contrato")
    void lembrete_dispensa_consentimento() throws SQLException {
        // A base legal do lembrete é o próprio serviço contratado (RT-CLI-004). Exigir opt-in para
        // avisar do horário que ela marcou seria confundir marketing com atendimento.
        UUID tenant = salao();
        UUID marilda = cliente(tenant, "11987654321");

        TenantContext.executar(tenant, () -> enfileirar.enfileirar(marilda,
                Finalidade.LEMBRETE_DE_AGENDAMENTO, CanalDeEnvio.WHATSAPP,
                "lembrete-de-agendamento", parametrosDoLembrete(), null));

        assertThat(statusDe(tenant)).isEqualTo(StatusDaMensagem.PENDENTE);
    }

    @Test
    @DisplayName("com consentimento registrado, o marketing passa")
    void com_consentimento_passa() throws SQLException {
        teste().enviados.clear();
        UUID tenant = salao();
        UUID marilda = cliente(tenant, "11987654321");
        TenantContext.executar(tenant, () -> registrarConsentimento.executar(marilda,
                Finalidade.MARKETING, true, CanalDeConsentimento.BALCAO));

        TenantContext.executar(tenant, () -> enfileirar.enfileirar(marilda,
                Finalidade.MARKETING, CanalDeEnvio.WHATSAPP, "vaga-disponivel",
                Map.of("nome", "Marilda", "salao", "Studio Marilda", "quando", "hoje"), null));
        despachante.executar();

        assertThat(statusDe(tenant)).isEqualTo(StatusDaMensagem.ENVIADA);
    }

    // ------------------------------------------------------------------ retry

    @Test
    @DisplayName("provedor fora do ar não perde a mensagem: ela volta para a fila")
    void falha_volta_para_a_fila() throws SQLException {
        // "O WhatsApp caiu por dez minutos" é rotina, não exceção. Gravar antes de enviar é o que
        // transforma uma falha de rede numa linha para tentar de novo.
        UUID tenant = salao();
        UUID marilda = cliente(tenant, "11987654321");
        TenantContext.executar(tenant, () -> enfileirar.enfileirar(marilda,
                Finalidade.LEMBRETE_DE_AGENDAMENTO, CanalDeEnvio.WHATSAPP,
                "lembrete-de-agendamento", parametrosDoLembrete(), null));
        teste().falharAsProximas = 1;

        despachante.executar();

        assertThat(statusDe(tenant)).as("continua pendente, com uma tentativa contada")
                .isEqualTo(StatusDaMensagem.PENDENTE);
        assertThat(tentativasDe(tenant)).isEqualTo(1);
    }

    @Test
    @DisplayName("a falha adia a próxima tentativa em vez de repetir imediatamente")
    void falha_adia() throws SQLException {
        // Sem a espera, um provedor fora do ar vira uma rajada que o mantém fora do ar — e o salão
        // descobre pelo bloqueio da conta.
        UUID tenant = salao();
        UUID marilda = cliente(tenant, "11987654321");
        TenantContext.executar(tenant, () -> enfileirar.enfileirar(marilda,
                Finalidade.LEMBRETE_DE_AGENDAMENTO, CanalDeEnvio.WHATSAPP,
                "lembrete-de-agendamento", parametrosDoLembrete(), null));
        teste().falharAsProximas = 1;
        despachante.executar();

        int naSegundaRodada = despachante.executar();

        assertThat(naSegundaRodada).as("a mensagem ainda não pode ser tentada").isZero();
    }

    // ------------------------------------------------------------------ recusas de entrada

    @Test
    @DisplayName("modelo que não existe é recusado no enfileiramento")
    void modelo_desconhecido() {
        UUID tenant = salao();
        UUID marilda = cliente(tenant, "11987654321");

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> enfileirar.enfileirar(marilda, Finalidade.LEMBRETE_DE_AGENDAMENTO,
                        CanalDeEnvio.WHATSAPP, "modelo-inventado", Map.of(), null)));

        assertThat(codigoDe(erro)).isEqualTo("ER-NOT-MODELO_DESCONHECIDO");
    }

    @Test
    @DisplayName("parâmetro faltando é recusado AGORA, não no envio")
    void parametros_validados_no_enfileiramento() {
        // Descoberto no envio, viraria uma falha que o retry repetiria cinco vezes sem chance de
        // dar certo.
        UUID tenant = salao();
        UUID marilda = cliente(tenant, "11987654321");

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> enfileirar.enfileirar(marilda, Finalidade.LEMBRETE_DE_AGENDAMENTO,
                        CanalDeEnvio.WHATSAPP, "lembrete-de-agendamento",
                        Map.of("nome", "Marilda"), null)));

        assertThat(codigoDe(erro)).isEqualTo("ER-NOT-PARAMETROS_INVALIDOS");
    }

    @Test
    @DisplayName("cliente sem telefone é recusada, com o motivo certo")
    void cliente_sem_destino() {
        UUID tenant = salao();
        UUID semTelefone = cliente(tenant, null);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> enfileirar.enfileirar(semTelefone, Finalidade.LEMBRETE_DE_AGENDAMENTO,
                        CanalDeEnvio.WHATSAPP, "lembrete-de-agendamento",
                        parametrosDoLembrete(), null)));

        assertThat(codigoDe(erro)).isEqualTo("ER-NOT-SEM_DESTINO");
    }

    // ------------------------------------------------------------------ auxiliares

    private int quantasMensagens(UUID tenant) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "select count(*) from mensagem where estabelecimento_id = ?")) {
            ps.setObject(1, tenant);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private StatusDaMensagem statusDe(UUID tenant) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "select status from mensagem where estabelecimento_id = ? order by criado_em "
                        + "desc limit 1")) {
            ps.setObject(1, tenant);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return StatusDaMensagem.valueOf(rs.getString(1));
            }
        }
    }

    private int tentativasDe(UUID tenant) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "select tentativas from mensagem where estabelecimento_id = ? order by criado_em "
                        + "desc limit 1")) {
            ps.setObject(1, tenant);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
