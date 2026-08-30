package br.com.salao.cliente.internal.infra;

import br.com.salao.cliente.api.ClienteApi;
import br.com.salao.cliente.api.ClienteResumo;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CLI-001 — persistência do cliente.
 *
 * <p>{@code JdbcClient}, sem entidade JPA: o cadastro é uma linha simples, e agregado só se paga
 * quando há invariante entre campos para proteger.
 *
 * <p>{@code @Transactional} é <strong>obrigatório</strong> mesmo na leitura: é a transação que
 * dispara o {@code set_config('app.tenant_id')}. Fora dela, em autocommit, a RLS não encontra o
 * tenant e a consulta devolve zero linhas — falha fechada, mas confusa para quem investiga.
 *
 * <p>Nenhuma consulta filtra {@code estabelecimento_id} na cláusula {@code where}: quem faz isso é
 * a RLS. Repetir o filtro em SQL ensinaria que ele é a defesa, e o dia em que alguém esquecesse
 * seria o dia do vazamento entre tenants.
 */
public class ClientesJdbc implements ClienteApi {

    private static final String INSERIR = """
            insert into cliente (estabelecimento_id, nome, apelido, telefone, email,
                                 observacao, criado_por)
            values (:estabelecimento, :nome, :apelido, :telefone, :email, :observacao, :criadoPor)
            returning id
            """;

    private static final String BUSCAR = """
            select id, nome, apelido, telefone, email
              from cliente
             where id = :id and anonimizado_em is null
            """;

    private static final String EXISTE = """
            select 1 from cliente where id = :id and anonimizado_em is null
            """;

    private final JdbcClient jdbc;

    public ClientesJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID inserir(UUID estabelecimentoId, String nome, String apelido, String telefone,
                        String email, String observacao, UUID criadoPor) {
        return jdbc.sql(INSERIR)
                .param("estabelecimento", estabelecimentoId)
                .param("nome", nome)
                .param("apelido", apelido)
                .param("telefone", telefone)
                .param("email", email)
                .param("observacao", observacao)
                .param("criadoPor", criadoPor)
                .query(UUID.class)
                .single();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ClienteResumo> porId(UUID clienteId) {
        return jdbc.sql(BUSCAR)
                .param("id", clienteId)
                .query((rs, linha) -> new ClienteResumo(
                        rs.getObject("id", UUID.class),
                        rs.getString("nome"),
                        rs.getString("apelido"),
                        rs.getString("telefone"),
                        rs.getString("email")))
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existe(UUID clienteId) {
        return !jdbc.sql(EXISTE).param("id", clienteId).query(Integer.class).list().isEmpty();
    }
}
