package br.com.salao.arquivos.api;

/**
 * RT-ARQ-001 — em que pé está o arquivo.
 *
 * <p>{@code AGUARDANDO} é o estado que existe porque o upload é direto: a linha nasce antes dos
 * bytes, e fica assim até alguém confirmar. Um arquivo que nunca sai daí é um upload que começou e
 * não terminou — o navegador fechou, a rede caiu — e é o expurgo que o remove.
 *
 * <p>{@code RECUSADO} guarda o motivo. Sem ele, "por que a foto não subiu?" não tem resposta, e a
 * resposta certa ("o arquivo não é uma imagem, apesar do nome") é a que evita a pessoa tentar seis
 * vezes o mesmo arquivo.
 */
public enum StatusDoArquivo {
    AGUARDANDO,
    VALIDADO,
    RECUSADO
}
