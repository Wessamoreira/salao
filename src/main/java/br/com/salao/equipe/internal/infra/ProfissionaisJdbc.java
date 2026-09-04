package br.com.salao.equipe.internal.infra;

import br.com.salao.equipe.api.BloqueioDeJornada;
import br.com.salao.equipe.api.Habilidades;
import br.com.salao.equipe.api.IntervaloDeTrabalho;
import br.com.salao.equipe.api.MotivoDoBloqueio;
import br.com.salao.equipe.api.JornadaSemanal;
import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.equipe.api.ProfissionalResumo;
import br.com.salao.equipe.api.TipoDeVinculo;
import br.com.salao.equipe.internal.domain.Documento;
import br.com.salao.equipe.internal.domain.SubtracaoDeIntervalos;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EQP-001 — persistência do profissional.
 *
 * <p>{@code @Transactional} é obrigatório mesmo na leitura: é a transação que dispara o
 * {@code set_config('app.tenant_id')}. Fora dela, a RLS não encontra o tenant e a consulta
 * devolve zero linhas.
 *
 * <p>Nenhuma consulta filtra {@code estabelecimento_id}: quem faz isso é a RLS. Repetir o filtro
 * ensinaria que ele é a defesa.
 */
public class ProfissionaisJdbc implements ProfissionalApi {

    private static final String CAMPOS = """
            id, nome, apelido, documento, tipo_vinculo, telefone, email,
            comissao_percentual, contrato_inicio, contrato_fim, contrato_assinado_em,
            ativo, versao
            """;

    private static final RowMapper<ProfissionalResumo> RESUMO = (rs, linha) ->
            new ProfissionalResumo(
                    rs.getObject("id", UUID.class),
                    rs.getString("nome"),
                    rs.getString("apelido"),
                    rs.getString("documento"),
                    TipoDeVinculo.valueOf(rs.getString("tipo_vinculo")),
                    rs.getString("telefone"),
                    rs.getString("email"),
                    rs.getBigDecimal("comissao_percentual"),
                    data(rs.getDate("contrato_inicio")),
                    data(rs.getDate("contrato_fim")),
                    data(rs.getDate("contrato_assinado_em")),
                    rs.getBoolean("ativo"),
                    rs.getInt("versao"));

    private static LocalDate data(Date valor) {
        return valor == null ? null : valor.toLocalDate();
    }

    private final JdbcClient jdbc;

