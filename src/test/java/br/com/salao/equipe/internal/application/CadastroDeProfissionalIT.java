package br.com.salao.equipe.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.equipe.api.ProfissionalApi;
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

/** RT-EQP-001 — cadastro de profissional. */
@WithMockUser(authorities = Permissao.USUARIO_GERENCIAR)
class CadastroDeProfissionalIT extends AbstractPostgresIT {

    /** Calculados pelo mod-11; não correspondem a ninguém. */
    private static final String CPF = "52998224725";
    private static final String CNPJ = "11222333000181";
    private static final String OUTRO_CNPJ = "11111111000191";

    @Autowired
    private ProvisionarEstabelecimentoUseCase provisionar;

    @Autowired
    private CadastrarProfissionalUseCase cadastrar;

    @Autowired
    private ListarProfissionaisUseCase listar;

    @Autowired
    private DefinirAtivacaoDoProfissionalUseCase definirAtivacao;

    @Autowired
    private ProfissionalApi equipe;

    private UUID salao() {
        return provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Ana",
                "ana-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    private DadosDoProfissional parceiro(String documento) {
        return new DadosDoProfissional("Marilda Nascimento", "Nice", documento,
                TipoDeVinculo.PARCEIRO, "11987654321", null, new BigDecimal("45.00"),
                LocalDate.of(2026, 1, 5), null, LocalDate.of(2026, 1, 5));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    @Test
    @DisplayName("cadastra parceiro com CNPJ, comissão e contrato")
    void cadastra_parceiro() {
        UUID tenant = salao();

        var criado = TenantContext.obter(tenant, () -> cadastrar.executar(parceiro(CNPJ)));

        assertThat(criado.documento()).as("guarda só dígitos").isEqualTo(CNPJ);
        assertThat(criado.vinculo()).isEqualTo(TipoDeVinculo.PARCEIRO);
        assertThat(criado.comissaoPercentual()).isEqualByComparingTo("45.00");
        assertThat(criado.comoChamar()).as("o apelido cabe na coluna da agenda").isEqualTo("Nice");
        assertThat(criado.contratoPendente()).isFalse();
        assertThat(criado.ativo()).isTrue();
    }

    @Test
    @DisplayName("parceiro sem contrato assinado é cadastrado, mas fica marcado como pendente")
    void parceiro_sem_contrato_avisa_e_nao_bloqueia() {
        // Parceria sem contrato escrito pode ser reclassificada como emprego, com encargos
        // retroativos — é o maior risco do modelo. Mas bloquear o cadastro faria o salão não
        // cadastrar o profissional, e aí perde-se o profissional E continua sem o contrato.
        UUID tenant = salao();

        var criado = TenantContext.obter(tenant, () -> cadastrar.executar(
                new DadosDoProfissional("Marilda", null, CNPJ, TipoDeVinculo.PARCEIRO,
                        null, null, new BigDecimal("50"), null, null, null)));

        assertThat(criado.id()).isNotNull();
        assertThat(criado.contratoPendente()).isTrue();
    }

    @Test
    @DisplayName("empregado e autônomo não ficam pendentes: não dependem de contrato de parceria")
    void so_parceria_exige_contrato() {
        UUID tenant = salao();

        var empregada = TenantContext.obter(tenant, () -> cadastrar.executar(
                new DadosDoProfissional("Joana", null, CPF, TipoDeVinculo.EMPREGADO,
                        null, null, BigDecimal.ZERO, null, null, null)));

        assertThat(empregada.contratoPendente()).isFalse();
    }

    @Test
    @DisplayName("parceiro com CPF é recusado — é vínculo empregatício disfarçado")
    void parceiro_exige_cnpj() {
        // Não é preferência de cadastro: um "parceiro" com CPF descreve exatamente o arranjo que
        // a Lei 13.352/2016 existe para evitar, e que uma fiscalização procura.
        UUID tenant = salao();

        var erro = catchThrowable(() ->
                TenantContext.executar(tenant, () -> cadastrar.executar(parceiro(CPF))));

        assertThat(codigoDe(erro)).isEqualTo("ER-EQP-DOCUMENTO_NAO_COMBINA_COM_VINCULO");
        assertThat(((ErroDeDominio) erro).campos()).singleElement()
                .extracting("campo").isEqualTo("documento");
    }

    @Test
    @DisplayName("empregado com CNPJ também é recusado")
    void empregado_exige_cpf() {
        UUID tenant = salao();

        var erro = catchThrowable(() -> TenantContext.executar(tenant, () -> cadastrar.executar(
                new DadosDoProfissional("Joana", null, CNPJ, TipoDeVinculo.EMPREGADO,
                        null, null, BigDecimal.ZERO, null, null, null))));

        assertThat(codigoDe(erro)).isEqualTo("ER-EQP-DOCUMENTO_NAO_COMBINA_COM_VINCULO");
    }

    @Test
    @DisplayName("documento com dígito verificador errado é recusado no cadastro")
    void documento_invalido_recusado() {
        UUID tenant = salao();

        var erro = catchThrowable(() ->
                TenantContext.executar(tenant, () -> cadastrar.executar(parceiro("11222333000182"))));

        assertThat(codigoDe(erro)).isEqualTo("ER-EQP-DOCUMENTO_INVALIDO");
    }

    @Test
    @DisplayName("o mesmo documento não entra duas vezes no mesmo salão")
    void documento_e_unico_no_salao() {
        // Duas fichas com o mesmo CNPJ dividiriam a comissão de um só em dois extratos.
        UUID tenant = salao();
        TenantContext.executar(tenant, () -> cadastrar.executar(parceiro(CNPJ)));

        var erro = catchThrowable(() ->
                TenantContext.executar(tenant, () -> cadastrar.executar(parceiro(CNPJ))));

        assertThat(codigoDe(erro)).isEqualTo("ER-EQP-DOCUMENTO_JA_CADASTRADO");
    }

    @Test
    @DisplayName("o mesmo documento pode existir em salões diferentes")
    void documento_repetido_entre_saloes() {
        // Profissional que atende em dois salões é comum, e cada um tem o próprio cadastro.
        UUID salaoA = salao();
        UUID salaoB = salao();

        TenantContext.executar(salaoA, () -> cadastrar.executar(parceiro(CNPJ)));
        var noB = TenantContext.obter(salaoB, () -> cadastrar.executar(parceiro(CNPJ)));

        assertThat(noB.id()).isNotNull();
    }

    @Test
    @DisplayName("comissão fora de 0 a 100 é recusada")
    void comissao_fora_da_faixa() {
        UUID tenant = salao();

        for (String invalida : new String[] { "-1", "100.01", "150" }) {
            var erro = catchThrowable(() -> TenantContext.executar(tenant, () -> cadastrar.executar(
                    new DadosDoProfissional("Marilda", null, CNPJ, TipoDeVinculo.PARCEIRO,
                            null, null, new BigDecimal(invalida), null, null, null))));
            assertThat(codigoDe(erro)).as(invalida).isEqualTo("ER-EQP-DADOS_INVALIDOS");
        }
    }

    @Test
    @DisplayName("desativar tira da lista de ativos e mantém a ficha")
    void desativar_mantem_a_ficha() {
        // O histórico aponta para quem executou cada serviço. Apagar quebraria o extrato de
        // comissão — inclusive o de meses já pagos, que é o que alguém vai querer conferir.
        UUID tenant = salao();
        var criado = TenantContext.obter(tenant, () -> cadastrar.executar(parceiro(CNPJ)));

        TenantContext.executar(tenant, () -> definirAtivacao.executar(criado.id(), false));

        assertThat(TenantContext.obter(tenant, () -> equipe.ativos())).isEmpty();
        assertThat(TenantContext.obter(tenant, () -> equipe.estaAtivo(criado.id()))).isFalse();
        assertThat(TenantContext.obter(tenant, () -> equipe.porId(criado.id()))).isPresent();
        assertThat(TenantContext.obter(tenant, () -> listar.executar()))
                .as("quem administra continua vendo quem saiu").hasSize(1);
    }

    @Test
    @DisplayName("desativar duas vezes não é erro")
    void desativar_e_idempotente() {
        // Duas abas abertas chegam aqui, e recusar diria que algo deu errado quando o estado
        // desejado já é o que existe.
        UUID tenant = salao();
        var criado = TenantContext.obter(tenant, () -> cadastrar.executar(parceiro(CNPJ)));

        TenantContext.executar(tenant, () -> definirAtivacao.executar(criado.id(), false));
        var segunda = TenantContext.obter(tenant,
                () -> definirAtivacao.executar(criado.id(), false));

        assertThat(segunda.ativo()).isFalse();
    }

    @Test
    @DisplayName("reativar traz de volta para a agenda")
    void reativar() {
        UUID tenant = salao();
        var criado = TenantContext.obter(tenant, () -> cadastrar.executar(parceiro(CNPJ)));
        TenantContext.executar(tenant, () -> definirAtivacao.executar(criado.id(), false));

        TenantContext.executar(tenant, () -> definirAtivacao.executar(criado.id(), true));

        assertThat(TenantContext.obter(tenant, () -> equipe.ativos()))
                .extracting(ProfissionalResumo::id).containsExactly(criado.id());
    }

    @Test
    @DisplayName("a equipe de um salão não aparece no outro")
    void isolamento_entre_tenants() {
        UUID salaoA = salao();
        UUID salaoB = salao();
        var daA = TenantContext.obter(salaoA, () -> cadastrar.executar(parceiro(CNPJ)));

        assertThat(TenantContext.obter(salaoB, () -> equipe.ativos())).isEmpty();
        assertThat(TenantContext.obter(salaoB, () -> equipe.porId(daA.id()))).isEmpty();
        assertThat(TenantContext.obter(salaoB, () -> equipe.estaAtivo(daA.id()))).isFalse();
    }

    @Test
    @DisplayName("a trilha guarda a decisão administrativa, não o documento")
    void trilha_sem_dado_pessoal() throws Exception {
        UUID tenant = salao();
        TenantContext.executar(tenant, () -> cadastrar.executar(parceiro(OUTRO_CNPJ)));

        try (var st = comoOwner().createStatement();
             var rs = st.executeQuery("""
                     select depois::text from auditoria where entidade = 'profissional'
                     """)) {
            assertThat(rs.next()).isTrue();
            String trilha = rs.getString(1);
            assertThat(trilha).contains("PARCEIRO").contains("45");
            assertThat(trilha).doesNotContain(OUTRO_CNPJ).doesNotContain("Marilda");
        }
    }

    @Test
    @DisplayName("a regra do vínculo vale no BANCO também, não só no domínio")
    void banco_recusa_vinculo_incoerente() throws Exception {
        // O caso de uso já recusa. A restrição existe para o dia em que alguém inserir por
        // script, migração ou correção manual — e é aí que a regra costuma ser burlada sem
        // ninguém perceber.
        UUID tenant = salao();

        try (var ps = comoOwner().prepareStatement("""
                insert into profissional
                    (estabelecimento_id, nome, documento, tipo_documento, tipo_vinculo)
                values (?, 'Direto no banco', ?, 'CPF', 'PARCEIRO')
                """)) {
            ps.setObject(1, tenant);
            ps.setString(2, CPF);
            assertThatThrownBy(ps::executeUpdate)
                    .hasMessageContaining("profissional_documento_do_vinculo");
        }
    }

    @Test
    @DisplayName("sem usuario:manage, cadastrar é recusado no caso de uso")
    @WithMockUser(authorities = Permissao.AGENDA_LER_TODAS)
    void sem_permissao_nao_cadastra() {
        UUID tenant = salao();

        assertThatThrownBy(() ->
                TenantContext.executar(tenant, () -> cadastrar.executar(parceiro(CNPJ))))
                .isInstanceOf(AccessDeniedException.class);
    }
}
