package br.com.salao.cliente.internal.infra;

import br.com.salao.cliente.api.ClienteApi;
import br.com.salao.cliente.api.ClienteResumo;
import br.com.salao.cliente.internal.domain.TermoDeBusca;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
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

    /**
     * RT-CLI-002 — busca por nome ou apelido.
     *
     * <p>A normalização (minúsculas + sem acento) é aplicada ao termo pela MESMA função que gerou
     * a coluna {@code busca}. É o ponto inteiro do desenho: se a normalização do que está guardado
     * e a do que foi digitado morassem em lugares diferentes, um dia divergiriam — e a busca
     * pararia de achar gente sem erro nenhum.
     *
     * <p>A ordenação existe porque relevância aqui é perceptível: quem digita "sil" espera "Silva"
     * antes de "Silvana Nascimento". Começo do nome primeiro, começo de qualquer palavra depois,
     * semelhança em seguida, e o nome como desempate estável — sem ele a mesma busca devolveria
     * ordens diferentes entre execuções.
     */
    private static final String BUSCAR_POR_NOME = """
            with t as (
              select imutavel_unaccent(lower(:termo))  as termo,
                     imutavel_unaccent(lower(:padrao)) as padrao
            )
            select c.id, c.nome, c.apelido, c.telefone, c.email
              from cliente c, t
             where c.anonimizado_em is null
               and c.busca like '%' || t.padrao || '%' escape '\\'
             order by (c.busca like t.padrao || '%' escape '\\') desc,
                      (c.busca like '% ' || t.padrao || '%' escape '\\') desc,
                      similarity(c.busca, t.termo) desc,
                      c.nome
             limit :limite
            """;

    /**
     * RT-CLI-002 — busca pelo fim do telefone (R-UX-03).
     *
     * <p>Sufixo, não prefixo: o começo do número é país e DDD, iguais para quase todo o salão.
     * Quem olha o identificador de chamadas lê os últimos dígitos.
     *
     * <p>A consulta é escrita sobre o número <strong>invertido</strong> para que o sufixo vire
     * prefixo e o índice btree possa servi-la. Escrevê-la como {@code telefone like '%' ||
     * digitos} daria o mesmo resultado e nenhum índice — a diferença aparece quando o salão tem
     * dezenas de milhares de fichas, e some do radar até lá.
     */
    private static final String BUSCAR_POR_TELEFONE = """
            select id, nome, apelido, telefone, email
              from cliente
             where telefone is not null
               and anonimizado_em is null
               and reverse(telefone) like reverse(:digitos) || '%'
             order by nome
             limit :limite
            """;

    private static final RowMapper<ClienteResumo> RESUMO = (rs, linha) -> new ClienteResumo(
            rs.getObject("id", UUID.class),
            rs.getString("nome"),
            rs.getString("apelido"),
            rs.getString("telefone"),
            rs.getString("email"));

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
        return jdbc.sql(BUSCAR).param("id", clienteId).query(RESUMO).optional();
    }

    @Transactional(readOnly = true)
    public List<ClienteResumo> buscar(TermoDeBusca termo, int limite) {
        return switch (termo) {
            case TermoDeBusca.PorNome(String texto) -> jdbc.sql(BUSCAR_POR_NOME)
                    .param("termo", texto)
                    .param("padrao", comoPadrao(texto))
                    .param("limite", limite)
                    .query(RESUMO)
                    .list();
            case TermoDeBusca.PorTelefone(String digitos) -> jdbc.sql(BUSCAR_POR_TELEFONE)
                    .param("digitos", digitos)
                    .param("limite", limite)
                    .query(RESUMO)
                    .list();
            case TermoDeBusca.Insuficiente ignorado -> List.of();
        };
    }

    /**
     * Neutraliza os curingas do {@code LIKE}.
     *
     * <p>Sem isto, quem digitasse {@code %} veria o salão inteiro, e {@code _} casaria com
     * qualquer letra. Não é injeção — o valor vai como parâmetro —, é resultado errado: a busca
     * devolveria gente que não corresponde ao que foi pedido.
     *
     * <p>Fica aqui, e não no domínio: {@code LIKE} é detalhe de como este repositório consulta,
     * e o domínio não deve saber que existe SQL do outro lado.
     */
    private static String comoPadrao(String termo) {
        return termo.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existe(UUID clienteId) {
        return !jdbc.sql(EXISTE).param("id", clienteId).query(Integer.class).list().isEmpty();
    }
}