    public ProfissionaisJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID inserir(UUID estabelecimentoId, String nome, String apelido, Documento documento,
                        TipoDeVinculo vinculo, String telefone, String email,
                        BigDecimal comissao, LocalDate inicio, LocalDate fim,
                        LocalDate assinadoEm, UUID criadoPor) {
        return jdbc.sql("""
                insert into profissional
                    (estabelecimento_id, nome, apelido, documento, tipo_documento, tipo_vinculo,
                     telefone, email, comissao_percentual, contrato_inicio, contrato_fim,
                     contrato_assinado_em, criado_por)
                values (:estabelecimento, :nome, :apelido, :documento, :tipoDocumento, :vinculo,
                        :telefone, :email, :comissao, :inicio, :fim, :assinadoEm, :criadoPor)
                returning id
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("nome", nome)
                .param("apelido", apelido)
                .param("documento", documento.digitos())
                .param("tipoDocumento", documento.tipo().name())
                .param("vinculo", vinculo.name())
                .param("telefone", telefone)
                .param("email", email)
                .param("comissao", comissao)
                .param("inicio", inicio)
                .param("fim", fim)
                .param("assinadoEm", assinadoEm)
                .param("criadoPor", criadoPor)
                .query(UUID.class)
                .single();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProfissionalResumo> porId(UUID profissionalId) {
        return jdbc.sql("select " + CAMPOS + " from profissional where id = :id")
                .param("id", profissionalId)
                .query(RESUMO)
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProfissionalResumo> ativos() {
        return jdbc.sql("select " + CAMPOS + " from profissional where ativo order by nome")
                .query(RESUMO)
                .list();
    }

    /** Inclui os inativos: quem administra precisa ver quem saiu para poder trazer de volta. */
    @Transactional(readOnly = true)
    public List<ProfissionalResumo> todos() {
        return jdbc.sql("select " + CAMPOS + " from profissional order by ativo desc, nome")
                .query(RESUMO)
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean estaAtivo(UUID profissionalId) {
        return !jdbc.sql("select 1 from profissional where id = :id and ativo")
                .param("id", profissionalId)
                .query(Integer.class)
                .list()
                .isEmpty();
    }

    // ------------------------------------------------------------------ RT-EQP-002

    @Override
    @Transactional(readOnly = true)
    public JornadaSemanal jornadaDe(UUID profissionalId) {
        Map<DayOfWeek, List<IntervaloDeTrabalho>> porDia = new EnumMap<>(DayOfWeek.class);
        jdbc.sql("""
                select dia_semana, inicio, fim
                  from jornada_profissional
                 where profissional_id = :id
                 order by dia_semana, inicio
                """)
                .param("id", profissionalId)
                .query((rs, linha) -> Map.entry(
                        DayOfWeek.of(rs.getInt("dia_semana")),
                        new IntervaloDeTrabalho(rs.getTime("inicio").toLocalTime(),
                                rs.getTime("fim").toLocalTime())))
                .list()
                .forEach(entrada -> porDia
                        .computeIfAbsent(entrada.getKey(), d -> new ArrayList<>())
                        .add(entrada.getValue()));
        return new JornadaSemanal(porDia);
    }

    /**
     * Apaga a semana e regrava.
     *
     * <p>Substituir em vez de reconciliar: descobrir quais intervalos mudaram para aplicar
     * inserções e remoções é código que existe só para economizar duas escritas por semana — e
     * que erra na primeira jornada com dois intervalos no mesmo dia. A semana inteira são
     * dez linhas.
     *
     * <p>Na mesma transação, então não há instante em que o profissional apareça sem jornada.
     */
    public void substituirJornada(UUID estabelecimentoId, UUID profissionalId,
                                  JornadaSemanal jornada) {
        jdbc.sql("delete from jornada_profissional where profissional_id = :id")
                .param("id", profissionalId)
                .update();

        jornada.porDia().forEach((dia, intervalos) -> intervalos.forEach(intervalo ->
                jdbc.sql("""
                        insert into jornada_profissional
                            (estabelecimento_id, profissional_id, dia_semana, inicio, fim)
                        values (:estabelecimento, :profissional, :dia, :inicio, :fim)
                        """)
                        .param("estabelecimento", estabelecimentoId)
                        .param("profissional", profissionalId)
                        .param("dia", dia.getValue())
                        .param("inicio", LocalTime.from(intervalo.inicio()))
                        .param("fim", LocalTime.from(intervalo.fim()))
                        .update()));
    }

    // ------------------------------------------------------------------ RT-EQP-003

    /**
     * Traz os bloqueios do profissional <strong>e os do salão inteiro</strong>.
     *
     * <p>O {@code profissional_id is null} não é caso especial esquecido: é o feriado. Deixá-lo
     * de fora faria o salão aparecer aberto no Natal para todo mundo.
     */
    private static final String BLOQUEIOS = """
            select id, profissional_id, motivo, observacao,
                   data_inicio, data_fim, hora_inicio, hora_fim
              from bloqueio_de_jornada
             where (profissional_id = :profissional or profissional_id is null)
               and data_inicio <= :fim
               and data_fim >= :inicio
             order by data_inicio, hora_inicio nulls first
            """;

    private static final RowMapper<BloqueioDeJornada> BLOQUEIO = (rs, linha) ->
            new BloqueioDeJornada(
                    rs.getObject("id", UUID.class),
                    rs.getObject("profissional_id", UUID.class),
                    MotivoDoBloqueio.valueOf(rs.getString("motivo")),
                    rs.getString("observacao"),
                    rs.getDate("data_inicio").toLocalDate(),
                    rs.getDate("data_fim").toLocalDate(),
                    hora(rs.getTime("hora_inicio")),
                    hora(rs.getTime("hora_fim")));

    private static LocalTime hora(java.sql.Time valor) {
        return valor == null ? null : valor.toLocalTime();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BloqueioDeJornada> bloqueiosEntre(UUID profissionalId, LocalDate inicio,
                                                  LocalDate fim) {
        return jdbc.sql(BLOQUEIOS)
                .param("profissional", profissionalId)
                .param("inicio", inicio)
                .param("fim", fim)
                .query(BLOQUEIO)
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<IntervaloDeTrabalho> intervalosEm(UUID profissionalId, LocalDate data) {
        List<IntervaloDeTrabalho> doDia = jornadaDe(profissionalId).em(data.getDayOfWeek());
        if (doDia.isEmpty()) {
            // Sem jornada não há o que subtrair, e a consulta de bloqueios seria desperdício.
            return List.of();
        }
        List<IntervaloDeTrabalho> removidos = bloqueiosEntre(profissionalId, data, data).stream()
                .map(bloqueio -> bloqueio.faixaEm(data))
                .filter(java.util.Objects::nonNull)
                .toList();
        return SubtracaoDeIntervalos.subtrair(doDia, removidos);
    }

    public UUID inserirBloqueio(UUID estabelecimentoId, UUID profissionalId,
                                MotivoDoBloqueio motivo, String observacao, LocalDate dataInicio,
                                LocalDate dataFim, LocalTime horaInicio, LocalTime horaFim,
                                UUID criadoPor) {
        return jdbc.sql("""
                insert into bloqueio_de_jornada
                    (estabelecimento_id, profissional_id, motivo, observacao,
                     data_inicio, data_fim, hora_inicio, hora_fim, criado_por)
                values (:estabelecimento, :profissional, :motivo, :observacao,
                        :dataInicio, :dataFim, :horaInicio, :horaFim, :criadoPor)
                returning id
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("profissional", profissionalId)
                .param("motivo", motivo.name())
                .param("observacao", observacao)
                .param("dataInicio", dataInicio)
                .param("dataFim", dataFim)
                .param("horaInicio", horaInicio)
                .param("horaFim", horaFim)
                .param("criadoPor", criadoPor)
                .query(UUID.class)
                .single();
    }

    public int removerBloqueio(UUID id) {
        return jdbc.sql("delete from bloqueio_de_jornada where id = :id")
                .param("id", id)
                .update();
    }

    public int definirAtivacao(UUID id, boolean ativo) {
        return jdbc.sql("""
                update profissional set ativo = :ativo, versao = versao + 1
                 where id = :id and ativo <> :ativo
                """)
                .param("id", id)
                .param("ativo", ativo)
                .update();
    }

    // ------------------------------------------------------------------ RT-EQP-004

    /**
     * Uma consulta só, e ela precisa distinguir três respostas: "executa", "não executa" e
     * "profissional não existe / está inativo".
     *
     * <p>O {@code where ativo} está na consulta e não em quem chama de propósito — quem não
     * trabalha mais não executa nada, e deixar as duas checagens separadas seria convidar a
     * esquecer uma delas em algum dos chamadores.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean executa(UUID profissionalId, UUID servicoId) {
        return jdbc.sql("""
                select p.executa_todos_os_servicos
                    or exists (select 1 from habilidade h
                                where h.profissional_id = p.id and h.servico_id = :servico)
                  from profissional p
                 where p.id = :profissional and p.ativo
                """)
                .param("profissional", profissionalId)
                .param("servico", servicoId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public Habilidades habilidadesDe(UUID profissionalId) {
        Boolean todos = jdbc.sql(
                "select executa_todos_os_servicos from profissional where id = :id")
                .param("id", profissionalId)
                .query(Boolean.class)
                .optional()
                .orElse(true);

        var servicos = new LinkedHashSet<>(jdbc.sql(
                "select servico_id from habilidade where profissional_id = :id order by servico_id")
                .param("id", profissionalId)
                .query(UUID.class)
                .list());

        // Não passa pelo construtor de Habilidades: ele recusa "restrito e vazio", e o banco pode
        // legitimamente ter essa combinação num profissional cujo único serviço saiu do cardápio.
        // Recusar na LEITURA transformaria um dado velho em erro 500 numa tela de consulta.
        return todos || !servicos.isEmpty()
                ? new Habilidades(todos, servicos)
                : Habilidades.todosOsServicos();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProfissionalResumo> queExecutam(UUID servicoId) {
        return jdbc.sql("select " + CAMPOS + """
                  from profissional p
                 where p.ativo
                   and (p.executa_todos_os_servicos
                        or exists (select 1 from habilidade h
                                    where h.profissional_id = p.id
                                      and h.servico_id = :servico))
                 order by nome
                """)
                .param("servico", servicoId)
                .query(RESUMO)
                .list();
    }

    /**
     * Substitui o regime e a lista inteira, como a jornada (RT-EQP-002).
     *
     * <p>Reconciliar o que entrou e o que saiu seria código existindo para economizar escritas num
     * conjunto de dezenas de linhas — e que erra na primeira vez que alguém desmarcar e remarcar o
     * mesmo serviço. Substituir é mais simples e não tem estado intermediário.
     */
    public void substituirHabilidades(UUID estabelecimentoId, UUID profissionalId,
                                      Habilidades habilidades, UUID criadoPor) {
        jdbc.sql("update profissional set executa_todos_os_servicos = :todos where id = :id")
                .param("id", profissionalId)
                .param("todos", habilidades.executaTodos())
                .update();

        jdbc.sql("delete from habilidade where profissional_id = :id")
                .param("id", profissionalId)
                .update();

        for (UUID servicoId : habilidades.servicos()) {
            jdbc.sql("""
                    insert into habilidade
                        (estabelecimento_id, profissional_id, servico_id, criado_por)
                    values (:estabelecimento, :profissional, :servico, :criadoPor)
                    """)
                    .param("estabelecimento", estabelecimentoId)
                    .param("profissional", profissionalId)
                    .param("servico", servicoId)
                    .param("criadoPor", criadoPor)
                    .update();
        }
    }
}
