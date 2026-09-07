package br.com.salao.financeiro.internal.application;

import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.financeiro.api.ErrosDoFinanceiro;
import br.com.salao.financeiro.api.EscopoDaRegra;
import br.com.salao.financeiro.api.RegraDeComissao;
import br.com.salao.financeiro.internal.infra.RegrasDeComissaoJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-002 — definir um nível da cascata.
 *
 * <h2>A permissão é `config:manage`, e só o dono a tem</h2>
 *
 * <p>Comissão é cláusula de contrato com quem trabalha no salão, não parâmetro operacional. Nem a
 * recepção nem o gerente a mudam — o gerente opera o dia e vê o financeiro; quem decide quanto cada
 * um ganha é quem contrata.
 *
 * <h2>Definir é substituir</h2>
 *
 * <p>Mandar a mesma combinação de novo troca o percentual, em vez de criar uma segunda regra. Duas
 * regras para o mesmo par fariam a cascata escolher uma ao acaso — e o índice único já impediria,
 * com uma mensagem de constraint que não diz nada a quem chamou.
 */
public class DefinirRegraDeComissaoUseCase {

    private static final Logger log =
            LoggerFactory.getLogger(DefinirRegraDeComissaoUseCase.class);

    private static final BigDecimal CEM = new BigDecimal("100");

    private final RegrasDeComissaoJdbc regras;
    private final ProfissionalApi profissionais;
    private final ServicoApi servicos;
    private final AuditoriaApi auditoria;
    private final Relogio relogio;

    public DefinirRegraDeComissaoUseCase(RegrasDeComissaoJdbc regras,
                                         ProfissionalApi profissionais, ServicoApi servicos,
                                         AuditoriaApi auditoria, Relogio relogio) {
        this.regras = regras;
        this.profissionais = profissionais;
        this.servicos = servicos;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    @PreAuthorize("hasAuthority('" + Permissao.CONFIG_GERENCIAR + "')")
    @Transactional
    public RegraDeComissao executar(EscopoDaRegra escopo, UUID profissionalId, UUID servicoId,
                                    BigDecimal percentual) {
        if (escopo == null) {
            throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS, "informe o escopo");
        }
        percentualValido(percentual);
        UUID profissional = conferirProfissional(escopo, profissionalId);
        UUID servico = conferirServico(escopo, servicoId);

        UUID id = regras.salvar(TenantContext.obrigatorio(), escopo, profissional, servico,
                percentual, relogio.agora(), UsuarioAtual.idOuNulo());
        auditoria.registrar(RegistroDeAuditoria.criacao("regra_comissao", id,
                Map.of("escopo", escopo.name(), "percentual", percentual.toPlainString(),
                        "profissionalId", String.valueOf(profissional),
                        "servicoId", String.valueOf(servico))));
        log.info("Regra de comissão {} ({}) definida em {}%", id, escopo, percentual);
        return regras.porId(id).orElseThrow();
    }

    private void percentualValido(BigDecimal percentual) {
        if (percentual == null || percentual.signum() < 0 || percentual.compareTo(CEM) > 0) {
            throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                    "o percentual precisa estar entre 0 e 100; veio " + percentual);
        }
    }

    /**
     * Confere que os ids existem <strong>neste salão</strong>.
     *
     * <p>Não há FK para profissional nem serviço — eles são de outros módulos —, e no PostgreSQL a
     * checagem de FK nem separaria tenant (RN-CAT-011). Quem confere é este caso de uso, pelas APIs
     * dos donos do dado. Sem isso, uma regra apontando para o serviço de outro salão ficaria
     * gravada e nunca casaria com nada — silenciosa.
     */
    private UUID conferirServico(EscopoDaRegra escopo, UUID servicoId) {
        if (escopo == EscopoDaRegra.SALAO) {
            if (servicoId != null) {
                // Ignorar em silêncio faria alguém achar que definiu "coloração paga 30%" quando
                // definiu "a casa paga 30%" — e a diferença só apareceria no extrato.
                throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                        "o escopo SALAO não leva serviço");
            }
            return null;
        }
        if (servicoId == null || servicos.porId(servicoId).isEmpty()) {
            throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                    "serviço não encontrado para o escopo " + escopo);
        }
        return servicoId;
    }

    private UUID conferirProfissional(EscopoDaRegra escopo, UUID profissionalId) {
        if (escopo != EscopoDaRegra.PROFISSIONAL_SERVICO) {
            if (profissionalId != null) {
                throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                        "o escopo " + escopo + " não leva profissional");
            }
            return null;
        }
        if (profissionalId == null || profissionais.porId(profissionalId).isEmpty()) {
            throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                    "profissional não encontrado para o escopo " + escopo);
        }
        return profissionalId;
    }
}
