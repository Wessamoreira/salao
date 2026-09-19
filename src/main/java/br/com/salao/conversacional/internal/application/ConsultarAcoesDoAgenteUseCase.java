package br.com.salao.conversacional.internal.application;

import br.com.salao.conversacional.api.ErrosDoConversacional;
import br.com.salao.conversacional.internal.infra.AcoesDoAgenteJdbc;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.NomesDeUsuariosApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.paginacao.Cursor;
import br.com.salao.shared.paginacao.Pagina;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * RT-CNV-007 — o painel de revisão: o que o agente fez, em nome de quem, e o que as trancas
 * recusaram.
 *
 * <h2>Quem vê</h2>
 *
 * <p>{@code agente:review} — o dono e o gerente. O painel mostra mensagens de quem escreveu, inclusive
 * de número desconhecido, e o que o agente consultou em nome de cada pessoa: não é a recepção
 * olhando a si mesma.
 *
 * <h2>O período é do salão, e tem teto</h2>
 *
 * <p>Os dias são dias civis no fuso do salão (ADR-0009). Sem período, os últimos sete dias; com
 * período, no máximo {@value #DIAS_MAXIMOS} — uma consulta por um ano inteiro de trilha é um
 * relatório, e relatório é outra rotina.
 *
 * <h2>Só leitura</h2>
 *
 * <p>Revisar não muda nada: nem "marcar como revisado". Quem precisa agir sobre o que viu age pela
 * tela do assunto — a agenda, a equipe.
 */
public class ConsultarAcoesDoAgenteUseCase {

    public static final int LIMITE_PADRAO = 30;
    public static final int LIMITE_MAXIMO = 100;
    public static final int DIAS_MAXIMOS = 92;
    static final int DIAS_PADRAO = 7;

    private final AcoesDoAgenteJdbc acoes;
    private final NomesDeUsuariosApi nomes;
    private final EstabelecimentoApi estabelecimentos;
    private final Relogio relogio;

    public ConsultarAcoesDoAgenteUseCase(AcoesDoAgenteJdbc acoes, NomesDeUsuariosApi nomes,
                                         EstabelecimentoApi estabelecimentos, Relogio relogio) {
        this.acoes = acoes;
        this.nomes = nomes;
        this.estabelecimentos = estabelecimentos;
        this.relogio = relogio;
    }

    /** O que mostrar: tudo, só o que gravou no domínio, ou só o que alguma tranca recusou. */
    public enum Recorte {
        TODAS,
        GRAVARAM,
        BLOQUEADAS
    }

    /** @param de, ate dias civis no fuso do salão, os dois inclusivos; nulos, os últimos sete dias */
    public record Filtro(Recorte recorte, UUID usuarioId, LocalDate de, LocalDate ate) {
    }

    public record Chamada(String nome, String desfecho, JsonNode argumentos) {
    }

    public record IntencaoDaAcao(String ferramenta, String resumo, String status, String motivo,
                                 String papel) {
    }

    /**
     * @param quemPediu     o nome de hoje de quem estava por trás; nulo sem usuário efetivo
     * @param telefoneFinal os quatro últimos dígitos, só quando não há usuário — o número
     *                      desconhecido e a profissional sem conta
     */
    public record Acao(UUID id, Instant ocorridaEm, String tipo, String desfecho, String motivoRecusa,
                       UUID usuarioEfetivoId, String quemPediu, String telefoneFinal,
                       boolean veioDeAudio, String pedido, String resposta,
                       List<Chamada> ferramentas, List<IntencaoDaAcao> intencoes, boolean gravou,
                       boolean bloqueada, String modelo, int tokensEntrada, int tokensSaida,
                       BigDecimal custoUsd, Instant anonimizadaEm) {
    }

    public record Resumo(LocalDate de, LocalDate ate, long conversas, long gravaram,
                         long bloqueadas, BigDecimal custoUsd, long tokensEntrada,
                         long tokensSaida) {
    }

    public record Pessoa(UUID usuarioId, String nome, long conversas) {
    }

    @PreAuthorize("hasAuthority('" + Permissao.AGENTE_REVISAR + "')")
    @Transactional(readOnly = true)
    public Pagina<Acao> listar(Filtro filtro, String cursor, Integer limite) {
        var consulta = consulta(filtro);
        int quantos = limite == null || limite < 1 ? LIMITE_PADRAO : Math.min(limite, LIMITE_MAXIMO);

        Instant antesDe = null;
        UUID antesDoId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] valores = Cursor.decodificar(cursor).valores();
                antesDe = Instant.parse(valores[0]);
                antesDoId = UUID.fromString(valores[1]);
            } catch (IllegalArgumentException | DateTimeParseException
                     | ArrayIndexOutOfBoundsException e) {
                throw new ErroDeDominio(ErrosDoConversacional.FILTRO_INVALIDO,
                        "cursor de paginação inválido");
            }
        }

        var linhas = acoes.listar(consulta, antesDe, antesDoId, quantos + 1);
        var nomesDaPagina = nomes.nomesDe(linhas.stream()
                .map(AcoesDoAgenteJdbc.Linha::usuarioEfetivoId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        return Pagina.de(linhas, quantos,
                        linha -> Cursor.de(linha.ocorridaEm().toString(), linha.id().toString()))
                .mapear(linha -> acao(linha, nomesDaPagina));
    }

    @PreAuthorize("hasAuthority('" + Permissao.AGENTE_REVISAR + "')")
    @Transactional(readOnly = true)
    public Resumo resumir(Filtro filtro) {
        var periodo = periodo(filtro);
        var totais = acoes.totais(new AcoesDoAgenteJdbc.Consulta(Recorte.TODAS, filtro.usuarioId(),
                periodo.inicio(), periodo.fim()));
        return new Resumo(periodo.de(), periodo.ate(), totais.conversas(), totais.gravaram(),
                totais.bloqueadas(), totais.custoUsd(), totais.tokensEntrada(),
                totais.tokensSaida());
    }

    @PreAuthorize("hasAuthority('" + Permissao.AGENTE_REVISAR + "')")
    @Transactional(readOnly = true)
    public List<Pessoa> pessoas(Filtro filtro) {
        var periodo = periodo(filtro);
        var participantes = acoes.participantes(periodo.inicio(), periodo.fim());
        Map<UUID, String> nomesDasPessoas = nomes.nomesDe(participantes.stream()
                .map(AcoesDoAgenteJdbc.Participacao::usuarioId).toList());
        var pessoas = new ArrayList<Pessoa>();
        for (var participante : participantes) {
            pessoas.add(new Pessoa(participante.usuarioId(),
                    nomesDasPessoas.getOrDefault(participante.usuarioId(), "Usuário removido"),
                    participante.conversas()));
        }
        return pessoas;
    }

    private record Periodo(LocalDate de, LocalDate ate, Instant inicio, Instant fim) {
    }

    private AcoesDoAgenteJdbc.Consulta consulta(Filtro filtro) {
        var periodo = periodo(filtro);
        return new AcoesDoAgenteJdbc.Consulta(
                filtro.recorte() == null ? Recorte.TODAS : filtro.recorte(), filtro.usuarioId(),
                periodo.inicio(), periodo.fim());
    }

    private Periodo periodo(Filtro filtro) {
        ZoneId fuso = estabelecimentos.fusoDe(TenantContext.obrigatorio());
        LocalDate ate = filtro.ate() != null ? filtro.ate() : relogio.hojeEm(fuso);
        LocalDate de = filtro.de() != null ? filtro.de() : ate.minusDays(DIAS_PADRAO - 1L);
        if (de.isAfter(ate)) {
            throw new ErroDeDominio(ErrosDoConversacional.FILTRO_INVALIDO,
                    "o início do período vem depois do fim");
        }
        if (ChronoUnit.DAYS.between(de, ate) + 1 > DIAS_MAXIMOS) {
            throw new ErroDeDominio(ErrosDoConversacional.FILTRO_INVALIDO,
                    "o período pode ter no máximo " + DIAS_MAXIMOS + " dias");
        }
        return new Periodo(de, ate, de.atStartOfDay(fuso).toInstant(),
                ate.plusDays(1).atStartOfDay(fuso).toInstant());
    }

    private static Acao acao(AcoesDoAgenteJdbc.Linha linha, Map<UUID, String> nomes) {
        var chamadas = new ArrayList<Chamada>();
        for (JsonNode chamada : linha.ferramentas()) {
            JsonNode argumentos = chamada.path("argumentos");
            chamadas.add(new Chamada(chamada.path("nome").asString(),
                    chamada.path("desfecho").asString(),
                    argumentos.isMissingNode() || argumentos.isNull() ? null : argumentos));
        }
        var intencoes = new ArrayList<IntencaoDaAcao>();
        for (JsonNode intencao : linha.intencoes()) {
            intencoes.add(new IntencaoDaAcao(intencao.path("ferramenta").asString(),
                    texto(intencao.path("resumo")), intencao.path("status").asString(),
                    texto(intencao.path("motivo")), intencao.path("papel").asString()));
        }
        String quemPediu = linha.usuarioEfetivoId() == null ? null
                : nomes.getOrDefault(linha.usuarioEfetivoId(), "Usuário removido");
        String telefoneFinal = linha.usuarioEfetivoId() != null || linha.deTelefone() == null
                || linha.deTelefone().length() < 4 ? null
                : linha.deTelefone().substring(linha.deTelefone().length() - 4);
        return new Acao(linha.id(), linha.ocorridaEm(), linha.tipo(), linha.desfecho(),
                linha.motivoRecusa(), linha.usuarioEfetivoId(), quemPediu, telefoneFinal,
                linha.veioDeAudio(), linha.pedido(), linha.resposta(), chamadas, intencoes,
                linha.gravou(), linha.bloqueada(), linha.modelo(), linha.tokensEntrada(),
                linha.tokensSaida(), linha.custoUsd(), linha.anonimizadaEm());
    }

    private static String texto(JsonNode no) {
        return no.isMissingNode() || no.isNull() ? null : no.asString();
    }
}
