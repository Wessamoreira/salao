package br.com.salao.agenda.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.agenda.api.EsperaNaFila;
import br.com.salao.catalogo.api.TipoDeBloco;
import br.com.salao.catalogo.internal.application.CadastrarServicoUseCase;
import br.com.salao.catalogo.internal.application.EtapaBruta;
import br.com.salao.cliente.internal.application.CadastrarClienteUseCase;
import br.com.salao.cliente.internal.application.DadosDoCliente;
import br.com.salao.equipe.api.TipoDeVinculo;
import br.com.salao.equipe.internal.application.CadastrarProfissionalUseCase;
import br.com.salao.equipe.internal.application.DadosDoProfissional;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-AGD-010 — fila de espera. */
@WithMockUser(authorities = { Permissao.USUARIO_GERENCIAR, Permissao.CONFIG_GERENCIAR,
        Permissao.CLIENTE_ESCREVER, Permissao.AGENDA_LER_TODAS,
        Permissao.AGENDA_ESCREVER_TODAS })
class FilaDeEsperaIT extends AbstractPostgresIT {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate QUINTA = LocalDate.now(SAO_PAULO).plusDays(7);

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private FilaDeEsperaUseCase fila;

    private UUID tenant;
    private UUID ana;
    private UUID marilda;
    private UUID escova;

