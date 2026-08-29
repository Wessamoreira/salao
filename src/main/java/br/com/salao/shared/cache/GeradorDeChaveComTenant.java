package br.com.salao.shared.cache;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.cache.interceptor.KeyGenerator;

/**
 * RT-INF-007 — gerador padrão de chave, com o tenant embutido.
 *
 * <p>Registrado como o {@code KeyGenerator} <strong>padrão</strong> de propósito: assim, um
 * {@code @Cacheable} escrito sem pensar em multi-tenant já nasce isolado. A alternativa — pedir
 * que cada anotação declare o tenant no SpEL — funciona até a primeira vez que alguém esquece, e
 * o esquecimento não dá erro: dá resposta errada, para outro estabelecimento, em silêncio.
 */
public class GeradorDeChaveComTenant implements KeyGenerator {

    @Override
    public Object generate(Object alvo, Method metodo, Object... parametros) {
        String argumentos = parametros.length == 0
                ? "sem-argumentos"
                : Arrays.stream(parametros).map(String::valueOf).collect(Collectors.joining(","));
        return ChaveDeCache.de(metodo.getName() + "(" + argumentos + ")");
    }
}
