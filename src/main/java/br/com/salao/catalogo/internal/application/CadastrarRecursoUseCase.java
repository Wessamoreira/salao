package br.com.salao.catalogo.internal.application;

import br.com.salao.catalogo.api.ErrosDoCatalogo;
import br.com.salao.catalogo.api.Recurso;
import br.com.salao.catalogo.internal.infra.RecursosJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tenant.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CAT-003 — cadastrar um recurso do salão.
 *
 * <p>{@code config:manage} pela mesma razão do cardápio (RN-CAT-004): dizer que o salão tem dois
 * lavatórios em vez de três muda quantos horários a agenda consegue oferecer no sábado inteiro.
 */
public class CadastrarRecursoUseCase {

    private static final Logger log = LoggerFactory.getLogger(CadastrarRecursoUseCase.class);

    private final RecursosJdbc recursos;
    private final AuditoriaApi auditoria;

    public CadastrarRecursoUseCase(RecursosJdbc recursos, AuditoriaApi auditoria) {
        this.recursos = recursos;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.CONFIG_GERENCIAR + "')")
    @Transactional
    public Recurso executar(String nome, Integer quantidade) {
        Recurso valido = new RecursoBruto(nome, quantidade).validar();
        try {
            UUID id = recursos.inserir(TenantContext.obrigatorio(), valido.nome(),
                    valido.quantidade(), UsuarioAtual.idOuNulo());

            auditoria.registrar(RegistroDeAuditoria.criacao("recurso", id,
                    Map.of("nome", valido.nome(), "quantidade", valido.quantidade())));
            log.info("Recurso cadastrado: {} ({} unidade(s))", valido.nome(),
                    valido.quantidade());

            return recursos.porId(id).orElseThrow();
        } catch (DuplicateKeyException e) {
            // Dois "Lavatório" no cadastro fariam metade dos serviços exigir um e metade o outro,
            // e a conta de disponibilidade daria o dobro da capacidade real.
            throw new ErroDeDominio(ErrosDoCatalogo.RECURSO_NOME_JA_CADASTRADO,
                    "já existe um recurso com este nome");
        }
    }
}