    private void cenario() {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        ana = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Ana", null, "11144477735", TipoDeVinculo.EMPREGADO,
                        null, null, null, null, null, null))).id();
        marilda = TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Marilda", null, null, null, null)));
        escova = TenantContext.obter(tenant, () -> cadastrarServico.executar("Escova", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 45)))).id();
    }

    private UUID outraCliente(String nome) {
        return TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente(nome, null, null, null, null)));
    }

    private EsperaNaFila entrar(UUID cliente, UUID profissional, LocalDate de, LocalDate ate,
                                LocalTime horaDe, LocalTime horaAte) {
        return TenantContext.obter(tenant, () -> fila.entrar(cliente, escova, profissional,
                de, ate, horaDe, horaAte, null));
    }

    private Instant em(LocalDate dia, int hora) {
        return dia.atTime(LocalTime.of(hora, 0)).atZone(SAO_PAULO).toInstant();
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ entrar e sair

    @Test
    @DisplayName("entra na fila com a janela que a cliente aceita")
    void entrar_na_fila() {
        cenario();

        var espera = entrar(marilda, ana, QUINTA, QUINTA, LocalTime.of(13, 0),
                LocalTime.of(18, 0));

        assertThat(espera.clienteId()).isEqualTo(marilda);
        assertThat(espera.aceitaDe()).isEqualTo(QUINTA);
        assertThat(TenantContext.obter(tenant, () -> fila.naFila())).hasSize(1);
    }

    @Test
    @DisplayName("a mesma cliente não entra duas vezes pelo mesmo serviço")
    void sem_duplicata() {
        // Duas entradas seriam duas ligações pela mesma vaga, e a segunda é a que faz o salão
        // parecer desorganizado justamente com quem já está esperando.
        cenario();
        entrar(marilda, ana, QUINTA, QUINTA, null, null);

        Throwable erro = catchThrowable(() -> entrar(marilda, ana, QUINTA, QUINTA, null, null));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-JA_ESTA_NA_FILA");
    }

    @Test
    @DisplayName("depois de sair da fila, pode entrar de novo")
    void reentrada_e_legitima() {
        // A restrição vale enquanto ela espera. Quem foi atendida em março e quer voltar em abril
        // não pode ser barrada pelo próprio histórico.
        cenario();
        var primeira = entrar(marilda, ana, QUINTA, QUINTA, null, null);
        TenantContext.executar(tenant, () -> fila.marcarAtendida(primeira.id()));

        assertThatCode(() -> entrar(marilda, ana, QUINTA, QUINTA, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("atendida e desistiu são saídas diferentes")
    void duas_saidas_diferentes() {
        // "Conseguiu horário" é o sistema funcionando; "cansou de esperar" é a fila longa demais.
        // Um campo só apagaria o número que diz se a fila serve para alguma coisa.
        cenario();
        var atendida = entrar(marilda, ana, QUINTA, QUINTA, null, null);
        var desistiu = entrar(outraCliente("Joana"), ana, QUINTA, QUINTA, null, null);

        TenantContext.executar(tenant, () -> fila.marcarAtendida(atendida.id()));
        TenantContext.executar(tenant, () -> fila.registrarDesistencia(desistiu.id()));

        assertThat(TenantContext.obter(tenant, () -> fila.naFila())).isEmpty();
    }

    @Test
    @DisplayName("sair duas vezes é recusado")
    void nao_sai_duas_vezes() {
        cenario();
        var espera = entrar(marilda, ana, QUINTA, QUINTA, null, null);
        TenantContext.executar(tenant, () -> fila.marcarAtendida(espera.id()));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> fila.registrarDesistencia(espera.id())));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-ESPERA_NAO_ENCONTRADA");
    }

    // ------------------------------------------------------------------ quem cabe na vaga

    @Test
    @DisplayName("abriu vaga: os candidatos vêm na ordem de quem chegou primeiro")
    void candidatos_em_ordem_de_chegada() {
        // Sem ordem explícita, "quem avisamos primeiro" viraria a ordem em que o banco devolveu —
        // e a cliente que espera há duas semanas ficaria atrás da que entrou ontem.
        cenario();
        UUID joana = outraCliente("Joana");
        var primeira = entrar(marilda, null, QUINTA, QUINTA, null, null);
        var segunda = entrar(joana, null, QUINTA, QUINTA, null, null);

        var candidatos = TenantContext.obter(tenant,
                () -> fila.candidatosA(escova, ana, em(QUINTA, 14)));

        assertThat(candidatos).extracting(EsperaNaFila::id)
                .containsExactly(primeira.id(), segunda.id());
    }

    @Test
    @DisplayName("a faixa de hora filtra os candidatos")
    void faixa_de_hora_filtra() {
        cenario();
        UUID joana = outraCliente("Joana");
        var deManha = entrar(marilda, null, QUINTA, QUINTA, LocalTime.of(9, 0),
                LocalTime.of(12, 0));
        var aTarde = entrar(joana, null, QUINTA, QUINTA, LocalTime.of(13, 0),
                LocalTime.of(18, 0));

        assertThat(TenantContext.obter(tenant,
                () -> fila.candidatosA(escova, ana, em(QUINTA, 14))))
                .extracting(EsperaNaFila::id).containsExactly(aTarde.id());
        assertThat(TenantContext.obter(tenant,
                () -> fila.candidatosA(escova, ana, em(QUINTA, 10))))
                .extracting(EsperaNaFila::id).containsExactly(deManha.id());
    }

    @Test
    @DisplayName("quem pediu outra profissional não é candidata")
    void profissional_filtra() {
        cenario();
        UUID julia = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Júlia", null, "52998224725", TipoDeVinculo.EMPREGADO,
                        null, null, null, null, null, null))).id();
        UUID joana = outraCliente("Joana");
        var querAna = entrar(marilda, ana, QUINTA, QUINTA, null, null);
        entrar(joana, julia, QUINTA, QUINTA, null, null);

        assertThat(TenantContext.obter(tenant,
                () -> fila.candidatosA(escova, ana, em(QUINTA, 14))))
                .extracting(EsperaNaFila::id).containsExactly(querAna.id());
    }

    @Test
    @DisplayName("a fila expira sozinha: janela vencida para de aparecer")
    void janela_vencida_some_sozinha() {
        // De propósito: uma fila que dependesse de job de limpeza para dar a resposta certa daria
        // a resposta errada exatamente quando o job falhasse — e ninguém percebe um job que não
        // roda até alguém ser chamado para um horário de duas semanas atrás.
        cenario();
        var vencida = entrar(marilda, null, QUINTA, QUINTA, null, null);

        assertThat(TenantContext.obter(tenant,
                () -> fila.candidatosA(escova, ana, em(QUINTA.plusDays(1), 14))))
                .as("um dia depois da janela").isEmpty();
        assertThat(TenantContext.obter(tenant, () -> fila.naFila()))
                .as("mas continua na fila, para quem quiser ver e resolver")
                .extracting(EsperaNaFila::id).containsExactly(vencida.id());
    }

    @Test
    @DisplayName("quem já saiu não é candidata")
    void quem_saiu_nao_e_candidata() {
        cenario();
        var espera = entrar(marilda, null, QUINTA, QUINTA, null, null);
        TenantContext.executar(tenant, () -> fila.registrarDesistencia(espera.id()));

        assertThat(TenantContext.obter(tenant,
                () -> fila.candidatosA(escova, ana, em(QUINTA, 14)))).isEmpty();
    }

    // ------------------------------------------------------------------ recusas e isolamento

    @Test
    @DisplayName("janela invertida é 422, não 500")
    void janela_invertida() {
        cenario();

        Throwable erro = catchThrowable(() -> entrar(marilda, ana, QUINTA, QUINTA.minusDays(1),
                null, null));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-ESPERA_INVALIDA");
    }

    @Test
    @DisplayName("cliente de outro salão é recusada")
    void cliente_de_outro_salao() {
        cenario();
        UUID salaoB = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Outra",
                "outra-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        UUID doB = TenantContext.obter(salaoB, () -> cadastrarCliente.executar(
                new DadosDoCliente("Alheia", null, null, null, null)));

        Throwable erro = catchThrowable(() -> entrar(doB, ana, QUINTA, QUINTA, null, null));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-CLIENTE_NAO_ENCONTRADO");
    }

    @Test
    @DisplayName("um salão não vê a fila do outro")
    void isolamento_entre_saloes() {
        cenario();
        entrar(marilda, ana, QUINTA, QUINTA, null, null);
        UUID salaoB = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Outra",
                "outra-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        assertThat(TenantContext.obter(salaoB, () -> fila.naFila())).isEmpty();
        assertThat(TenantContext.obter(salaoB,
                () -> fila.candidatosA(escova, ana, em(QUINTA, 14)))).isEmpty();
    }
}
