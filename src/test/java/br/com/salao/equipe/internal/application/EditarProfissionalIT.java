package br.com.salao.equipe.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.equipe.api.ProfissionalResumo;
import br.com.salao.equipe.api.TipoDeVinculo;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-EQP-005 — corrigir a ficha de quem já está na equipe. */
@WithMockUser(authorities = { Permissao.USUARIO_GERENCIAR, Permissao.AGENDA_LER_TODAS })
class EditarProfissionalIT extends AbstractPostgresIT {

    @Autowired
    private ProvisionarEstabelecimentoUseCase provisionar;

    @Autowired
    private CadastrarProfissionalUseCase cadastrar;

    @Autowired
    private EditarProfissionalUseCase editar;

    private UUID salao() {
        return provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    /** CPF válido gerado para teste; o dígito verificador é conferido no domínio (RN-EQP-001). */
    private static final String CPF = "52998224725";

    private ProfissionalResumo ana(UUID tenant) {
        return TenantContext.obter(tenant, () -> cadastrar.executar(new DadosDoProfissional(
                "Ana Ribeiro", "Ana", CPF, TipoDeVinculo.EMPREGADO, "11987654321",
                "ana@salao.test", new BigDecimal("40.00"),
                LocalDate.of(2026, 1, 10), null, LocalDate.of(2026, 1, 8))));
    }

    private EditarProfissionalUseCase.DadosEditaveis dados(String nome, String telefone,
                                                           BigDecimal comissao) {
        return new EditarProfissionalUseCase.DadosEditaveis(nome, "Ana", telefone,
                "ana@salao.test", comissao, LocalDate.of(2026, 1, 10), null,
                LocalDate.of(2026, 1, 8));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    @Test
    @DisplayName("corrigir o nome e o telefone não cria ficha nova")
    void edita_a_ficha() {
        // Sem isto, a saída seria cadastrar a pessoa de novo e desativar a primeira — partindo o
        // extrato de comissão em duas fichas com o mesmo nome.
        UUID tenant = salao();
        var antes = ana(tenant);

        var depois = TenantContext.obter(tenant, () -> editar.executar(antes.id(),
                dados("Ana Ribeiro Souza", "11955554444", new BigDecimal("45.00")),
                antes.versao()));

        assertThat(depois.id()).isEqualTo(antes.id());
        assertThat(depois.nome()).isEqualTo("Ana Ribeiro Souza");
        assertThat(depois.comissaoPercentual()).isEqualByComparingTo("45.00");
        assertThat(depois.versao()).isGreaterThan(antes.versao());
    }

    @Test
    @DisplayName("o telefone é guardado em E.164, como o resto do sistema (RN-EQP-016)")
    void telefone_normalizado() {
        UUID tenant = salao();
        var antes = ana(tenant);

        var depois = TenantContext.obter(tenant, () -> editar.executar(antes.id(),
                dados("Ana Ribeiro", "(11) 95555-4444", new BigDecimal("40.00")),
                antes.versao()));

        assertThat(depois.telefone()).isEqualTo("5511955554444");
    }

    @Test
    @DisplayName("documento e vínculo não mudam por edição (RN-EQP-017)")
    void identidade_nao_muda() {
        // Trocá-los faria uma pessoa jurídica herdar o extrato de comissão de outra, sem que
        // nada na trilha dissesse o que aconteceu.
        UUID tenant = salao();
        var antes = ana(tenant);

        var depois = TenantContext.obter(tenant, () -> editar.executar(antes.id(),
                dados("Ana Ribeiro", "11987654321", new BigDecimal("40.00")), antes.versao()));

        assertThat(depois.documento()).isEqualTo(antes.documento());
        assertThat(depois.vinculo()).isEqualTo(antes.vinculo());
    }

    @Test
    @DisplayName("comissão nula continua nula — é 'usa a cascata', não zero")
    void comissao_nula() {
        UUID tenant = salao();
        var antes = ana(tenant);

        var depois = TenantContext.obter(tenant, () -> editar.executar(antes.id(),
                dados("Ana Ribeiro", "11987654321", null), antes.versao()));

        assertThat(depois.comissaoPercentual()).isNull();
    }

    @Test
    @DisplayName("comissão fora de 0–100 é recusada com código estável")
    void comissao_fora_da_faixa() {
        UUID tenant = salao();
        var antes = ana(tenant);

        Throwable erro = catchThrowable(() -> TenantContext.obter(tenant, () -> editar.executar(
                antes.id(), dados("Ana Ribeiro", "11987654321", new BigDecimal("120")),
                antes.versao())));

        assertThat(codigoDe(erro)).isEqualTo("ER-EQP-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("versão velha perde para quem salvou antes, em vez de apagar a correção dele")
    void conflito_de_versao() {
        UUID tenant = salao();
        var antes = ana(tenant);
        TenantContext.obter(tenant, () -> editar.executar(antes.id(),
                dados("Ana Ribeiro Primeira", "11987654321", new BigDecimal("40.00")),
                antes.versao()));

        Throwable erro = catchThrowable(() -> TenantContext.obter(tenant, () -> editar.executar(
                antes.id(), dados("Ana Ribeiro Segunda", "11987654321", new BigDecimal("40.00")),
                antes.versao())));

        assertThat(codigoDe(erro)).isEqualTo("ER-EQP-CONFLITO_DE_VERSAO");
    }

    @Test
    @DisplayName("profissional de outro salão não é encontrado")
    void de_outro_salao() {
        UUID meu = salao();
        UUID outro = salao();
        var doOutro = ana(outro);

        Throwable erro = catchThrowable(() -> TenantContext.obter(meu, () -> editar.executar(
                doOutro.id(), dados("Ana Ribeiro", "11987654321", new BigDecimal("40.00")),
                doOutro.versao())));

        assertThat(codigoDe(erro)).isEqualTo("ER-EQP-PROFISSIONAL_NAO_ENCONTRADO");
    }

    @Test
    @WithMockUser(authorities = { Permissao.AGENDA_LER_TODAS })
    @DisplayName("quem só lê a agenda não edita a ficha de quem atende")
    void so_quem_gerencia_edita() {
        UUID tenant = salao();

        Throwable erro = catchThrowable(() -> TenantContext.obter(tenant, () -> editar.executar(
                UUID.randomUUID(), dados("Ana", "11987654321", null), 0)));

        assertThat(erro).isInstanceOf(AccessDeniedException.class);
    }
}
