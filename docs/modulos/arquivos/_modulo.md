# Módulo `arquivos`

- **Sigla:** ARQ · **Fase de introdução:** 1 · **Status:** em construção

## O que ele responde

*Guardar o que chega de fora — e garantir que só entre o que deveria.*

## Fronteira

Expõe `TipoDeArquivo`, `StatusDoArquivo`, `ArquivoResumo` e dois ports: `Armazenamento` — assinar,
ler o começo, medir, apagar — e `EscritaDeConteudo`, que é a parte destinada a **desaparecer**:
escrever os bytes pela aplicação só faz sentido enquanto não há provedor de objeto. Separados
porque juntos obrigariam a implementação de bucket a ter um `guardar` que existe só para recusar.

O módulo é o **único lugar** onde se decide o que um arquivo é. Qualquer outro módulo que aceitasse
um `Content-Type` como verdade estaria refazendo essa decisão pior.

## O que ele **não** sabe

**De quem é o arquivo.** `dono_tipo` + `dono_id` são um par opaco, sem FK: cliente e profissional
são de outros módulos, e o dono pode ser de qualquer um deles. Este módulo guarda; quem interpreta
o dono é quem perguntou.

**Se o arquivo pode ser mostrado.** A permissão de subir é a do dono (RT-ARQ-001 §8), e é o módulo
do dono que a define. `arquivos` não tem permissão própria — ter uma separaria o anexo do dado a que
ele pertence.

## Depende de

- `iam` — `Permissao` (as do dono) e `UsuarioAtual`
- nada mais. É de propósito: um módulo que valida entrada não deveria precisar de ninguém para isso

## Rotinas

| Rotina | Situação |
|---|---|
| [RT-ARQ-001](RT-ARQ-001-upload.md) — upload por presigned URL + magic bytes | implementada |

## Rotas

| Rota | O que faz |
|---|---|
| `POST /api/v1/arquivos` | autoriza e devolve para onde escrever |
| `PUT /api/v1/arquivos/conteudo/{chave}` | recebe os bytes — **só enquanto não há bucket** |
| `POST /api/v1/arquivos/{id}/confirmacao` | valida os bytes e aceita ou recusa (200 nos dois) |

## Regras

Ver [regras.md](regras.md) — RN-ARQ-001 a RN-ARQ-004.
