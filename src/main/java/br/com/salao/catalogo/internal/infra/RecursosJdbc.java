package br.com.salao.catalogo.internal.infra;

import br.com.salao.catalogo.api.Recurso;
import br.com.salao.catalogo.api.RecursoApi;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CAT-003 — persistência dos recursos.
 *
 * <p>{@code @Transactional} também na leitura: é a transação que dispara o
 * {@code set_config('app.tenant_id')}, sem o qual a política de tenant não devolve linha nenhuma.
 */
public class RecursosJdbc implements RecursoApi {

    private final JdbcClient jdbc;

    public RecursosJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID inserir(UUID estabelecimentoId, String nome, int quantidade, UUID criadoPor) {
        return jdbc.sql("""
                insert into recurso (estabelecimento_id, nome, quantidade, criado_por)
                values (:estabelecimento, :nome, :quantidade, :criadoPor)
                returning id
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("nome", nome)
                .param("quantidade", quantidade)
                .param("criadoPor", criadoPor)
                .query(UUID.class)
                .single();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Recurso> porId(UUID recursoId) {
        return jdbc.sql("""
                select id, nome, quantidade, ativo, versao from recurso where id = :id
                """)
                .param("id", recursoId)
                .query(RecursosJdbc::mapear)
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Recurso> ativos() {
        return listar(true);
    }

    /** Inclui os desativados: quem administra precisa ver o que saiu de operação. */
    @Transactional(readOnly = true)
    public List<Recurso> todos() {
        return listar(false);
    }

    private List<Recurso> listar(boolean apenasAtivos) {
        return jdbc.sql("""
                select id, nome, quantidade, ativo, versao
                  from recurso
                 where (:apenasAtivos = false or ativo)
                 order by ativo desc, nome
                """)
                .param("apenasAtivos", apenasAtivos)
                .query(RecursosJdbc::mapear)
                .list();
    }

    /**
     * Quais destes ids são recursos ativos <strong>deste salão</strong>.
     *
     * <p>É esta consulta, e não a chave estrangeira, que separa os salões: no PostgreSQL as
     * checagens de integridade referencial rodam como o dono da tabela e não passam pela RLS,
     * então a FK aceitaria um recurso alheio. Aqui a política de tenant se aplica normalmente, e
     * o id de outro salão simplesmente não volta.
     */
    @Transactional(readOnly = true)
    public Set<UUID> idsAtivos(Collection<UUID> candidatos) {
        if (candidatos.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql("select id from recurso where id in (:ids) and ativo")
                .param("ids", List.copyOf(candidatos))
                .query(UUID.class)
                .list());
    }

    /**
     * Os serviços <strong>ativos</strong> que exigem este recurso, em ordem alfabética.
     *
     * <p>Só os ativos: um serviço desativado não pode ser marcado, então não há agenda para
     * quebrar. É também a saída do salão que precisa mesmo aposentar um recurso — tira o serviço
     * do cardápio primeiro.
     */
    @Transactional(readOnly = true)
    public List<String> servicosAtivosQueExigem(UUID recursoId) {
        return jdbc.sql("""
                select distinct s.nome
                  from bloco_exige_recurso ber
                  join bloco_do_servico b on b.id = ber.bloco_id
                  join servico s on s.id = b.servico_id
                 where ber.recurso_id = :recurso
                   and s.ativo
                 order by s.nome
                """)
                .param("recurso", recursoId)
                .query(String.class)
                .list();
    }

    public int atualizar(UUID id, int versao, String nome, int quantidade) {
        return jdbc.sql("""
                update recurso
                   set nome = :nome, quantidade = :quantidade, versao = versao + 1
                 where id = :id and versao = :versao
                """)
                .param("id", id)
                .param("versao", versao)
                .param("nome", nome)
                .param("quantidade", quantidade)
                .update();
    }

    public int definirAtivacao(UUID id, boolean ativo) {
        return jdbc.sql("""
                update recurso set ativo = :ativo, versao = versao + 1
                 where id = :id and ativo <> :ativo
                """)
                .param("id", id)
                .param("ativo", ativo)
                .update();
    }

    private static Recurso mapear(ResultSet rs, int linha) throws SQLException {
        return new Recurso(rs.getObject("id", UUID.class), rs.getString("nome"),
                rs.getInt("quantidade"), rs.getBoolean("ativo"), rs.getInt("versao"));
    }
}
