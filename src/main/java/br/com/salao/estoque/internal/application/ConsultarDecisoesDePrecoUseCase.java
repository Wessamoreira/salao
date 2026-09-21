package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.ErrosDoEstoque;
import br.com.salao.estoque.internal.infra.DecisoesDePrecoJdbc;
import br.com.salao.iam.api.NomesDeUsuariosApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.paginacao.Cursor;
import br.com.salao.shared.paginacao.Pagina;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/** RT-EST-011 — a trilha: o que foi decidido, por quem, e com que informação (RN-EST-036). */
public class ConsultarDecisoesDePrecoUseCase {

    static final int LIMITE_PADRAO = 50;
    static final int LIMITE_MAXIMO = 200;

    private final DecisoesDePrecoJdbc decisoes;
    private final NomesDeUsuariosApi nomes;

    public ConsultarDecisoesDePrecoUseCase(DecisoesDePrecoJdbc decisoes, NomesDeUsuariosApi nomes) {
        this.decisoes = decisoes;
        this.nomes = nomes;
    }

    /** @param quemDecidiu o nome de hoje; nulo quando a decisão não teve usuário */
    public record Item(UUID id, UUID produtoId, String produto, String decisao, String motivo,
                       BigDecimal precoAnterior, BigDecimal custoMedio, BigDecimal precoSugerido,
                       BigDecimal precoAplicado, String racional, String justificativa,
                       String quemDecidiu, Instant decididaEm) {
    }

    @PreAuthorize("hasAuthority('" + Permissao.ESTOQUE_LER + "')")
    @Transactional(readOnly = true)
    public Pagina<Item> listar(String cursor, Integer limite) {
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
                throw new ErroDeDominio(ErrosDoEstoque.DADOS_INVALIDOS,
                        "cursor de paginação inválido");
            }
        }
        var linhas = decisoes.listar(antesDe, antesDoId, quantos + 1);
        var nomesDaPagina = nomes.nomesDe(linhas.stream()
                .map(DecisoesDePrecoJdbc.Linha::decididaPor).filter(Objects::nonNull)
                .distinct().toList());
        return Pagina.de(linhas, quantos,
                        l -> Cursor.de(l.decididaEm().toString(), l.id().toString()))
                .mapear(l -> new Item(l.id(), l.produtoId(), l.produto(), l.decisao(),
                        l.motivo().name(), l.precoAnterior(), l.custoMedio(), l.precoSugerido(),
                        l.precoAplicado(), l.racional(), l.justificativa(),
                        l.decididaPor() == null ? null
                                : nomesDaPagina.getOrDefault(l.decididaPor(), "Usuário removido"),
                        l.decididaEm()));
    }
}
