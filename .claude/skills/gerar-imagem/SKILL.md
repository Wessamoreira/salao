---
name: gerar-imagem
description: Gera imagens de verdade (logo, símbolo, ícone de app, ilustração, mockup de marca) pela API oficial da OpenAI e salva os PNGs no projeto. Use sempre que o pedido for criar, gerar, desenhar ou refazer um logo, marca, símbolo, ícone do PWA, favicon ou qualquer imagem — mesmo quando a pessoa não diz "gerar imagem" e só fala "faz um logo", "quero um ícone melhor", "cria a marca do sistema" ou "refaz esse símbolo". Também para variar ou ajustar uma imagem já gerada.
---

# Gerar imagem

Chama a API de imagens da OpenAI (`gpt-image-1` por padrão) com `scripts/gerar.py` e grava os
PNGs numa pasta do projeto. O script usa só biblioteca padrão do Python — nenhum pacote de
terceiros fica entre a chave da pessoa e a API.

## Antes de gerar

1. **A chave.** O script lê `OPENAI_API_KEY` do ambiente. Se faltar, ele para com a instrução —
   mostre à pessoa a seção "Configurar a chave" abaixo. Nunca peça para colar a chave na conversa
   e nunca a grave em arquivo do repositório.
2. **Cada imagem custa** (centavos de dólar em `high`). Gere 2 a 4 variações por rodada, não 20;
   use `--qualidade low` para explorar ideias e `high` para a versão que vai ficar.
3. **O que vai para a OpenAI é só o prompt e as imagens de referência.** Não ponha nome, e-mail
   ou dado de cliente no prompt.

## Escrever o prompt

Um logo bom sai de um prompt que diz o que o designer precisaria saber. Inclua:

- **O nome e o que o produto faz**, em uma frase — o modelo desenha melhor quando entende o
  assunto.
- **O tipo de marca**: só símbolo, só nome escrito (logotipo), ou os dois.
- **A paleta, em hex.** Neste projeto as cores vivem em `design/tokens.css`; a cor de marca é
  `--vinho` e a moldura é `--espelho`. Leia o arquivo em vez de supor.
- **Restrições de ícone**, quando for ícone: "forma simples e chapada, legível em 32px, sem
  texto, sem gradiente complexo, centralizado, fundo transparente".
- **O que evitar**: clichês da categoria, efeito 3D, sombra, texto pequeno.

Exemplo:

```
Símbolo para "Encaixe", sistema de agenda e gestão para salões e barbearias. Uma forma
geométrica simples que sugira duas peças se encaixando. Chapado, 2 cores: #6f3651 e #f7f1f4.
Legível em 32px, sem texto, sem gradiente, sem sombra, centralizado, fundo transparente.
```

## Rodar

```bash
python3 .claude/skills/gerar-imagem/scripts/gerar.py \
  --saida design/marca/rascunhos --nome encaixe-a --n 3 --qualidade low \
  "Símbolo para ..."
```

| Opção | Para quê |
|---|---|
| `--n 1..4` | variações por chamada |
| `--tamanho` | `1024x1024` (ícone), `1536x1024` (horizontal), `1024x1536`, `auto` |
| `--qualidade` | `low` explorar · `medium` · `high` final |
| `--fundo` | `transparent` (padrão — ícone e logo), `opaque`, `auto` |
| `--referencia arq.png` | parte de uma imagem existente (edição); pode repetir |
| `--modelo` | outro modelo de imagem; padrão vem de `OPENAI_IMAGE_MODEL` ou `gpt-image-1` |
| `--simular` | mostra o pedido sem chamar a API — use para conferir antes de gastar |

O script imprime os caminhos gravados e o uso de tokens.

## Depois de gerar

1. **Olhe cada imagem** (Read no PNG) antes de mostrar — descarte as que têm texto torto,
   detalhe que some em tamanho pequeno ou cara de clip-art.
2. **Mostre lado a lado e em tamanho de ícone.** Uma folha de contato com cada opção em 200,
   64, 32 e 16 px revela o que funciona como ícone; a imagem grande sozinha engana.
3. **Iterar** é gerar de novo com `--referencia` apontando para a escolhida e um prompt que diz
   só o que muda.
4. **Para virar ícone do app**, a imagem escolhida não vai crua para `public/`: o PWA precisa de
   `any` (192 e 512), `maskable` (512, desenho dentro do círculo de 80%), `apple-touch-icon`
   (180, opaco) e favicon. Gere essas versões a partir da escolhida e registre a decisão na
   doc da rotina, como o projeto exige.

## Configurar a chave

1. Crie a chave em https://platform.openai.com/api-keys (a conta precisa de saldo; a geração de
   imagem do `gpt-image-1` pode exigir verificação da organização).
2. No terminal, uma vez:
   ```bash
   echo 'export OPENAI_API_KEY="sk-..."' >> ~/.zshrc && source ~/.zshrc
   ```
3. Reinicie o Claude Code para ele herdar a variável.
