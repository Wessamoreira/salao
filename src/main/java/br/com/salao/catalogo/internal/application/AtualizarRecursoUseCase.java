package br.com.salao.catalogo.internal.application;

import br.com.salao.catalogo.api.ErrosDoCatalogo;
import br.com.salao.catalogo.api.Recurso;
import br.com.salao.catalogo.internal.infra.RecursosJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.erro.ErrosDaInfra;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CAT-003 — corrigir nome e quantidade.
 *
 * <p>Mudar a quantidade é a operação frequente, e é a razão de o recurso ser contável em vez de
 * uma linha por unidade: <em>"o terceiro lavatório chegou"</em> é uma edição, não um cadastro que
 * precise ser ligado às receitas que já existem.
 *
 * <p>Diminuir é permitido — um lavatório quebra e o salão passa a ter dois. A agenda encolhe junto,
 * e é isso que se quer: prometer três lavagens simultâneas quando só há dois lavatórios é o defeito
 * que a rotina inteira existe para impedir. O que <strong>não</strong> se pode é chegar a zero
 * (RN-CAT-009), porque zero e inativo diriam a mesma coisa de duas formas.
 *
 * <p>Baixar a quantidade não derruba agendamento já marcado: quando a agenda existir, ela vai
 * precisar mostrar quantos horários futuros passam a não caber — está registrado como pendência.
 */
public class AtualizarRecursoUseCase {

    private static final Logger log = LoggerFactory.getLogger(AtualizarRecursoUseCase.class);

    private final RecursosJdbc recursos;
    private final AuditoriaApi auditoria;

    public AtualizarRecursoUseCase(RecursosJdbc recursos, AuditoriaApi auditoria) {
        this.recursos = recursos;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.CONFIG_GERENCIAR + "')")
    @Transactional
    public Recurso executar(UUID id, int versao, String nome, Integer quantidade) {
        Recurso antes = recursos.porId(id).orElseThrow(() -> new ErroDeDominio(
                ErrosDoCatalogo.RECURSO_NAO_ENCONTRADO, "recurso " + id + " não encontrado"));
        Recurso valido = new RecursoBruto(nome, quantidade).validar();

        int alteradas;
        try {
            alteradas = recursos.atualizar(id, versao, valido.nome(), valido.quantidade());
        } catch (DuplicateKeyException e) {
            throw new ErroDeDominio(ErrosDoCatalogo.RECURSO_NOME_JA_CADASTRADO,
                    "já existe um recurso com este nome");
        }
        if (alteradas == 0) {
            // O recurso existe (a leitura acima o achou), então o que não bate é a versão.
            throw new ErroDeDominio(ErrosDaInfra.VERSAO_DESATUALIZADA,
                    "o recurso foi alterado por outra pessoa; recarregue antes de salvar");
        }

        auditoria.registrar(RegistroDeAuditoria.alteracao("RECURSO_ALTERADO", "recurso", id,
                Map.of("nome", antes.nome(), "quantidade", antes.quantidade()),
                Map.of("nome", valido.nome(), "quantidade", valido.quantidade())));
        if (valido.quantidade() < antes.quantidade()) {
            log.info("Recurso {} encolheu de {} para {} unidade(s)", antes.nome(),
                    antes.quantidade(), valido.quantidade());
        }
        return recursos.porId(id).orElseThrow();
    }
}
