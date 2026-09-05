package br.com.salao.arquivos.api;

import java.io.IOException;

/**
 * RT-ARQ-001 — escrever os bytes <strong>pela aplicação</strong>.
 *
 * <p>Está separado de {@link Armazenamento} de propósito, porque é a parte que deixa de existir: um
 * provedor de objeto recebe os bytes direto do navegador, e a aplicação nunca os vê passar. O port
 * principal — assinar, ler o começo, medir, apagar — continua valendo nos dois mundos; este só vale
 * enquanto o disco local é o armazenamento.
 *
 * <p>Ter dois contratos evita a alternativa ruim: um {@code guardar} no port principal que a
 * implementação de bucket teria de existir só para recusar.
 */
public interface EscritaDeConteudo {

    /** Escreve os bytes na chave. Quem chama já conferiu que a chave é do salão atual. */
    void guardar(String chave, byte[] conteudo) throws IOException;
}
