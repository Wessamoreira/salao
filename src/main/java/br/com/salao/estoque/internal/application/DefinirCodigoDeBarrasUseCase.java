package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.ErrosDoEstoque;
import br.com.salao.estoque.internal.infra.ProdutosJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-009 — amarrar o que o leitor emite ao produto.
 *
 * <h2>Sem validar dígito verificador</h2>
 *
 * <p>EAN-13 tem dígito verificador, e conferi-lo parece rigor. Na prática recusaria cadastro
 * legítimo: produto importado com etiqueta mal impressa, item reembalado pelo distribuidor, e a
 * etiqueta que o próprio salão imprime para o que não tem código nenhum.
 *
 * <p>O que importa é que o guardado seja <strong>exatamente</strong> o que o leitor emite — se ele
 * lê um código inválido, o cadastro precisa poder guardá-lo, senão o produto nunca é encontrado.
 * Validar aqui só garantiria que o sistema recusasse a realidade.
 *
 * <h2>O que é imposto: sem espaço, e único por salão</h2>
 *
 * <p>Leitores anexam CR/LF, e um espaço perdido torna o código inencontrável — então ele é aparado
 * na escrita, uma vez, e a leitura compara texto exato. Normalizar na leitura em vez da escrita
 * criaria pares que casam de um lado e não do outro.
 *
 * <p>A unicidade é por estabelecimento, porque dois salões vendem o mesmo shampoo com o mesmo EAN.
 * Quem garante é o índice parcial da V49 — conferir antes e gravar depois deixaria a janela entre
 * a leitura e a escrita, e duas pessoas cadastrando o mesmo produto bipado ao mesmo tempo é o caso
 * comum no recebimento de uma carga.
 */
public class DefinirCodigoDeBarrasUseCase {

    private final ProdutosJdbc produtos;
    private final AuditoriaApi auditoria;

    public DefinirCodigoDeBarrasUseCase(ProdutosJdbc produtos, AuditoriaApi auditoria) {
        this.produtos = produtos;
        this.auditoria = auditoria;
    }

    /** @param codigo {@code null} ou vazio remove o código do produto */
    @PreAuthorize("hasAuthority('" + Permissao.ESTOQUE_ESCREVER + "')")
    @Transactional
    public void executar(UUID produtoId, String codigo) {
        String limpo = codigo == null || codigo.isBlank() ? null : codigo.strip();
        if (limpo != null && limpo.matches(".*\\s.*")) {
            // Espaço no meio não vem de leitor: vem de quem digitou à mão olhando a etiqueta. E o
            // código com espaço nunca casaria com o que o leitor emite.
            throw new ErroDeDominio(ErrosDoEstoque.QUANTIDADE_INVALIDA,
                    "código de barras não pode ter espaço; veio \"" + codigo + "\"");
        }
        /*
         * A checagem vem ANTES, e o `catch` fica sem consulta. Por quê:
         *
         * A primeira versão tentava o update e, no `catch (DuplicateKeyException)`, consultava
         * quem era o dono do código para nomeá-lo na mensagem. Não funciona — depois de uma
         * violação de constraint o PostgreSQL ABORTA a transação, e qualquer comando seguinte
         * falha com 25P02. O efeito foi um `UncategorizedSQLException` escapando no lugar do erro
         * de domínio, e o teste pegou.
         *
         * Então: a consulta prévia serve à MENSAGEM, que é o que ajuda quem bipou — quase sempre
         * é o mesmo item cadastrado duas vezes, e a ação é ir ver aquele produto. A CORREÇÃO
         * continua sendo do índice único da V49: a janela entre esta leitura e a escrita existe, e
         * duas pessoas cadastrando a mesma carga bipada ao mesmo tempo é o caso comum. Quem perder
         * a corrida recebe a mensagem genérica, que é pior de ler e igualmente correta.
         */
        if (limpo != null) {
            produtos.porCodigoDeBarras(limpo)
                    .filter(dono -> !dono.id().equals(produtoId))
                    .ifPresent(dono -> {
                        throw new ErroDeDominio(ErrosDoEstoque.CODIGO_DE_BARRAS_EM_USO,
                                "o código " + limpo + " já é de " + dono.nome());
                    });
        }
        try {
            if (produtos.definirCodigoDeBarras(produtoId, limpo) == 0) {
                throw new ErroDeDominio(ErrosDoEstoque.PRODUTO_NAO_ENCONTRADO,
                        "produto " + produtoId + " não encontrado");
            }
        } catch (DuplicateKeyException e) {
            throw new ErroDeDominio(ErrosDoEstoque.CODIGO_DE_BARRAS_EM_USO,
                    "o código " + limpo + " acabou de ser usado por outro produto");
        }
        auditoria.registrar(RegistroDeAuditoria.alteracao("CODIGO_DE_BARRAS_DEFINIDO", "produto",
                produtoId, null, Map.of("codigoBarras", String.valueOf(limpo))));
    }
}
