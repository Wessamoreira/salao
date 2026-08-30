package br.com.salao.cliente.internal.application;

import br.com.salao.cliente.api.ClienteResumo;
import br.com.salao.cliente.internal.domain.GrauDeSemelhanca;

/**
 * RT-CLI-003 — uma ficha que talvez seja a mesma pessoa que está sendo cadastrada.
 *
 * <p>O grau vem junto porque a tela precisa dele: {@code PROVAVEL} pede "é esta pessoa?",
 * {@code MESMO_TELEFONE} é só contexto ("este número já está com Maria Silva") e não deve parecer
 * um erro.
 */
public record PossivelDuplicado(ClienteResumo cliente, GrauDeSemelhanca grau) {
}
