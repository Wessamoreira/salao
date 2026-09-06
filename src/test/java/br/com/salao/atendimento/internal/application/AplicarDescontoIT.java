package br.com.salao.atendimento.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.atendimento.api.ComandaDetalhada;
import br.com.salao.catalogo.api.TipoDeBloco;
import br.com.salao.catalogo.internal.application.CadastrarServicoUseCase;
import br.com.salao.catalogo.internal.application.DefinirPrecoUseCase;
import br.com.salao.catalogo.internal.application.EtapaBruta;
import br.com.salao.cliente.internal.application.CadastrarClienteUseCase;
import br.com.salao.cliente.internal.application.DadosDoCliente;
import br.com.salao.equipe.api.TipoDeVinculo;
import br.com.salao.equipe.internal.application.CadastrarProfissionalUseCase;
import br.com.salao.equipe.internal.application.DadosDoProfissional;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.AtualizarConfiguracaoUseCase;
import br.com.salao.iam.internal.application.ConfiguracaoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * RT-ATD-004 — desconto com teto por quem o dá.
 *
 * <p>Não usa {@code @WithMockUser}: o teto depende de <strong>quem</strong> está agindo, e o
 * usuário simulado não tem id. Aqui a identidade é um JWT de verdade, com o id do usuário
 * provisionado — que é o que o caso de uso usa para perguntar o teto ao {@code iam}.
 *
 * <p>As permissões concedidas são sempre as mesmas, de propósito: o que muda entre os testes é o
 * <em>perfil no banco</em>. É assim que se prova que a permissão responde "pode dar desconto?" e o
 * teto responde "de quanto?" — duas perguntas diferentes.
 */
class AplicarDescontoIT extends AbstractPostgresIT {

    private static final List<String> PERMISSOES = List.of(
            Permissao.COMANDA_ABRIR, Permissao.COMANDA_DESCONTO, Permissao.CLIENTE_ESCREVER,
            Permissao.USUARIO_GERENCIAR, Permissao.CONFIG_GERENCIAR);

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private DefinirPrecoUseCase definirPreco;
    @Autowired private AtualizarConfiguracaoUseCase atualizarConfiguracao;
    @Autowired private EstabelecimentoApi estabelecimentos;
    @Autowired private AbrirComandaUseCase abrir;
    @Autowired private AdicionarItemDeServicoUseCase adicionar;
    @Autowired private AplicarDescontoUseCase desconto;

    private UUID tenant;
    private UUID usuario;
    private UUID comanda;
    private UUID item;

    @AfterEach
    void limparIdentidade() {
        SecurityContextHolder.clearContext();
    }

