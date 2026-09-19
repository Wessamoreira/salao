package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.api.ErrosDoFinanceiro;
import br.com.salao.financeiro.internal.domain.ArquivoDeExtratoInvalidoException;
import br.com.salao.financeiro.internal.domain.CampoDoExtrato;
import br.com.salao.financeiro.internal.domain.FormatoDeNumero;
import br.com.salao.financeiro.internal.domain.LeitorDeCsv;
import br.com.salao.financeiro.internal.domain.MapeamentoDoExtrato;
import br.com.salao.shared.erro.ErroDeDominio;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * RT-FIN-009 — o que a prévia e a importação fazem igual: ler o arquivo e sugerir o mapeamento.
 *
 * <p>Uma leitura só, para as duas: se a prévia e a importação lessem o arquivo de jeitos diferentes, o
 * que o dono conferiu não seria o que foi gravado.
 */
final class LeituraDoExtrato {

    /** Só Stone por enquanto. Outro adquirente é outro valor aqui e no {@code check} da V58. */
    static final String ADQUIRENTE = "STONE";

    /** Um mês de vendas de um salão fica longe disso; o limite do multipart é o mesmo. */
    static final int BYTES_MAXIMOS = 5 * 1024 * 1024;

    private static final int AMOSTRAS_DO_FORMATO = 200;

    private LeituraDoExtrato() {
    }

    static LeitorDeCsv.Tabela ler(byte[] arquivo) {
        if (arquivo != null && arquivo.length > BYTES_MAXIMOS) {
            throw new ErroDeDominio(ErrosDoFinanceiro.ARQUIVO_INVALIDO,
                    "o arquivo passa de 5 MB — exporte um período menor");
        }
        try {
            return LeitorDeCsv.ler(arquivo);
        } catch (ArquivoDeExtratoInvalidoException e) {
            throw new ErroDeDominio(ErrosDoFinanceiro.ARQUIVO_INVALIDO, e.getMessage());
        }
    }

    /** O mapeamento sugerido, com o formato de número votado nas colunas de valor. */
    static MapeamentoDoExtrato sugerir(LeitorDeCsv.Tabela tabela,
                                       Optional<MapeamentoDoExtrato> salvo) {
        var provisorio = MapeamentoDoExtrato.sugerir(tabela.cabecalho(), salvo,
                FormatoDeNumero.BRASILEIRO);
        List<String> amostras = new ArrayList<>();
        for (CampoDoExtrato campo : List.of(CampoDoExtrato.VALOR_BRUTO, CampoDoExtrato.VALOR_LIQUIDO)) {
            int i = provisorio.indice(campo, tabela.cabecalho());
            if (i < 0) {
                continue;
            }
            for (LeitorDeCsv.Registro registro : tabela.registros()) {
                if (amostras.size() >= AMOSTRAS_DO_FORMATO) {
                    break;
                }
                if (i < registro.campos().size()) {
                    amostras.add(registro.campos().get(i));
                }
            }
        }
        return new MapeamentoDoExtrato(provisorio.colunas(), FormatoDeNumero.detectar(amostras));
    }

    static List<String> rotulos(List<CampoDoExtrato> campos) {
        return campos.stream().map(CampoDoExtrato::rotulo).toList();
    }
}
