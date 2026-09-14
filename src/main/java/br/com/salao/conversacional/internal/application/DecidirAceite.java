package br.com.salao.conversacional.internal.application;

import br.com.salao.conversacional.internal.application.EfetivarIntencao.DecisaoDoAceite;
import br.com.salao.conversacional.internal.application.EfetivarIntencao.DesfechoDoAceite;
import br.com.salao.conversacional.internal.domain.BotaoDeAceite;
import br.com.salao.iam.api.ErrosDoIam;
import br.com.salao.shared.erro.ErroDeDominio;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;

/**
 * RT-CNV-006 — o toque num botão de aceite: decidir sem o modelo, e responder com texto do servidor.
 *
 * <p>Não transacional de propósito: a decisão roda em {@link EfetivarIntencao#decidirAceite}, e se a
 * gravação for recusada — o horário tomado enquanto a profissional pensava, a recepcionista que perdeu a
 * permissão — a transação é desfeita e o registro da falha, com o aviso a quem pediu, vai numa transação
 * nova ({@link EfetivarIntencao#registrarFalhaDoAceite}).
 */
public class DecidirAceite {

    private static final Logger log = LoggerFactory.getLogger(DecidirAceite.class);

    private final EfetivarIntencao efetivar;

    public DecidirAceite(EfetivarIntencao efetivar) {
        this.efetivar = efetivar;
    }

    public DecisaoDoAceite executar(String telefone, UUID execucaoId, String idDoBotao) {
        var botao = BotaoDeAceite.de(idDoBotao);
        if (botao.isEmpty()) {
            return new DecisaoDoAceite(DesfechoDoAceite.NAO_ENCONTRADO,
                    "Não encontrei esse pedido.", null);
        }
        try {
            return efetivar.decidirAceite(botao.get(), telefone, execucaoId);
        } catch (AccessDeniedException negado) {
            return falha(botao.get(), telefone, execucaoId, "SEM_PERMISSAO",
                    "quem pediu não tem mais permissão para marcar.");
        } catch (ErroDeDominio erro) {
            if (erro.codigo() == ErrosDoIam.CONTATO_NAO_IDENTIFICADO) {
                return falha(botao.get(), telefone, execucaoId, "SEM_PERMISSAO",
                        "quem pediu não tem mais permissão para marcar.");
            }
            return falha(botao.get(), telefone, execucaoId, erro.codigo().codigo(),
                    erro.getMessage());
        }
    }

    private DecisaoDoAceite falha(BotaoDeAceite botao, String telefone, UUID execucaoId,
                                  String motivo, String detalhe) {
        log.info("Aceite da intenção {} não gravou: {}", botao.intencaoId(), motivo);
        return efetivar.registrarFalhaDoAceite(botao, telefone, execucaoId, motivo, detalhe);
    }
}
