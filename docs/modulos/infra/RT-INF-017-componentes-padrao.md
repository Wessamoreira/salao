---
id: RT-INF-017
titulo: Componentes padrão — um campo, uma altura, a marca na lateral, o QR do segundo fator
modulo: infra
fase: 7
perfil: leve
status: implementado
depende_de: [RT-INF-016, RT-IAM-005]
permissoes: []
eventos: []
regras: []
atualizado_em: 2026-11-15
---

# RT-INF-017 — Componentes padrão

## 1. Objetivo

Um componente, uma definição. O dono mostrou quatro telas (paleta ⌘K, configurações, lateral)
e pediu: *"deixe os componentes tudo com padrão — pode refatorar o sistema por dentro"*.

## 2. Contexto — medido

| Achado | Medido |
|---|---|
| **O campo existia 20 vezes** | `.campo` compartilhado + 19 regras locais em 11 arquivos (`acs-campo`, `cat-campo`, `eqp-campo`, `est-campo`, `fin-campo`, `cnc-*`, `cmd-campo`…), com alturas de 28, 32, 34, 36, 40 e 42px |
| **Select com o degradê nativo do macOS** | O `.campo` compartilhado não tinha `appearance: none`: a configuração inteira desenhava o controle de 2008 |
| **Botão e campo de alturas diferentes na mesma linha** | `pagina-acao` 32px ao lado de campo de 40–42px |
| **Configurações esticada** | Regressão de `RT-INF-016`: a regra de largura única tinha especificidade (0,2,0) e vencia os 560px da tela (0,1,0) |
| **Paleta ⌘K mostra `/agenda`, `/fila`** | Endereço técnico na cara de quem atende |
| **Lateral sem a marca** | Placa de aço com iniciais em didone e "GESTÃO DO SALÃO" em versalete: nada do Nexo |
| **Perfil "Recepcao"** sem acento no rodapé da lateral | `RECEPCAO` em minúsculas, sem tradução |
| **Segundo fator só com a chave em texto** | 32 caracteres para digitar no celular. O servidor já devolvia `uriOtpauth`; a tela não usava |

## 3. Decisões

**Um controle, definido uma vez**, em `componentes.css`, com especificidade **zero**
(`:where(...)`): vale para todo `input`, `select` e `textarea` do sistema sem a tela pedir, e
qualquer regra de tela que precise de algo particular (fonte monoespaçada para código, alinhamento
à direita para número, controle sem borda dentro de uma caixa) vence sem `!important`. As 19
cópias perdem o desenho repetido e ficam só com o que é delas.

**Uma altura para controle**: `--controle` 36px, `--controle-toque` 44px abaixo de 820px. Campo e
botão na mesma linha têm a mesma altura.

**Select redesenhado sem imagem**: seta em dois degradês (nada a liberar na CSP), nativo por baixo
— no celular continua abrindo o seletor do sistema.

**Paleta**: o endereço sai; a linha marcada mostra `↵`, que é o que a pessoa pode fazer com ela.
O telefone da cliente fica — é o dado que separa duas Marias.

**Lateral**: o ícone do Nexo e o nome do salão; embaixo, "nexo" discreto. A didone sai da lateral
(continua nos totais).

**Configurações**: ajuste à esquerda (rótulo e explicação), controle à direita — o desenho de tela
de ajustes; os textos de ajuda param de atravessar a tela.

**Segundo fator**: QR code gerado **no navegador** a partir do `uriOtpauth` — nunca por serviço
externo, que receberia o segredo do segundo fator. Biblioteca `qrcode-generator` (MIT, sem
dependências); desenhado como `<path>` SVG pelo React, sem `innerHTML` (compatível com Trusted
Types, `RT-INF-015`). No celular, onde não dá para escanear a própria tela, o botão "Abrir no
aplicativo autenticador" abre o `otpauth://` direto; e a chave vem em grupos de quatro, com botão
de copiar, para quem precisar digitar.

**Backend (junto)**: `URLEncoder` codificava espaço como `+` no endereço do QR — o autenticador
mostrava "Salao+Wesley". `Totp.uriDeConfiguracao` usa `%20`, com teste unitário.

## 15. Testes obrigatórios

- [x] `TotpTest` — espaço como `%20`, acento em UTF-8 (11 testes)
- [x] `SegundoFatorIT` — inscrição e confirmação de ponta a ponta (8 testes, Postgres real)
- [x] `SegundoFator.test.tsx` — QR desenhado como caminho SVG, link `otpauth` para o celular,
      chave em grupos de quatro (3 testes novos; a tela não tinha teste nenhum)
- [x] Suíte do front (386), typecheck, lint e build
- [x] Fotografado no app real (5173) e nas prévias: configurações em duas colunas, paleta sem
      endereço, lateral com a marca, QR do segundo fator
