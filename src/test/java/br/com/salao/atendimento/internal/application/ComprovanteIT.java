package br.com.salao.atendimento.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.atendimento.api.ComprovanteDeAtendimento;
import br.com.salao.catalogo.api.TipoDeBloco;
import br.com.salao.catalogo.internal.application.CadastrarServicoUseCase;
import br.com.salao.catalogo.internal.application.DefinirPrecoUseCase;
import br.com.salao.catalogo.internal.application.EtapaBruta;
import br.com.salao.cliente.internal.application.CadastrarClienteUseCase;
import br.com.salao.cliente.internal.application.DadosDoCliente;
import br.com.salao.equipe.api.TipoDeVinculo;
import br.com.salao.equipe.internal.application.CadastrarProfissionalUseCase;
import br.com.salao.equipe.internal.application.DadosDoProfissional;
import br.com.salao.financeiro.api.DadosDoPagamento;
import br.com.salao.financeiro.api.FormaDePagamento;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** RT-FIN-008 — comprovante de atendimento. */
@org.springframework.security.test.context.support.WithMockUser(authorities = {
        Permissao.COMANDA_ABRIR, Permissao.COMANDA_FECHAR, Permissao.COMANDA_REABRIR,
        Permissao.CLIENTE_ESCREVER, Permissao.CLIENTE_LER, Permissao.CONFIG_GERENCIAR,
        Permissao.USUARIO_GERENCIAR })
class ComprovanteIT extends AbstractPostgresIT {

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private DefinirPrecoUseCase definirPreco;
    @Autowired private AbrirComandaUseCase abrir;
    @Autowired private AdicionarItemDeServicoUseCase adicionarServico;
    @Autowired private RegistrarPagamentoUseCase pagar;
    @Autowired private FecharComandaUseCase fechar;
    @Autowired private ReabrirComandaUseCase reabrir;
    @Autowired private EmitirComprovanteUseCase emitir;
    @Autowired private EnviarComprovanteUseCase enviar;

    private UUID tenant;
    private UUID maria;
    private UUID coloracao;
    private UUID ana;

    private void cenario() {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Studio Bertoldo", null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        maria = TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                // O telefone é o TERCEIRO campo; no segundo ele virava apelido, e o envio
                // falhava com "cliente sem telefone".
                DadosDoCliente.rapido("Maria Silva", "11987654321")));
        ana = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Ana Ribeiro", "Ana", "11144477735",
                        TipoDeVinculo.EMPREGADO, null, null, new BigDecimal("30"),
                        null, null, null))).id();
        coloracao = TenantContext.obter(tenant, () -> cadastrarServico.executar("Coloração", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 90)))).id();
        TenantContext.executar(tenant, () -> definirPreco.executar(coloracao, Money.de("200"),
                LocalDate.now().minusDays(1)));
    }

    private UUID atenderEFechar() {
        UUID comanda = TenantContext.obter(tenant, () -> abrir.avulsa(maria)).comanda().id();
        TenantContext.executar(tenant,
                () -> adicionarServico.executar(comanda, coloracao, ana, null, null));
        TenantContext.executar(tenant, () -> pagar.executar(comanda,
                DadosDoPagamento.de(FormaDePagamento.PIX, Money.de("200"))));
        TenantContext.executar(tenant, () -> fechar.executar(comanda));
        return comanda;
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    @Test
    @DisplayName("o comprovante traz salão, cliente, item e pagamento")
    void comprovante_completo() {
        cenario();
        UUID comanda = atenderEFechar();

        ComprovanteDeAtendimento c = TenantContext.obter(tenant, () -> emitir.executar(comanda));

        assertThat(c.salao()).isEqualTo("Studio Bertoldo");
        assertThat(c.cliente()).isEqualTo("Maria Silva");
        assertThat(c.total()).isEqualTo(Money.de("200"));
        assertThat(c.itens()).singleElement()
                .satisfies(i -> assertThat(i.descricao()).contains("Coloração"));
        assertThat(c.pagamentos()).singleElement()
                .satisfies(p -> assertThat(p.forma()).isEqualTo("Pix"));
    }

    @Test
    @DisplayName("o texto sai em 48 colunas e avisa que não é documento fiscal")
    void texto_pronto_para_a_termica() {
        cenario();
        UUID comanda = atenderEFechar();

        String texto = TenantContext.obter(tenant, () -> emitir.texto(comanda));

        assertThat(texto).contains("STUDIO BERTOLDO").contains("Maria Silva")
                .contains("NÃO É DOCUMENTO FISCAL");
        assertThat(texto.lines()).allSatisfy(l -> assertThat(l.length())
                .as("linha \"%s\"", l).isLessThanOrEqualTo(48));
    }

    @Test
    @DisplayName("comanda ABERTA não tem comprovante")
    void aberta_nao_tem_comprovante() {
        // Um recibo impresso agora seria desmentido no próximo item lançado.
        cenario();
        UUID comanda = TenantContext.obter(tenant, () -> abrir.avulsa(maria)).comanda().id();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> emitir.executar(comanda)));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-COMPROVANTE_EXIGE_FECHADA");
    }

    @Test
    @DisplayName("reabrir invalida o comprovante: ele deixa de ser emitido")
    void reabrir_invalida() {
        // A cliente pode ter o papel na mão, e ele deixou de valer. Emitir outro fingindo que
        // está tudo certo é pior do que recusar — é a pendência que RT-ATD-007 registrou.
        cenario();
        UUID comanda = atenderEFechar();
        TenantContext.executar(tenant, () -> reabrir.executar(comanda, "corrigir item"));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> emitir.executar(comanda)));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-COMPROVANTE_EXIGE_FECHADA");
    }

    @Test
    @DisplayName("dois comprovantes da mesma comanda são idênticos")
    void comprovante_e_reproduzivel() {
        // Projeção, não registro: não há cópia guardada para discordar depois.
        cenario();
        UUID comanda = atenderEFechar();

        String primeiro = TenantContext.obter(tenant, () -> emitir.texto(comanda));
        String segundo = TenantContext.obter(tenant, () -> emitir.texto(comanda));

        assertThat(segundo).isEqualTo(primeiro);
    }

    @Test
    @DisplayName("o segundo clique em enviar não manda a segunda mensagem")
    void envio_e_idempotente() {
        // A referência é o id da comanda, e o `notificacao` devolve a mensagem que já existia.
        cenario();
        UUID comanda = atenderEFechar();

        UUID primeira = TenantContext.obter(tenant, () -> enviar.executar(comanda, maria));
        UUID segunda = TenantContext.obter(tenant, () -> enviar.executar(comanda, maria));

        assertThat(segunda).isEqualTo(primeira);
    }

    @Test
    @DisplayName("enviar de comanda reaberta é recusado, não enfileirado")
    void envio_exige_fechada() {
        cenario();
        UUID comanda = atenderEFechar();
        TenantContext.executar(tenant, () -> reabrir.executar(comanda, "corrigir"));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> enviar.executar(comanda, maria)));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-COMPROVANTE_EXIGE_FECHADA");
    }

    @Test
    @DisplayName("um salão não emite comprovante do outro")
    void isolamento_entre_saloes() {
        cenario();
        UUID comanda = atenderEFechar();
        UUID salaoB = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Outro " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        Throwable erro = catchThrowable(() -> TenantContext.executar(salaoB,
                () -> emitir.executar(comanda)));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-COMANDA_NAO_ENCONTRADA");
    }
}
