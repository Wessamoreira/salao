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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-AGD-010 — fila de espera: entrar e sair. Quem cabe numa vaga é de {@code VagaAbertaIT}. */
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

    // ------------------------------------------------------------------ dias que se sobrepõem

    @Test
    @DisplayName("dias que se sobrepõem, mesmo em parte, são a mesma espera")
    void sobreposicao_parcial_e_recusada() {
        // RN-AGD-028: segunda a quinta e quinta a sábado dividem a quinta — uma vaga na quinta
        // seria duas ligações para a mesma pessoa.
        cenario();
        entrar(marilda, ana, QUINTA.minusDays(3), QUINTA, null, null);

        Throwable erro = catchThrowable(() -> entrar(marilda, ana, QUINTA, QUINTA.plusDays(2),
                null, null));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-JA_ESTA_NA_FILA");
    }

    @Test
    @DisplayName("em dias separados, a mesma cliente espera duas vezes")
    void dias_separados_podem() {
        // O caso que o índice antigo impedia: duas quintas de uma série que não couberam são duas
        // esperas legítimas, de um dia cada.
        cenario();
        entrar(marilda, ana, QUINTA, QUINTA, null, null);

        assertThatCode(() -> entrar(marilda, ana, QUINTA.plusDays(7), QUINTA.plusDays(7), null,
                null)).doesNotThrowAnyException();
        assertThat(TenantContext.obter(tenant, () -> fila.naFila())).hasSize(2);
    }

    @Test
    @DisplayName("\"qualquer uma\" e \"a Ana\" são esperas diferentes")
    void qualquer_uma_e_ana_sao_diferentes() {
        cenario();
        entrar(marilda, null, QUINTA, QUINTA, null, null);

        assertThatCode(() -> entrar(marilda, ana, QUINTA, QUINTA, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a fila traz os nomes que a tela escreve")
    void na_fila_com_nomes() {
        cenario();
        entrar(marilda, ana, QUINTA, QUINTA, null, null);
        entrar(outraCliente("Joana"), null, QUINTA, QUINTA, null, null);

        var fila = TenantContext.obter(tenant, () -> this.fila.naFila());

        assertThat(fila).extracting(EsperaComNomes::cliente).containsExactly("Marilda", "Joana");
        assertThat(fila).extracting(EsperaComNomes::servico).containsOnly("Escova");
        assertThat(fila).extracting(EsperaComNomes::profissional)
                .as("nulo é qualquer uma").containsExactly("Ana", null);
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
    }
}
