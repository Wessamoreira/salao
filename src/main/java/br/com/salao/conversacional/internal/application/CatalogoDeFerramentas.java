package br.com.salao.conversacional.internal.application;

import br.com.salao.iam.api.UsuarioEfetivo;
import br.com.salao.shared.agente.Ferramenta;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * RT-CNV-004 / RN-CNV-010 — quais ferramentas existem, e quais este usuário pode usar.
 *
 * <h2>A lista permitida é calculada no servidor, das permissões</h2>
 *
 * <p>docs/05: "a defesa é a allowlist de tools resolvida no servidor pelo perfil do usuário efetivo —
 * nunca uma instrução no system prompt". Uma ferramenta é oferecida quando é de LEITURA e o usuário
 * tem TODAS as permissões que ela declara. Ferramenta sem permissão declarada nunca é oferecida:
 * quem não disse o que exige é tratado como perigoso, não como livre.
 *
 * <h2>Falha na subida, não na conversa</h2>
 *
 * <p>Nome repetido ou fora do formato derruba a aplicação ao iniciar. Dois beans com o mesmo nome
 * fariam o modelo pedir um e o sistema executar o outro — sem erro nenhum.
 */
public class CatalogoDeFerramentas {

    private static final Pattern NOME = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    private final Map<String, Ferramenta> porNome = new LinkedHashMap<>();

    public CatalogoDeFerramentas(List<Ferramenta> ferramentas) {
        for (Ferramenta ferramenta : ferramentas.stream()
                .sorted(Comparator.comparing(Ferramenta::nome)).toList()) {
            if (ferramenta.nome() == null || !NOME.matcher(ferramenta.nome()).matches()) {
                throw new IllegalStateException(
                        "ferramenta com nome inválido: " + ferramenta.nome());
            }
            if (porNome.putIfAbsent(ferramenta.nome(), ferramenta) != null) {
                throw new IllegalStateException(
                        "duas ferramentas com o nome " + ferramenta.nome());
            }
        }
    }

    public Optional<Ferramenta> porNome(String nome) {
        return Optional.ofNullable(nome == null ? null : porNome.get(nome));
    }

    public List<Ferramenta> permitidasPara(UsuarioEfetivo usuario) {
        return porNome.values().stream()
                .filter(f -> f.natureza() == Ferramenta.Natureza.LEITURA)
                .filter(f -> f.permissoesExigidas() != null && !f.permissoesExigidas().isEmpty())
                .filter(f -> usuario.permissoes().containsAll(f.permissoesExigidas()))
                .toList();
    }
}
