package br.com.salao.catalogo.internal.infra;

import br.com.salao.catalogo.api.BlocoDoServico;
import br.com.salao.catalogo.api.SequenciaDeBlocos;
import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.catalogo.api.PrecoVigente;
import br.com.salao.catalogo.api.ServicoResumo;
import br.com.salao.catalogo.api.TipoDeBloco;
import br.com.salao.shared.dinheiro.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
            UUID blocoId = jdbc.sql("""
                    insert into bloco_do_servico
                        (estabelecimento_id, servico_id, ordem, tipo, duracao_minutos)
                    values (:estabelecimento, :servico, :ordem, :tipo, :duracao)
                    returning id
                    """)
                    .param("estabelecimento", estabelecimentoId)
                    .param("servico", servicoId)
                    .param("ordem", ordem)
                    .param("tipo", bloco.tipo().name())
                    .param("duracao", bloco.duracaoMinutos())
                    .query(UUID.class)
                    .single();

            // RT-CAT-003 — a exigência de recurso é parte da receita e é regravada junto. Se
            // morasse numa tabela à parte, o "apaga e regrava" acima a destruiria a cada edição
            // do serviço, em silêncio.
            for (UUID recursoId : bloco.recursosExigidos()) {
                jdbc.sql("""
                        insert into bloco_exige_recurso
                            (estabelecimento_id, bloco_id, recurso_id)
                        values (:estabelecimento, :bloco, :recurso)
                        """)
                        .param("estabelecimento", estabelecimentoId)
                        .param("bloco", blocoId)
                        .param("recurso", recursoId)
                        .update();
            }
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

    /**
     * Uma consulta para todos os serviços da lista — nunca uma por serviço.
     *
     * <p>Com RT-CAT-003 são duas: blocos e exigências de recurso. Continuam sendo duas para
     * trinta serviços como para um. Juntá-las num join devolveria o bloco repetido uma vez por
     * recurso, que é o mesmo problema que já fez serviço e bloco serem lidos separados.
     */
    private Map<UUID, SequenciaDeBlocos> blocosDe(List<UUID> servicoIds) {
        if (servicoIds.isEmpty()) {
            return Map.of();
        }
        record Linha(UUID id, UUID servicoId, TipoDeBloco tipo, int duracao) {
        }
        List<Linha> linhas = jdbc.sql("""
                select id, servico_id, tipo, duracao_minutos
                  from bloco_do_servico
                 where servico_id in (:ids)
                 order by servico_id, ordem
                """)
                .param("ids", servicoIds)
                .query((rs, n) -> new Linha(rs.getObject("id", UUID.class),
                        rs.getObject("servico_id", UUID.class),
                        TipoDeBloco.valueOf(rs.getString("tipo")),
                        rs.getInt("duracao_minutos")))
                .list();

        Map<UUID, Set<UUID>> recursos = recursosDe(linhas.stream().map(Linha::id).toList());

        var porServico = new LinkedHashMap<UUID, List<BlocoDoServico>>();
        linhas.forEach(linha -> porServico
                .computeIfAbsent(linha.servicoId(), id -> new ArrayList<>())
                .add(new BlocoDoServico(linha.tipo(), linha.duracao(),
                        recursos.getOrDefault(linha.id(), Set.of()))));

        var saida = new LinkedHashMap<UUID, SequenciaDeBlocos>();
        porServico.forEach((id, lista) -> saida.put(id, new SequenciaDeBlocos(lista)));
        return saida;
    }

    /** RT-CAT-003 — os recursos de todos os blocos da lista, numa consulta. */
    private Map<UUID, Set<UUID>> recursosDe(List<UUID> blocoIds) {
        if (blocoIds.isEmpty()) {
            return Map.of();
        }
        var porBloco = new LinkedHashMap<UUID, Set<UUID>>();
        jdbc.sql("""
                select bloco_id, recurso_id
                  from bloco_exige_recurso
                 where bloco_id in (:ids)
                 order by bloco_id
                """)
                .param("ids", blocoIds)
                .query((rs, n) -> Map.entry(rs.getObject("bloco_id", UUID.class),
                        rs.getObject("recurso_id", UUID.class)))
                .list()
                .forEach(entrada -> porBloco
                        .computeIfAbsent(entrada.getKey(), id -> new LinkedHashSet<>())
                        .add(entrada.getValue()));
        return porBloco;
    }

    // ------------------------------------------------------------------ RT-CAT-002

    /**
     * O preço vigente numa data: o de maior {@code vigente_de} que já começou.
     *
     * <p>Uma linha, lida descendo pelo índice. Sem {@code vigente_ate} não há intervalo para
     * conferir nem duas linhas para manter coerentes — a coerência é consequência da consulta.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<Money> precoEm(UUID servicoId, LocalDate data) {
        return jdbc.sql("""
                select valor
                  from preco_do_servico
                 where servico_id = :servico
                   and vigente_de <= :data
                 order by vigente_de desc
                 limit 1
                """)
                .param("servico", servicoId)
                .param("data", data)
                .query((rs, linha) -> new Money(rs.getBigDecimal("valor")))
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PrecoVigente> historicoDePrecos(UUID servicoId) {
        return jdbc.sql("""
                select id, valor, vigente_de
                  from preco_do_servico
                 where servico_id = :servico
                 order by vigente_de desc
                """)
                .param("servico", servicoId)
                .query((rs, linha) -> new PrecoVigente(
                        rs.getObject("id", UUID.class),
                        new Money(rs.getBigDecimal("valor")),
                        rs.getDate("vigente_de").toLocalDate()))
                .list();
    }

    public UUID inserirPreco(UUID estabelecimentoId, UUID servicoId, Money valor,
                             LocalDate vigenteDe, UUID criadoPor) {
        return jdbc.sql("""
                insert into preco_do_servico
                    (estabelecimento_id, servico_id, valor, vigente_de, criado_por)
                values (:estabelecimento, :servico, :valor, :vigenteDe, :criadoPor)
                returning id
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("servico", servicoId)
                .param("valor", valor.valor())
                .param("vigenteDe", vigenteDe)
                .param("criadoPor", criadoPor)
                .query(UUID.class)
                .single();
    }

    /** Só remove o que ainda não começou a valer — ver {@code RemoverPrecoFuturoUseCase}. */
    public int removerPrecoFuturo(UUID precoId, LocalDate hoje) {
        return jdbc.sql("""
                delete from preco_do_servico
                 where id = :id and vigente_de > :hoje
                """)
                .param("id", precoId)
                .param("hoje", hoje)
                .update();
    }

    /** Ver {@code ServicoApi.idsExistentes} — a validação em lote que evita o laço de porId. */
    @Override
    @Transactional(readOnly = true)
    public Set<UUID> idsExistentes(java.util.Collection<UUID> candidatos) {
        if (candidatos.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql("select id from servico where id in (:ids)")
                .param("ids", List.copyOf(candidatos))
                .query(UUID.class)
                .list());
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
