package br.com.salao.shared.erro;

import java.util.List;
import java.util.Map;

/**
 * RT-INF-003 — exceção de negócio, com código estável.
 *
 * <p>Lançada pelo domínio ou pelo caso de uso; traduzida para HTTP por
 * {@link ManipuladorGlobalDeErros}. O controller <strong>nunca</strong> monta erro na mão.
 *
 * <p>{@code parametros} alimenta a interpolação da mensagem no front — por exemplo o nome da
 * profissional e o horário em {@code ER-AGD-CONFLITO_HORARIO}. Nunca coloque PII aqui: o valor
 * atravessa log e telemetria.
 */
public class ErroDeDominio extends RuntimeException {

    private final transient CodigoDeErro codigo;
    private final transient Map<String, Object> parametros;
    private final transient List<CampoInvalido> campos;

    public ErroDeDominio(CodigoDeErro codigo, String detalhe) {
        this(codigo, detalhe, Map.of(), List.of());
    }

    public ErroDeDominio(CodigoDeErro codigo, String detalhe, Map<String, Object> parametros) {
        this(codigo, detalhe, parametros, List.of());
    }

    public ErroDeDominio(CodigoDeErro codigo, String detalhe,
                         Map<String, Object> parametros, List<CampoInvalido> campos) {
        super(detalhe);
        this.codigo = codigo;
        this.parametros = Map.copyOf(parametros);
        this.campos = List.copyOf(campos);
    }

    public CodigoDeErro codigo() {
        return codigo;
    }

    public Map<String, Object> parametros() {
        return parametros;
    }

    public List<CampoInvalido> campos() {
        return campos;
    }
}
