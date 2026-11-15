#!/usr/bin/env python3
"""Gera imagens pela API oficial da OpenAI e salva os PNGs numa pasta.

Só biblioteca padrão: nada de SDK nem pacote de terceiros entre a chave e a API.

Uso:
  python3 gerar.py --saida design/marca/rascunhos --nome logo-a "prompt..."
  python3 gerar.py --saida ... --nome logo-a2 --referencia design/marca/rascunhos/logo-a-1.png "ajuste..."

A chave vem de OPENAI_API_KEY, no ambiente. Nunca de argumento (fica no histórico do shell)
e nunca de arquivo versionado.
"""
import argparse
import base64
import json
import mimetypes
import os
import pathlib
import sys
import urllib.error
import urllib.request
import uuid

API = "https://api.openai.com/v1/images"
MODELO_PADRAO = os.environ.get("OPENAI_IMAGE_MODEL", "gpt-image-1")
TAMANHOS = ("1024x1024", "1536x1024", "1024x1536", "auto")
QUALIDADES = ("low", "medium", "high", "auto")
FUNDOS = ("transparent", "opaque", "auto")


def argumentos():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("prompt", help="o que gerar, em uma descrição completa")
    p.add_argument("--saida", required=True, help="pasta onde os PNGs serão gravados")
    p.add_argument("--nome", required=True, help="prefixo do arquivo: <nome>-1.png, <nome>-2.png…")
    p.add_argument("--n", type=int, default=1, choices=range(1, 5), metavar="1-4",
                   help="quantas variações (cada uma custa)")
    p.add_argument("--tamanho", default="1024x1024", choices=TAMANHOS)
    p.add_argument("--qualidade", default="high", choices=QUALIDADES)
    p.add_argument("--fundo", default="transparent", choices=FUNDOS)
    p.add_argument("--referencia", action="append", default=[],
                   help="imagem de partida (edição). Pode repetir")
    p.add_argument("--modelo", default=MODELO_PADRAO)
    p.add_argument("--simular", action="store_true",
                   help="mostra o que seria enviado, sem chamar a API nem gastar")
    return p.parse_args()


def multipart(campos, arquivos):
    """Corpo multipart/form-data para o endpoint de edição, sem biblioteca externa."""
    fronteira = uuid.uuid4().hex
    partes = []
    for nome, valor in campos.items():
        partes.append(f'--{fronteira}\r\nContent-Disposition: form-data; name="{nome}"\r\n\r\n{valor}\r\n'.encode())
    for nome, caminho in arquivos:
        tipo = mimetypes.guess_type(caminho.name)[0] or "application/octet-stream"
        partes.append((f'--{fronteira}\r\nContent-Disposition: form-data; name="{nome}"; '
                       f'filename="{caminho.name}"\r\nContent-Type: {tipo}\r\n\r\n').encode())
        partes.append(caminho.read_bytes() + b"\r\n")
    partes.append(f"--{fronteira}--\r\n".encode())
    return b"".join(partes), f"multipart/form-data; boundary={fronteira}"


def chamar(url, corpo, tipo, chave):
    pedido = urllib.request.Request(url, data=corpo, method="POST", headers={
        "Authorization": f"Bearer {chave}", "Content-Type": tipo})
    try:
        with urllib.request.urlopen(pedido, timeout=300) as resposta:
            return json.load(resposta)
    except urllib.error.HTTPError as erro:
        # A mensagem da API diz o que corrigir (modelo sem acesso, política de conteúdo,
        # saldo). Ela não contém a chave.
        try:
            detalhe = json.load(erro).get("error", {}).get("message", "")
        except Exception:
            detalhe = ""
        sys.exit(f"A API recusou ({erro.code}): {detalhe or erro.reason}")
    except urllib.error.URLError as erro:
        sys.exit(f"Sem conexão com a API: {erro.reason}")


def main():
    a = argumentos()
    referencias = [pathlib.Path(r) for r in a.referencia]
    faltando = [str(r) for r in referencias if not r.is_file()]
    if faltando:
        sys.exit(f"Referência não encontrada: {', '.join(faltando)}")

    campos = {"model": a.modelo, "prompt": a.prompt, "n": a.n, "size": a.tamanho,
              "quality": a.qualidade, "background": a.fundo, "output_format": "png"}
    editar = bool(referencias)

    if a.simular:
        print(json.dumps({"endpoint": f"{API}/{'edits' if editar else 'generations'}",
                          "campos": campos, "referencias": [str(r) for r in referencias]},
                         ensure_ascii=False, indent=2))
        return

    chave = os.environ.get("OPENAI_API_KEY")
    if not chave:
        sys.exit("OPENAI_API_KEY não está no ambiente. Veja 'Configurar a chave' no SKILL.md.")

    if editar:
        corpo, tipo = multipart({k: str(v) for k, v in campos.items()},
                                [("image[]", r) for r in referencias])
        dados = chamar(f"{API}/edits", corpo, tipo, chave)
    else:
        dados = chamar(f"{API}/generations", json.dumps(campos).encode(), "application/json", chave)

    pasta = pathlib.Path(a.saida)
    pasta.mkdir(parents=True, exist_ok=True)
    gravados = []
    for i, item in enumerate(dados.get("data", []), start=1):
        destino = pasta / f"{a.nome}-{i}.png"
        destino.write_bytes(base64.b64decode(item["b64_json"]))
        gravados.append(str(destino))
    if not gravados:
        sys.exit("A API respondeu sem imagem.")
    uso = dados.get("usage")
    print(json.dumps({"arquivos": gravados, "uso": uso}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
