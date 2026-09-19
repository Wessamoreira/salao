package br.com.salao.financeiro.internal.domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * RT-FIN-009 — qual coluna do arquivo é qual campo, e como o arquivo escreve dinheiro.
 *
 * <p>Pelo <strong>nome</strong> da coluna, nunca pela posição: a Stone mudar a ordem das colunas não
 * pode trocar valor bruto por líquido em silêncio.
 */
public record MapeamentoDoExtrato(Map<CampoDoExtrato, String> colunas, FormatoDeNumero formato) {

    public MapeamentoDoExtrato {
        var limpas = new EnumMap<CampoDoExtrato, String>(CampoDoExtrato.class);
        if (colunas != null) {
            colunas.forEach((campo, coluna) -> {
                if (campo != null && coluna != null && !coluna.isBlank()) {
                    limpas.put(campo, coluna.strip());
                }
            });
        }
        colunas = java.util.Collections.unmodifiableMap(limpas);
        formato = formato == null ? FormatoDeNumero.BRASILEIRO : formato;
    }

    /** A posição da coluna mapeada para o campo, ou -1 se não mapeado ou ausente no cabeçalho. */
    public int indice(CampoDoExtrato campo, List<String> cabecalho) {
        String coluna = colunas.get(campo);
        if (coluna == null) {
            return -1;
        }
        String alvo = CampoDoExtrato.normalizar(coluna);
        for (int i = 0; i < cabecalho.size(); i++) {
            if (CampoDoExtrato.normalizar(cabecalho.get(i)).equals(alvo)) {
                return i;
            }
        }
        return -1;
    }

    /** Os obrigatórios que não apontam para uma coluna deste arquivo. */
    public List<CampoDoExtrato> obrigatoriosFaltando(List<String> cabecalho) {
        var faltando = new ArrayList<CampoDoExtrato>();
        for (CampoDoExtrato campo : CampoDoExtrato.values()) {
            if (campo.obrigatorio() && indice(campo, cabecalho) < 0) {
                faltando.add(campo);
            }
        }
        return faltando;
    }

    /**
     * A sugestão para um cabeçalho: primeiro o que o salão já confirmou antes, se as colunas ainda
     * existirem; depois os apelidos. Cada coluna serve a um campo só.
     */
    public static MapeamentoDoExtrato sugerir(List<String> cabecalho,
                                              Optional<MapeamentoDoExtrato> salvo,
                                              FormatoDeNumero formato) {
        var escolhidas = new EnumMap<CampoDoExtrato, String>(CampoDoExtrato.class);
        Set<Integer> usadas = new HashSet<>();

        salvo.ifPresent(anterior -> {
            for (CampoDoExtrato campo : CampoDoExtrato.values()) {
                int i = anterior.indice(campo, cabecalho);
                if (i >= 0 && usadas.add(i)) {
                    escolhidas.put(campo, cabecalho.get(i));
                }
            }
        });

        for (CampoDoExtrato campo : CampoDoExtrato.values()) {
            if (escolhidas.containsKey(campo)) {
                continue;
            }
            procura:
            for (String apelido : campo.apelidos()) {
                for (int i = 0; i < cabecalho.size(); i++) {
                    if (!usadas.contains(i)
                            && CampoDoExtrato.normalizar(cabecalho.get(i)).equals(apelido)) {
                        usadas.add(i);
                        escolhidas.put(campo, cabecalho.get(i));
                        break procura;
                    }
                }
            }
        }
        return new MapeamentoDoExtrato(escolhidas, formato);
    }
}