    /** Um salão com uma comanda aberta e um serviço de R$ 80,00 lançado. */
    private void cenario(Perfil perfil) throws SQLException {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        usuario = idDaDona();
        agirComo(perfil);

        UUID maria = TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Maria Silva", null, null, null, null)));
        UUID ana = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Ana Ribeiro", "Ana", "11144477735",
                        TipoDeVinculo.EMPREGADO, null, null, new BigDecimal("40.00"),
                        null, null, null))).id();
        UUID escova = TenantContext.obter(tenant, () -> cadastrarServico.executar("Escova", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 45)))).id();
        TenantContext.executar(tenant,
                () -> definirPreco.executar(escova, Money.de("80"), LocalDate.now().minusDays(1)));
        comanda = TenantContext.obter(tenant, () -> abrir.avulsa(maria)).comanda().id();
        item = TenantContext.obter(tenant,
                () -> adicionar.executar(comanda, escova, ana, null, null)).itens().get(0).id();
    }

    private UUID idDaDona() throws SQLException {
        try (var st = comoOwner().createStatement();
             var rs = st.executeQuery("select id from usuario limit 1")) {
            rs.next();
            return rs.getObject(1, UUID.class);
        }
    }

    /** Troca o perfil no banco e põe no contexto um JWT com o id daquele usuário. */
    private void agirComo(Perfil perfil) throws SQLException {
        try (var ps = comoOwner().prepareStatement("update usuario set perfil = ? where id = ?")) {
            ps.setString(1, perfil.name());
            ps.setObject(2, usuario);
            ps.executeUpdate();
        }
        Jwt jwt = Jwt.withTokenValue("token-de-teste")
                .header("alg", "none")
                .subject(usuario.toString())
                .claim("perfil", perfil.name())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                PERMISSOES.stream().map(SimpleGrantedAuthority::new).toList()));
    }

    private ComandaDetalhada descontar(Money valor, BigDecimal percentual) {
        return TenantContext.obter(tenant,
                () -> desconto.executar(comanda, item, valor, percentual));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ o teto

    @Test
    @DisplayName("a recepção desconta até 10%")
    void recepcao_dentro_do_teto() throws SQLException {
        cenario(Perfil.RECEPCAO);

        var detalhada = descontar(null, new BigDecimal("10"));

        assertThat(detalhada.itens().get(0).desconto()).isEqualTo(Money.de("8"));
        assertThat(detalhada.itens().get(0).valorTotal()).isEqualTo(Money.de("72"));
        assertThat(detalhada.comanda().valorDesconto()).isEqualTo(Money.de("8"));
        assertThat(detalhada.comanda().valorLiquido()).isEqualTo(Money.de("72"));
    }

    @Test
    @DisplayName("a recepção não passa dos 10%")
    void recepcao_acima_do_teto() throws SQLException {
        cenario(Perfil.RECEPCAO);

        Throwable erro = catchThrowable(() -> descontar(null, new BigDecimal("20")));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-DESCONTO_ACIMA_DO_LIMITE");
        assertThat(erro).hasMessageContaining("seu limite é 10%");
    }

    @Test
    @DisplayName("o teto vale também quando o desconto é digitado em dinheiro")
    void teto_nao_e_contornavel_por_valor() throws SQLException {
        // Senão o limite dependeria da forma de digitar: "50%" recusado e "R$ 40,00" — que é o
        // mesmo desconto — aceito. É o furo que um limite percentual sempre tem se ele só olha
        // para o campo percentual.
        cenario(Perfil.RECEPCAO);

        Throwable erro = catchThrowable(() -> descontar(Money.de("40"), null));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-DESCONTO_ACIMA_DO_LIMITE");
        assertThat(erro).hasMessageContaining("50.00%");
    }

    @Test
    @DisplayName("o gerente desconta o item inteiro")
    void gerente_desconta_tudo() throws SQLException {
        cenario(Perfil.GERENTE);

        var detalhada = descontar(null, new BigDecimal("100"));

        assertThat(detalhada.itens().get(0).valorTotal()).isEqualTo(Money.ZERO);
        assertThat(detalhada.comanda().valorLiquido()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("sem identidade, o teto é zero")
    void sem_identidade_nao_desconta() throws SQLException {
        // Job agendado e consumidor de evento agem sem ninguém logado. Falha fechada: quem não
        // tem rosto não dá desconto.
        cenario(Perfil.GERENTE);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("job", null,
                        PERMISSOES.stream().map(SimpleGrantedAuthority::new).toList()));

        Throwable erro = catchThrowable(() -> descontar(null, new BigDecimal("1")));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-DESCONTO_ACIMA_DO_LIMITE");
        assertThat(erro).hasMessageContaining("seu limite é 0%");
    }

    // ------------------------------------------------------------------ a comissão

    @Test
    @DisplayName("por padrão o desconto sai do salão: a comissão continua sobre o valor cheio")
    void desconto_nao_afeta_comissao() throws SQLException {
        cenario(Perfil.GERENTE);

        var detalhada = descontar(null, new BigDecimal("50"));

        var executante = detalhada.itens().get(0).executantes().get(0);
        assertThat(executante.baseCalculo()).isEqualTo(Money.de("80"));
        assertThat(executante.valorComissao()).isEqualTo(Money.de("32"));
    }

    @Test
    @DisplayName("configurado o contrário, o desconto sai também da comissão")
    void desconto_afeta_comissao() throws SQLException {
        // Pergunta 2 de 13-perguntas-em-aberto: quem paga o desconto, o salão ou o profissional?
        // A resposta muda de salão para salão, e por isso é configuração e não código.
        cenario(Perfil.GERENTE);
        // A configuração é substituída inteira, não remendada: reenvia o que já vale, com o
        // único campo que interessa trocado.
        var atual = TenantContext.obter(tenant,
                () -> estabelecimentos.configuracao(tenant)).orElseThrow();
        TenantContext.executar(tenant, () -> atualizarConfiguracao.executar(
                new ConfiguracaoCommand(atual.nome(), null, atual.fuso().getId(), atual.moeda(),
                        atual.baseComissao(), true, atual.periodicidadeDeFechamento(),
                        atual.dddPadrao())));

        var detalhada = descontar(null, new BigDecimal("50"));

        var executante = detalhada.itens().get(0).executantes().get(0);
        assertThat(executante.baseCalculo()).isEqualTo(Money.de("40"));
        assertThat(executante.valorComissao()).isEqualTo(Money.de("16"));
    }

    @Test
    @DisplayName("o percentual gravado no lançamento não é relido da ficha")
    void percentual_continua_snapshot() throws SQLException {
        // Recalcular a comissão é refazer a CONTA, não a REGRA: o percentual sai da própria linha,
        // que é o snapshot de RN-ATD-010. Reler a ficha aqui apagaria o snapshot em silêncio.
        cenario(Perfil.GERENTE);
        try (var ps = comoOwner().prepareStatement(
                "update profissional set comissao_percentual = 90")) {
            ps.executeUpdate();
        }

        var executante = descontar(null, new BigDecimal("10")).itens().get(0).executantes().get(0);

        assertThat(executante.percentualComissao()).isEqualByComparingTo("40.00");
        assertThat(executante.valorComissao()).isEqualTo(Money.de("32"));
    }

    // ------------------------------------------------------------------ o que é recusado

    @Test
    @DisplayName("zero remove o desconto")
    void zero_remove() throws SQLException {
        cenario(Perfil.GERENTE);
        descontar(null, new BigDecimal("50"));

        var detalhada = descontar(Money.ZERO, null);

        assertThat(detalhada.itens().get(0).desconto()).isEqualTo(Money.ZERO);
        assertThat(detalhada.comanda().valorLiquido()).isEqualTo(Money.de("80"));
    }

    @Test
    @DisplayName("desconto maior que o item é recusado")
    void desconto_maior_que_o_item() throws SQLException {
        // Total negativo seria um item que devolve dinheiro — isso é estorno, não desconto.
        cenario(Perfil.GERENTE);

        Throwable erro = catchThrowable(() -> descontar(Money.de("100"), null));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-DESCONTO_INVALIDO");
    }

    @Test
    @DisplayName("desconto negativo é acréscimo, e não passa")
    void desconto_negativo() throws SQLException {
        cenario(Perfil.GERENTE);

        Throwable erro = catchThrowable(() -> descontar(Money.de("-10"), null));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-DESCONTO_INVALIDO");
    }

    @Test
    @DisplayName("valor e percentual juntos, ou nenhum dos dois, é recusado")
    void forma_ambigua() throws SQLException {
        cenario(Perfil.GERENTE);

        assertThat(codigoDe(catchThrowable(() -> descontar(Money.de("5"), BigDecimal.ONE))))
                .isEqualTo("ER-ATD-DESCONTO_INVALIDO");
        assertThat(codigoDe(catchThrowable(() -> descontar(null, null))))
                .isEqualTo("ER-ATD-DESCONTO_INVALIDO");
    }

    @Test
    @DisplayName("comanda que não está aberta não recebe desconto")
    void comanda_fechada() throws SQLException {
        cenario(Perfil.GERENTE);
        try (var ps = comoOwner().prepareStatement(
                "update comanda set status = 'FECHADA' where id = ?")) {
            ps.setObject(1, comanda);
            ps.executeUpdate();
        }

        Throwable erro = catchThrowable(() -> descontar(null, new BigDecimal("10")));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-COMANDA_NAO_ESTA_ABERTA");
    }
}
