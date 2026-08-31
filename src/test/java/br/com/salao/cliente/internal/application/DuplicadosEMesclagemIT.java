package br.com.salao.cliente.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.cliente.api.ClienteApi;
import br.com.salao.cliente.api.ClienteResumo;
import br.com.salao.cliente.internal.domain.GrauDeSemelhanca;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-CLI-003 — detecção de duplicado e mesclagem. */
@WithMockUser(authorities = { Permissao.CLIENTE_ESCREVER, Permissao.CLIENTE_LER })
class DuplicadosEMesclagemIT extends AbstractPostgresIT {

    @Autowired
    private ProvisionarEstabelecimentoUseCase provisionar;

    @Autowired
    private CadastrarClienteUseCase cadastrar;

    @Autowired
    private ConsultarClienteUseCase consultar;

    @Autowired
    private BuscarClientesUseCase buscar;

    @Autowired
    private DetectarDuplicadosUseCase duplicados;

    @Autowired
    private MesclarClientesUseCase mesclar;

    @Autowired
    private ClienteApi clienteApi;

    private UUID salao() {
        return provisionar.executar(new ProvisionarEstabelecimentoCommand(
                "Salão " + UUID.randomUUID(), null, null, null, null, null, null, "11",
                "Ana", "ana-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    private List<PossivelDuplicado> avisos(UUID tenant, String nome, String telefone) {
        return TenantContext.obter(tenant, () -> duplicados.executar(nome, telefone, null));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ---------------------------------------------------------------- detecção

    @Test
    @DisplayName("mesmo telefone e mesmo primeiro nome: avisa que é provavelmente a mesma pessoa")
    void detecta_o_duplicado_obvio() {
        UUID tenant = salao();
        TenantContext.executar(tenant, () ->
                cadastrar.executar(DadosDoCliente.rapido("Maria Silva", "11987654321")));

        var avisos = avisos(tenant, "Maria da Silva", "5511987654321");

        assertThat(avisos).singleElement()
                .satisfies(a -> {
                    assertThat(a.grau()).isEqualTo(GrauDeSemelhanca.PROVAVEL);
                    assertThat(a.cliente().nome()).isEqualTo("Maria Silva");
                });
    }

    @Test
    @DisplayName("mãe e filha: avisa como contexto, não como duplicado")
    void mae_e_filha_nao_viram_duplicado() {
        // O aviso continua útil — "este número já está com a Maria" —, mas não pode parecer erro:
        // tratar família como duplicidade treinaria a recepção a ignorar o aviso, e aí ele
        // deixaria de servir para o caso em que está certo.
        UUID tenant = salao();
        TenantContext.executar(tenant, () ->
                cadastrar.executar(DadosDoCliente.rapido("Maria Silva", "11987654321")));

        var avisos = avisos(tenant, "Júlia Silva", "5511987654321");

        assertThat(avisos).singleElement()
                .extracting(PossivelDuplicado::grau)
                .isEqualTo(GrauDeSemelhanca.MESMO_TELEFONE);
    }

    @Test
    @DisplayName("Mariana não é confundida com Maria, mesmo dividindo o telefone")
    void mariana_nao_e_maria() {
        UUID tenant = salao();
        TenantContext.executar(tenant, () ->
                cadastrar.executar(DadosDoCliente.rapido("Maria Silva", "11987654321")));

        assertThat(avisos(tenant, "Mariana Silva", "5511987654321"))
                .singleElement().extracting(PossivelDuplicado::grau)
                .isEqualTo(GrauDeSemelhanca.MESMO_TELEFONE);
    }

    @Test
    @DisplayName("sem telefone, nome muito parecido vira 'possível'")
    void sem_telefone_nome_parecido() {
        UUID tenant = salao();
        TenantContext.executar(tenant, () ->
                cadastrar.executar(DadosDoCliente.rapido("Maria Silva Santos", null)));

        assertThat(avisos(tenant, "Maria Silva", null))
                .singleElement().extracting(PossivelDuplicado::grau)
                .isEqualTo(GrauDeSemelhanca.POSSIVEL);
    }

    @Test
    @DisplayName("nome sem relação nenhuma não gera aviso")
    void nome_sem_relacao_nao_avisa() {
        UUID tenant = salao();
        TenantContext.executar(tenant, () ->
                cadastrar.executar(DadosDoCliente.rapido("Maria Silva", "11987654321")));

        assertThat(avisos(tenant, "João Pereira", "5511900000000")).isEmpty();
    }

    @Test
    @DisplayName("a detecção não atravessa salões")
    void deteccao_isolada_por_tenant() {
        UUID salaoA = salao();
        UUID salaoB = salao();
        TenantContext.executar(salaoA, () ->
                cadastrar.executar(DadosDoCliente.rapido("Maria Silva", "11987654321")));

        assertThat(avisos(salaoA, "Maria Silva", "5511987654321")).isNotEmpty();
        assertThat(avisos(salaoB, "Maria Silva", "5511987654321")).isEmpty();
    }

    // --------------------------------------------------------------- mesclagem

    @Test
    @DisplayName("mesclar completa os vazios da ficha que fica, sem sobrescrever o que já havia")
    void mesclagem_completa_vazios() {
        // Dado bom substituído por outro bom não melhora nada; dado bom substituído por dado
        // velho é perda silenciosa. Por isso só os vazios são preenchidos.
        UUID tenant = salao();
        var ids = TenantContext.obter(tenant, () -> new UUID[] {
                cadastrar.executar(new DadosDoCliente("Maria Silva", null, "11987654321",
                        null, "alérgica a amônia")),
                cadastrar.executar(new DadosDoCliente("Maria da Silva", "Mari", "11999998888",
                        "maria@teste.com", "prefere manhã")) });

        var restante = TenantContext.obter(tenant, () -> {
            mesclar.executar(ids[0], ids[1]);
            return consultar.executar(ids[0]);
        });

        assertThat(restante.telefone()).as("o telefone que já existia é preservado")
                .isEqualTo("5511987654321");
        assertThat(restante.apelido()).as("o vazio é completado").isEqualTo("Mari");
        assertThat(restante.email()).isEqualTo("maria@teste.com");
    }

    @Test
    @DisplayName("a ficha absorvida redireciona: quem guardou o id antigo continua achando a pessoa")
    void absorvida_redireciona() {
        // É o ponto do desenho. Agendamento, comanda e comissão guardam cliente_id; se mesclar
        // apagasse a ficha, cada referência esquecida viraria um registro órfão.
        UUID tenant = salao();
        var ids = TenantContext.obter(tenant, () -> new UUID[] {
                cadastrar.executar(DadosDoCliente.rapido("Maria Silva", "11987654321")),
                cadastrar.executar(DadosDoCliente.rapido("Maria da Silva", null)) });

        TenantContext.executar(tenant, () -> mesclar.executar(ids[0], ids[1]));

        var pelaAntiga = TenantContext.obter(tenant, () -> consultar.executar(ids[1]));
        assertThat(pelaAntiga.id()).isEqualTo(ids[0]);
        assertThat(pelaAntiga.nome()).isEqualTo("Maria Silva");
        assertThat(TenantContext.obter(tenant, () -> clienteApi.existe(ids[1]))).isTrue();
    }

    @Test
    @DisplayName("a absorvida some da busca")
    void absorvida_some_da_busca() {
        UUID tenant = salao();
        var ids = TenantContext.obter(tenant, () -> new UUID[] {
                cadastrar.executar(DadosDoCliente.rapido("Maria Silva", "11987654321")),
                cadastrar.executar(DadosDoCliente.rapido("Maria da Silva", null)) });

        TenantContext.executar(tenant, () -> mesclar.executar(ids[0], ids[1]));

        assertThat(TenantContext.obter(tenant, () -> buscar.executar("maria", null)).itens())
                .extracting(ClienteResumo::id)
                .containsExactly(ids[0]);
    }

    @Test
    @DisplayName("mesclar em cadeia mantém um salto só")
    void cadeia_fica_plana() {
        // Sem repontar quem já apontava para a absorvida, A→B→C exigiria seguir ponteiros em toda
        // leitura — e leitura recursiva é onde um ciclo acidental vira laço infinito em produção.
        UUID tenant = salao();
        var ids = TenantContext.obter(tenant, () -> new UUID[] {
                cadastrar.executar(DadosDoCliente.rapido("Maria Silva", null)),
                cadastrar.executar(DadosDoCliente.rapido("Maria da Silva", null)),
                cadastrar.executar(DadosDoCliente.rapido("Maria Silva Santos", null)) });

        TenantContext.executar(tenant, () -> mesclar.executar(ids[1], ids[2]));
        TenantContext.executar(tenant, () -> mesclar.executar(ids[0], ids[1]));

        // A ficha 2 apontava para a 1, que foi absorvida pela 0: as duas resolvem para a 0.
        assertThat(TenantContext.obter(tenant, () -> consultar.executar(ids[2]).id()))
                .isEqualTo(ids[0]);
        assertThat(TenantContext.obter(tenant, () -> consultar.executar(ids[1]).id()))
                .isEqualTo(ids[0]);
    }

    @Test
    @DisplayName("mesclar na ficha que já foi absorvida cai no destino final, sem erro")
    void mesclar_em_ficha_ja_absorvida_resolve() {
        // Duas recepções mesclando ao mesmo tempo chegam aqui sem ter feito nada errado.
        UUID tenant = salao();
        var ids = TenantContext.obter(tenant, () -> new UUID[] {
                cadastrar.executar(DadosDoCliente.rapido("Maria Silva", null)),
                cadastrar.executar(DadosDoCliente.rapido("Maria da Silva", null)),
                cadastrar.executar(DadosDoCliente.rapido("Maria Silva Santos", null)) });

        TenantContext.executar(tenant, () -> mesclar.executar(ids[0], ids[1]));
        UUID destino = TenantContext.obter(tenant, () -> mesclar.executar(ids[1], ids[2]));

        assertThat(destino).isEqualTo(ids[0]);
    }

    @Test
    @DisplayName("não se mescla uma ficha nela mesma")
    void nao_mescla_em_si_mesma() {
        UUID tenant = salao();
        UUID id = TenantContext.obter(tenant,
                () -> cadastrar.executar(DadosDoCliente.rapido("Maria Silva", null)));

        var erro = catchThrowable(() ->
                TenantContext.executar(tenant, () -> mesclar.executar(id, id)));

        assertThat(codigoDe(erro)).isEqualTo("ER-CLI-MESCLAGEM_INVALIDA");
    }

    @Test
    @DisplayName("uma ficha já absorvida não é absorvida de novo")
    void nao_mescla_duas_vezes() {
        UUID tenant = salao();
        var ids = TenantContext.obter(tenant, () -> new UUID[] {
                cadastrar.executar(DadosDoCliente.rapido("Maria Silva", null)),
                cadastrar.executar(DadosDoCliente.rapido("Maria da Silva", null)),
                cadastrar.executar(DadosDoCliente.rapido("Maria Silva Santos", null)) });

        TenantContext.executar(tenant, () -> mesclar.executar(ids[0], ids[1]));
        var erro = catchThrowable(() ->
                TenantContext.executar(tenant, () -> mesclar.executar(ids[2], ids[1])));

        assertThat(codigoDe(erro)).isEqualTo("ER-CLI-MESCLAGEM_INVALIDA");
    }

    @Test
    @DisplayName("ficha de outro salão não existe para mesclar — a RLS não devolve a linha")
    void mesclagem_isolada_por_tenant() {
        UUID salaoA = salao();
        UUID salaoB = salao();
        UUID daA = TenantContext.obter(salaoA,
                () -> cadastrar.executar(DadosDoCliente.rapido("Maria Silva", null)));
        UUID daB = TenantContext.obter(salaoB,
                () -> cadastrar.executar(DadosDoCliente.rapido("Maria Silva", null)));

        var erro = catchThrowable(() ->
                TenantContext.executar(salaoB, () -> mesclar.executar(daB, daA)));

        assertThat(codigoDe(erro)).isEqualTo("ER-CLI-CLIENTE_NAO_ENCONTRADO");
    }

    @Test
    @DisplayName("sem cliente:write, mesclar é recusado no caso de uso")
    @WithMockUser(authorities = Permissao.CLIENTE_LER)
    void sem_permissao_nao_mescla() {
        UUID tenant = salao();

        assertThatThrownBy(() -> TenantContext.executar(tenant,
                () -> mesclar.executar(UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(AccessDeniedException.class);
    }
}
