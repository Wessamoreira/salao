package br.com.salao.iam.internal.application;

import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.ConfiguracaoDoEstabelecimento;
import br.com.salao.iam.api.ErrosDoIam;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.iam.internal.domain.DadosDoEstabelecimento;
import br.com.salao.iam.internal.domain.DadosDoEstabelecimentoInvalidosException;
import br.com.salao.iam.internal.infra.EstabelecimentoCacheado;
import br.com.salao.iam.internal.infra.EstabelecimentoJdbc;
import br.com.salao.shared.cache.InvalidadorDeCache;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CFG-001 — alterar a configuração do salão.
 *
 * <h2>Fecha um buraco que o cadastro de cliente abriu</h2>
 *
 * <p>O {@code ddd_padrao} entrou em RT-CLI-001 e só podia ser definido no provisionamento. Um
 * salão provisionado sem ele recusa todo telefone digitado sem DDD — que é como quem atende anota
 * o número — e não havia como corrigir a não ser no banco.
 *
 * <h2>Primeiro uso real da invalidação de cache</h2>
 *
 * <p>{@code EstabelecimentoCacheado} guarda a configuração por cinco minutos, e o javadoc dele já
 * dizia que a invalidação "acontece em outra rotina". Esta é a rotina. Sem o {@code NOTIFY}, o
 * salão trocaria o DDD e continuaria recusando telefone por até cinco minutos — o tipo de defeito
 * que faz alguém concluir que "não salvou" e tentar de novo.
 *
 * <p>A invalidação vai para todas as instâncias pelo {@code NOTIFY}, e a desta instância é
 * limpa ao commitar — sem isso, quem clicou em salvar continuaria vendo o valor velho por alguns
 * instantes, que é justamente quem precisa ver o efeito. Foi este uso que revelou a falta, e a
 * correção ficou no invalidador, onde o defeito estava.
 */
public class AtualizarConfiguracaoUseCase {

    private static final Logger log = LoggerFactory.getLogger(AtualizarConfiguracaoUseCase.class);

    private final EstabelecimentoJdbc estabelecimentos;
    private final InvalidadorDeCache invalidador;
    private final AuditoriaApi auditoria;

    public AtualizarConfiguracaoUseCase(EstabelecimentoJdbc estabelecimentos,
                                        InvalidadorDeCache invalidador, AuditoriaApi auditoria) {
        this.estabelecimentos = estabelecimentos;
        this.invalidador = invalidador;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.CONFIG_GERENCIAR + "')")
    @Transactional
    public ConfiguracaoDoEstabelecimento executar(ConfiguracaoCommand comando) {
        UUID tenant = TenantContext.obrigatorio();
        ConfiguracaoDoEstabelecimento antes = estabelecimentos.configuracao(tenant).orElseThrow(
                () -> new ErroDeDominio(ErrosDoIam.DADOS_INVALIDOS, "estabelecimento não encontrado"));

        DadosDoEstabelecimento dados = validar(comando);
        estabelecimentos.atualizar(dados);

        // Depois da escrita e ainda na transação: o NOTIFY do Postgres só é entregue no commit,
        // então não há janela em que alguém invalide o cache antes de o dado novo existir.
        invalidador.invalidar(EstabelecimentoCacheado.CACHE, tenant);

        auditoria.registrar(RegistroDeAuditoria.alteracao("CONFIGURACAO_ALTERADA",
                "estabelecimento", tenant, resumo(antes), resumoDe(comando)));
        log.info("Configuração do estabelecimento {} alterada", tenant);

        return estabelecimentos.configuracao(tenant).orElseThrow();
    }

    /**
     * A mesma validação do provisionamento, pelo mesmo objeto.
     *
     * <p>É o motivo de {@code DadosDoEstabelecimento} ter perdido o "Novo" do nome: uma segunda
     * validação para edição aceitaria, um dia, um fuso que a criação recusa.
     */
    private DadosDoEstabelecimento validar(ConfiguracaoCommand comando) {
        try {
            return DadosDoEstabelecimento.comFuso(comando.nome(), comando.documento(),
                    comando.fusoIana(), comando.moeda(), comando.baseComissao(),
                    Boolean.TRUE.equals(comando.descontoAfetaComissao()),
                    comando.periodicidadeDeFechamento(), comando.dddPadrao());
        } catch (DadosDoEstabelecimentoInvalidosException e) {
            throw new ErroDeDominio(ErrosDoIam.DADOS_INVALIDOS, e.getMessage());
        }
    }

    /**
     * A trilha guarda a regra de comissão antes e depois.
     *
     * <p>Não é registro decorativo: enquanto o módulo financeiro não gravar a regra vigente em
     * cada fechamento, esta trilha é a <strong>única</strong> resposta para "qual base de comissão
     * valia em março?" — pergunta que aparece na primeira vez que um profissional contesta o
     * extrato. Ver RN-IAM-017.
     */
    private Map<String, Object> resumo(ConfiguracaoDoEstabelecimento config) {
        var mapa = new LinkedHashMap<String, Object>();
        mapa.put("baseComissao", config.baseComissao().name());
        mapa.put("descontoAfetaComissao", config.descontoAfetaComissao());
        mapa.put("periodicidade", config.periodicidadeDeFechamento().name());
        mapa.put("fuso", config.fuso().getId());
        mapa.put("dddPadrao", config.dddPadrao());
        return mapa;
    }

    private Map<String, Object> resumoDe(ConfiguracaoCommand comando) {
        var mapa = new LinkedHashMap<String, Object>();
        mapa.put("baseComissao", String.valueOf(comando.baseComissao()));
        mapa.put("descontoAfetaComissao", Boolean.TRUE.equals(comando.descontoAfetaComissao()));
        mapa.put("periodicidade", String.valueOf(comando.periodicidadeDeFechamento()));
        mapa.put("fuso", comando.fusoIana());
        mapa.put("dddPadrao", comando.dddPadrao());
        return mapa;
    }
}
