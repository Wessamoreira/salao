package br.com.salao.catalogo.internal.infra;

import br.com.salao.catalogo.api.BlocoDoServico;
import br.com.salao.catalogo.api.SequenciaDeBlocos;
import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.catalogo.api.ServicoResumo;
import br.com.salao.catalogo.api.TipoDeBloco;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CAT-001 — persistência do serviço e dos blocos.
 *
 * <p>Serviço e blocos são lidos em <strong>duas consultas</strong>, não num join. O join
 * devolveria a cabeça repetida uma vez por bloco e obrigaria a desduplicar em memória — e com
 * cinco blocos e trinta serviços isso é mais dado trafegando do que as duas consultas juntas.
 *
 * <p>{@code @Transactional} é obrigatório mesmo na leitura: é a transação que dispara o
 * {@code set_config('app.tenant_id')}, e as duas consultas precisam ver o mesmo tenant.
 */
public class ServicosJdbc implements ServicoApi {

    private final JdbcClient jdbc;

    public ServicosJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID inserir(UUID estabelecimentoId, String nome, String descricao,
                        SequenciaDeBlocos blocos, UUID criadoPor) {
        UUID id = jdbc.sql("""
                insert into servico (estabelecimento_id, nome, descricao, criado_por)
                values (:estabelecimento, :nome, :descricao, :criadoPor)
                returning id
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("nome", nome)
                .param("descricao", descricao)
                .param("criadoPor", criadoPor)
                .query(UUID.class)
                .single();
        gravarBlocos(estabelecimentoId, id, blocos);
        return id;
    }

    /**
     * Apaga e regrava a sequência.
     *
     * <p>Substituir em vez de reconciliar, como na jornada (RT-EQP-002): são cinco linhas, e
     * descobrir o que mudou seria código que existe para economizar escritas e erra na primeira
     * receita com dois blocos iguais em posições diferentes.
     */
    public void gravarBlocos(UUID estabelecimentoId, UUID servicoId, SequenciaDeBlocos blocos) {
        jdbc.sql("delete from bloco_do_servico where servico_id = :id")
                .param("id", servicoId)
                .update();

        List<BlocoDoServico> lista = blocos.blocos();
        for (int ordem = 0; ordem < lista.size(); ordem++) {
            BlocoDoServico bloco = lista.get(ordem);
            jdbc.sql("""
                    insert into bloco_do_servico
                        (estabelecimento_id, servico_id, ordem, tipo, duracao_minutos)
                    values (:estabelecimento, :servico, :ordem, :tipo, :duracao)
                    """)
                    .param("estabelecimento", estabelecimentoId)
                    .param("servico", servicoId)
                    .param("ordem", ordem)
                    .param("tipo", bloco.tipo().name())
                    .param("duracao", bloco.duracaoMinutos())
                    .update();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ServicoResumo> porId(UUID servicoId) {
        record Cabeca(UUID id, String nome, String descricao, boolean ativo, int versao) {
        }
        Optional<Cabeca> cabeca = jdbc.sql("""
                select id, nome, descricao, ativo, versao from servico where id = :id
                """)
                .param("id", servicoId)
                .query((rs, linha) -> new Cabeca(rs.getObject("id", UUID.class),
                        rs.getString("nome"), rs.getString("descricao"),
                        rs.getBoolean("ativo"), rs.getInt("versao")))
                .optional();

        return cabeca.map(c -> new ServicoResumo(c.id(), c.nome(), c.descricao(),
                blocosDe(List.of(c.id())).get(c.id()), c.ativo(), c.versao()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ServicoResumo> ativos() {
        return listar(true);
    }

    /** Inclui os desativados: quem administra precisa ver o que saiu do cardápio. */
    @Transactional(readOnly = true)
    public List<ServicoResumo> todos() {
        return listar(false);
    }

    private List<ServicoResumo> listar(boolean apenasAtivos) {
        record Cabeca(UUID id, String nome, String descricao, boolean ativo, int versao) {
        }
        List<Cabeca> cabecas = jdbc.sql("""
                select id, nome, descricao, ativo, versao
                  from servico
                 where (:apenasAtivos = false or ativo)
                 order by ativo desc, nome
                """)
                .param("apenasAtivos", apenasAtivos)
                .query((rs, linha) -> new Cabeca(rs.getObject("id", UUID.class),
                        rs.getString("nome"), rs.getString("descricao"),
                        rs.getBoolean("ativo"), rs.getInt("versao")))
                .list();

        Map<UUID, SequenciaDeBlocos> blocos = blocosDe(cabecas.stream().map(Cabeca::id).toList());
        return cabecas.stream()
                .map(c -> new ServicoResumo(c.id(), c.nome(), c.descricao(),
                        blocos.get(c.id()), c.ativo(), c.versao()))
                .toList();
    }

    /** Uma consulta para todos os serviços da lista — nunca uma por serviço. */
    private Map<UUID, SequenciaDeBlocos> blocosDe(List<UUID> servicoIds) {
        if (servicoIds.isEmpty()) {
            return Map.of();
        }
        var porServico = new LinkedHashMap<UUID, List<BlocoDoServico>>();
        jdbc.sql("""
                select servico_id, tipo, duracao_minutos
                  from bloco_do_servico
                 where servico_id in (:ids)
                 order by servico_id, ordem
                """)
                .param("ids", servicoIds)
                .query((rs, linha) -> Map.entry(rs.getObject("servico_id", UUID.class),
                        new BlocoDoServico(TipoDeBloco.valueOf(rs.getString("tipo")),
                                rs.getInt("duracao_minutos"))))
                .list()
                .forEach(entrada -> porServico
                        .computeIfAbsent(entrada.getKey(), id -> new ArrayList<>())
                        .add(entrada.getValue()));

        var saida = new LinkedHashMap<UUID, SequenciaDeBlocos>();
        porServico.forEach((id, lista) -> saida.put(id, new SequenciaDeBlocos(lista)));
        return saida;
    }

    public int definirAtivacao(UUID id, boolean ativo) {
        return jdbc.sql("""
                update servico set ativo = :ativo, versao = versao + 1
                 where id = :id and ativo <> :ativo
                """)
                .param("id", id)
                .param("ativo", ativo)
                .update();
    }
}
