package br.com.salao.financeiro.internal.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

/**
 * RT-FIN-006 — identifica exatamente os números que a pessoa conferiu antes de fechar.
 *
 * <p>Não é assinatura nem autorização. É um detector de mudança: se comissão, vale ou repasse
 * mudar entre a prévia e o POST, o fechamento recusa e obriga a tela a mostrar os valores novos.
 */
final class VersaoDaPrevia {

    private VersaoDaPrevia() {
    }

    static String calcular(LocalDate de, LocalDate ate, List<ContasDoPeriodo.Posicao> posicoes) {
        StringBuilder canonico = new StringBuilder()
                .append(de).append('|').append(ate).append('\n');
        for (ContasDoPeriodo.Posicao posicao : posicoes) {
            canonico.append(posicao.profissionalId()).append('|')
                    .append(posicao.saldoAnterior()).append('|')
                    .append(posicao.movimento()).append('|')
                    .append(posicao.aRepassar()).append('\n');
        }
        try {
            byte[] resumo = MessageDigest.getInstance("SHA-256")
                    .digest(canonico.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(resumo);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
