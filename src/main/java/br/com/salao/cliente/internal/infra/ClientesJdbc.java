package br.com.salao.cliente.internal.infra;

import br.com.salao.cliente.api.CanalDeConsentimento;
import br.com.salao.cliente.api.ClienteApi;
import br.com.salao.cliente.api.ClienteResumo;
import br.com.salao.cliente.api.EstadoDoConsentimento;
import br.com.salao.cliente.api.Finalidade;
import br.com.salao.cliente.internal.domain.TermoDeBusca;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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

    /**
     * RT-CLI-003 — a leitura segue o redirecionamento da mesclagem.
     *
     * <p>Quem guardou o id de uma ficha que depois foi absorvida continua achando a pessoa certa,
     * sem que a mesclagem precise reescrever referência nenhuma. O {@code left join} tem um salto
     * só porque {@code redirecionar} mantém as cadeias planas.
     */
    private static final String BUSCAR = """
            select coalesce(destino.id, c.id)             as id,
                   coalesce(destino.nome, c.nome)         as nome,
                   coalesce(destino.apelido, c.apelido)   as apelido,
                   coalesce(destino.telefone, c.telefone) as telefone,
                   coalesce(destino.email, c.email)       as email,
                   coalesce(destino.versao, c.versao)     as versao
              from cliente c
              left join cliente destino on destino.id = c.mesclado_para
             where c.id = :id
               and c.anonimizado_em is null
               and coalesce(destino.anonimizado_em, c.anonimizado_em) is null
            """;

    private static final String EXISTE = """
            select 1
              from cliente c
              left join cliente destino on destino.id = c.mesclado_para
             where c.id = :id
               and c.anonimizado_em is null
               and coalesce(destino.anonimizado_em, c.anonimizado_em) is null
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
    static final String BUSCAR_POR_NOME = """
            with t as (
              select imutavel_unaccent(lower(:termo))  as termo,
                     imutavel_unaccent(lower(:padrao)) as padrao
            )
            select c.id, c.nome, c.apelido, c.telefone, c.email, c.versao
              from cliente c, t
             where c.anonimizado_em is null
               and c.mesclado_para is null
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
    static final String BUSCAR_POR_TELEFONE = """
            select id, nome, apelido, telefone, email, versao
              from cliente
             where telefone is not null
               and anonimizado_em is null
               and mesclado_para is null
               and reverse(telefone) like reverse(:digitos) || '%'
             order by nome
             limit :limite
            """;

    /**
     * RT-CLI-003 — candidatos a duplicado.
     *
     * <p>O {@code %} do pg_trgm é a rede barata: usa o índice e traz quem tem chance. A decisão
     * fina fica no domínio ({@code Semelhanca}), que compara primeiro nome — o escore sozinho não
     * separa "Mariana"/"Maria" de "José Antônio"/"José Antônio da Costa".
     *
     * <p>O escore é calculado sobre {@code nome} e não sobre {@code busca}: {@code busca} inclui o
     * apelido, e um apelido longo derrubaria a semelhança de nomes idênticos.
     */
    private static final String CANDIDATOS = """
            with t as (select imutavel_unaccent(lower(:nome)) as nome_norm)
            select c.id, c.nome, c.apelido, c.telefone, c.email, c.versao,
                   similarity(imutavel_unaccent(lower(c.nome)), t.nome_norm) as escore,
                   (c.telefone is not null and c.telefone = cast(:telefone as text))
                       as mesmo_telefone
              from cliente c, t
             where c.anonimizado_em is null
               and c.mesclado_para is null
               and c.id is distinct from cast(:excluir as uuid)
               and ((c.telefone is not null and c.telefone = cast(:telefone as text))
                    or c.busca % t.nome_norm)
             order by escore desc, c.nome
             limit :limite
            """;

    private static final RowMapper<ClienteResumo> RESUMO = (rs, linha) -> new ClienteResumo(
            rs.getObject("id", UUID.class),
            rs.getString("nome"),
            rs.getString("apelido"),
            rs.getString("telefone"),
            rs.getString("email"),
            rs.getInt("versao"));

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

    /** RT-CLI-003 — traz quem pode ser a mesma pessoa, com o que o domínio precisa para decidir. */
    @Transactional(readOnly = true)
    public List<CandidatoADuplicado> candidatos(String nome, String telefone, UUID excluir,
                                                int limite) {
        return jdbc.sql(CANDIDATOS)
                .param("nome", nome)
                .param("telefone", telefone)
                .param("excluir", excluir)
                .param("limite", limite)
                .query((rs, linha) -> new CandidatoADuplicado(
                        RESUMO.mapRow(rs, linha),
                        rs.getDouble("escore"),
                        rs.getBoolean("mesmo_telefone")))
                .list();
    }

    /** Uma ficha existente e o que se sabe da comparação dela com o que foi digitado. */
    public record CandidatoADuplicado(ClienteResumo cliente, double escore,
                                      boolean mesmoTelefone) {
    }

    /** Estado de uma ficha para as validações da mesclagem. */
    public record EstadoDaFicha(UUID id, UUID mescladoPara, boolean anonimizado) {
    }

    @Transactional(readOnly = true)
    public Optional<EstadoDaFicha> estado(UUID id) {
        return jdbc.sql("select id, mesclado_para, anonimizado_em from cliente where id = :id")
                .param("id", id)
                .query((rs, linha) -> new EstadoDaFicha(
                        rs.getObject("id", UUID.class),
                        rs.getObject("mesclado_para", UUID.class),
                        rs.getTimestamp("anonimizado_em") != null))
                .optional();
    }

    /**
     * RT-CLI-003 — completa os vazios da ficha sobrevivente com o que a absorvida tinha.
     *
     * <p>{@code coalesce} e nunca sobrescrita: o dado que já está lá foi conferido por alguém, e
     * um dado bom substituído por outro bom não melhora nada — mas um bom substituído por um
     * velho é perda silenciosa.
     *
     * <p>A observação é a exceção: as duas são concatenadas, porque texto livre costuma guardar a
     * única anotação que existe sobre a pessoa ("alérgica a amônia") e descartá-la seria perder
     * informação que ninguém sabe que existiu.
     */
    public void absorverCampos(UUID sobrevivente, UUID absorvida) {
        jdbc.sql("""
                update cliente s set
                    apelido    = coalesce(s.apelido, d.apelido),
                    telefone   = coalesce(s.telefone, d.telefone),
                    email      = coalesce(s.email, d.email),
                    observacao = case
                        when s.observacao is null then d.observacao
                        when d.observacao is null then s.observacao
                        else s.observacao || E'\\n---\\n' || d.observacao
                    end,
                    versao     = s.versao + 1
                  from cliente d
                 where s.id = :sobrevivente and d.id = :absorvida
                """)
                .param("sobrevivente", sobrevivente)
                .param("absorvida", absorvida)
                .update();
    }

    /**
     * Marca a absorvida e <strong>repõe as cadeias em um salto só</strong>.
     *
     * <p>O segundo update é o que impede cadeia: quem já apontava para a ficha absorvida passa a
     * apontar direto para a sobrevivente. Sem ele, A→B→C exigiria seguir ponteiros em toda leitura
     * — e uma leitura recursiva é onde um ciclo acidental vira laço infinito em produção.
     */
    public void redirecionar(UUID absorvida, UUID sobrevivente, Instant quando) {
        jdbc.sql("""
                update cliente
                   set mesclado_para = :sobrevivente, mesclado_em = :quando,
                       versao = versao + 1
                 where id = :absorvida
                """)
                .param("absorvida", absorvida)
                .param("sobrevivente", sobrevivente)
                .param("quando", java.sql.Timestamp.from(quando))
                .update();

        jdbc.sql("""
                update cliente
                   set mesclado_para = :sobrevivente
                 where mesclado_para = :absorvida
                """)
                .param("absorvida", absorvida)
                .param("sobrevivente", sobrevivente)
                .update();
    }

    // ------------------------------------------------------------------ RT-CLI-004

    /**
     * A decisão vigente de cada finalidade.
     *
     * <p>{@code distinct on} com a ordenação casada ao índice pega a linha mais recente por
     * finalidade sem ler o histórico inteiro. O desempate por {@code id} importa: duas decisões
     * na mesma transação compartilham o instante, e sem ele a "vigente" seria escolhida ao acaso.
     *
     * <p>A cláusula que também aceita fichas <strong>absorvidas</strong> (RT-CLI-003) é o que faz
     * a mesclagem não precisar mexer em consentimento: se a pessoa revogou numa ficha e a outra
     * ainda dizia "sim", a decisão mais recente vence — que é a única leitura defensável. Sem
     * isso, mesclar poderia ressuscitar um consentimento revogado.
     */
    private static final String CONSENTIMENTOS_VIGENTES = """
            select distinct on (finalidade)
                   finalidade, concedido, canal, registrado_em
              from cliente_consentimento
             where cliente_id = :id
                or cliente_id in (select id from cliente where mesclado_para = :id)
             order by finalidade, registrado_em desc, id desc
            """;

    public void registrarConsentimento(UUID estabelecimentoId, UUID clienteId,
                                       Finalidade finalidade, boolean concedido,
                                       CanalDeConsentimento canal,
                                       Instant quando, UUID registradoPor) {
        jdbc.sql("""
                insert into cliente_consentimento
                    (estabelecimento_id, cliente_id, finalidade, concedido, canal,
                     registrado_em, registrado_por)
                values (:estabelecimento, :cliente, :finalidade, :concedido, :canal,
                        :quando, :por)
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("cliente", clienteId)
                .param("finalidade", finalidade.name())
                .param("concedido", concedido)
                .param("canal", canal.name())
                .param("quando", java.sql.Timestamp.from(quando))
                .param("por", registradoPor)
                .update();
    }

    @Override
    @Transactional(readOnly = true)
    public List<EstadoDoConsentimento> consentimentos(UUID clienteId) {
        Map<Finalidade, EstadoDoConsentimento> registrados = new EnumMap<>(Finalidade.class);
        jdbc.sql(CONSENTIMENTOS_VIGENTES)
                .param("id", clienteId)
                .query((rs, linha) -> EstadoDoConsentimento.registrado(
                        Finalidade.valueOf(rs.getString("finalidade")),
                        rs.getBoolean("concedido"),
                        rs.getTimestamp("registrado_em").toInstant(),
                        CanalDeConsentimento.valueOf(rs.getString("canal"))))
                .list()
                .forEach(estado -> registrados.put(estado.finalidade(), estado));

        // Toda finalidade aparece, inclusive a que ninguém perguntou: uma lista que só mostra o
        // que foi registrado esconde justamente o que falta perguntar.
        return java.util.Arrays.stream(Finalidade.values())
                .map(f -> registrados.getOrDefault(f, EstadoDoConsentimento.semRegistro(f)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean podeReceber(UUID clienteId, Finalidade finalidade) {
        if (!existe(clienteId)) {
            // Falha fechada: cliente que não existe (ou é de outro tenant, ou foi anonimizado)
            // não recebe nada. O contrário mandaria mensagem para quem pediu para ser esquecido.
            return false;
        }
        return consentimentos(clienteId).stream()
                .filter(estado -> estado.finalidade() == finalidade)
                .findFirst()
                .map(EstadoDoConsentimento::podeReceber)
                .orElse(finalidade.permitidoSemRegistro());
    }

    // ------------------------------------------------------------------ RT-CLI-005

    /**
     * RT-CLI-005 — apaga o dado pessoal e mantém a linha.
     *
     * <p>Alcança <strong>também as fichas absorvidas</strong> por esta (RT-CLI-003). Elas
     * continuam guardando o nome e o telefone com que foram cadastradas, e são a mesma pessoa:
     * anonimizar só a sobrevivente deixaria o dado intacto na ficha ao lado, exatamente onde
     * ninguém pensaria em olhar.
     *
     * <p>{@code nome} recebe um rótulo em vez de ficar vazio porque a coluna é {@code not null} e
     * relatório financeiro precisa de algo para mostrar. Ele não identifica ninguém — e a coluna
     * {@code busca}, sendo gerada, se recalcula sozinha a partir dele.
     *
     * @return quantas linhas foram anonimizadas
     */
    public int anonimizar(UUID clienteId, String rotulo, Instant quando) {
        return jdbc.sql("""
                update cliente
                   set nome = :rotulo,
                       apelido = null,
                       telefone = null,
                       email = null,
                       observacao = null,
                       anonimizado_em = :quando,
                       versao = versao + 1
                 where (id = :id or mesclado_para = :id)
                   and anonimizado_em is null
                """)
                .param("id", clienteId)
                .param("rotulo", rotulo)
                .param("quando", java.sql.Timestamp.from(quando))
                .update();
    }

    // ------------------------------------------------------------------ RT-CLI-007

    /**
     * RT-CLI-007 — corrigir os dados da ficha, com trava otimista.
     *
     * <p>O {@code where versao = :versao} é o que impede a segunda pessoa a salvar de apagar a
     * correção da primeira sem ninguém perceber. Zero linhas afetadas quer dizer uma de duas
     * coisas — ficha inexistente ou versão vencida —, e quem chama distingue consultando depois.
     *
     * <p>Trava otimista e não bloqueio: duas recepções corrigindo a mesma ficha no mesmo segundo é
     * raro, e segurar linha por um caso raro custa em todos os outros.
     *
     * @return quantas linhas foram alteradas: 1 ou 0
     */
    public int atualizar(UUID id, int versao, String nome, String apelido, String telefone,
                         String email, String observacao) {
        return jdbc.sql("""
                update cliente
                   set nome = :nome,
                       apelido = :apelido,
                       telefone = :telefone,
                       email = :email,
                       observacao = :observacao,
                       versao = versao + 1
                 where id = :id
                   and versao = :versao
                   and anonimizado_em is null
                   and mesclado_para is null
                """)
                .param("id", id)
                .param("versao", versao)
                .param("nome", nome)
                .param("apelido", apelido)
                .param("telefone", telefone)
                .param("email", email)
                .param("observacao", observacao)
                .update();
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
