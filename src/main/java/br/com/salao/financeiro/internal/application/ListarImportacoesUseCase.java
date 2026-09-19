package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.api.ErrosDoFinanceiro;
import br.com.salao.financeiro.internal.infra.ImportacoesDeExtratoJdbc;
import br.com.salao.iam.api.NomesDeUsuariosApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.paginacao.Cursor;
import br.com.salao.shared.paginacao.Pagina;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/** RT-FIN-009 — as importações do salão, das mais recentes para trás. */
public class ListarImportacoesUseCase {

    static final int LIMITE_PADRAO = 20;
    static final int LIMITE_MAXIMO = 100;

    private final ImportacoesDeExtratoJdbc importacoes;
    private final NomesDeUsuariosApi nomes;

    public ListarImportacoesUseCase(ImportacoesDeExtratoJdbc importacoes, NomesDeUsuariosApi nomes) {
        this.importacoes = importacoes;
        this.nomes = nomes;
    }

    /** @param quemImportou o nome de hoje; nulo se a importação não teve usuário */
    public record ImportacaoResumo(UUID id, Instant importadaEm, String nomeDoArquivo,
                                   String quemImportou, int linhas, int novas, int canceladas,
                                   int repetidas, int ignoradas, int recusadas) {
    }

    @PreAuthorize("hasAuthority('" + Permissao.FINANCEIRO_CONCILIAR + "')")
    @Transactional(readOnly = true)
    public Pagina<ImportacaoResumo> listar(String cursor, Integer limite) {
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
                throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                        "cursor de paginação inválido");
            }
        }
        var linhas = importacoes.listar(antesDe, antesDoId, quantos + 1);
        var nomesDaPagina = nomes.nomesDe(linhas.stream()
                .map(ImportacoesDeExtratoJdbc.Linha::importadaPor).filter(Objects::nonNull)
                .distinct().toList());
        return Pagina.de(linhas, quantos,
                        linha -> Cursor.de(linha.importadaEm().toString(), linha.id().toString()))
                .mapear(linha -> new ImportacaoResumo(linha.id(), linha.importadaEm(),
                        linha.nomeDoArquivo(),
                        linha.importadaPor() == null ? null
                                : nomesDaPagina.getOrDefault(linha.importadaPor(), "Usuário removido"),
                        linha.linhas(), linha.novas(), linha.canceladas(), linha.repetidas(),
                        linha.ignoradas(), linha.recusadas()));
    }
}
