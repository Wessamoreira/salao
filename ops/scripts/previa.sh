#!/usr/bin/env bash
# Sobe a prévia visual do front (RT-CLI-006).
#
# Existe porque o node instalado por nvm não está no PATH de processos que não
# passam pelo shell interativo — e a prévia precisa subir tanto pelo terminal
# quanto por ferramenta de automação.
set -euo pipefail

for candidato in "$HOME"/.nvm/versions/node/*/bin; do
  [ -d "$candidato" ] && export PATH="$candidato:$PATH" && break
done

command -v node >/dev/null || { echo "node não encontrado"; exit 1; }
exec npm run previa --prefix "$(dirname "$0")/../../frontend"
