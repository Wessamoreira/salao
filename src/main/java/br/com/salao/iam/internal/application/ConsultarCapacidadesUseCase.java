package br.com.salao.iam.internal.application;

import br.com.salao.iam.api.Capacidades;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.domain.MapaDePermissoes;
import br.com.salao.iam.internal.infra.CredenciaisJdbc;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.erro.ErrosDaInfra;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * RT-IAM-006 — monta o que o front precisa para desenhar a tela.
 *
 * <p>Menus e limites são <strong>derivados no servidor</strong>, a partir das permissões. A
 * alternativa — mandar as permissões e deixar o front decidir quais menus mostrar — recria no
 * JavaScript a regra que este endpoint existe para eliminar, e as duas cópias divergem na primeira
 * permissão nova.
 */
public class ConsultarCapacidadesUseCase {

    private final CredenciaisJdbc credenciais;
    private final EstabelecimentoApi estabelecimentos;

    public ConsultarCapacidadesUseCase(CredenciaisJdbc credenciais,
                                       EstabelecimentoApi estabelecimentos) {
        this.credenciais = credenciais;
        this.estabelecimentos = estabelecimentos;
    }

    public Capacidades executar(UUID usuarioId, UUID estabelecimentoId) {
        var usuario = credenciais.porId(usuarioId).orElseThrow(() ->
                new ErroDeDominio(ErrosDaInfra.NAO_ENCONTRADO, "Usuário não encontrado."));
        var salao = estabelecimentos.configuracao(estabelecimentoId).orElseThrow(() ->
                new ErroDeDominio(ErrosDaInfra.NAO_ENCONTRADO, "Estabelecimento não encontrado."));

        Perfil perfil = usuario.perfil();
        Set<String> permissoes = MapaDePermissoes.de(perfil);

        return new Capacidades(
                usuarioId,
                salao.nome(),
                usuario.email(),
                perfil,
                new Capacidades.EstabelecimentoResumo(salao.id(), salao.nome(),
                        salao.fuso().getId(), salao.moeda()),
                permissoes,
                menusDe(permissoes),
                Map.of(
                        // Nome que descreve o EFEITO, não o perfil: o front pergunta "posso ver
                        // valor de outros?", nunca "sou gerente?".
                        "podeVerValorDeOutros", permissoes.contains(Permissao.FINANCEIRO_LER_TODOS),
                        "podeAgendarParaOutros", permissoes.contains(Permissao.AGENDA_ESCREVER_TODAS),
                        "podeVerFichaDoCliente", permissoes.contains(Permissao.CLIENTE_FICHA_LER)),
                Map.of("descontoMaximoPercentual",
                        MapaDePermissoes.descontoMaximoPercentual(perfil)),
                usuario.mfaAtivo(),
                MapaDePermissoes.exigeMfa(perfil));
    }

    private List<Capacidades.Menu> menusDe(Set<String> permissoes) {
        var menus = new ArrayList<Capacidades.Menu>();
        if (permissoes.contains(Permissao.AGENDA_LER_PROPRIA)
                || permissoes.contains(Permissao.AGENDA_LER_TODAS)) {
            menus.add(new Capacidades.Menu("agenda", "Agenda", "/agenda", "calendario"));
        }
        if (permissoes.contains(Permissao.COMANDA_ABRIR)) {
            menus.add(new Capacidades.Menu("atendimento", "Atendimento", "/comandas", "tesoura"));
        }
        if (permissoes.contains(Permissao.CLIENTE_LER)) {
            menus.add(new Capacidades.Menu("clientes", "Clientes", "/clientes", "pessoas"));
        }
        if (permissoes.contains(Permissao.ESTOQUE_LER)) {
            menus.add(new Capacidades.Menu("estoque", "Estoque", "/estoque", "caixa"));
        }
        if (permissoes.contains(Permissao.FINANCEIRO_LER_PROPRIO)
                || permissoes.contains(Permissao.FINANCEIRO_LER_TODOS)) {
            menus.add(new Capacidades.Menu("financeiro", "Financeiro", "/financeiro", "cifrao"));
        }
        if (permissoes.contains(Permissao.CONFIG_GERENCIAR)) {
            menus.add(new Capacidades.Menu("configuracao", "Configurações", "/config", "engrenagem"));
        }
        return List.copyOf(menus);
    }
}
