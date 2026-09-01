package br.com.salao.catalogo.internal.application;

import br.com.salao.catalogo.api.ErrosDoCatalogo;
import br.com.salao.catalogo.internal.infra.ServicosJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CAT-002 — cancelar um aumento que ainda não entrou em vigor.
 *
 * <h2>Só o futuro se apaga</h2>
 *
 * <p>Um preço anunciado para o mês que vem e depois cancelado nunca valeu para ninguém: apagá-lo
 * não muda nada que aconteceu.
 *
 * <p>Um preço que já começou é outra coisa. Apagá-lo faria o serviço voltar ao valor anterior
 * <strong>retroativamente</strong> — o relatório do mês passado mudaria sozinho. Quem errou um
 * preço já vigente corrige cadastrando outro, o que deixa rastro; apagar não deixaria.
 *
 * <p>É a mesma distinção de RT-EQP-003 sobre apagar bloqueio: o que se apaga é o que não sustenta
 * nada.
 */
public class RemoverPrecoFuturoUseCase {

    private final ServicosJdbc servicos;
    private final EstabelecimentoApi estabelecimentos;
    private final AuditoriaApi auditoria;
    private final Relogio relogio;

    public RemoverPrecoFuturoUseCase(ServicosJdbc servicos, EstabelecimentoApi estabelecimentos,
                                     AuditoriaApi auditoria, Relogio relogio) {
        this.servicos = servicos;
        this.estabelecimentos = estabelecimentos;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    @PreAuthorize("hasAuthority('" + Permissao.CONFIG_GERENCIAR + "')")
    @Transactional
    public void executar(UUID precoId) {
        UUID tenant = TenantContext.obrigatorio();
        var hoje = relogio.hojeEm(estabelecimentos.fusoDe(tenant));

        if (servicos.removerPrecoFuturo(precoId, hoje) == 0) {
            // Zero linhas quer dizer três coisas — não existe, é de outro salão, ou já começou a
            // valer. A mensagem trata a última, que é a única que a pessoa pode corrigir.
            throw new ErroDeDominio(ErrosDoCatalogo.PRECO_JA_VIGENTE,
                    "só é possível cancelar um preço que ainda não começou a valer");
        }
        auditoria.registrar(RegistroDeAuditoria.alteracao("PRECO_FUTURO_CANCELADO",
                "preco_do_servico", precoId, Map.of("existia", true), null));
    }
}
