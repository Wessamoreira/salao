package br.com.salao.catalogo.internal.application;

import br.com.salao.catalogo.api.ErrosDoCatalogo;
import br.com.salao.catalogo.api.PrecoVigente;
import br.com.salao.catalogo.internal.infra.ServicosJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CAT-002 — definir o preço a partir de uma data.
 *
 * <h2>Aumentar o preço não reescreve o passado</h2>
 *
 * <p>É a rotina inteira. Com o preço numa coluna de {@code servico}, subir a escova de 60 para 70
 * faria o relatório de fevereiro passar a dizer 70 — e a comissão já paga deixar de bater com o
 * extrato, sem nada quebrar e sem ninguém perceber.
 *
 * <h2>Preço futuro é o uso normal</h2>
 *
 * <p>"A partir de 1º de abril, 70" cadastrado em março funciona sem tratamento especial: a consulta
 * filtra por {@code vigente_de <= data}. Anunciar o aumento antes de aplicá-lo é o que um salão
 * faz, e sai de graça do modelo.
 *
 * <h2>Preço retroativo é permitido, e vigiado</h2>
 *
 * <p>Recusá-lo obrigaria o salão a conviver com um erro de digitação — "700 em vez de 70, ontem".
 * Mas ele muda o que o passado diz, e por isso vai para a trilha com a marca de retroativo.
 *
 * <p>A proteção de verdade não está aqui: é {@code atendimento} <strong>copiar o preço para o item
 * da comanda</strong> no momento do atendimento (RN-CAT-006). Com essa cópia, nenhuma alteração de
 * preço alcança o que já foi feito. Enquanto ela não existir, esta trilha é a única defesa.
 */
public class DefinirPrecoUseCase {

    private static final Logger log = LoggerFactory.getLogger(DefinirPrecoUseCase.class);

    private final ServicosJdbc servicos;
    private final EstabelecimentoApi estabelecimentos;
    private final AuditoriaApi auditoria;
    private final Relogio relogio;

    public DefinirPrecoUseCase(ServicosJdbc servicos, EstabelecimentoApi estabelecimentos,
                               AuditoriaApi auditoria, Relogio relogio) {
        this.servicos = servicos;
        this.estabelecimentos = estabelecimentos;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    @PreAuthorize("hasAuthority('" + Permissao.CONFIG_GERENCIAR + "')")
    @Transactional
    public PrecoVigente executar(UUID servicoId, Money valor, LocalDate vigenteDe) {
        if (valor == null || valor.valor().signum() < 0) {
            throw new ErroDeDominio(ErrosDoCatalogo.DADOS_INVALIDOS,
                    "o preço não pode ser negativo");
        }
        if (servicos.porId(servicoId).isEmpty()) {
            throw new ErroDeDominio(ErrosDoCatalogo.SERVICO_NAO_ENCONTRADO,
                    "serviço " + servicoId + " não encontrado");
        }

        UUID tenant = TenantContext.obrigatorio();
        LocalDate hoje = hojeNoSalao(tenant);
        LocalDate inicio = vigenteDe == null ? hoje : vigenteDe;

        try {
            UUID id = servicos.inserirPreco(tenant, servicoId, valor, inicio,
                    UsuarioAtual.idOuNulo());

            auditoria.registrar(RegistroDeAuditoria.criacao("preco_do_servico", id,
                    trilha(servicoId, valor, inicio, hoje)));
            if (inicio.isBefore(hoje)) {
                log.warn("Preço RETROATIVO definido para o serviço {}: {} a partir de {}",
                        servicoId, valor.valor(), inicio);
            } else {
                log.info("Preço do serviço {}: {} a partir de {}", servicoId, valor.valor(),
                        inicio);
            }
            return servicos.historicoDePrecos(servicoId).stream()
                    .filter(preco -> preco.id().equals(id))
                    .findFirst()
                    .orElseThrow();
        } catch (DuplicateKeyException e) {
            // Dois preços começando no mesmo dia é ambiguidade pura: nenhuma regra diria qual
            // vale.
            throw new ErroDeDominio(ErrosDoCatalogo.DADOS_INVALIDOS,
                    "já existe um preço começando em " + inicio);
        }
    }

    /**
     * "Hoje" é no fuso do estabelecimento, nunca no da JVM.
     *
     * <p>O servidor roda em UTC (ADR-0009). Depois das 21h no horário de Brasília, o dia em UTC já
     * virou — e um preço cadastrado "para hoje" nasceria retroativo, ou um retroativo passaria por
     * futuro.
     */
    private LocalDate hojeNoSalao(UUID tenant) {
        return relogio.hojeEm(estabelecimentos.fusoDe(tenant));
    }

    private Map<String, Object> trilha(UUID servicoId, Money valor, LocalDate inicio,
                                       LocalDate hoje) {
        var mapa = new LinkedHashMap<String, Object>();
        mapa.put("servicoId", servicoId.toString());
        mapa.put("valor", valor.valor().toPlainString());
        mapa.put("vigenteDe", inicio.toString());
        // Marcado explicitamente: é a única defesa enquanto `atendimento` não copiar o preço
        // para o item da comanda.
        mapa.put("retroativo", inicio.isBefore(hoje));
        return mapa;
    }
}
