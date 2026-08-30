#!/usr/bin/env python3
"""Reconcilia o plano com o que existe de fato.

Existe porque o plano sozinho não impede desvio: nesta sessão o bloco RT-IAM
foi pulado e só se percebeu rotinas depois. Um documento não avisa quando é
desobedecido; este script avisa.

Uso: python3 ops/scripts/checklist.py [--ci] [--pendencias]
  --ci          sai com codigo 1 se houver inconsistencia estrutural
  --pendencias  lista todas as pendencias abertas, por rotina
"""
import re, sys, pathlib, collections

RAIZ = pathlib.Path(__file__).resolve().parents[2]
DOCS = RAIZ / "docs"
PLANO = DOCS / "09-plano-de-implementacao.md"
BASELINE = pathlib.Path(__file__).with_name("saltos-aceitos.txt")

ORDEM_STATUS = ["implementado", "implementado-parcial", "em-implementacao",
                "especificado", "rascunho", "obsoleto"]


def rotinas_do_plano():
    """Toda linha de tabela do plano que comece com um ID RT-."""
    itens = {}
    fase = None
    for linha in PLANO.read_text(encoding="utf-8").splitlines():
        m = re.match(r"^##\s+Fase\s+(\d+)", linha)
        if m:
            fase = int(m.group(1))
        m = re.match(r"^\|\s*(RT-[A-Z]{3}-\d{3})\s*\|\s*([^|]+?)\s*\|", linha)
        if m:
            itens[m.group(1)] = {"titulo": m.group(2).strip(), "fase": fase}
    return itens


def rotinas_documentadas():
    itens = {}
    for arquivo in sorted(DOCS.glob("modulos/*/RT-*.md")):
        texto = arquivo.read_text(encoding="utf-8")
        cabecalho = re.match(r"^---\n(.*?)\n---", texto, re.S)
        if not cabecalho:
            itens[arquivo.stem] = {"erro": "sem cabecalho YAML", "arquivo": arquivo}
            continue
        campos = dict(re.findall(r"^(\w+):\s*(.+)$", cabecalho.group(1), re.M))
        pendentes = len(re.findall(r"^\s*-\s\[ \]", texto, re.M))
        itens[campos.get("id", arquivo.stem)] = {
            "status": campos.get("status", "?"),
            "titulo": campos.get("titulo", "?"),
            "atualizado": campos.get("atualizado_em", "?"),
            "pendencias": pendentes,
            "arquivo": arquivo.relative_to(RAIZ),
        }
    return itens


def pendencias_detalhadas():
    for arquivo in sorted(DOCS.glob("modulos/*/RT-*.md")):
        texto = arquivo.read_text(encoding="utf-8")
        abertas = re.findall(r"^\s*-\s\[ \]\s*(.+?)$", texto, re.M)
        if abertas:
            yield arquivo.stem, abertas


def salto_de_ordem(plano, docs):
    """Rotina concluida enquanto uma anterior da MESMA fase nem comecou.

    Este é o cheque que pega o erro que de fato aconteceu: RT-INF-005 em diante
    foram implementadas com o bloco RT-IAM-001..008 inteiro por fazer, e nada
    avisou. Ordem no plano existe porque há dependência real — escrever caso de
    uso de negócio antes da autorização significa escrevê-lo duas vezes.
    """
    saltos = []
    por_fase = collections.defaultdict(list)
    for ident, item in plano.items():
        por_fase[item["fase"]].append(ident)

    for fase, idents in sorted(por_fase.items()):
        nao_iniciadas = []
        for ident in idents:
            status = docs.get(ident, {}).get("status", "nao-iniciada")
            if status == "implementado" and nao_iniciadas:
                saltos.append((ident, list(nao_iniciadas)))
            elif status == "nao-iniciada":
                nao_iniciadas.append(ident)
    return saltos


def main():
    plano = rotinas_do_plano()
    docs = rotinas_documentadas()
    problemas = []

    print(f"PLANO: {len(plano)} rotinas · DOCUMENTADAS: {len(docs)}\n")

    por_status = collections.Counter(d.get("status", "?") for d in docs.values())
    print("Situação do que já foi escrito")
    for s in ORDEM_STATUS:
        if por_status.get(s):
            print(f"  {s:24} {por_status[s]}")
    print()

    # Documentado mas fora do plano: ou o plano está desatualizado, ou apareceu
    # trabalho que ninguém decidiu fazer.
    fora = [i for i in docs if i not in plano]
    if fora:
        problemas.append(f"{len(fora)} rotina(s) documentadas fora do plano")
        print("FORA DO PLANO (atualizar o plano ou justificar):")
        for i in sorted(fora):
            print(f"  {i}  {docs[i].get('titulo')}")
        print()

    print("Próximas do plano, em ordem (as 12 primeiras não concluídas)")
    restantes = 0
    for ident, item in plano.items():
        doc = docs.get(ident)
        status = doc["status"] if doc else "nao-iniciada"
        if status in ("implementado",):
            continue
        restantes += 1
        if restantes <= 12:
            marca = "[~]" if doc else "[ ]"
            extra = f" · {doc['pendencias']} pendência(s)" if doc and doc["pendencias"] else ""
            print(f"  {marca} Fase {item['fase']}  {ident}  {item['titulo']}"
                  f"  ({status}){extra}")
    print(f"\n  ... {restantes} rotinas não concluídas no total")

    concluidas = sum(1 for i in plano if docs.get(i, {}).get("status") == "implementado")
    # Catraca: o desvio já cometido fica registrado e aceito; qualquer desvio NOVO
    # quebra o build. Fazer o CI falhar por algo conhecido só ensinaria a ignorar
    # vermelho — e aí o vermelho que importa passa despercebido.
    aceitos = set()
    if BASELINE.exists():
        aceitos = {l.split("#")[0].strip() for l in BASELINE.read_text(encoding="utf-8").splitlines()
                   if l.split("#")[0].strip()}

    saltos = salto_de_ordem(plano, docs)
    novos = [(i, a) for i, a in saltos if i not in aceitos]
    if saltos:
        print("\nSALTOS DE ORDEM (concluída com anterior da mesma fase por iniciar):")
        for ident, anteriores in saltos:
            marca = "novo!" if ident not in aceitos else "aceito"
            print(f"  [{marca}] {ident} passou à frente de: {', '.join(anteriores)}")
    if novos:
        problemas.append(
            f"{len(novos)} salto(s) NOVOS de ordem: "
            + ", ".join(i for i, _ in novos)
            + " — corrija a ordem ou registre em ops/scripts/saltos-aceitos.txt com o motivo")

    print(f"\nCONCLUÍDAS: {concluidas}/{len(plano)}")

    pendencias = sum(d.get("pendencias", 0) for d in docs.values())
    print(f"PENDÊNCIAS ABERTAS dentro de rotinas já entregues: {pendencias}")

    if problemas:
        print("\nINCONSISTÊNCIAS:")
        for p in problemas:
            print(f"  - {p}")
    if "--pendencias" in sys.argv:
        print("\n" + "=" * 70)
        print("PENDÊNCIAS ABERTAS, POR ROTINA")
        for nome, abertas in pendencias_detalhadas():
            print(f"\n{nome}  ({len(abertas)})")
            for item in abertas:
                print(f"  - {item}")

    if "--ci" in sys.argv and problemas:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
