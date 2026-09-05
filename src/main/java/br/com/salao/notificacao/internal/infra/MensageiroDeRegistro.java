package br.com.salao.notificacao.internal.infra;

import br.com.salao.notificacao.api.CanalDeEnvio;
import br.com.salao.notificacao.api.Mensageiro;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RT-NOT-001 — o mensageiro que não manda nada, só registra.
 *
 * <p>É a implementação de hoje, e ela é honesta sobre o que é: nenhum provedor está contratado,
 * nenhuma credencial existe, e o módulo inteiro — fila, template, retry, opt-out — funciona e é
 * testável sem isso. Trocar por um provedor real é implementar {@link Mensageiro} e mudar uma linha
 * de configuração.
 *
 * <p><strong>Não registra o texto.</strong> O log não tem RLS, é lido por quem opera a máquina e
 * costuma ir para fora do perímetro — e o texto carrega nome e horário da cliente. O que vai para o
 * log é o suficiente para diagnosticar entrega: canal, destino mascarado e tamanho.
 */
public class MensageiroDeRegistro implements Mensageiro {

    private static final Logger log = LoggerFactory.getLogger(MensageiroDeRegistro.class);

    @Override
    public void enviar(CanalDeEnvio canal, String destino, String texto) {
        log.info("Mensagem NÃO enviada (sem provedor configurado): canal={} destino={} chars={}",
                canal, mascarar(destino), texto.length());
    }

    /** "5511987654321" vira "5511*****321": dá para reconhecer o número sem expor o número. */
    private String mascarar(String destino) {
        if (destino == null || destino.length() < 8) {
            return "***";
        }
        return destino.substring(0, 4) + "*****" + destino.substring(destino.length() - 3);
    }
}
