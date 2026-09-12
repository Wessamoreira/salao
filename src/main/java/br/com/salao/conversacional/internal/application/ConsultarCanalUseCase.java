package br.com.salao.conversacional.internal.application;

import br.com.salao.conversacional.api.CanalDoWhatsApp;
import br.com.salao.conversacional.api.ErrosDoConversacional;
import br.com.salao.conversacional.internal.infra.CanaisJdbc;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.erro.ErroDeDominio;
import org.springframework.security.access.prepost.PreAuthorize;

/** RT-CNV-001 — qual número deste salão está ligado ao WhatsApp. */
public class ConsultarCanalUseCase {

    private final CanaisJdbc canais;

    public ConsultarCanalUseCase(CanaisJdbc canais) {
        this.canais = canais;
    }

    @PreAuthorize("hasAuthority('" + Permissao.CONFIG_GERENCIAR + "')")
    public CanalDoWhatsApp executar() {
        return canais.doSalao().orElseThrow(() -> new ErroDeDominio(
                ErrosDoConversacional.CANAL_NAO_CONFIGURADO,
                "este estabelecimento não tem canal de WhatsApp cadastrado"));
    }
}
