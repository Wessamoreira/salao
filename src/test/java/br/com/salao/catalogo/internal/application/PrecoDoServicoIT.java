package br.com.salao.catalogo.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.catalogo.api.PrecoVigente;
import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.catalogo.api.TipoDeBloco;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-CAT-002 — preço com vigência. */
@WithMockUser(authorities = { Permissao.CONFIG_GERENCIAR, Permissao.AGENDA_LER_TODAS })
class PrecoDoServicoIT extends AbstractPostgresIT {

    @Autowired
    private ProvisionarEstabelecimentoUseCase provisionar;

    @Autowired
    private CadastrarServicoUseCase cadastrarServico;

    @Autowired
    private DefinirPrecoUseCase definirPreco;

    @Autowired
    private RemoverPrecoFuturoUseCase removerPrecoFuturo;

    @Autowired
    private ServicoApi catalogo;

    private UUID salao() {
        return provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Ana",
                "ana-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    private UUID escovaEm(UUID tenant) {
        return TenantContext.obter(tenant, () -> cadastrarServico.executar("Escova", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 45)))).id();
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    private boolean marcadoComoRetroativo() {
        try (var st = comoOwner().createStatement();
             var rs = st.executeQuery("""
                     select depois->>'retroativo' from auditoria
                      where entidade = 'preco_do_servico'
                     """)) {
            assertThat(rs.next()).as("a trilha registrou a definição de preço").isTrue();
            return Boolean.parseBoolean(rs.getString(1));
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("aumentar o preço NÃO reescreve o passado")
    void aumento_nao_reescreve_o_passado() {
        // É a rotina inteira. Com o preço numa coluna de `servico`, o relatório de fevereiro
        // passaria a dizer 70 — e a comissão já paga deixaria de bater com o extrato, sem nada
        // quebrar e sem ninguém perceber.
        UUID tenant = salao();
        UUID escova = escovaEm(tenant);

        TenantContext.executar(tenant, () -> definirPreco.executar(escova, Money.de("60"),
                LocalDate.of(2026, 1, 1)));
        TenantContext.executar(tenant, () -> definirPreco.executar(escova, Money.de("70"),
                LocalDate.of(2026, 3, 1)));

        assertThat(TenantContext.obter(tenant,
                () -> catalogo.precoEm(escova, LocalDate.of(2026, 2, 15))))
                .as("em fevereiro ainda custava 60").contains(Money.de("60"));
        assertThat(TenantContext.obter(tenant,
                () -> catalogo.precoEm(escova, LocalDate.of(2026, 3, 15))))
                .contains(Money.de("70"));
    }

    @Test
    @DisplayName("o preço vale a partir do dia exato, inclusive")
    void vigencia_e_inclusiva() {
        UUID tenant = salao();
        UUID escova = escovaEm(tenant);
        TenantContext.executar(tenant, () -> definirPreco.executar(escova, Money.de("60"),
                LocalDate.of(2026, 1, 1)));
        TenantContext.executar(tenant, () -> definirPreco.executar(escova, Money.de("70"),
                LocalDate.of(2026, 3, 1)));

        assertThat(TenantContext.obter(tenant,
                () -> catalogo.precoEm(escova, LocalDate.of(2026, 2, 28))))
                .contains(Money.de("60"));
        assertThat(TenantContext.obter(tenant,
                () -> catalogo.precoEm(escova, LocalDate.of(2026, 3, 1))))
                .as("no dia 1º já vale o novo").contains(Money.de("70"));
    }

    @Test
    @DisplayName("preço futuro é o uso normal: anunciar o aumento antes de aplicar")
    void preco_futuro() {
        UUID tenant = salao();
        UUID escova = escovaEm(tenant);
        LocalDate hoje = LocalDate.now();

        TenantContext.executar(tenant,
                () -> definirPreco.executar(escova, Money.de("60"), hoje.minusDays(30)));
        TenantContext.executar(tenant,
                () -> definirPreco.executar(escova, Money.de("70"), hoje.plusDays(30)));

        assertThat(TenantContext.obter(tenant, () -> catalogo.precoEm(escova, hoje)))
                .as("hoje ainda é o preço antigo").contains(Money.de("60"));
        assertThat(TenantContext.obter(tenant,
                () -> catalogo.precoEm(escova, hoje.plusDays(31))))
                .contains(Money.de("70"));
    }

    @Test
    @DisplayName("sem preço até a data, devolve vazio — nunca zero")
    void sem_preco_e_vazio() {
        // Zero é um preço. Um serviço sem preço num relatório financeiro precisa aparecer como
        // pendência de cadastro, não como brinde.
        UUID tenant = salao();
        UUID escova = escovaEm(tenant);
        TenantContext.executar(tenant, () -> definirPreco.executar(escova, Money.de("60"),
                LocalDate.of(2026, 3, 1)));

        assertThat(TenantContext.obter(tenant,
                () -> catalogo.precoEm(escova, LocalDate.of(2026, 1, 1))))
                .as("antes do primeiro preço, não havia preço").isEmpty();
        assertThat(TenantContext.obter(tenant,
                () -> catalogo.precoEm(UUID.randomUUID(), LocalDate.of(2026, 3, 1)))).isEmpty();
    }

    @Test
    @DisplayName("o histórico devolve tudo, do mais recente para o mais antigo")
    void historico_completo() {
        UUID tenant = salao();
        UUID escova = escovaEm(tenant);
        TenantContext.executar(tenant, () -> {
            definirPreco.executar(escova, Money.de("50"), LocalDate.of(2025, 1, 1));
            definirPreco.executar(escova, Money.de("60"), LocalDate.of(2026, 1, 1));
            definirPreco.executar(escova, Money.de("70"), LocalDate.of(2026, 6, 1));
        });

        assertThat(TenantContext.obter(tenant, () -> catalogo.historicoDePrecos(escova)))
                .extracting(PrecoVigente::valor)
                .containsExactly(Money.de("70"), Money.de("60"), Money.de("50"));
    }

    @Test
    @DisplayName("dois preços no mesmo dia são recusados — nenhuma regra diria qual vale")
    void mesma_data_e_ambiguidade() {
        UUID tenant = salao();
        UUID escova = escovaEm(tenant);
        TenantContext.executar(tenant, () -> definirPreco.executar(escova, Money.de("60"),
                LocalDate.of(2026, 3, 1)));

        var erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> definirPreco.executar(escova, Money.de("70"), LocalDate.of(2026, 3, 1))));

        assertThat(codigoDe(erro)).isEqualTo("ER-CAT-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("preço retroativo é permitido e marcado na trilha")
    void retroativo_e_marcado() {
        // Recusar obrigaria o salão a conviver com "700 em vez de 70, ontem". Mas ele muda o que
        // o passado diz, e por isso vai marcado — é a única defesa enquanto `atendimento` não
        // copiar o preço para o item da comanda (RN-CAT-006).
        UUID tenant = salao();
        UUID escova = escovaEm(tenant);

        TenantContext.executar(tenant, () -> definirPreco.executar(escova, Money.de("60"),
                LocalDate.now().minusDays(10)));

        // Consulta o CAMPO, não o texto do jsonb: o Postgres renderiza com espaço depois do
        // dois-pontos, e uma asserção presa a isso testaria a formatação em vez do dado.
        assertThat(marcadoComoRetroativo()).isTrue();
    }

    @Test
    @DisplayName("preço de hoje não é marcado como retroativo")
    void hoje_nao_e_retroativo() {
        UUID tenant = salao();
        UUID escova = escovaEm(tenant);

        TenantContext.executar(tenant,
                () -> definirPreco.executar(escova, Money.de("60"), null));

        // Consulta o CAMPO, não o texto do jsonb: o Postgres renderiza com espaço depois do
        // dois-pontos, e uma asserção presa a isso testaria a formatação em vez do dado.
        assertThat(marcadoComoRetroativo()).isFalse();
    }

    @Test
    @DisplayName("cancelar um preço que ainda não começou desfaz o anúncio")
    void cancela_preco_futuro() {
        UUID tenant = salao();
        UUID escova = escovaEm(tenant);
        TenantContext.executar(tenant, () -> definirPreco.executar(escova, Money.de("60"),
                LocalDate.now().minusDays(30)));
        var futuro = TenantContext.obter(tenant, () -> definirPreco.executar(escova,
                Money.de("90"), LocalDate.now().plusDays(15)));

        TenantContext.executar(tenant, () -> removerPrecoFuturo.executar(futuro.id()));

        assertThat(TenantContext.obter(tenant, () -> catalogo.historicoDePrecos(escova)))
                .extracting(PrecoVigente::valor).containsExactly(Money.de("60"));
    }

    @Test
    @DisplayName("preço que já começou NÃO se apaga — o passado mudaria sozinho")
    void nao_apaga_preco_vigente() {
        // Corrige-se cadastrando outro, o que deixa rastro. Apagar não deixaria.
        UUID tenant = salao();
        UUID escova = escovaEm(tenant);
        var vigente = TenantContext.obter(tenant, () -> definirPreco.executar(escova,
                Money.de("60"), LocalDate.now().minusDays(5)));

        var erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> removerPrecoFuturo.executar(vigente.id())));

        assertThat(codigoDe(erro)).isEqualTo("ER-CAT-PRECO_JA_VIGENTE");
        assertThat(TenantContext.obter(tenant, () -> catalogo.historicoDePrecos(escova)))
                .hasSize(1);
    }

    @Test
    @DisplayName("preço negativo é recusado")
    void preco_negativo() {
        UUID tenant = salao();
        UUID escova = escovaEm(tenant);

        var erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> definirPreco.executar(escova, Money.de("-10"), null)));

        assertThat(codigoDe(erro)).isEqualTo("ER-CAT-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("preço de serviço inexistente é recusado")
    void servico_inexistente() {
        UUID tenant = salao();

        var erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> definirPreco.executar(UUID.randomUUID(), Money.de("60"), null)));

        assertThat(codigoDe(erro)).isEqualTo("ER-CAT-SERVICO_NAO_ENCONTRADO");
    }

    @Test
    @DisplayName("o preço de um salão não vaza para o outro")
    void isolamento_entre_tenants() {
        UUID salaoA = salao();
        UUID salaoB = salao();
        UUID daA = escovaEm(salaoA);
        TenantContext.executar(salaoA, () -> definirPreco.executar(daA, Money.de("60"), null));

        assertThat(TenantContext.obter(salaoB, () -> catalogo.precoEm(daA, LocalDate.now())))
                .isEmpty();
        assertThat(TenantContext.obter(salaoB, () -> catalogo.historicoDePrecos(daA))).isEmpty();
    }

    @Test
    @DisplayName("a recepção lê o preço mas não o define")
    @WithMockUser(authorities = Permissao.AGENDA_LER_TODAS)
    void recepcao_nao_define_preco() {
        UUID tenant = salao();

        assertThatThrownBy(() -> TenantContext.executar(tenant,
                () -> definirPreco.executar(UUID.randomUUID(), Money.de("60"), null)))
                .isInstanceOf(AccessDeniedException.class);
    }
}
