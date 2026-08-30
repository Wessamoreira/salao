package br.com.salao.iam.internal.infra;

import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.internal.domain.MapaDePermissoes;
import java.util.Collection;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * RT-IAM-006 — transforma o perfil do token nas permissões que o {@code @PreAuthorize} confere.
 *
 * <p><strong>O token carrega o perfil, não a lista de permissões.</strong> Assim o mapa pode mudar
 * — corrigir uma permissão errada, atender um pedido do salão — e passa a valer na requisição
 * seguinte, sem esperar todo mundo entrar de novo. Uma lista dentro do token congelaria a decisão
 * por até quinze minutos, e cresceria o token à toa.
 *
 * <p>É esta classe que faz {@code hasAuthority('agenda:write:all')} funcionar. Sem ela, todo
 * {@code @PreAuthorize} do projeto negaria acesso a todo mundo — silenciosamente, porque negar é
 * o comportamento correto quando não há autoridade nenhuma.
 */
public class ConversorDePermissoes implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        String perfil = jwt.getClaimAsString(EmissorDeTokenJwt.CLAIM_PERFIL);
        if (perfil == null) {
            return List.of();
        }
        try {
            return MapaDePermissoes.de(Perfil.valueOf(perfil)).stream()
                    .map(p -> (GrantedAuthority) new SimpleGrantedAuthority(p))
                    .toList();
        } catch (IllegalArgumentException e) {
            // Perfil que não existe mais no código: nenhuma permissão. Falha fechada.
            return List.of();
        }
    }
}
